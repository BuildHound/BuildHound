package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlinx.serialization.Serializable

/**
 * One task ranked by how much of the build it serialized (plan 062, research F12): [serializedMs]
 * is the total time this task was the **sole** runner while at least one other task was still
 * queued to start — a candidate to *investigate*, not a confirmed dependency-chain fix (the
 * timeline alone can't separate inherent serialization from missed parallelism opportunity; see
 * [GatingAnalyzer]).
 */
@Serializable
data class BlockerRow(
    val taskPath: String,
    val module: String? = null,
    val serializedMs: Long,
    val durationMs: Long,
)

/**
 * Pure sweep-line gating-task detector (plan 062, research F12): needs only the core `tasks[]`
 * timeline (`startMs`/`durationMs`), so — unlike [GraphCentrality] — it survives isolated projects
 * and needs no `internalAdapters` opt-in ("most critical-path insight without `criticalPathMs`").
 */
object GatingAnalyzer {

    /**
     * A task's execution interval only "occupies a slot" when it actually ran: `durationMs > 0` and
     * `outcome` is EXECUTED or FROM_CACHE. A 0 ms NO_SOURCE/SKIPPED/UP_TO_DATE row never blocks
     * anything.
     */
    private fun occupiesSlot(task: TaskExecution): Boolean =
        task.durationMs > 0 &&
            (task.outcome == TaskOutcome.EXECUTED || task.outcome == TaskOutcome.FROM_CACHE)

    /**
     * Sweeps the boundary events (every task start + every task end) left to right, tracking the
     * set of tasks active in each inter-boundary interval. An interval is attributed to its sole
     * active task only when **work remained** — some task (possibly the same one that starts a
     * later interval) starts at or after this interval's end (`next <= maxStart`); otherwise the
     * sole runner is just the build's tail with nothing left to parallelize against, and must not
     * be penalized. At a shared boundary timestamp, ends are applied before starts, so a task
     * ending exactly when another begins is correctly excluded from the interval that follows
     * (half-open `[start, end)` occupancy) — deterministic regardless of input order since
     * membership is a set, never order-dependent.
     *
     * Known limitation: `maxStart` is a single scalar taken from the raw `startMs` values below,
     * with no cross-check against the payload's own `startedAt`/`finishedAt` build envelope. One
     * corrupted or future-dated `startMs` outlier (clock skew on a collecting agent, bad collector
     * data) inflates `maxStart` and defeats the "work remained" tail-exclusion test for every other
     * task, misattributing every genuine solo tail as gating. `TaskExecution` carries no
     * independent wall-clock field to cross-check `startMs` against, so guarding this would mean
     * threading the build-level envelope through this function's signature (and every
     * [GatingAnalyzerTest] call site) rather than a one-line clamp — left as a documented gap, not
     * a silent trust assumption.
     */
    fun analyze(tasks: List<TaskExecution>, topN: Int = RollupCalculator.TOP_N): List<BlockerRow> {
        val eligible = tasks.filter(::occupiesSlot)
        if (eligible.isEmpty()) return emptyList()

        val maxStart = eligible.maxOf { it.startMs }
        val startsAt = eligible.groupBy { it.startMs }
        val endsAt = eligible.groupBy { it.startMs + it.durationMs }
        val boundaries = (startsAt.keys + endsAt.keys).toSortedSet().toList()

        val active = LinkedHashMap<String, TaskExecution>()
        val serializedMsByPath = LinkedHashMap<String, Long>()
        for (i in boundaries.indices) {
            val t = boundaries[i]
            endsAt[t]?.forEach { active.remove(it.path) }
            startsAt[t]?.forEach { active[it.path] = it }

            val next = boundaries.getOrNull(i + 1) ?: break
            if (active.size == 1 && next <= maxStart) {
                val sole = active.values.first().path
                serializedMsByPath[sole] = (serializedMsByPath[sole] ?: 0L) + (next - t)
            }
        }

        val durationByPath = eligible.associate { it.path to it.durationMs }
        val moduleByPath = eligible.associate { it.path to it.module }
        return serializedMsByPath.entries
            .map { (path, ms) ->
                BlockerRow(
                    taskPath = path,
                    module = moduleByPath[path],
                    serializedMs = ms,
                    durationMs = durationByPath[path] ?: 0L,
                )
            }
            .sortedWith(compareByDescending<BlockerRow> { it.serializedMs }.thenBy { it.taskPath })
            .take(topN)
    }
}

/**
 * One task ranked by duration-weighted degree centrality over the dependency graph (plan 062,
 * research F12): the Plaid-hub property the Talaiot research found — weighted **degree** surfaces a
 * heavily fanned-in/out task a single longest-chain view misses. [durationMs] is `0` for a node
 * referenced only as someone else's dependency but absent from core `tasks[]` (its own duration is
 * unknown).
 */
@Serializable
data class CentralityRow(
    val taskPath: String,
    val module: String? = null,
    val durationMs: Long,
    val degree: Int,
    val weightedDegree: Long,
)

/**
 * Pure duration-weighted degree centrality (plan 062, research F12) over the already-serialized
 * `extensions["internalAdapters"].dependencyEdges` (plan 038: task path -> the task paths it
 * depends on). Builds the reverse adjacency (dependents) so each node's neighbor set is `deps ∪
 * dependents`, unlike [GatingAnalyzer] this needs the internal-adapters edge list, so it degrades
 * to an empty ranking whenever the caller has no edges (adapters off, isolated-projects' empty
 * `whenReady` walk, or a `PayloadCapper` byte-drop of the whole `internalAdapters` blob) — the
 * caller ([ParallelismAnalyzer]) turns that into the response's honest `centrality = null`.
 */
object GraphCentrality {

    fun rank(
        dependencyEdges: Map<String, List<String>>,
        tasks: List<TaskExecution>,
        topN: Int = RollupCalculator.TOP_N,
    ): List<CentralityRow> {
        if (dependencyEdges.isEmpty()) return emptyList()

        val durationByPath = tasks.associate { it.path to it.durationMs }
        val moduleByPath = tasks.associate { it.path to it.module }

        val dependents = LinkedHashMap<String, MutableSet<String>>()
        val nodes = LinkedHashSet<String>()
        for ((path, deps) in dependencyEdges) {
            nodes += path
            for (dep in deps) {
                nodes += dep
                dependents.getOrPut(dep) { LinkedHashSet() }.add(path)
            }
        }

        return nodes
            .map { path ->
                val neighbors = dependencyEdges[path].orEmpty().toSet() + dependents[path].orEmpty()
                CentralityRow(
                    taskPath = path,
                    module = moduleByPath[path],
                    durationMs = durationByPath[path] ?: 0L,
                    degree = neighbors.size,
                    weightedDegree = neighbors.sumOf { durationByPath[it] ?: 0L },
                )
            }
            .sortedWith(
                compareByDescending<CentralityRow> { it.weightedDegree }.thenBy { it.taskPath }
            )
            .take(topN)
    }
}

/**
 * `GET /v1/builds/{buildId}/parallelism` response (plan 062, research F12). [gatingBlockers] is
 * always populated when the build has a timeline (needs no opt-in module); [centrality] is null
 * exactly when [centralityAvailable] is false — the honest three-way absent-case union
 * (`internalAdapters` off, isolated-projects, or capped) plan 062's Risks section names, never an
 * empty-pretending-complete list. Both rankings are candidates to *investigate*, never a confirmed
 * fix (heuristic-honesty framing).
 */
@Serializable
data class ParallelismView(
    val gatingBlockers: List<BlockerRow>,
    val centrality: List<CentralityRow>?,
    val centralityAvailable: Boolean,
    val topN: Int,
)

/**
 * Combines [GatingAnalyzer] (always available from core `tasks[]`) with [GraphCentrality] (gated on
 * [dependencyEdgesOf]) into one [ParallelismView] — the route's entire compute step, so the route
 * itself stays a thin fetch-then-serialize (mirroring [BuildDiagnoser]'s per-build-payload
 * synthesis shape).
 */
object ParallelismAnalyzer {
    fun analyze(payload: BuildPayload, topN: Int = RollupCalculator.TOP_N): ParallelismView {
        val edges = dependencyEdgesOf(payload)
        return ParallelismView(
            gatingBlockers = GatingAnalyzer.analyze(payload.tasks, topN),
            centrality = edges?.let { GraphCentrality.rank(it, payload.tasks, topN) },
            centralityAvailable = edges != null,
            topN = topN,
        )
    }
}
