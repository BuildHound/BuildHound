package dev.buildhound.commons.payload

/**
 * Derived metrics over the task list (spec §4 `derived` block). Pure schema logic so
 * plugin and server rollups compute identical numbers.
 *
 * v0 honesty: `avoidedMs` needs origin execution timings (v1.x cache-origin work) and
 * `criticalPathMs` needs the task dependency graph — both stay null rather than being
 * approximated wrongly (plan 005).
 */
object DerivedMetricsCalculator {

    /**
     * @param cores executor cores, for utilization; null or non-positive → utilization null.
     * @param configurationMs configuration-phase duration (plan 016): measured value,
     *   `0` on a config-cache hit, null when unmeasurable. Passed straight through.
     */
    fun compute(tasks: List<TaskExecution>, cores: Int?, configurationMs: Long? = null): DerivedMetrics? {
        if (tasks.isEmpty()) return null
        return DerivedMetrics(
            cacheableHitRate = cacheableHitRate(tasks),
            avoidedMs = null,
            criticalPathMs = null,
            parallelUtilization = parallelUtilization(tasks, cores),
            configurationMs = configurationMs,
        )
    }

    /**
     * Cache hit rate over a **cacheable-only** denominator (plan 016). A task is
     * *cache-relevant* iff its static `cacheable` flag is true, or its outcome is
     * FROM_CACHE — a FROM_CACHE outcome proves cacheability even when the static flag
     * missed a runtime `cacheIf {}`. Numerator = cache-relevant tasks FROM_CACHE or
     * UP_TO_DATE; denominator = cache-relevant tasks in {EXECUTED, FROM_CACHE, UP_TO_DATE}
     * (FAILED/SKIPPED/NO_SOURCE excluded, as before).
     *
     * Returns **null** when no task carries a non-null `cacheable` flag at all
     * (isolated-projects degradation, or legacy pre-016 payloads): reporting the old
     * mixed-denominator number there would splice two metric definitions into one trend
     * line, which is worse than an honest gap (honest-nulls principle, plan 005). Also
     * null when cacheable flags exist but no task is cache-relevant.
     */
    fun cacheableHitRate(tasks: List<TaskExecution>): Double? {
        if (tasks.none { it.cacheable != null }) return null
        val relevant = tasks.filter { it.cacheable == true || it.outcome == TaskOutcome.FROM_CACHE }
        val avoided = relevant.count { it.outcome == TaskOutcome.FROM_CACHE || it.outcome == TaskOutcome.UP_TO_DATE }
        val considered = relevant.count {
            it.outcome == TaskOutcome.EXECUTED ||
                it.outcome == TaskOutcome.FROM_CACHE ||
                it.outcome == TaskOutcome.UP_TO_DATE
        }
        return if (considered == 0) null else avoided.toDouble() / considered
    }

    /** Σ task durations / (wall-clock × cores), clamped to [0, 1]. */
    fun parallelUtilization(tasks: List<TaskExecution>, cores: Int?): Double? {
        if (cores == null || cores <= 0) return null
        val wallMs = wallClockMs(tasks)
        if (wallMs <= 0) return null
        val busyMs = tasks.sumOf { it.durationMs }
        return (busyMs.toDouble() / (wallMs.toDouble() * cores)).coerceIn(0.0, 1.0)
    }

    /** First task start to last task end; 0 when timestamps are unusable. */
    fun wallClockMs(tasks: List<TaskExecution>): Long {
        val start = tasks.minOfOrNull { it.startMs } ?: return 0
        val end = tasks.maxOfOrNull { it.startMs + it.durationMs } ?: return 0
        return (end - start).coerceAtLeast(0)
    }
}
