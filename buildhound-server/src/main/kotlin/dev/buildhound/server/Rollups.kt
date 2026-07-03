package dev.buildhound.server

import kotlinx.serialization.Serializable

/** Per-module Project Cost family (plan 026, mirrors eBay's ProjectCostSummarizer). */
@Serializable
data class ProjectCostRow(
    val module: String?,
    val builds: Int,
    val executedBuilds: Int,
    val buildImpactedUsers: Int,
    val serialTaskMs: Long,
    val buildAvgDurationMs: Long,
    val buildPercentage: Double,
    val buildCostScalar: Long,
)

/** One task-duration ranking row, grouped by name or type. */
@Serializable
data class TaskDurationRow(
    val key: String,
    val count: Int,
    val totalMs: Long,
    val avgMs: Long,
    val minMs: Long,
    val maxMs: Long,
)

/**
 * Task-duration rollup: top-25 by name and by type. [byTypeAvailable] distinguishes "no builds"
 * from "types not populated yet" (plan 016) so the UI can prompt rather than show an empty table.
 */
@Serializable
data class TaskDurationRollup(
    val byName: List<TaskDurationRow>,
    val byType: List<TaskDurationRow>,
    val byTypeAvailable: Boolean,
)

/** One negative-avoidance ranking row (avoidance cost more than execution) by type/name group. */
@Serializable
data class NegativeAvoidanceRow(
    val key: String,
    val count: Int,
    val totalExcessMs: Long,
    val worstExcessMs: Long,
)

/** Flat per-task row the in-memory store flattens payloads into; the SQL store mirrors it. */
data class TaskRow(
    val buildId: String,
    val userId: String?,
    val module: String?,
    val name: String,
    val type: String?,
    val outcome: String,
    val durationMs: Long,
    val buildWallMs: Long,
)

/**
 * Pure rollup math (plan 026). The in-memory store computes over flattened payload [TaskRow]s with
 * this; `PostgresBuildStore` runs equivalent SQL over `task_executions`. The Testcontainers parity
 * test asserts the two agree byte-for-byte, so this is the single source of the aggregation rules.
 *
 * The negative-avoidance rollup compares against the **window** executed median per group
 * (`percentile_cont(0.5)`), which is why it does not reuse the build-local
 * `DerivedMetricsCalculator.negativeAvoidanceMs` (that one is for the per-build artifact signal).
 */
object RollupCalculator {

    const val TOP_N = 25

    fun projectCost(rows: List<TaskRow>): List<ProjectCostRow> {
        val totalBuilds = rows.map { it.buildId }.distinct().size
        if (totalBuilds == 0) return emptyList()
        return rows.groupBy { it.module }.map { (module, moduleRows) ->
            val containing = moduleRows.map { it.buildId }.distinct()
            val executedBuildIds = moduleRows.filter { it.outcome == "EXECUTED" }.map { it.buildId }.distinct().toSet()
            val containingWalls = moduleRows.distinctBy { it.buildId }
            val executedWalls = containingWalls.filter { it.buildId in executedBuildIds }
            val executedAvgWall = executedWalls.map { it.buildWallMs }.averageOrZero()
            val executedPercentInt = (executedBuildIds.size.toDouble() / totalBuilds * 100).toInt()
            ProjectCostRow(
                module = module,
                builds = containing.size,
                executedBuilds = executedBuildIds.size,
                buildImpactedUsers = moduleRows.mapNotNull { it.userId }.distinct().size,
                serialTaskMs = moduleRows.sumOf { it.durationMs },
                buildAvgDurationMs = containingWalls.map { it.buildWallMs }.averageOrZero(),
                // Rounded to 6 dp so Kotlin double-division and SQL float8 division agree exactly
                // (raw IEEE-754 division isn't guaranteed bit-identical across the two — parity safety).
                buildPercentage = roundTo6(containing.size.toDouble() / totalBuilds),
                // eBay's ProjectCostSummarizer truncates the percentage to an int before multiplying
                // — copied verbatim so the number matches the reference (their README hedges it "may
                // change"). Surfaces modules that are both frequently and expensively built.
                buildCostScalar = executedAvgWall * executedPercentInt,
            )
        }.sortedWith(compareByDescending<ProjectCostRow> { it.buildCostScalar }.thenBy { it.module ?: "" }).take(TOP_N)
    }

    fun taskDuration(rows: List<TaskRow>): TaskDurationRollup {
        fun rank(grouped: Map<String, List<TaskRow>>): List<TaskDurationRow> =
            grouped.map { (key, group) ->
                val durations = group.map { it.durationMs }
                val total = durations.sum()
                TaskDurationRow(
                    key = key, count = group.size, totalMs = total,
                    avgMs = total / group.size, minMs = durations.min(), maxMs = durations.max(),
                )
            }.sortedWith(compareByDescending<TaskDurationRow> { it.totalMs }.thenBy { it.key }).take(TOP_N)

        return TaskDurationRollup(
            byName = rank(rows.groupBy { it.name }),
            byType = rank(rows.filter { it.type != null }.groupBy { it.type!! }),
            byTypeAvailable = rows.any { it.type != null },
        )
    }

    fun negativeAvoidance(rows: List<TaskRow>): List<NegativeAvoidanceRow> =
        rows.groupBy { it.type ?: it.name }.mapNotNull { (key, group) ->
            val executed = group.filter { it.outcome == "EXECUTED" }.map { it.durationMs }
            if (executed.isEmpty()) return@mapNotNull null // no baseline → never negative
            val median = medianDouble(executed)
            val excesses = group
                .filter { it.outcome == "UP_TO_DATE" || it.outcome == "FROM_CACHE" }
                .map { it.durationMs - median }
                .filter { it > 0.0 }
            if (excesses.isEmpty()) return@mapNotNull null
            NegativeAvoidanceRow(
                key = key,
                count = excesses.size,
                totalExcessMs = excesses.sum().toLong(),
                worstExcessMs = excesses.max().toLong(),
            )
        }.sortedWith(compareByDescending<NegativeAvoidanceRow> { it.totalExcessMs }.thenBy { it.key }).take(TOP_N)

    /** Matches SQL `percentile_cont(0.5)`: interpolated (averaged) middle for even counts. */
    private fun medianDouble(values: List<Long>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun List<Long>.averageOrZero(): Long = if (isEmpty()) 0L else (sum() / size)

    private fun roundTo6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
