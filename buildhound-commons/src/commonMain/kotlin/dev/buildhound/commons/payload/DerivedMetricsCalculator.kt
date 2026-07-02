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
     */
    fun compute(tasks: List<TaskExecution>, cores: Int?): DerivedMetrics? {
        if (tasks.isEmpty()) return null
        return DerivedMetrics(
            cacheableHitRate = cacheableHitRate(tasks),
            avoidedMs = null,
            criticalPathMs = null,
            parallelUtilization = parallelUtilization(tasks, cores),
            configurationMs = null,
        )
    }

    /**
     * Share of work avoided by caching/up-to-date checks among tasks that did or could
     * have run: (FROM_CACHE + UP_TO_DATE) / (those + EXECUTED). Task-level `cacheable`
     * flags are not collected yet, so this is over all tasks (plan 005).
     */
    fun cacheableHitRate(tasks: List<TaskExecution>): Double? {
        val avoided = tasks.count { it.outcome == TaskOutcome.FROM_CACHE || it.outcome == TaskOutcome.UP_TO_DATE }
        val executed = tasks.count { it.outcome == TaskOutcome.EXECUTED }
        val considered = avoided + executed
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
