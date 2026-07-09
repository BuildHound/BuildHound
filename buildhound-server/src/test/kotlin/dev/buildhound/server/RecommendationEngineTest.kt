package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.ProcessInfo
import dev.buildhound.commons.payload.ProcessRole
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.ToolchainInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Pure-engine unit tests for plan 054 (the `RegressionEngineTest`/`WarningCalculatorTest` style): each
 * family fires on its positive fixture and stays silent on absent/insufficient signal; the KAPT
 * ksp-suppression + isolated-projects null-type degrade; the Gradle-10 card with and without the
 * internal-adapters deprecations section; the deterministic ranking; and the landed-calculator reuse
 * (GC via [DaemonTuningCandidates]). No I/O, no store.
 */
class RecommendationEngineTest {

    private fun ids(recs: List<Recommendation>) = recs.map { it.ruleId }.toSet()

    private fun ccBuild(id: String, state: ConfigurationCacheState, mode: BuildMode = BuildMode.CI) =
        TestPayloads.build(buildId = id, mode = mode, configurationCache = state)

    /** A process whose lifetime GC fraction (gcTimeMs ÷ uptimeS*1000) exceeds the plan-065 15% threshold. */
    private fun gcProcess(gcTimeMs: Long = 3000, uptimeS: Long = 10) =
        ProcessInfo(role = ProcessRole.GRADLE_DAEMON, gcTimeMs = gcTimeMs, uptimeS = uptimeS)

    private fun deprecationsExtension(vararg warnings: String): Map<String, JsonElement> = mapOf(
        "internalAdapters" to buildJsonObject {
            put("schemaVersion", 1)
            putJsonArray("deprecations") { warnings.forEach { add(it) } }
        },
    )

    // ---- empty / silence ------------------------------------------------------------------------

    @Test
    fun `an empty window yields no recommendations`() {
        assertTrue(RecommendationEngine.compute(emptyList()).isEmpty())
    }

    @Test
    fun `a clean fleet trips no rule`() {
        val clean = listOf(
            TestPayloads.build(buildId = "a", configurationCache = ConfigurationCacheState.HIT, hitRate = 0.95),
            TestPayloads.build(buildId = "b", configurationCache = ConfigurationCacheState.HIT, hitRate = 0.9),
        )
        assertTrue(RecommendationEngine.compute(clean).isEmpty())
    }

    // ---- family 1: hygiene ----------------------------------------------------------------------

    @Test
    fun `HYGIENE-CACHE-OFF and BP-CC-ENABLE both fire from the same CC-disabled share, distinctly`() {
        val payloads = listOf(
            ccBuild("a", ConfigurationCacheState.DISABLED),
            ccBuild("b", ConfigurationCacheState.DISABLED),
            ccBuild("c", ConfigurationCacheState.HIT),
        )
        val recs = RecommendationEngine.compute(payloads)
        val hygiene = recs.single { it.ruleId == "HYGIENE-CACHE-OFF" }
        val bp = recs.single { it.ruleId == "BP-CC-ENABLE" }
        // Same underlying share (2 of 3 disabled), two ruleIds, distinct copy + severity.
        assertEquals("2", hygiene.evidence["disabledBuilds"])
        assertEquals("3", hygiene.evidence["ccObservedBuilds"])
        assertEquals(RecommendationSeverity.WARN.name, hygiene.severity)
        assertEquals(RecommendationSeverity.INFO.name, bp.severity)
        assertTrue(hygiene.advice != bp.advice, "the two cards must read differently, not the same card twice")
        assertEquals(RecommendationOrigin.MEASURED.name, hygiene.origin)
    }

    @Test
    fun `CC-off stays silent below the share threshold and when CC is never observed`() {
        // 1 of 4 disabled = 0.25 share, below the 0.5 gate.
        val below = listOf(
            ccBuild("a", ConfigurationCacheState.DISABLED),
            ccBuild("b", ConfigurationCacheState.HIT),
            ccBuild("c", ConfigurationCacheState.HIT),
            ccBuild("d", ConfigurationCacheState.MISS_STORED),
        )
        assertTrue("HYGIENE-CACHE-OFF" !in ids(RecommendationEngine.compute(below)))
        // No CC state observed at all → uncaptured, not "enabled" → silent (honest-null).
        val unobserved = listOf(TestPayloads.build(buildId = "x"))
        assertTrue("HYGIENE-CACHE-OFF" !in ids(RecommendationEngine.compute(unobserved)))
    }

    @Test
    fun `HYGIENE-CACHE-HITRATE fires below target and is silent at or above it`() {
        val below = listOf(
            TestPayloads.build(buildId = "a", hitRate = 0.3),
            TestPayloads.build(buildId = "b", hitRate = 0.5),
        )
        val rec = RecommendationEngine.compute(below).single { it.ruleId == "HYGIENE-CACHE-HITRATE" }
        assertEquals(RecommendationOrigin.MEASURED.name, rec.origin)

        val above = listOf(TestPayloads.build(buildId = "a", hitRate = 0.9))
        assertTrue("HYGIENE-CACHE-HITRATE" !in ids(RecommendationEngine.compute(above)))
    }

    @Test
    fun `HYGIENE-GC fires via the reused DaemonTuningCandidates gc fraction`() {
        val pressured = listOf(
            TestPayloads.build(buildId = "a", durationMs = 5000, processes = listOf(gcProcess())),
            TestPayloads.build(buildId = "b", durationMs = 5000, processes = listOf(gcProcess())),
        )
        val rec = RecommendationEngine.compute(pressured).single { it.ruleId == "HYGIENE-GC" }
        assertEquals("2", rec.evidence["pressuredBuilds"])
        assertEquals(RecommendationOrigin.MEASURED.name, rec.origin)

        // A low-GC process (0.01 fraction) is observed but never pressured → silent.
        val calm = listOf(TestPayloads.build(buildId = "a", processes = listOf(gcProcess(gcTimeMs = 100, uptimeS = 100))))
        assertTrue("HYGIENE-GC" !in ids(RecommendationEngine.compute(calm)))
        // No process probe at all → no signal, silent.
        assertTrue("HYGIENE-GC" !in ids(RecommendationEngine.compute(listOf(TestPayloads.build(buildId = "a")))))
    }

    // ---- family 2: KAPT tax ---------------------------------------------------------------------

    private fun kaptBuild(id: String, ksp: String? = null) = TestPayloads.build(
        buildId = id,
        toolchain = if (ksp != null) ToolchainInfo(ksp = ksp) else ToolchainInfo(kgp = "2.0.0"),
        tasks = listOf(
            TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 4000, type = "org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask"),
            TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 1000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile"),
        ),
    )

    @Test
    fun `KAPT-TAX and BP-KSP-OVER-KAPT fire, with a hedged ESTIMATED projected saving`() {
        val recs = RecommendationEngine.compute(listOf(kaptBuild("a")))
        val tax = recs.single { it.ruleId == "KAPT-TAX" }
        // 4000 of 5000 EXECUTED ms is KAPT; projected KSP saving = 40% of 4000 = 1600 (ESTIMATED).
        assertEquals("4000", tax.evidence["kaptMs"])
        assertEquals(1600L, tax.projectedSavingsMs)
        assertEquals(RecommendationOrigin.ESTIMATED.name, tax.origin)
        assertEquals(":app", tax.evidence["topModule"])
        assertTrue("BP-KSP-OVER-KAPT" in ids(recs))
    }

    @Test
    fun `KAPT is suppressed when the build is already on KSP`() {
        val recs = RecommendationEngine.compute(listOf(kaptBuild("a", ksp = "2.0.0-1.0.0")))
        assertTrue("KAPT-TAX" !in ids(recs))
        assertTrue("BP-KSP-OVER-KAPT" !in ids(recs))
    }

    @Test
    fun `KAPT reads a null task type as no-signal (isolated projects degrade), never KAPT-absent`() {
        // Under isolated projects the type dictionary is empty (plan 016) — a kapt-NAMED task with a null
        // type must not be counted as KAPT, and the build contributes no signal at all.
        val ipBuild = TestPayloads.build(
            buildId = "a",
            toolchain = ToolchainInfo(kgp = "2.0.0"),
            tasks = listOf(TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 4000, type = null)),
        )
        assertTrue("KAPT-TAX" !in ids(RecommendationEngine.compute(listOf(ipBuild))))
    }

    // ---- family 4: Gradle-10 readiness ----------------------------------------------------------

    @Test
    fun `G10-READINESS is HIGH on a sub-floor daemon JDK and renders a note when deprecations are absent`() {
        val payloads = listOf(
            TestPayloads.build(buildId = "a", toolchain = ToolchainInfo(jdk = "17.0.11"), configurationCache = ConfigurationCacheState.DISABLED),
        )
        val rec = RecommendationEngine.compute(payloads).single { it.ruleId == "G10-READINESS" }
        assertEquals(RecommendationSeverity.HIGH.name, rec.severity, "a sub-21 daemon JDK is a hard Gradle-10 blocker")
        assertEquals(RecommendationOrigin.ESTIMATED.name, rec.origin, "the JDK-21 requirement is expected, not measured")
        assertEquals("17", rec.evidence["subFloorJdkMajors"])
        assertEquals("false", rec.evidence["deprecationsAvailable"])
        assertTrue(rec.advice.contains("unavailable"), "the parent-lookup term renders a note when the signal is absent")
    }

    @Test
    fun `G10-READINESS folds in the internal-adapters deprecation count when present`() {
        val payloads = listOf(
            TestPayloads.build(
                buildId = "a",
                toolchain = ToolchainInfo(jdk = "21.0.2"),
                configurationCache = ConfigurationCacheState.DISABLED,
                extensions = deprecationsExtension("The foo() method is deprecated", "bar is scheduled for removal"),
            ),
        )
        val rec = RecommendationEngine.compute(payloads).single { it.ruleId == "G10-READINESS" }
        // JDK 21 is at the floor (no JDK gap) → INFO, driven by CC-disabled + deprecations.
        assertEquals(RecommendationSeverity.INFO.name, rec.severity)
        assertEquals("true", rec.evidence["deprecationsAvailable"])
        assertEquals("2", rec.evidence["deprecationCount"])
    }

    @Test
    fun `G10-READINESS is silent on a ready fleet`() {
        val ready = listOf(
            TestPayloads.build(buildId = "a", toolchain = ToolchainInfo(jdk = "21.0.2"), configurationCache = ConfigurationCacheState.HIT),
        )
        assertTrue("G10-READINESS" !in ids(RecommendationEngine.compute(ready)))
    }

    // ---- family 5: wasted work ------------------------------------------------------------------

    @Test
    fun `WASTE-CI-X-TEST fires when CI habitually excludes tests`() {
        val payloads = listOf(
            TestPayloads.build(buildId = "a", mode = BuildMode.CI, excludedTaskNames = listOf(":app:test")),
            TestPayloads.build(buildId = "b", mode = BuildMode.CI, excludedTaskNames = listOf(":app:testDebugUnitTest")),
            TestPayloads.build(buildId = "c", mode = BuildMode.CI, excludedTaskNames = emptyList()),
        )
        val rec = RecommendationEngine.compute(payloads).single { it.ruleId == "WASTE-CI-X-TEST" }
        // 2 of 3 CI builds excluded tests = 0.667 share.
        assertEquals("2", rec.evidence["ciBuildsExcludingTest"])
        assertEquals("3", rec.evidence["ciBuilds"])
        assertEquals(RecommendationOrigin.MEASURED.name, rec.origin)
    }

    @Test
    fun `WASTE-CI-X-TEST is silent when no CI build excludes tests`() {
        val payloads = listOf(
            TestPayloads.build(buildId = "a", mode = BuildMode.CI, excludedTaskNames = listOf(":app:lint")),
            TestPayloads.build(buildId = "b", mode = BuildMode.LOCAL, excludedTaskNames = listOf(":app:test")),
        )
        assertTrue("WASTE-CI-X-TEST" !in ids(RecommendationEngine.compute(payloads)))
    }

    @Test
    fun `WASTE-LOCAL-VERIFICATION fires when local builds spend heavily on verification`() {
        val local = TestPayloads.build(
            buildId = "a",
            mode = BuildMode.LOCAL,
            tasks = listOf(
                TestPayloads.task(":app:testDebugUnitTest", TaskOutcome.EXECUTED, 7000),
                TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 3000),
            ),
        )
        val rec = RecommendationEngine.compute(listOf(local)).single { it.ruleId == "WASTE-LOCAL-VERIFICATION" }
        // 7000 of 10000 EXECUTED ms is verification (a `test` task) = 0.7 median share.
        assertEquals("1", rec.evidence["localBuilds"])
        assertEquals(RecommendationOrigin.MEASURED.name, rec.origin)
    }

    // ---- ranking + per-build --------------------------------------------------------------------

    @Test
    fun `recommendations are ranked severity-desc with a deterministic tie-break`() {
        val payloads = listOf(
            TestPayloads.build(
                buildId = "a",
                mode = BuildMode.CI,
                toolchain = ToolchainInfo(jdk = "17.0.11", kgp = "2.0.0"),
                configurationCache = ConfigurationCacheState.DISABLED,
                hitRate = 0.2,
                excludedTaskNames = listOf(":app:test"),
                processes = listOf(gcProcess()),
                tasks = listOf(
                    TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 4000, type = "com.example.KaptTask"),
                    TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 1000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile"),
                ),
            ),
        )
        val recs = RecommendationEngine.compute(payloads)
        // The severity ordinals must be monotonically non-increasing (HIGH → WARN → INFO).
        val ordinals = recs.map { RecommendationSeverity.valueOf(it.severity).ordinal }
        assertEquals(ordinals.sortedDescending(), ordinals, "recommendations must be ranked severity-desc")
        // G10-READINESS (HIGH, sub-floor JDK) must lead.
        assertEquals("G10-READINESS", recs.first().ruleId)
        // Idempotent/deterministic: recompute yields the identical ordered list.
        assertEquals(recs, RecommendationEngine.compute(payloads))
    }

    @Test
    fun `the engine runs the same over a one-element list (the per-build route path)`() {
        // The per-build route calls compute(listOf(payload)); a single bad build still yields cards
        // because every gate is a share of *observed* builds (1.0 for a one-element list), not a
        // fleet build-count minimum.
        val single: BuildPayload = TestPayloads.build(
            buildId = "solo",
            mode = BuildMode.CI,
            configurationCache = ConfigurationCacheState.DISABLED,
            excludedTaskNames = listOf(":app:test"),
        )
        val recs = RecommendationEngine.compute(listOf(single))
        assertTrue("HYGIENE-CACHE-OFF" in ids(recs))
        assertTrue("WASTE-CI-X-TEST" in ids(recs))
        assertNull(recs.firstOrNull { it.ruleId == "KAPT-TAX" }, "no KAPT task → no KAPT card even on a single build")
    }
}
