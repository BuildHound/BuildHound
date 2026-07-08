package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-calculator coverage for the plan-060 warning taxonomy: each rule's fire/silent boundary, name/type corroboration, clean-build exclusion, and cross-store determinism. */
class WarningCalculatorTest {

    private fun row(
        buildId: String,
        name: String,
        durationMs: Long,
        outcome: String = "EXECUTED",
        type: String? = null,
        module: String? = ":app",
        cacheable: Boolean? = null,
        incremental: Boolean = false,
        executionReasons: List<String> = emptyList(),
    ) = TaskRow(
        buildId = buildId, userId = null, module = module, name = name, type = type,
        outcome = outcome, durationMs = durationMs, buildWallMs = durationMs,
        cacheable = cacheable, executionReasons = executionReasons, incremental = incremental,
    )

    @Test
    fun `an empty window is honestly empty`() {
        val rollup = WarningCalculator.compute(emptyList(), period = 7)
        assertEquals(emptyList(), rollup.warnings)
        assertEquals(false, rollup.typeDataAvailable)
        assertEquals(7, rollup.period)
    }

    // ---- ALWAYS_RUN ----

    @Test
    fun `ALWAYS_RUN fires when the group executes with a matching reason in at least the share threshold of builds`() {
        val rows = (1..3).map { i -> row("b$i", "customTask", 100, executionReasons = listOf("Task.upToDateWhen is false.")) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        val warning = rollup.warnings.single { it.category == WarningCategory.ALWAYS_RUN.name }
        assertEquals(3, warning.buildsObserved)
        assertEquals(3, warning.buildsAffected)
        assertEquals(1.0, warning.share)
        assertEquals(300L, warning.totalMs)
        assertEquals("Task.upToDateWhen is false.", warning.evidenceReason)
    }

    @Test
    fun `ALWAYS_RUN also matches the has-not-declared-outputs pattern, case-insensitively`() {
        val rows = (1..3).map { i -> row("b$i", "customTask", 50, executionReasons = listOf("HAS NOT DECLARED ANY OUTPUTS.")) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.any { it.category == WarningCategory.ALWAYS_RUN.name })
    }

    @Test
    fun `ALWAYS_RUN stays silent below MIN_BUILDS`() {
        val rows = (1..2).map { i -> row("b$i", "customTask", 100, executionReasons = listOf("Task.upToDateWhen is false.")) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.ALWAYS_RUN.name })
    }

    @Test
    fun `ALWAYS_RUN stays silent when only a minority of builds match`() {
        val rows = listOf(
            row("b1", "customTask", 100, executionReasons = listOf("Task.upToDateWhen is false.")),
            row("b2", "customTask", 100, outcome = "UP_TO_DATE"),
            row("b3", "customTask", 100, outcome = "UP_TO_DATE"),
        )
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.ALWAYS_RUN.name })
    }

    @Test
    fun `an unrecognized reason buckets as no-fire, never a false positive`() {
        val rows = (1..3).map { i -> row("b$i", "customTask", 100, executionReasons = listOf("Some brand-new Gradle wording nobody has seen.")) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.ALWAYS_RUN.name })
    }

    @Test
    fun `ALWAYS_RUN evidenceReason is deterministic regardless of build encounter order`() {
        val forward = listOf(
            row("b1", "customTask", 100, executionReasons = listOf("Task.upToDateWhen is false.")),
            row("b2", "customTask", 100, executionReasons = listOf("has not declared any outputs.")),
            row("b3", "customTask", 100, executionReasons = listOf("Task.upToDateWhen is false.")),
        )
        val forwardEvidence = WarningCalculator.compute(forward, period = 7)
            .warnings.single { it.category == WarningCategory.ALWAYS_RUN.name }.evidenceReason
        val reversedEvidence = WarningCalculator.compute(forward.reversed(), period = 7)
            .warnings.single { it.category == WarningCategory.ALWAYS_RUN.name }.evidenceReason
        assertEquals(forwardEvidence, reversedEvidence, "evidenceReason must not depend on row/build order (parity discipline)")
        assertEquals("Task.upToDateWhen is false.", forwardEvidence)
    }

    // ---- NON_INCREMENTAL_AP ----

    @Test
    fun `NON_INCREMENTAL_AP fires when a kapt task is persistently non-incremental on non-clean builds`() {
        val rows = (1..3).map { i -> row("b$i", "kaptDebugKotlin", 200, incremental = false) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        val warning = rollup.warnings.single { it.category == WarningCategory.NON_INCREMENTAL_AP.name }
        assertEquals(3, warning.buildsObserved)
        assertEquals(3, warning.buildsAffected)
        assertEquals(1.0, warning.share)
        assertEquals(600L, warning.totalMs)
    }

    @Test
    fun `NON_INCREMENTAL_AP matches by name alone when type is null (isolated projects), and flags typeDataAvailable false`() {
        val rows = (1..3).map { i -> row("b$i", "kspDebugKotlin", 150, type = null, incremental = false) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.any { it.category == WarningCategory.NON_INCREMENTAL_AP.name })
        assertEquals(false, rollup.typeDataAvailable)
    }

    @Test
    fun `NON_INCREMENTAL_AP requires a present type to also match — AND, not OR`() {
        // Name matches (kapt*) but the populated type is unrelated: must NOT fire.
        val rows = (1..3).map { i -> row("b$i", "kaptDebugKotlin", 150, type = "com.example.SomeUnrelatedTask", incremental = false) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.NON_INCREMENTAL_AP.name })
    }

    @Test
    fun `NON_INCREMENTAL_AP excludes clean-rebuild builds from the denominator`() {
        // 3 non-clean builds (a cache-relevant sibling task shows real avoidance: UP_TO_DATE) where
        // kapt is persistently non-incremental, plus 2 clean/full-rebuild builds (the sibling shows
        // zero avoidance: EXECUTED) where kapt also happens to be non-incremental — the clean builds
        // must not inflate buildsObserved/buildsAffected.
        val nonClean = (1..3).flatMap { i ->
            listOf(
                row("nc$i", "kaptDebugKotlin", 200, incremental = false),
                row("nc$i", "compileDebugKotlin", 500, cacheable = true, outcome = "UP_TO_DATE"),
            )
        }
        val clean = (1..2).flatMap { i ->
            listOf(
                row("cl$i", "kaptDebugKotlin", 200, incremental = false),
                row("cl$i", "compileDebugKotlin", 500, cacheable = true, outcome = "EXECUTED"),
            )
        }
        val rollup = WarningCalculator.compute(nonClean + clean, period = 7)
        val warning = rollup.warnings.single { it.category == WarningCategory.NON_INCREMENTAL_AP.name }
        assertEquals(3, warning.buildsObserved, "the 2 clean/full-rebuild builds must be excluded from the denominator")
        assertEquals(3, warning.buildsAffected)
    }

    @Test
    fun `NON_INCREMENTAL_AP stays silent below MIN_BUILDS after clean-build exclusion`() {
        val rows = (1..2).map { i -> row("b$i", "kaptDebugKotlin", 200, incremental = false) } +
            listOf(
                row("clean1", "kaptDebugKotlin", 200, incremental = false),
                row("clean1", "compileDebugKotlin", 500, cacheable = true, outcome = "EXECUTED"),
            )
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.NON_INCREMENTAL_AP.name }, "only 2 non-clean builds remain — below MIN_BUILDS")
    }

    @Test
    fun `NON_INCREMENTAL_AP stays silent when only a minority of builds are non-incremental`() {
        val rows = listOf(
            row("b1", "kaptDebugKotlin", 200, incremental = false),
            row("b2", "kaptDebugKotlin", 200, incremental = true),
            row("b3", "kaptDebugKotlin", 200, incremental = true),
        )
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.NON_INCREMENTAL_AP.name })
    }

    @Test
    fun `NON_INCREMENTAL_AP never names the offending processor`() {
        val rows = (1..3).map { i -> row("b$i", "kaptDebugKotlin", 200, incremental = false) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        val warning = rollup.warnings.single { it.category == WarningCategory.NON_INCREMENTAL_AP.name }
        assertEquals(null, warning.evidenceReason, "this rule is a proxy — it never carries a processor-identifying string")
    }

    // ---- DYNAMIC_DEBUG_VALUES ----

    @Test
    fun `DYNAMIC_DEBUG_VALUES fires when the group never hits UP_TO_DATE across every build it appeared in`() {
        val rows = (1..3).map { i -> row("b$i", "processDebugManifest", 80, type = "com.android.build.gradle.tasks.ManifestProcessorTask") }
        val rollup = WarningCalculator.compute(rows, period = 7)
        val warning = rollup.warnings.single { it.category == WarningCategory.DYNAMIC_DEBUG_VALUES.name }
        assertEquals(3, warning.buildsObserved)
        assertEquals(3, warning.buildsAffected)
        assertEquals(1.0, warning.share)
        assertEquals(240L, warning.totalMs)
    }

    @Test
    fun `DYNAMIC_DEBUG_VALUES stays silent as soon as a single build hits UP_TO_DATE`() {
        val rows = listOf(
            row("b1", "processDebugManifest", 80, type = "com.android.build.gradle.tasks.ManifestProcessorTask"),
            row("b2", "processDebugManifest", 80, type = "com.android.build.gradle.tasks.ManifestProcessorTask"),
            row("b3", "processDebugManifest", 0, outcome = "UP_TO_DATE", type = "com.android.build.gradle.tasks.ManifestProcessorTask"),
        )
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.DYNAMIC_DEBUG_VALUES.name })
    }

    @Test
    fun `DYNAMIC_DEBUG_VALUES matches by name alone when type is null`() {
        val rows = (1..3).map { i -> row("b$i", "generateDebugBuildConfig", 40, type = null) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.any { it.category == WarningCategory.DYNAMIC_DEBUG_VALUES.name })
    }

    @Test
    fun `a non-AGP task, even if it never hits UP_TO_DATE, is never classified as DYNAMIC_DEBUG_VALUES`() {
        val rows = (1..3).map { i -> row("b$i", "someOtherTask", 40) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.warnings.none { it.category == WarningCategory.DYNAMIC_DEBUG_VALUES.name })
    }

    // ---- rollup-level ----

    @Test
    fun `typeDataAvailable is true when at least one row carries a type`() {
        val rows = (1..3).map { i -> row("b$i", "compileDebugJavaWithJavac", 10, type = "org.gradle.api.tasks.compile.JavaCompile", incremental = false) }
        val rollup = WarningCalculator.compute(rows, period = 7)
        assertTrue(rollup.typeDataAvailable)
    }

    @Test
    fun `ranking is totalMs desc across categories`() {
        val alwaysRun = (1..3).map { i -> row("ar$i", "customTask", 1000, executionReasons = listOf("Task.upToDateWhen is false.")) }
        val nonIncremental = (1..3).map { i -> row("ni$i", "kaptDebugKotlin", 10, incremental = false) }
        val rollup = WarningCalculator.compute(alwaysRun + nonIncremental, period = 7)
        assertEquals(WarningCategory.ALWAYS_RUN.name, rollup.warnings.first().category)
    }
}
