package dev.buildhound.server

/**
 * Pure LPT (Longest-Processing-Time) shard balancer for the test-sharding addon (plan 040). Greedy:
 * weight each suite by its p90 duration, sort descending, assign each to the least-loaded shard. This
 * is the whole balancer — no Ktor/DB — so it unit-tests directly and both stores memoize its output.
 *
 * Fallbacks (Tuist's verified defaults, research §1.2): a suite with no timing uses the **median of
 * known** p90s; when there is **no history at all**, every suite floors at [DEFAULT_FLOOR_MS]. Output
 * is `total` shards, oldest-index-first; deterministic (weight desc, then key asc; least-loaded ties
 * to the lowest index). The client runs its assigned classes plus any locally-discovered suite the
 * plan didn't name (catch-all tail) — that guard lives client-side, not here.
 */
object LptBalancer {

    const val DEFAULT_FLOOR_MS: Long = 5_000

    fun plan(
        suites: List<String>,
        total: Int,
        p90ByKey: Map<String, Long>,
        floorMs: Long = DEFAULT_FLOOR_MS,
    ): List<List<String>> {
        val distinct = suites.distinct()
        val shardCount = total.coerceAtLeast(1)
        if (shardCount == 1) return listOf(distinct)

        val known = distinct.mapNotNull { p90ByKey[it] }
        val fallback = if (known.isEmpty()) floorMs else medianLong(known)
        fun weight(key: String): Long = p90ByKey[key] ?: fallback

        val shards = Array(shardCount) { mutableListOf<String>() }
        val loads = LongArray(shardCount)
        for (suite in distinct.sortedWith(compareByDescending<String> { weight(it) }.thenBy { it })) {
            val target = (0 until shardCount).minByOrNull { loads[it] }!! // ties → lowest index
            shards[target].add(suite)
            loads[target] += weight(suite)
        }
        return shards.map { it.toList() }
    }

    /** Nearest-rank p90 over a duration list (plan 040 timing input); 0 when empty. See [NearestRankPercentile]. */
    fun p90(durations: List<Long>): Long = NearestRankPercentile.of(durations, P90_QUANTILE)

    private fun medianLong(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
