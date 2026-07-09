package dev.buildhound.commons.payload

/**
 * Derived metrics over the task list (spec §4 `derived` block). Pure schema logic so
 * plugin and server rollups compute identical numbers.
 *
 * `avoidedMs` needs origin execution timings and `criticalPathMs` needs the task dependency graph
 * — both stay null (plan 005 honest-nulls) unless the opt-in `buildhound-internal-adapters` module
 * (plan 038) supplies them: it passes `avoidedMs` (from cache-transfer/origin timings) and a
 * dependency edge list through [compute], which this calculator turns into a weighted longest path.
 */
object DerivedMetricsCalculator {

    /**
     * @param cores executor cores, for utilization; null or non-positive → utilization null.
     * @param configurationMs configuration-phase duration (plan 016): measured value,
     *   `0` on a config-cache hit, null when unmeasurable. Passed straight through.
     * @param avoidedMs cache-avoided milliseconds (plan 038): adapter-supplied, passed through; null
     *   when the internal-adapters module is not applied.
     * @param dependencyEdges task path → its dependency task paths (plan 038): drives
     *   [criticalPathMs]; null when the graph is unavailable (module absent, or isolated projects).
     * @param ccEntrySizeBytes configuration-cache entry byte size (plan 064): finalizer-measured,
     *   passed straight through; null when CC was not requested or the probe degraded.
     * @param ccLoadMs configuration-cache entry-load proxy (plan 064): finalizer-measured on a CC hit
     *   only, passed straight through; null on every non-hit build and when unmeasurable.
     */
    fun compute(
        tasks: List<TaskExecution>,
        cores: Int?,
        configurationMs: Long? = null,
        avoidedMs: Long? = null,
        dependencyEdges: Map<String, List<String>>? = null,
        ccEntrySizeBytes: Long? = null,
        ccLoadMs: Long? = null,
    ): DerivedMetrics? {
        if (tasks.isEmpty()) return null
        return DerivedMetrics(
            cacheableHitRate = cacheableHitRate(tasks),
            avoidedMs = avoidedMs,
            criticalPathMs = criticalPathMs(tasks, dependencyEdges),
            parallelUtilization = parallelUtilization(tasks, cores),
            configurationMs = configurationMs,
            ccEntrySizeBytes = ccEntrySizeBytes,
            ccLoadMs = ccLoadMs,
        )
    }

    /**
     * Longest weighted path (ms) through the task dependency DAG (plan 038): each task's weight is
     * its `durationMs`, edges point a task → the tasks it depends on. Returns **null** when
     * [dependencyEdges] is null/empty (the graph is an internal-adapters deliverable core has never
     * had) or when a cycle is detected (degrade to null, never hang). A path referenced only as a
     * dependency but absent from [tasks] weighs 0 (its duration is unknown).
     */
    fun criticalPathMs(tasks: List<TaskExecution>, dependencyEdges: Map<String, List<String>>?): Long? {
        if (dependencyEdges.isNullOrEmpty()) return null
        val durationByPath = tasks.associate { it.path to it.durationMs }
        val memo = HashMap<String, Long>()
        val onStack = HashSet<String>()
        var cyclic = false

        fun finish(path: String): Long {
            memo[path]?.let { return it }
            if (!onStack.add(path)) { cyclic = true; return 0 } // back-edge → cycle
            val deps = dependencyEdges[path].orEmpty()
            val longestDep = deps.maxOfOrNull { finish(it) } ?: 0L
            onStack.remove(path)
            val result = (durationByPath[path] ?: 0L) + longestDep
            memo[path] = result
            return result
        }

        var max = 0L
        for (node in durationByPath.keys + dependencyEdges.keys) {
            val f = finish(node)
            if (cyclic) return null
            if (f > max) max = f
        }
        return max
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

    /**
     * Negative-avoidance milliseconds (plan 026): work where *avoiding* it cost more than *doing*
     * it. A candidate is a task that was avoided (`UP_TO_DATE`/`FROM_CACHE`) yet ran longer than the
     * median **executed** duration of its group — the cache/up-to-date check was slower than the
     * work. Grouped by `type`, falling back to the task name when `type` is null (pre-016 payloads).
     *
     * Build-local and independent of plan 038's per-task savings: it compares against a baseline
     * that already exists in this build's task list. A group with no executed task has no baseline,
     * so its avoided tasks contribute 0 (never negative). Returns the sum of positive excesses only.
     * Kept in commons so the server rollup and the HTML artifact compute the same number.
     */
    fun negativeAvoidanceMs(tasks: List<TaskExecution>): Long {
        val medianByGroup = tasks
            .filter { it.outcome == TaskOutcome.EXECUTED }
            .groupBy { groupKey(it) }
            .mapValues { (_, group) -> medianLong(group.map { it.durationMs }) }

        var excess = 0L
        for (task in tasks) {
            if (task.outcome != TaskOutcome.UP_TO_DATE && task.outcome != TaskOutcome.FROM_CACHE) continue
            val baseline = medianByGroup[groupKey(task)] ?: continue // no executed baseline → 0
            val delta = task.durationMs - baseline
            if (delta > 0) excess += delta
        }
        return excess
    }

    /** The negative-avoidance grouping key: static type when known, else the task name. */
    fun groupKey(task: TaskExecution): String = task.type ?: taskName(task)

    /** Task name = the last path segment (`:app:compileKotlin` → `compileKotlin`). */
    fun taskName(task: TaskExecution): String = task.path.substringAfterLast(':')

    private fun medianLong(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
