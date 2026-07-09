package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.ProcessInfo
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Advisory weight of a [Recommendation] (plan 054); server-local enum, wire model carries the `.name`. */
enum class RecommendationSeverity { INFO, WARN, HIGH }

/**
 * Whether a recommendation's numbers are **measured** from this fleet's own telemetry (observed shares,
 * counts, task durations) or **estimated** from published rules of thumb (the blog "KAPT costs 30–50 %"
 * claim, the "Gradle 10 is expected to require JDK 21" requirement) — plan 054 narrowing 5. A `MEASURED`
 * card's evidence is auditable against this window's builds; an `ESTIMATED` card's `projectedSavingsMs`
 * is a hedged projection, never a promise.
 */
enum class RecommendationOrigin { MEASURED, ESTIMATED }

/**
 * One ranked, advisory recommendation (plan 054, research F4). A **candidate**, never a confirmed fix —
 * the same framing as plan 060's [WarningRow] and plan 065's [TuningCandidate]; nothing is ever
 * auto-applied. [ruleId] is stable and machine-readable (`HYGIENE-CACHE-OFF`, `KAPT-TAX`, `BP-CC-ENABLE`,
 * `G10-READINESS`, `WASTE-CI-X-TEST`, …) so the future MCP/agent surface (F21) can key on it. [evidence]
 * carries observed shares/counts/module — project-internal Gradle names, version strings, and derived
 * numbers only (no absolute paths, PII, or secrets; spec §3.7). [advice]/[title] are **static server-side
 * copy**, so nothing from a payload is echoed unscrubbed. [projectedSavingsMs] is populated only when a
 * rule can hedge a saving; [origin] tells the reader whether it is measured or estimated.
 */
@Serializable
data class Recommendation(
    val ruleId: String,
    val severity: String,
    val title: String,
    val advice: String,
    val evidence: Map<String, String> = emptyMap(),
    val projectedSavingsMs: Long? = null,
    val origin: String,
)

/**
 * `GET /v1/rollups/recommendations` (fleet) and `GET /v1/builds/{buildId}/recommendations` (per-build)
 * response (plan 054): a ranked [recommendations] list plus window meta. [buildsAnalyzed] is how many
 * builds the engine actually saw (the fleet window, or 1 for the per-build variant). Empty (never an
 * error) on no data.
 */
@Serializable
data class RecommendationsRollup(
    /** Window size in days for the fleet route; 0 for the single-build variant (no window). */
    val period: Int,
    val buildsAnalyzed: Int,
    val recommendations: List<Recommendation>,
)

/**
 * Tunable gates for the recommendation rules (plan 054). Bare defaults today — there is no
 * recommendation-settings store yet, so this exists for plan fidelity (`compute(payloads, settings)`) and
 * to name the thresholds in one place, the [WarningCalculator]/[CacheRoiCalculator] `const val` discipline
 * lifted into a struct. Not `@Serializable`: no wire surface.
 */
data class RecommendationSettings(
    /** Fleet cacheable-hit-rate target; below it, HYGIENE-CACHE-HITRATE fires. Reuses the plan-071 constant. */
    val cacheHitTarget: Double = BuildDiagnoser.DEFAULT_CACHE_HIT_TARGET,
    /** Share of CC-observed builds that must be DISABLED before the CC-off cards fire. */
    val ccDisabledShareThreshold: Double = 0.5,
    /** Share of GC-observed builds under GC pressure before HYGIENE-GC fires. */
    val gcPressureShareThreshold: Double = 0.25,
    /** KAPT share of EXECUTED time before KAPT-TAX / BP-KSP-OVER-KAPT fire. */
    val kaptShareThreshold: Double = 0.15,
    /** Share of CI builds excluding tests, or median local verification share, before the waste cards fire. */
    val wasteShareThreshold: Double = 0.30,
    /** The Gradle-10-expected daemon-JDK floor (plan 054 family 4, phrased "expected" — narrowing 5). */
    val jdk21FloorMajor: Int = 21,
) {
    companion object {
        val DEFAULT: RecommendationSettings = RecommendationSettings()
    }
}

/**
 * Pure, server-side rule-based recommendations engine (plan 054, research F4) — the
 * `RegressionEngine`/`BottleneckCalculator` discipline: the store fetches the windowed payloads, the
 * route feeds this pure function, and both stores hand it the identical set so the two agree
 * byte-for-byte (the plan-026/032 parity discipline). No I/O.
 *
 * Five rule families, each a pure fn returning `Recommendation?` (or a short list) that **self-gates to
 * silence** when its signal is absent or insufficient — the `INSUFFICIENT_DATA` guard, so a missing
 * signal is silence, never a false positive. Rules gate on the **share** of *observed* builds, never a
 * minimum build-count: a single build with CC disabled / high GC / KAPT present / JDK < 21 yields a
 * share of 1.0 and each fires — which is exactly why the per-build route (a one-element list) still
 * produces cards (plan 054 exit criterion).
 *
 * **Landed-calculator reuse (the task's central ask):** family 1's GC-pressure primary input is
 * [DaemonTuningCandidates.gcFraction] + [DaemonTuningCandidates.GC_FRACTION_THRESHOLD] (plan 065, so the
 * uptimeS-normalized proxy and its 15 % threshold stay a single source of truth, not a second copy);
 * family 1's hit-rate target is [BuildDiagnoser.DEFAULT_CACHE_HIT_TARGET] (plan 071); JDK-major parsing
 * is [DaemonTuningCandidates.leadingNumericSegment]; medians are [RegressionEngine.median].
 *
 * **Within-054 sharing:** `HYGIENE-CACHE-OFF` (family 1 hygiene threshold) and `BP-CC-ENABLE` (family 3
 * conformance taxonomy) are the same underlying CC-DISABLED share computed **once** in [ccDisabledShare]
 * and emitted under two ruleIds with distinct copy — the plan names both explicitly. `BP-KSP-OVER-KAPT`
 * (family 3) reuses family 2's [kaptStats]. The remaining Best-Practices rule IDs are reserved-dormant
 * ([RESERVED_BP_RULES]) — self-gated to silence, not emitted, because the signal is unavailable today.
 */
object RecommendationEngine {

    /**
     * KSP saving as a fraction of KAPT time (plan 054, ESTIMATED): the midpoint of the blog "KAPT costs
     * 30–50 % more than KSP" claim, hedged — never framed as a measured per-fleet figure.
     */
    const val KSP_SAVINGS_FRACTION: Double = 0.4

    /**
     * Best-Practices rule IDs reserved for future concrete checks once their signal is collected (plan
     * 054 family 3). Rendered **dormant** — self-gated to silence, never emitted with a fabricated value.
     * `BP-PARALLEL-ON` needs plaintext `org.gradle.parallel` fleet aggregation (F1), `BP-CACHE-ON` the
     * build-cache-configured snapshot (F17); both are separate plans. The two concrete checks that DO
     * ship are `BP-CC-ENABLE` and `BP-KSP-OVER-KAPT`.
     */
    val RESERVED_BP_RULES: List<String> = listOf("BP-PARALLEL-ON", "BP-CACHE-ON", "BP-STABLE-GRADLE")

    /** Task NAME patterns for family 5's "verification/packaging" share (lint/test/check/package/bundle/assemble). */
    private val VERIFICATION_NAME_PATTERNS = listOf(
        Regex("test", RegexOption.IGNORE_CASE),
        Regex("lint", RegexOption.IGNORE_CASE),
        Regex("^check", RegexOption.IGNORE_CASE),
        Regex("^package", RegexOption.IGNORE_CASE),
        Regex("^bundle", RegexOption.IGNORE_CASE),
        Regex("^assemble", RegexOption.IGNORE_CASE),
    )

    /** A `-x`/excluded name that targets tests (family 5 CI smell): a `test`-ish task name. */
    private fun excludesTests(names: List<String>): Boolean =
        names.any { it.substringAfterLast(':').contains("test", ignoreCase = true) }

    fun compute(
        payloads: List<BuildPayload>,
        settings: RecommendationSettings = RecommendationSettings.DEFAULT,
    ): List<Recommendation> {
        val kapt = kaptStats(payloads)
        val recommendations = listOfNotNull(
            hygieneCacheOff(payloads, settings),
            hygieneCacheHitRate(payloads, settings),
            hygieneGc(payloads, settings),
            kaptTax(kapt, settings),
            bpCcEnable(payloads, settings),
            bpKspOverKapt(kapt, settings),
            gradle10Readiness(payloads, settings),
            wasteCiExcludeTest(payloads, settings),
            wasteLocalVerification(payloads, settings),
        )
        // Deterministic ranking: severity desc, then the larger hedged saving, then ruleId (stable tie-break).
        return recommendations.sortedWith(
            compareByDescending<Recommendation> { RecommendationSeverity.valueOf(it.severity).ordinal }
                .thenByDescending { it.projectedSavingsMs ?: -1L }
                .thenBy { it.ruleId },
        )
    }

    // ---- Family 1: hygiene / threshold ----------------------------------------------------------

    /** CC-observed builds carry a non-null `environment.configurationCache`; a null is "uncaptured", not "enabled". */
    private data class CcShare(val disabled: Int, val observed: Int) {
        val share: Double get() = if (observed == 0) 0.0 else disabled.toDouble() / observed
    }

    private fun ccDisabledShare(payloads: List<BuildPayload>): CcShare {
        val observed = payloads.filter { it.environment?.configurationCache != null }
        val disabled = observed.count { it.environment?.configurationCache == ConfigurationCacheState.DISABLED }
        return CcShare(disabled = disabled, observed = observed.size)
    }

    /** Family 1 hygiene: the configuration cache is off across the fleet (the single biggest local-iteration win). */
    private fun hygieneCacheOff(payloads: List<BuildPayload>, settings: RecommendationSettings): Recommendation? {
        val cc = ccDisabledShare(payloads)
        if (cc.observed == 0 || cc.disabled == 0 || cc.share < settings.ccDisabledShareThreshold) return null
        return Recommendation(
            ruleId = "HYGIENE-CACHE-OFF",
            severity = RecommendationSeverity.WARN.name,
            title = "Enable the configuration cache",
            advice = "${percent(cc.share)} of analyzed builds (${cc.disabled} of ${cc.observed}) ran with the " +
                "configuration cache disabled. Enabling it (org.gradle.configuration-cache=true) skips the " +
                "configuration phase on a hit — usually the largest single local-iteration win.",
            evidence = mapOf(
                "disabledBuilds" to cc.disabled.toString(),
                "ccObservedBuilds" to cc.observed.toString(),
                "disabledShare" to round6(cc.share).toString(),
            ),
            origin = RecommendationOrigin.MEASURED.name,
        )
    }

    /** Family 1 hygiene: the fleet's median cacheable-hit rate sits below the v1 target. */
    private fun hygieneCacheHitRate(payloads: List<BuildPayload>, settings: RecommendationSettings): Recommendation? {
        val rates = payloads.mapNotNull { it.derived?.cacheableHitRate }
        if (rates.isEmpty()) return null
        val median = RegressionEngine.median(rates)
        if (median >= settings.cacheHitTarget) return null
        return Recommendation(
            ruleId = "HYGIENE-CACHE-HITRATE",
            severity = RecommendationSeverity.INFO.name,
            title = "Cacheable-task hit rate is below target",
            advice = "The median cacheable-task hit rate over ${rates.size} build(s) is ${percent(median)}, below " +
                "the ${percent(settings.cacheHitTarget)} target. Investigate non-relocatable tasks and volatile " +
                "inputs (see /rollups/cache-miss-diagnostics) to recover avoided time.",
            evidence = mapOf(
                "medianHitRate" to round6(median).toString(),
                "target" to round6(settings.cacheHitTarget).toString(),
                "buildsWithHitRate" to rates.size.toString(),
            ),
            origin = RecommendationOrigin.MEASURED.name,
        )
    }

    /**
     * Family 1 hygiene: daemon GC pressure (plan 029 process probe). Reuses [DaemonTuningCandidates
     * .gcFraction] + [DaemonTuningCandidates.GC_FRACTION_THRESHOLD] (plan 065) so the uptimeS-normalized
     * proxy and its 15 % threshold stay a single source of truth. A build "fires" when any of its probed
     * processes exceeds the threshold; the rule fires when that share of GC-observed builds clears the
     * gate. The `gcTimeMs÷uptimeS` proxy is coarse (jstat GCT is cumulative since daemon start — a finer
     * per-PID delta is F15's scope), which the advice copy states honestly.
     */
    private fun hygieneGc(payloads: List<BuildPayload>, settings: RecommendationSettings): Recommendation? {
        val gcObserved = payloads.filter { p -> p.processes.any { gcFractionOf(p, it) != null } }
        if (gcObserved.isEmpty()) return null
        val pressured = gcObserved.filter { p ->
            p.processes.any { proc ->
                val f = gcFractionOf(p, proc)
                f != null && f >= DaemonTuningCandidates.GC_FRACTION_THRESHOLD
            }
        }
        val share = pressured.size.toDouble() / gcObserved.size
        if (pressured.isEmpty() || share < settings.gcPressureShareThreshold) return null
        return Recommendation(
            ruleId = "HYGIENE-GC",
            severity = RecommendationSeverity.INFO.name,
            title = "Raise daemon heap — GC pressure observed",
            advice = "${percent(share)} of builds with a process probe (${pressured.size} of ${gcObserved.size}) " +
                "showed a JVM spending over ${percent(DaemonTuningCandidates.GC_FRACTION_THRESHOLD)} of its " +
                "lifetime in GC — consider raising the heap (org.gradle.jvmargs / kotlin.daemon.jvmargs). This " +
                "gcTimeMs÷uptimeS ratio is a coarse lifetime proxy, not a per-build GC %.",
            evidence = mapOf(
                "pressuredBuilds" to pressured.size.toString(),
                "gcObservedBuilds" to gcObserved.size.toString(),
                "pressuredShare" to round6(share).toString(),
                "gcFractionThreshold" to round6(DaemonTuningCandidates.GC_FRACTION_THRESHOLD).toString(),
            ),
            origin = RecommendationOrigin.MEASURED.name,
        )
    }

    private fun gcFractionOf(payload: BuildPayload, process: ProcessInfo): Double? =
        DaemonTuningCandidates.gcFraction(
            process = process,
            environment = payload.environment,
            buildDurationMs = payload.finishedAt - payload.startedAt,
            priorProcesses = emptyList(),
        )

    // ---- Family 2: KAPT tax ---------------------------------------------------------------------

    /**
     * Fleet KAPT accounting (plan 054 family 2). Only builds whose task-type dictionary is populated
     * participate — a build under isolated projects (`type` null everywhere, plan 016) is "no signal" and
     * is excluded from both numerator and denominator, never read as "KAPT absent" (narrowing 4). Builds
     * already on KSP (`toolchain.ksp != null`) are suppressed. [kaptMs] sums EXECUTED tasks whose `type`
     * **contains** `Kapt` (substring, not exact FQCN — plan 016 strips `_Decorated`, the class moves
     * across versions); [executedMs] is all EXECUTED task time in those builds. [topModule] is the module
     * carrying the most KAPT time (deterministic tie-break by name).
     */
    private data class KaptStats(
        val kaptMs: Long,
        val executedMs: Long,
        val typeDataAvailable: Boolean,
        val topModule: String?,
    ) {
        val share: Double get() = if (executedMs == 0L) 0.0 else kaptMs.toDouble() / executedMs
    }

    private fun kaptStats(payloads: List<BuildPayload>): KaptStats {
        // A build participates only if some task carries a type (dictionary populated) and it is not on KSP.
        val considered = payloads.filter { p -> p.toolchain?.ksp == null && p.tasks.any { it.type != null } }
        var kaptMs = 0L
        var executedMs = 0L
        val kaptMsByModule = LinkedHashMap<String, Long>()
        for (payload in considered) {
            for (task in payload.tasks) {
                if (task.outcome != TaskOutcome.EXECUTED) continue
                executedMs += task.durationMs
                if (task.type?.contains("Kapt") == true) {
                    kaptMs += task.durationMs
                    val module = task.module ?: ""
                    kaptMsByModule[module] = (kaptMsByModule[module] ?: 0L) + task.durationMs
                }
            }
        }
        val topModule = kaptMsByModule.entries
            .maxWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            ?.key
            ?.ifEmpty { null }
        return KaptStats(
            kaptMs = kaptMs,
            executedMs = executedMs,
            typeDataAvailable = considered.any { p -> p.tasks.any { it.type != null } },
            topModule = topModule,
        )
    }

    /** Family 2: KAPT is eating a large share of EXECUTED time; projected KSP saving is hedged (ESTIMATED). */
    private fun kaptTax(kapt: KaptStats, settings: RecommendationSettings): Recommendation? {
        if (!kapt.typeDataAvailable || kapt.kaptMs == 0L || kapt.share < settings.kaptShareThreshold) return null
        val projected = Math.round(kapt.kaptMs * KSP_SAVINGS_FRACTION)
        return Recommendation(
            ruleId = "KAPT-TAX",
            severity = RecommendationSeverity.WARN.name,
            title = "Migrate KAPT to KSP",
            advice = "KAPT accounts for ${percent(kapt.share)} of EXECUTED build time" +
                (kapt.topModule?.let { " (heaviest in $it)" } ?: "") +
                ". Migrating annotation processors to KSP typically saves 30–50 % of that time — a projected " +
                "~${projected} ms per the observed KAPT total, hedged (not a measured per-fleet figure).",
            evidence = buildMap {
                put("kaptMs", kapt.kaptMs.toString())
                put("executedMs", kapt.executedMs.toString())
                put("kaptShare", round6(kapt.share).toString())
                kapt.topModule?.let { put("topModule", it) }
            },
            projectedSavingsMs = projected,
            origin = RecommendationOrigin.ESTIMATED.name,
        )
    }

    // ---- Family 3: conformance (BP-*) -----------------------------------------------------------

    /** Family 3 conformance: the same CC-DISABLED share as HYGIENE-CACHE-OFF, framed as a Best-Practices rule. */
    private fun bpCcEnable(payloads: List<BuildPayload>, settings: RecommendationSettings): Recommendation? {
        val cc = ccDisabledShare(payloads)
        if (cc.observed == 0 || cc.disabled == 0 || cc.share < settings.ccDisabledShareThreshold) return null
        return Recommendation(
            ruleId = "BP-CC-ENABLE",
            severity = RecommendationSeverity.INFO.name,
            title = "Best Practices: enable the configuration cache",
            advice = "The Gradle/Google/JetBrains Best Practices guide recommends enabling the configuration " +
                "cache. ${cc.disabled} of ${cc.observed} CC-observed builds are non-conformant (disabled).",
            evidence = mapOf(
                "disabledBuilds" to cc.disabled.toString(),
                "ccObservedBuilds" to cc.observed.toString(),
                "disabledShare" to round6(cc.share).toString(),
            ),
            origin = RecommendationOrigin.MEASURED.name,
        )
    }

    /** Family 3 conformance: reuses family 2's KAPT signal — the guide's "prefer KSP over KAPT" rule. */
    private fun bpKspOverKapt(kapt: KaptStats, settings: RecommendationSettings): Recommendation? {
        if (!kapt.typeDataAvailable || kapt.kaptMs == 0L || kapt.share < settings.kaptShareThreshold) return null
        return Recommendation(
            ruleId = "BP-KSP-OVER-KAPT",
            severity = RecommendationSeverity.INFO.name,
            title = "Best Practices: prefer KSP over KAPT",
            advice = "The Best Practices guide recommends KSP over KAPT for annotation processing. KAPT is " +
                "present and accounts for ${percent(kapt.share)} of EXECUTED time in the window.",
            evidence = mapOf(
                "kaptMs" to kapt.kaptMs.toString(),
                "kaptShare" to round6(kapt.share).toString(),
            ),
            origin = RecommendationOrigin.ESTIMATED.name,
        )
    }

    // ---- Family 4: Gradle-10 readiness ----------------------------------------------------------

    /**
     * Family 4: a Gradle-10 readiness card composed from the CC-DISABLED fraction + distinct daemon-JDK
     * majors below the expected floor + (when present) parent-lookup deprecations. The parent-lookup term
     * is added only when `extensions.internalAdapters.deprecations` is present (plan 047); otherwise the
     * card renders from CC+JDK with an explicit "deprecation signal unavailable" note (narrowing 3). The
     * JDK-21 requirement is phrased "expected", so the card's `origin` is ESTIMATED (narrowing 5). Fires
     * only when a real readiness gap exists (CC disabled, a sub-floor JDK, or deprecations); a fully-ready
     * fleet stays silent.
     */
    private fun gradle10Readiness(payloads: List<BuildPayload>, settings: RecommendationSettings): Recommendation? {
        val cc = ccDisabledShare(payloads)
        val subFloorJdks = payloads
            .mapNotNull { it.toolchain?.jdk }
            .mapNotNull { DaemonTuningCandidates.leadingNumericSegment(it) }
            .filter { it < settings.jdk21FloorMajor }
            .distinct()
            .sorted()
        val deprecations = deprecationCount(payloads)

        val ccGap = cc.disabled > 0
        val jdkGap = subFloorJdks.isNotEmpty()
        val depGap = deprecations != null && deprecations > 0
        if (!ccGap && !jdkGap && !depGap) return null

        val terms = buildList {
            if (jdkGap) add("daemon JDK major(s) below ${settings.jdk21FloorMajor}: ${subFloorJdks.joinToString(", ")}")
            if (ccGap) add("${cc.disabled} of ${cc.observed} builds run with the configuration cache disabled")
            if (depGap) {
                add("$deprecations deprecation warning(s) observed via internal-adapters")
            } else {
                add("parent-lookup deprecation signal unavailable (needs the internal-adapters deprecations module)")
            }
        }
        val evidence = buildMap {
            put("ccDisabledShare", round6(cc.share).toString())
            put("ccDisabledBuilds", cc.disabled.toString())
            if (jdkGap) put("subFloorJdkMajors", subFloorJdks.joinToString(","))
            put("deprecationsAvailable", (deprecations != null).toString())
            deprecations?.let { put("deprecationCount", it.toString()) }
        }
        return Recommendation(
            ruleId = "G10-READINESS",
            // A sub-floor daemon JDK is a hard blocker for the expected Gradle-10 requirement (HIGH); a
            // CC/deprecation-only gap is advisory (INFO).
            severity = (if (jdkGap) RecommendationSeverity.HIGH else RecommendationSeverity.INFO).name,
            title = "Prepare for Gradle 10",
            advice = "Gradle 10 is expected to require JDK 21+ and to remove long-deprecated APIs. Readiness " +
                "gaps: ${terms.joinToString("; ")}.",
            evidence = evidence,
            origin = RecommendationOrigin.ESTIMATED.name,
        )
    }

    /**
     * Total `extensions.internalAdapters.deprecations` entries across the window (plan 047), via guarded
     * JsonElement navigation — the server keeps **no** dependency on `buildhound-internal-adapters` (the
     * plan-039 decoupling invariant), so an unexpected shape degrades to "not present" rather than
     * crashing. Null when **no** windowed build carried the block at all (the honest "signal unavailable"
     * state family 4 renders a note for); 0 when the block was present but empty.
     */
    private fun deprecationCount(payloads: List<BuildPayload>): Int? {
        var total = 0
        var seen = false
        for (payload in payloads) {
            val element = payload.extensions["internalAdapters"] ?: continue
            val list = runCatching { element.jsonObject["deprecations"]?.jsonArray }.getOrNull() ?: continue
            seen = true
            total += list.mapNotNull { it.jsonPrimitive.contentOrNull }.size
        }
        return if (seen) total else null
    }

    // ---- Family 5: wasted work ------------------------------------------------------------------

    /** Family 5: the habitual `-x test`-on-CI smell, over the new `excludedTaskNames` field. */
    private fun wasteCiExcludeTest(payloads: List<BuildPayload>, settings: RecommendationSettings): Recommendation? {
        val ci = payloads.filter { it.mode == BuildMode.CI }
        if (ci.isEmpty()) return null
        val excludingTests = ci.filter { excludesTests(it.excludedTaskNames) }
        if (excludingTests.isEmpty()) return null
        val share = excludingTests.size.toDouble() / ci.size
        if (share < settings.wasteShareThreshold) return null
        return Recommendation(
            ruleId = "WASTE-CI-X-TEST",
            severity = RecommendationSeverity.WARN.name,
            title = "CI habitually excludes tests",
            advice = "${percent(share)} of CI builds (${excludingTests.size} of ${ci.size}) excluded tests via " +
                "-x / --exclude-task. CI is where tests belong; excluding them habitually removes the safety net " +
                "that catches regressions before merge.",
            evidence = mapOf(
                "ciBuildsExcludingTest" to excludingTests.size.toString(),
                "ciBuilds" to ci.size.toString(),
                "excludeShare" to round6(share).toString(),
            ),
            origin = RecommendationOrigin.MEASURED.name,
        )
    }

    /**
     * Family 5: iterative LOCAL builds spending a large median share of EXECUTED time on verification /
     * packaging (lint/test/check/package/bundle/assemble) — work an inner-loop `assembleDebug`/unit-test
     * iteration rarely needs on every run. Median (not sum) so one heavy CI-shaped local build doesn't
     * dominate; self-gated to silence when no LOCAL build carries EXECUTED work.
     */
    private fun wasteLocalVerification(payloads: List<BuildPayload>, settings: RecommendationSettings): Recommendation? {
        val shares = payloads
            .filter { it.mode == BuildMode.LOCAL }
            .mapNotNull { verificationShareOf(it.tasks) }
        if (shares.isEmpty()) return null
        val median = RegressionEngine.median(shares)
        if (median < settings.wasteShareThreshold) return null
        return Recommendation(
            ruleId = "WASTE-LOCAL-VERIFICATION",
            severity = RecommendationSeverity.INFO.name,
            title = "Local builds spend heavily on verification/packaging",
            advice = "Across ${shares.size} local build(s), a median ${percent(median)} of EXECUTED time went to " +
                "verification/packaging tasks (lint/test/check/package/bundle/assemble). For a fast inner loop, " +
                "scope local invocations to the module and task under change rather than a full verify.",
            evidence = mapOf(
                "localBuilds" to shares.size.toString(),
                "medianVerificationShare" to round6(median).toString(),
            ),
            origin = RecommendationOrigin.MEASURED.name,
        )
    }

    /** EXECUTED verification/packaging ms ÷ total EXECUTED ms for one build; null when it did no EXECUTED work. */
    private fun verificationShareOf(tasks: List<TaskExecution>): Double? {
        val executed = tasks.filter { it.outcome == TaskOutcome.EXECUTED }
        val totalMs = executed.sumOf { it.durationMs }
        if (totalMs == 0L) return null
        val verifyMs = executed
            .filter { task -> VERIFICATION_NAME_PATTERNS.any { it.containsMatchIn(task.path.substringAfterLast(':')) } }
            .sumOf { it.durationMs }
        return verifyMs.toDouble() / totalMs
    }

    // ---- shared formatting ----------------------------------------------------------------------

    private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0

    private fun percent(fraction: Double): String = "${Math.round(fraction * 100)}%"
}
