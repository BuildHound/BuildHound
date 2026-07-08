package dev.buildhound.server

import dev.buildhound.commons.payload.PayloadScrubber
import kotlinx.serialization.Serializable

/**
 * Fixed Build-Analyzer-style warning taxonomy (plan 060, research F10) [WarningCalculator] evaluates.
 * Server-local enum (not `@Serializable`), mirroring the [RerunCause]/[FlakySignal] convention — the
 * wire model ([WarningRow.category]) carries the plain `.name` string, never the enum type. Two of
 * the three (`ALWAYS_RUN`, `NON_INCREMENTAL_AP`) are drawn from Android Studio's Build Analyzer; the
 * third (`DYNAMIC_DEBUG_VALUES`) is not a Build Analyzer family — it is sourced from the separate
 * Gradle/Android profile-your-build guidance (see the plan's Source section). Build Analyzer's fourth
 * family, `task-setup-conflict`, has no feasible rule from current telemetry (no output-property
 * data) and is deliberately not modeled here.
 */
enum class WarningCategory { ALWAYS_RUN, NON_INCREMENTAL_AP, DYNAMIC_DEBUG_VALUES }

/**
 * One ranked warning candidate (plan 060). Every row is a *candidate*, never a confirmed fix — the
 * plan's load-bearing framing, enforced by [WarningCalculator]'s thresholds and echoed in the
 * dashboard's "likely/investigate" copy. [buildsObserved] is the distinct builds the group ([key])
 * appeared in at all (any outcome) over the window; [buildsAffected] is the subset where the rule
 * actually fired; [share] = affected/observed. [totalMs] is the rule's attributable EXECUTED
 * milliseconds (summed only over the builds where the rule fired) — the ranking metric across
 * categories. [evidenceReason] is populated only for `ALWAYS_RUN` (a representative matched
 * `executionReasons` string); it is the same `executionReasons` value [RerunCauseRollupCalculator]
 * reads — scrubbed client-side by the plugin before upload (spec §3.7) and, since plan 076,
 * scrubbed a second time defensively at server ingest (`Routes.kt`'s
 * `PayloadCapper.cap(PayloadScrubber.scrub(payload, emptyList()))`) before it is ever stored. Not
 * a new exposure: `GET /v1/builds/{buildId}` already returns these same stored strings at the
 * same read scope. This coverage claim is specific to `executionReasons` (and the other fields
 * enumerated in `PayloadScrubber`'s class KDoc) — plan 076's ingest scrub does not cover every
 * field on the payload; tag values, `ci.*`/`vcs.*`, links, `requestedTasks`, `environment.*`, and
 * `extensions` are untouched by design (spec §3.7 scope) and reach storage/reads unscrubbed.
 */
@Serializable
data class WarningRow(
    val category: String,
    val key: String,
    val module: String? = null,
    val buildsObserved: Int,
    val buildsAffected: Int,
    val share: Double,
    val totalMs: Long,
    val evidenceReason: String? = null,
)

/**
 * `GET /v1/rollups/warnings` response (plan 060): ranked candidates over the last [period] days.
 * [typeDataAvailable] mirrors [TaskDurationRollup.byTypeAvailable]/[PluginCostRollup.available] —
 * false only when *no* row in the window carries a `type` (isolated-projects, plan 016's empty
 * `whenReady` dictionary) — the rules still fire (name-driven), but the UI should say so.
 */
@Serializable
data class WarningsRollup(val period: Int, val warnings: List<WarningRow>, val typeDataAvailable: Boolean)

/**
 * Pure warning-taxonomy math (plan 060, research F10), the single source both stores defer to (the
 * plan-026/032 parity discipline). Takes flat [TaskRow]s only — no separate `BuildKpiRow` list: that
 * DTO ([BottleneckCalculator]'s own input) carries no `buildId`, so it cannot be joined back to a
 * specific build for the per-build clean-rebuild judgment [nonIncrementalAp] needs; every input this
 * calculator requires ([TaskRow.cacheable], [TaskRow.outcome], [TaskRow.incremental],
 * [TaskRow.executionReasons]) is already present on the shared row type.
 *
 * Every rule requires at least [MIN_BUILDS] distinct builds before it is even considered (a
 * single-build fluke never ranks as a candidate), and fires only when its share of affected builds
 * clears a per-rule threshold — see each rule function's KDoc. Determinism matters as much as it does
 * for [RerunCauseRollupCalculator]: in-memory feeds builds in `ConcurrentHashMap` iteration order,
 * Postgres's jsonb scan has no `ORDER BY`, so every output field here is computed order-invariantly
 * (sums, counts, `distinct().singleOrNull()`, and — the one non-obvious case — [WarningRow.evidenceReason],
 * picked via `minOrNull()` over every matched reason string rather than "first seen").
 */
object WarningCalculator {

    /** A group needs at least this many distinct observed builds before any rule considers it. */
    const val MIN_BUILDS = 3

    /**
     * "~100%" per the plan wording: real fleets have noise (a rerun, a flaky agent), so ALWAYS_RUN and
     * NON_INCREMENTAL_AP fire above this share rather than requiring an exact 1.0.
     */
    const val ALWAYS_RUN_SHARE_THRESHOLD = 0.9
    const val NON_INCREMENTAL_SHARE_THRESHOLD = 0.9

    /**
     * DYNAMIC_DEBUG_VALUES is an absolute "never" (plan wording, not "~never"), so this is compared
     * with `>=` against a share that — by construction — can only land on 0.0 or 1.0. Named for
     * symmetry with the other two thresholds, not because intermediate values are possible.
     */
    private const val DYNAMIC_DEBUG_SHARE_THRESHOLD = 1.0

    /**
     * "Task always runs" reason patterns (Build Analyzer's AlwaysRunTaskIssue), matched as
     * case-insensitive substrings with no fire on an unrecognized reason (F11 version-tolerance
     * discipline). `"uptodatewhen is false"` is a verified-exact Gradle 9.6.1 message
     * ([RerunCauseClassifier] classifies the identical substring into its own, different, `FORCED`
     * bucket for the rerun-cause taxonomy — same raw text, a different rule here). `"has not declared
     * any outputs"` is template-reconstructed, not golden-fixture-confirmed (plan Risks).
     */
    private val ALWAYS_RUN_PATTERNS = listOf("uptodatewhen is false", "has not declared any outputs")

    private val AP_NAME_PATTERNS = listOf(
        Regex("^kapt", RegexOption.IGNORE_CASE),
        Regex("^ksp", RegexOption.IGNORE_CASE),
        Regex("JavaWithJavac$", RegexOption.IGNORE_CASE),
    )

    /** Not golden-fixture-confirmed (no bundled AP report to check against, plan Risks) — a best-effort catalog, matched as substrings for version tolerance. */
    private val AP_TYPE_SUBSTRINGS = listOf("org.gradle.api.tasks.compile.JavaCompile", "KaptTask", "KaptGenerateStubs")

    private val AGP_NAME_PATTERNS = listOf(
        Regex("^process.*Manifest$", RegexOption.IGNORE_CASE),
        Regex("^generate.*BuildConfig$", RegexOption.IGNORE_CASE),
    )

    fun compute(rows: List<TaskRow>, period: Int): WarningsRollup {
        val cleanBuildIds = cleanBuildIds(rows)
        val warnings = rows.groupBy { it.type ?: it.name }
            .flatMap { (key, groupRows) ->
                listOfNotNull(
                    alwaysRun(key, groupRows),
                    nonIncrementalAp(key, groupRows, cleanBuildIds),
                    dynamicDebugValues(key, groupRows),
                )
            }
            .sortedWith(compareByDescending<WarningRow> { it.totalMs }.thenBy { it.category }.thenBy { it.key })
        return WarningsRollup(period = period, warnings = warnings, typeDataAvailable = rows.any { it.type != null })
    }

    /**
     * Full-rebuild builds (plan 060, the "Clean-build exclusion" design note): a build whose avoided
     * share (`UP_TO_DATE`+`FROM_CACHE` over its own cache-relevant tasks — the same
     * cache-relevant-only denominator the 2026-07-03 decision log pins for `cacheableHitRate`) is
     * *exactly* zero is a clean/full rebuild, where [nonIncrementalAp]'s `incremental == false` is
     * expected, not a regression signal. A build with no cache-relevant task at all (`cacheable`
     * never populated — pre-016 payload, or isolated-projects degradation) cannot be judged either
     * way, so it is **not** excluded — never silently drop non-incremental evidence just because
     * `cacheable` happens to be absent.
     *
     * Aligned with `DerivedMetricsCalculator.cacheableHitRate`'s `considered` denominator: a
     * cache-relevant task only counts toward the avoided-share judgment when its outcome is EXECUTED,
     * FROM_CACHE, or UP_TO_DATE. A cache-relevant task that landed as e.g. SKIPPED, NO_SOURCE, or
     * FAILED carries no avoidance evidence either way and must not, on its own, make a build's
     * cache-relevant set non-empty — otherwise a build whose only cache-relevant tasks are SKIPPED
     * would be misjudged "clean" (avoided share vacuously 0-of-0) instead of "unjudgeable", the same
     * distinction `cacheableHitRate` draws by returning null when its `considered` count is 0.
     */
    private fun cleanBuildIds(rows: List<TaskRow>): Set<String> =
        rows.groupBy { it.buildId }
            .filterValues { buildRows ->
                val cacheRelevant = buildRows.filter { it.cacheable == true || it.outcome == "FROM_CACHE" }
                val considered = cacheRelevant.filter { it.outcome == "EXECUTED" || it.outcome == "FROM_CACHE" || it.outcome == "UP_TO_DATE" }
                considered.isNotEmpty() && considered.none { it.outcome == "UP_TO_DATE" || it.outcome == "FROM_CACHE" }
            }
            .keys

    /**
     * "This task always runs" (Build Analyzer's AlwaysRunTaskIssue). A build "fires" when *any* of its
     * EXECUTED occurrences of the group carries a reason matching [ALWAYS_RUN_PATTERNS]; the rule
     * fires when the share of fired builds (over every build the group appeared in, any outcome)
     * clears [ALWAYS_RUN_SHARE_THRESHOLD]. An unrecognized reason simply doesn't count toward
     * "fired" — it never forces a false positive (the plan's "unclassified fallback ... no fire").
     *
     * [totalMs] sums only the EXECUTED *occurrences whose own reason matched* — not every EXECUTED
     * occurrence in a fired build. This matters for a multi-module group (grouped by [key] =
     * `type ?: name`, which spans task paths in every module): if only one module's occurrence of the
     * group matched in a given build, an unrelated module's occurrence of the same group must not have
     * its duration folded into this warning just because the build as a whole fired (mirrors
     * [nonIncrementalAp]'s `filter { !it.incremental }` before summing).
     */
    private fun alwaysRun(key: String, rows: List<TaskRow>): WarningRow? {
        val byBuild = rows.groupBy { it.buildId }
        val observed = byBuild.size
        if (observed < MIN_BUILDS) return null
        var affected = 0
        var totalMs = 0L
        val matchedReasons = mutableListOf<String>()
        byBuild.values.forEach { buildRows ->
            val executed = buildRows.filter { it.outcome == "EXECUTED" }
            val matchedRows = executed.filter { row ->
                row.executionReasons.any { reason -> ALWAYS_RUN_PATTERNS.any { pattern -> reason.lowercase().contains(pattern) } }
            }
            if (matchedRows.isNotEmpty()) {
                affected++
                totalMs += matchedRows.sumOf { it.durationMs }
                matchedRows.forEach { row ->
                    matchedReasons += row.executionReasons.filter { reason ->
                        ALWAYS_RUN_PATTERNS.any { pattern -> reason.lowercase().contains(pattern) }
                    }
                }
            }
        }
        val share = affected.toDouble() / observed
        if (share < ALWAYS_RUN_SHARE_THRESHOLD) return null
        return WarningRow(
            category = WarningCategory.ALWAYS_RUN.name,
            key = key,
            module = soleModule(rows),
            buildsObserved = observed,
            buildsAffected = affected,
            share = roundTo6(share),
            totalMs = totalMs,
            // Deterministic representative, NOT "first seen" (byte-for-byte parity discipline: the two
            // stores feed builds in different orders — see the class KDoc). Defense-in-depth scrub
            // (§3.2 hardening, commit 8916dcf): added when the server did not yet re-scrub stored
            // payloads and this route echoed the string straight back out. Since plan 076 wired a
            // defensive PayloadScrubber pass into ingest itself, this call is now belt-and-braces (a
            // true no-op, by the scrubber's idempotency property) for any build ingested after 076
            // landed — but retroactive scrub of already-stored rows is explicitly out of scope for
            // that plan, so this guard remains the *sole* protection for evidenceReason on every
            // pre-076 row. Kept, not removed.
            evidenceReason = matchedReasons.minOrNull()?.let { PayloadScrubber.scrubText(it, emptyList()) },
        )
    }

    /**
     * "Non-incremental annotation processing" (Build Analyzer's AnnotationProcessorsAnalyzerResult,
     * generalized to any Java-compile task). [apGroupMatches] selects the group primarily by task
     * NAME (`kapt*`, `ksp*`, `compile*JavaWithJavac`); when a `type` is populated it must *also*
     * match ([AP_TYPE_SUBSTRINGS]) — AND, not OR, so type corroborates a name match rather than
     * independently admitting an unrelated task that merely shares a name pattern. Under
     * isolated-projects (`type` null) name alone decides — degrades, never vanishes
     * ([WarningsRollup.typeDataAvailable] tells the UI it's name-only).
     *
     * Clean/full-rebuild builds ([cleanBuildIds]) are excluded from both the numerator and
     * denominator. A non-excluded build "fires" when *any* of its EXECUTED occurrences of the group
     * has `incremental == false` (mirrors [alwaysRun]'s per-build ANY semantics); the rule fires when
     * that share clears [NON_INCREMENTAL_SHARE_THRESHOLD]. This can never name the offending
     * processor (not collected) — a proxy candidate, stated in the dashboard copy.
     */
    private fun nonIncrementalAp(key: String, rows: List<TaskRow>, cleanBuildIds: Set<String>): WarningRow? {
        if (!apGroupMatches(rows)) return null
        val executedByBuild = rows
            .filter { it.buildId !in cleanBuildIds && it.outcome == "EXECUTED" }
            .groupBy { it.buildId }
        val observed = executedByBuild.size
        if (observed < MIN_BUILDS) return null
        val affectedBuilds = executedByBuild.filterValues { executed -> executed.any { !it.incremental } }
        val affected = affectedBuilds.size
        val share = affected.toDouble() / observed
        if (share < NON_INCREMENTAL_SHARE_THRESHOLD) return null
        val totalMs = affectedBuilds.values.sumOf { executed -> executed.filter { !it.incremental }.sumOf { it.durationMs } }
        return WarningRow(
            category = WarningCategory.NON_INCREMENTAL_AP.name,
            key = key,
            module = soleModule(rows),
            buildsObserved = observed,
            buildsAffected = affected,
            share = roundTo6(share),
            totalMs = totalMs,
        )
    }

    private fun apGroupMatches(rows: List<TaskRow>): Boolean {
        val nameMatches = rows.any { row -> AP_NAME_PATTERNS.any { it.containsMatchIn(row.name) } }
        if (!nameMatches) return false
        val type = rows.firstNotNullOfOrNull { it.type } ?: return true
        return AP_TYPE_SUBSTRINGS.any { type.contains(it) }
    }

    /**
     * "Dynamic debug values" — not a Build Analyzer family; sourced from the separate Gradle/Android
     * profile-your-build guidance (research F10, plan Source section). [agpManifestGroupMatches]
     * selects an AGP manifest/BuildConfig group by task NAME (`process*Manifest`,
     * `generate*BuildConfig`), corroborated by the shared `"com.android.build."` prefix
     * ([PluginAttribution]'s own catalog convention) when `type` is populated — this repo has no
     * bundled AGP jar to verify an exact internal task-type FQCN against (unlike
     * [RerunCauseClassifier]'s Gradle-jar-verified strings), so this stays a coarse, honestly-hedged
     * heuristic (plan Risks). Fires only when the group **never** hits `UP_TO_DATE` across every
     * build it appeared in (an always-executes pattern typical of a debug `buildConfigField`/
     * `resValue` that embeds a changing value, e.g. a timestamp) — an absolute "never", so the
     * comparison is against [DYNAMIC_DEBUG_SHARE_THRESHOLD] (1.0) rather than a soft "~100%".
     */
    private fun dynamicDebugValues(key: String, rows: List<TaskRow>): WarningRow? {
        if (!agpManifestGroupMatches(rows)) return null
        val byBuild = rows.groupBy { it.buildId }
        val observed = byBuild.size
        if (observed < MIN_BUILDS) return null
        val neverUpToDate = byBuild.filterValues { buildRows -> buildRows.none { it.outcome == "UP_TO_DATE" } }
        val affected = neverUpToDate.size
        val share = affected.toDouble() / observed
        if (share < DYNAMIC_DEBUG_SHARE_THRESHOLD) return null
        val totalMs = neverUpToDate.values.sumOf { buildRows -> buildRows.filter { it.outcome == "EXECUTED" }.sumOf { it.durationMs } }
        return WarningRow(
            category = WarningCategory.DYNAMIC_DEBUG_VALUES.name,
            key = key,
            module = soleModule(rows),
            buildsObserved = observed,
            buildsAffected = affected,
            share = roundTo6(share),
            totalMs = totalMs,
        )
    }

    private fun agpManifestGroupMatches(rows: List<TaskRow>): Boolean {
        val nameMatches = rows.any { row -> AGP_NAME_PATTERNS.any { it.containsMatchIn(row.name) } }
        if (!nameMatches) return false
        val type = rows.firstNotNullOfOrNull { it.type } ?: return true
        return type.startsWith("com.android.build.") && (type.contains("Manifest") || type.contains("BuildConfig"))
    }

    private fun soleModule(rows: List<TaskRow>): String? = rows.map { it.module }.distinct().singleOrNull()

    private fun roundTo6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
