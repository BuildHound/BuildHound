package dev.buildhound.commons.payload

import kotlin.math.ceil

/**
 * Percentile summary of a benchmark series (plan 030, spec §7). Pure + KMP so the server computes the
 * same numbers the plugin/UI would — same-machine profiler runs are noisy, so a series is summarized
 * by percentiles over N iterations, never a single run (Telltale/Bagan). Nearest-rank percentiles
 * (deterministic, no interpolation) over the sorted durations.
 */
object BenchmarkSeriesCalculator {

    data class Summary(val p50: Long, val p90: Long, val min: Long, val count: Int)

    /** null for an empty series; otherwise p50/p90/min over [durationsMs] (input order irrelevant). */
    fun summarize(durationsMs: List<Long>): Summary? {
        if (durationsMs.isEmpty()) return null
        val sorted = durationsMs.sorted()
        return Summary(
            p50 = percentile(sorted, 0.50),
            p90 = percentile(sorted, 0.90),
            min = sorted.first(),
            count = sorted.size,
        )
    }

    /** Nearest-rank: rank = ceil(q·n), 1-indexed, clamped to `[1, n]`. */
    private fun percentile(sorted: List<Long>, q: Double): Long {
        val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
