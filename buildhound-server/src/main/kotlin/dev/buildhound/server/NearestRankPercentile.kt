package dev.buildhound.server

import kotlin.math.ceil

/**
 * Nearest-rank percentile: rank = ceil(q·n), 1-indexed, clamped to `[1, n]`. The one definition shared by
 * [LptBalancer.p90] (CI class-timing p90 for the shard balancer, plan 040) and
 * [MetricsSnapshotCalculator] (Prometheus egress p50/p95, plan 070) — both independently implemented the
 * same formula before this review-driven extraction (plan 070 review finding #2); this file is now the
 * only place it's written. Kept server-local rather than lifted into `buildhound-commons` for the same
 * module-scope reason [MetricsSnapshotCalculator]'s own doc gives: plan 070's "Modules touched" line says
 * "No `buildhound-commons`", and generalizing the commons `BenchmarkSeriesCalculator` (p50/p90 only,
 * `private`-percentile internally) was out of scope for that server-only slice.
 */
internal object NearestRankPercentile {

    /** [durations] need not be sorted; this sorts a copy first. `0` when empty. */
    fun of(durations: List<Long>, q: Double): Long {
        if (durations.isEmpty()) return 0
        return ofSorted(durations.sorted(), q)
    }

    /** [sorted] must already be sorted ascending and non-empty. */
    fun ofSorted(sorted: List<Long>, q: Double): Long {
        val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
