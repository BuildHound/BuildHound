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

/**
 * Curated FQCN-prefix → plugin-label catalog (plan 058, research F8, Layer 1 only — zero new
 * collection). Maps a task's [TaskRow.type] (e.g. `"org.jetbrains.kotlin.gradle.tasks.KotlinCompile"`)
 * to the plugin that owns it, purely by prefix — no registry, no plugin-id inventory (that's the
 * deferred Layer 2). Server-side only, deliberately not `buildhound-commons`: F8's own thesis is
 * "plugin stays dumb, server rules carry the knowledge," and commons ships *inside* the published
 * plugin artifact, where a catalog would contradict that pattern.
 *
 * Heuristic and openly so (research narrowing 1): a `DefaultTask`/`Copy`/build-script-defined type, a
 * null `type` (isolated-projects, plan 016's empty `whenReady` dictionary), or any prefix this catalog
 * doesn't (yet) recognize all fold into [UNATTRIBUTED] — a distinct, honestly labeled bucket, never
 * silently dropped or merged into a real plugin's numbers. Core `org.gradle.*` types (`JavaCompile`,
 * `Test`, `Copy`, …) get their own [GRADLE_CORE] bucket, kept apart from [UNATTRIBUTED] so a
 * large-but-honest "this is just Gradle" share stays visible rather than inflating "unknown."
 * [PluginAttributionTest] pins every entry with a golden list so the catalog can't silently drift.
 */
object PluginAttribution {

    const val GRADLE_CORE = "Gradle core"
    const val UNATTRIBUTED = "(unattributed)"

    // Every prefix below is disjoint from every other (none is a prefix of another), so plain
    // first-match iteration is correctness-safe regardless of order; ordered by expected fleet
    // frequency (AGP/KGP/KSP first) purely for readability.
    private val PREFIXES: List<Pair<String, String>> = listOf(
        "com.android.build." to "Android Gradle Plugin",
        "org.jetbrains.kotlin." to "Kotlin Gradle Plugin",
        "com.google.devtools.ksp." to "KSP",
        "dagger.hilt." to "Hilt/Dagger",
        "io.gitlab.arturbosch.detekt." to "Detekt",
        "org.jlleitschuh.gradle.ktlint." to "ktlint",
        "com.diffplug.gradle.spotless." to "Spotless",
        "com.google.protobuf.gradle." to "Protobuf Gradle Plugin",
        "org.gradle." to GRADLE_CORE,
    )

    fun owningPlugin(type: String?): String {
        if (type == null) return UNATTRIBUTED
        // DefaultTask is Gradle's fallback for a task registered with no explicit type (a bare
        // `tasks.register("foo") { doLast {} }` in a build script) — despite its org.gradle. FQCN, it
        // carries no genuine "Gradle core did work" signal, so the plan (Design section, narrowing 1)
        // carves it out into UNATTRIBUTED rather than letting the generic "org.gradle." prefix below
        // swallow it into GRADLE_CORE.
        if (type == "org.gradle.api.DefaultTask") return UNATTRIBUTED
        for ((prefix, label) in PREFIXES) {
            if (type.startsWith(prefix)) return label
        }
        return UNATTRIBUTED
    }
}

/**
 * One plugin's fleet-wide task-time cost (plan 058); [sharePct] is this plugin's share of the
 * window's total task time.
 */
@Serializable
data class PluginCostRow(val plugin: String, val totalMs: Long, val count: Int, val sharePct: Double)

/**
 * One "costliest module to change" ranking row (plan 063, research F13): the cost module [module]
 * inflicts on *others* when it changes. [changeCount] = distinct builds in the window where [module]
 * was in the changed-module set; [medianDownstreamMs] = the median, over those builds, of the executed
 * task time in *every other* module (`sum(duration_ms WHERE outcome=EXECUTED AND module != this)`);
 * [blastScore] = `medianDownstreamMs × changeCount`, the ranking scalar (frequent + expensive to
 * change ranks highest). Complements plan-026 Project Cost, which ranks a module by *its own* cost.
 */
@Serializable
data class ChangeBlastRadiusRow(
    val module: String,
    val changeCount: Int,
    val medianDownstreamMs: Long,
    val blastScore: Long,
)

/**
 * One build's contribution to the change blast-radius rollup (plan 063): the distinct changed Gradle
 * module paths it reported + the executed task time summed per module for that build. Both stores
 * flatten to this shape and defer to [RollupCalculator.changeBlastRadius] (the plan-058 `pluginCost`
 * fold-in-Kotlin parity posture — no SQL median/`module != M` NULL-semantics trap), so in-memory and
 * Postgres agree byte-for-byte. A `null`-module task keys under [NULL_MODULE_KEY]; since a changed
 * module is always a non-null Gradle path, null-module executed time always counts as downstream.
 */
data class ChangeBlastBuild(
    val buildId: String,
    val changedModules: List<String>,
    /**
     * Executed (outcome == EXECUTED) task duration summed per module for this build; null module →
     * [NULL_MODULE_KEY].
     */
    val executedMsByModule: Map<String, Long>,
) {
    companion object {
        /** Sentinel key for tasks with a null module — kept distinct from every real Gradle path. */
        const val NULL_MODULE_KEY: String = "\u0000null-module"
    }
}

/**
 * Owning-plugin cost rollup (plan 058, research F8 Layer 1): folds every task in the window by
 * [PluginAttribution.owningPlugin]. [available] mirrors [TaskDurationRollup.byTypeAvailable] — false
 * only when no task in the window carries a `type` at all (isolated-projects degradation, plan 016),
 * in which case every row folds into the single "(unattributed)" bucket and the dashboard shows the
 * plan-016 "not collected yet" notice rather than reading the fold as real data.
 */
@Serializable
data class PluginCostRollup(val plugins: List<PluginCostRow>, val available: Boolean)

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
    /** Cacheability flag (plan 016); null on pre-016 payloads → cache-miss hotspots degrade (plan 032). */
    val cacheable: Boolean? = null,
    /**
     * Raw Gradle `executionReasons` (plan 061, research F11); empty on pre-V12 rows (the column read
     * back NULL) and on any task the payload never carried reasons for. [RerunCauseRollupCalculator]
     * is the only consumer today — an empty list here degrades to the `UNCLASSIFIED` bucket, never a
     * crash or a silently-dropped task.
     */
    val executionReasons: List<String> = emptyList(),
    /**
     * Tooling `TaskExecutionResult.isIncremental` (plan 060, research F10); defaults `false` — the
     * same default [dev.buildhound.commons.payload.TaskExecution.incremental] itself carries, so a
     * pre-this-field row (there isn't one; the payload field predates this server-side row addition)
     * or a `taskRowsInDaysWindow`/`taskRowsBetween` fetch that never sets it (task_executions has no
     * `incremental` column) degrades to "not incremental" rather than crashing. [WarningCalculator] is
     * the only consumer today.
     */
    val incremental: Boolean = false,
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
            val executedPercentInt =
                (executedBuildIds.size.toDouble() / totalBuilds * PERCENT_FACTOR).toInt()
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

    /**
     * Owning-plugin cost rollup (plan 058, research F8 Layer 1): folds [rows] by
     * [PluginAttribution.owningPlugin], summing duration/count per plugin. The single source both
     * stores defer to — `pluginCost`'s Postgres implementation fetches the identical windowed
     * [TaskRow]s and calls this (the plan-026 parity discipline: same multiset → same calculator →
     * bit-identical [PluginCostRow.sharePct]), unlike [taskDuration]/[projectCost]/[negativeAvoidance]'s
     * Postgres implementations, which aggregate in SQL directly — the FQCN-prefix mapping has no SQL
     * equivalent, so this rollup must fold in Kotlin on both sides.
     */
    fun pluginCost(rows: List<TaskRow>): PluginCostRollup {
        if (rows.isEmpty()) return PluginCostRollup(plugins = emptyList(), available = false)
        val grandTotal = rows.sumOf { it.durationMs }
        val plugins = rows.groupBy { PluginAttribution.owningPlugin(it.type) }
            .map { (plugin, group) ->
                val total = group.sumOf { it.durationMs }
                PluginCostRow(
                    plugin = plugin,
                    totalMs = total,
                    count = group.size,
                    // Guard mirrors RerunCauseRollupCalculator's: an all-zero-duration window would
                    // otherwise divide 0.0/0.0 into NaN — explicit here so 0.0 is intentional.
                    sharePct = if (grandTotal <= 0L) 0.0 else roundTo6(total.toDouble() / grandTotal),
                )
            }
            .sortedWith(compareByDescending<PluginCostRow> { it.totalMs }.thenBy { it.plugin })
            .take(TOP_N)
        return PluginCostRollup(plugins = plugins, available = rows.any { it.type != null })
    }

    /**
     * Costliest-modules-to-change rollup (plan 063, research F13). For each build, every changed
     * module's "downstream" cost is that build's total executed time **minus** the executed time in the
     * module itself (`sum WHERE outcome=EXECUTED AND module != M` = cost inflicted on *others*). Per
     * module, the per-build downstream values are collected, their [medianLong] taken, and ranked by
     * `median × changeCount`. `LIMIT` [TOP_N], deterministic tiebreak by module ascending.
     *
     * Honest heuristic (plan documents it): when a build changes several modules, the whole build's
     * downstream time is attributed to *each* — shared/over-counted, a ranking signal, not causal
     * isolation (true direct-dependents "depth" is deferred to F12/plan 038, like plan-026's
     * `buildCostScalar` quirk).
     */
    fun changeBlastRadius(builds: List<ChangeBlastBuild>): List<ChangeBlastRadiusRow> {
        // module -> its per-build downstream-executed-ms samples (one per distinct build it changed in).
        val downstreamByModule = LinkedHashMap<String, MutableList<Long>>()
        for (build in builds) {
            val totalExecuted = build.executedMsByModule.values.sum()
            for (module in build.changedModules.distinct()) {
                val downstream = totalExecuted - (build.executedMsByModule[module] ?: 0L)
                downstreamByModule.getOrPut(module) { mutableListOf() }.add(downstream)
            }
        }
        return downstreamByModule.map { (module, samples) ->
            val median = medianLong(samples)
            ChangeBlastRadiusRow(
                module = module,
                changeCount = samples.size,
                medianDownstreamMs = median,
                blastScore = median * samples.size,
            )
        }.sortedWith(compareByDescending<ChangeBlastRadiusRow> { it.blastScore }.thenBy { it.module }).take(TOP_N)
    }

    /** Integer median: middle for odd counts, floor of the two-middle average for even (all values ≥ 0). */
    private fun medianLong(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /** Matches SQL `percentile_cont(0.5)`: interpolated (averaged) middle for even counts. */
    private fun medianDouble(values: List<Long>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun List<Long>.averageOrZero(): Long = if (isEmpty()) 0L else (sum() / size)

    private fun roundTo6(value: Double): Double = Math.round(value * SIX_DECIMAL_FACTOR) / SIX_DECIMAL_FACTOR
}
