package dev.buildhound.server

/**
 * Per-project metrics snapshot for the Prometheus egress endpoint (plan 070, spec §5 F20). Every field
 * is `null`/omitted when the window carries no underlying samples for it — the omit-not-zero rule
 * (never a spurious `0`, which reads as "broken" in Grafana — the confusion F17 warns of). [windowDays]
 * is the only field always present (it is the request's window, not a KPI).
 */
data class MetricsSnapshot(
    val windowDays: Int,
    val p50DurationMs: Long? = null,
    val p95DurationMs: Long? = null,
    val cacheHitRate: Double? = null,
    val successRate: Double? = null,
    /** Windowed build count by [dev.buildhound.commons.payload.BuildOutcome] name; empty when no builds. */
    val buildCountsByOutcome: Map<String, Int> = emptyMap(),
    /** Distinct flaky test units detected in the window; null when [BuildStore.flaky] found none (plan
     * 070's honest-empty rule — an empty detector result can't be told apart from "no test data was ever
     * reported", so it is omitted rather than shown as a false "0 flaky tests"). */
    val flakyTestCount: Int? = null,
    /** Sum of `derived.avoidedMs` over builds that carry it; null when none do (plan 038 opt-in only). */
    val avoidedMs: Long? = null,
)

/**
 * Pure metrics-snapshot math (plan 070), the single source both [BuildStore] implementations defer to
 * (the plan-026/032 parity discipline) — same windowed [BuildKpiRow]s the bottlenecks/trends rollups
 * already fetch.
 *
 * Percentiles use the shared [NearestRankPercentile] helper rather than the commons
 * `BenchmarkSeriesCalculator` the plan's Design section points at: that calculator only exposes p50/p90
 * and is `private`-percentile internally, and generalizing it to p95 would mean editing
 * `buildhound-commons` — out of scope for this server-only slice (the plan's own "Modules touched" line
 * says "No `buildhound-commons`"). [NearestRankPercentile] is the same nearest-rank formula `LptBalancer.p90`
 * used before the two were unified behind one server-local helper (plan 070 review finding #2).
 */
object MetricsSnapshotCalculator {

    fun compute(
        windowDays: Int,
        builds: List<BuildKpiRow>,
        flakyRecordCount: Int,
        avoidedMsValues: List<Long>,
    ): MetricsSnapshot {
        val durations = builds.map { it.durationMs }.sorted()
        return MetricsSnapshot(
            windowDays = windowDays,
            p50DurationMs = if (durations.isEmpty()) null else percentile(durations, P50_QUANTILE),
            p95DurationMs = if (durations.isEmpty()) null else percentile(durations, P95_QUANTILE),
            // sorted() before average() keeps the fold order-independent (same discipline as
            // BottleneckCalculator.avgHitRate) so in-memory and Postgres agree byte-for-byte
            // regardless of the row order each store reads them in.
            cacheHitRate = builds.mapNotNull { it.hitRate }.takeIf { it.isNotEmpty() }?.sorted()?.average(),
            successRate = builds.takeIf { it.isNotEmpty() }
                ?.let { it.count { row -> row.outcome == "SUCCESS" }.toDouble() / it.size },
            buildCountsByOutcome = builds.groupingBy { it.outcome }.eachCount(),
            flakyTestCount = flakyRecordCount.takeIf { it > 0 },
            avoidedMs = avoidedMsValues.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    /** [sorted] must be non-empty. See [NearestRankPercentile]. */
    private fun percentile(sorted: List<Long>, q: Double): Long = NearestRankPercentile.ofSorted(sorted, q)
}
