package dev.buildhound.server

import kotlinx.serialization.Serializable

/** One tag value's build count within a project window (plan 057) — feeds the `/v1/tags` picker. */
@Serializable
data class TagValueCount(val value: String, val count: Int)

/**
 * One distinct tag key + its top-N most-frequent values (plan 057), capped
 * ([TagCohortCalculator.MAX_VALUES_PER_KEY]) so a misused high-cardinality tag can't blow up the
 * `/v1/tags` response.
 */
@Serializable
data class TagKeySummary(val key: String, val values: List<TagValueCount>)

/**
 * Raw per-cohort material for one tag value (plan 057): both stores fetch raw per-build rows and
 * fold them through [TagCohortCalculator.groupByCohort], so in-memory and Postgres agree
 * byte-for-byte (the plan-026 parity discipline). [durationsMs] is SUCCESS/FAILED only — the same
 * "finished builds" population `BuildStore.trends` aggregates over — and is what
 * [CohortComparator] takes its median/MAD from; [points] are the daily series for the chart.
 */
data class TagCohortRaw(val value: String, val points: List<TrendPoint>, val durationsMs: List<Long>)

/**
 * One raw per-build row a store fetches for cohort grouping (plan 057). [value] is the build's
 * value for the tag key being split on — a build missing the key never produces a row (no
 * synthetic "null" cohort).
 */
data class TagCohortBuildRow(val value: String, val day: String, val outcome: String, val durationMs: Long, val hitRate: Double?)

/**
 * Groups raw per-build rows into per-cohort daily trend series (plan 057) — the same day-bucketing
 * `BuildStore.trends` uses, applied per cohort value. Both stores feed raw rows through this, so
 * they agree byte-for-byte (the plan-026/032 "raw rows → one pure calculator" discipline
 * `BottleneckCalculator`/`RollupCalculator` established).
 */
object TagCohortCalculator {

    /**
     * Distinct-key and values-per-key caps for `/v1/tags` (plan 057): reuses the existing top-25
     * convention ([RollupCalculator.TOP_N]) rather than inventing a new number.
     */
    const val MAX_KEYS: Int = RollupCalculator.TOP_N
    const val MAX_VALUES_PER_KEY: Int = RollupCalculator.TOP_N

    fun groupByCohort(rows: List<TagCohortBuildRow>): List<TagCohortRaw> =
        rows.groupBy { it.value }.map { (value, group) ->
            val points = group.groupBy { it.day }.toSortedMap().map { (day, dayRows) ->
                // Mirrors BuildStore.trends exactly: interrupted builds count but never feed the
                // duration/hit-rate aggregates (their duration is synthetic).
                val finished = dayRows.filter { it.outcome == "SUCCESS" || it.outcome == "FAILED" }
                val durations = finished.map { it.durationMs }
                val hitRates = finished.mapNotNull { it.hitRate }
                TrendPoint(
                    day = day,
                    builds = dayRows.size,
                    failures = dayRows.count { it.outcome == "FAILED" },
                    avgDurationMs = if (durations.isEmpty()) 0 else Math.round(durations.average()),
                    maxDurationMs = durations.maxOrNull() ?: 0,
                    avgHitRate = hitRates.takeIf { it.isNotEmpty() }?.average(),
                    interrupted = dayRows.count { it.outcome == "INTERRUPTED" },
                )
            }
            val durationsMs = group.filter { it.outcome == "SUCCESS" || it.outcome == "FAILED" }.map { it.durationMs }
            TagCohortRaw(value = value, points = points, durationsMs = durationsMs)
        }

    /** Ranks tag keys and, within each, values — both by build count descending, ties by name. */
    fun tagKeySummaries(tagMaps: List<Map<String, String>>): List<TagKeySummary> {
        val byKey = mutableMapOf<String, MutableMap<String, Int>>()
        for (tags in tagMaps) {
            for ((key, value) in tags) byKey.getOrPut(key) { mutableMapOf() }.merge(value, 1, Int::plus)
        }
        return byKey.entries
            .sortedWith(compareByDescending<Map.Entry<String, MutableMap<String, Int>>> { it.value.values.sum() }.thenBy { it.key })
            .take(MAX_KEYS)
            .map { (key, valueCounts) ->
                val values = valueCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(MAX_VALUES_PER_KEY)
                    .map { TagValueCount(it.key, it.value) }
                TagKeySummary(key, values)
            }
    }
}

/** One cohort's daily series + summary stat (plan 057) — one `/v1/trends/cohorts` chart series. */
@Serializable
data class TagCohortSeries(
    val value: String,
    val sampleCount: Int,
    val medianDurationMs: Long,
    val points: List<TrendPoint>,
)

/**
 * Cohort-vs-reference status (plan 057): honest about small samples, never a causal claim — a
 * confounded split (e.g. a tag skewed to CI or a branch) reads as "investigate"
 * (DISTINGUISHABLE), never a confirmed "N% faster".
 */
enum class CohortStatus { INSUFFICIENT_DATA, DISTINGUISHABLE, INDISTINGUISHABLE }

/**
 * One cohort's delta vs the reference cohort (plan 057). [robustZ] is null when either side is
 * under [RegressionEngine.MIN_BASELINE] or the reference MAD is degenerate (zero) — never a
 * crash, per plan's zero-MAD guard rail.
 */
@Serializable
data class CohortComparisonRow(
    val value: String,
    val medianDeltaMs: Long,
    val pctChange: Double?,
    val robustZ: Double? = null,
    val status: String,
)

/** The reference cohort (most builds — most stable) plus every other cohort's delta (plan 057). */
@Serializable
data class CohortDelta(val referenceValue: String, val comparisons: List<CohortComparisonRow>)

/**
 * `GET /v1/trends/cohorts` response (plan 057): per-cohort series + the delta summary. [delta] is
 * null only when there are no cohorts at all (an unknown tag key never produces a build row).
 */
@Serializable
data class TagCohortComparison(val tagKey: String, val cohorts: List<TagCohortSeries>, val delta: CohortDelta?)

/**
 * Cohort-vs-cohort robust-z delta (plan 057). **Adapts, does not reuse,** plan 025's
 * single-value-vs-baseline verdict: this is one cohort's median vs the reference cohort's
 * median+MAD, not a candidate build vs a rolling baseline. Reuses only
 * [RegressionEngine.median]/[RegressionEngine.mad]/[RegressionEngine.MIN_BASELINE].
 *
 * Reference cohort = the one with the most (finished) builds — the most stable estimate. Every
 * other cohort is compared against it. Honest insufficient-data: either side under
 * [RegressionEngine.MIN_BASELINE] builds ⇒ [CohortStatus.INSUFFICIENT_DATA], never a claimed
 * z-score. A [CohortStatus.DISTINGUISHABLE] delta is a ranked *candidate to investigate*, never a
 * causal claim — a confounded split reads as "investigate", not "confirmed fix" (research
 * narrowing 2).
 */
object CohortComparator {

    /** Cohorts capped to the top-6 by sample count for a readable legend (plan 057). */
    const val MAX_COHORTS: Int = 6

    /**
     * |z| at/above this is a distinguishable candidate signal. Not a per-project [ProjectSettings]
     * knob — unlike plan 025's configurable `warnZ`, [compare] takes no settings — mirrors the
     * default `warnZ` (3.5) as a fixed threshold.
     */
    const val WARN_Z: Double = 3.5

    fun compare(tagKey: String, raw: List<TagCohortRaw>): TagCohortComparison {
        // Deterministic cap + tie-break: largest sample count first, ties by value ascending, so
        // the result never depends on map/row arrival order (the BottleneckCalculator discipline).
        val capped = raw.sortedWith(compareByDescending<TagCohortRaw> { it.durationsMs.size }.thenBy { it.value }).take(MAX_COHORTS)
        if (capped.isEmpty()) return TagCohortComparison(tagKey, emptyList(), null)

        val cohorts = capped.map { r ->
            TagCohortSeries(value = r.value, sampleCount = r.durationsMs.size, medianDurationMs = medianOf(r.durationsMs), points = r.points)
        }

        val reference = capped.first() // already sorted by sample count desc, tie-broken by value
        val refValues = reference.durationsMs.map { it.toDouble() }
        val refMedian = if (refValues.isEmpty()) 0.0 else RegressionEngine.median(refValues)
        val refMad = if (refValues.isEmpty()) 0.0 else RegressionEngine.mad(refValues, refMedian)

        val comparisons = capped.filter { it.value != reference.value }.map { c ->
            val cValues = c.durationsMs.map { it.toDouble() }
            val cMedian = if (cValues.isEmpty()) 0.0 else RegressionEngine.median(cValues)
            val insufficientData = c.durationsMs.size < RegressionEngine.MIN_BASELINE || reference.durationsMs.size < RegressionEngine.MIN_BASELINE
            // Zero-MAD reference (every reference build the same duration) never crashes: z simply
            // stays null and the status falls through to INDISTINGUISHABLE, not a divide-by-zero.
            val z = if (!insufficientData && refMad > 0.0) 0.6745 * (cMedian - refMedian) / refMad else null
            val status = when {
                insufficientData -> CohortStatus.INSUFFICIENT_DATA
                z != null && kotlin.math.abs(z) >= WARN_Z -> CohortStatus.DISTINGUISHABLE
                else -> CohortStatus.INDISTINGUISHABLE
            }
            CohortComparisonRow(
                value = c.value,
                medianDeltaMs = Math.round(cMedian - refMedian),
                pctChange = if (refMedian != 0.0) roundTo6((cMedian - refMedian) / refMedian) else null,
                robustZ = z,
                status = status.name,
            )
        }
        return TagCohortComparison(tagKey, cohorts, CohortDelta(reference.value, comparisons))
    }

    private fun medianOf(values: List<Long>): Long =
        if (values.isEmpty()) 0 else Math.round(RegressionEngine.median(values.map { it.toDouble() }))

    private fun roundTo6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
