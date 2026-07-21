package dev.buildhound.server

import kotlinx.serialization.Serializable

/**
 * A headline KPI's this-period value, the prior-period value, and the percent change (plan 032).
 */
@Serializable
data class KpiDelta(
    val current: Double? = null,
    val prior: Double? = null,
    val deltaPct: Double? = null,
)

/**
 * One ranked bottleneck (plan 032). `currentMs` is the family's primary metric (avg duration for
 * regressions, total for slowest/cache-miss, total excess for negative avoidance). Delta fields are
 * populated only for the regressed family; `isNew`/`isVanished` flag groups present in only one
 * window so the client never divides by zero or shows ∞/−100 %.
 */
@Serializable
data class BottleneckRow(
    val key: String,
    val module: String? = null,
    val currentMs: Long,
    val priorMs: Long? = null,
    val deltaMs: Long = 0,
    val deltaPct: Double? = null,
    val isNew: Boolean = false,
    val isVanished: Boolean = false,
    val count: Int,
)

/**
 * "What got worse this week" (plan 032, spec §6): a this-period-vs-prior-equal-period comparison
 * over four families, plus headline KPIs. Coarse and always-available (not plan 025's median+MAD
 * baseline); [budgetBreaches]/[trendRegressions] are populated only when a verdict store is wired
 * (null → the UI omits the card). [cacheDataAvailable] is false until `cacheable` is populated
 * (plan 016).
 */
@Serializable
data class BottlenecksRollup(
    val period: Int,
    val buildCount: KpiDelta,
    val successRate: KpiDelta,
    val avgDurationMs: KpiDelta,
    val hitRate: KpiDelta,
    val regressedTasks: List<BottleneckRow>,
    val slowestWork: List<BottleneckRow>,
    val negativeAvoidance: List<BottleneckRow>,
    val cacheMissHotspots: List<BottleneckRow>,
    val cacheDataAvailable: Boolean,
    val budgetBreaches: Int? = null,
    val trendRegressions: Int? = null,
    /**
     * Owning-plugin ranking by total task time this window (plan 058, research F8 Layer 1), folded
     * from the same [currentTasks][BottleneckCalculator.compute] as [slowestWork] — so it inherits
     * the bottlenecks store's period window + benchmark exclusion for free. Additive, defaulted
     * empty: older clients ignore it, like [budgetBreaches].
     */
    val topPlugins: List<BottleneckRow> = emptyList(),
    /**
     * Mirrors [PluginCostRollup.available]/[TaskDurationRollup.byTypeAvailable]: false only when no
     * task in the window carries a `type` at all (isolated-projects degradation, plan 016). Without
     * this flag, an all-null-type window would fold every [topPlugins] row into the single
     * "(unattributed)" bucket — a non-empty list the dashboard would render as if it were real
     * data, instead of the plan-016 "not collected yet" notice.
     */
    val topPluginsAvailable: Boolean,
)

/**
 * One toolchain version's fleet footprint (plan 032); [distinctUsers] counts hashed ids only.
 * [durationP50Ms] (plan 065): the p50 build duration over the samples carrying one — the daemon-JDK
 * fleet comparison ("your JDK 17 daemons are p50 X % slower than your JDK 21 daemons"). Null when
 * no sample in the group carried a duration (a dimension the stores don't feed one).
 */
@Serializable
data class ToolchainVersionRow(
    val version: String,
    val builds: Int,
    val sharePct: Double,
    val distinctUsers: Int,
    val lastSeenMs: Long,
    val durationP50Ms: Long? = null,
)

/**
 * One toolchain dimension's adoption. [available] is false when the dimension is never populated
 * (AGP/KGP/KSP today) so the UI shows an honest "not collected yet" panel, never an empty chart.
 * [behind] = versions older than the latest observed = "who is still behind".
 */
@Serializable
data class ToolchainDimension(
    val available: Boolean,
    val versions: List<ToolchainVersionRow> = emptyList(),
    val behind: List<ToolchainVersionRow> = emptyList(),
)

/**
 * Adoption across every toolchain dimension (plan 032). Only gradle/jdk carry data today.
 * [springBoot] (plan 072, research F22) is the server-service analogue of [agp]; additive with an
 * unavailable default so older clients ignore it and a store that doesn't feed it reports "not
 * collected yet".
 */
@Serializable
data class ToolchainRollup(
    val gradle: ToolchainDimension,
    val jdk: ToolchainDimension,
    val agp: ToolchainDimension,
    val kgp: ToolchainDimension,
    val ksp: ToolchainDimension,
    val springBoot: ToolchainDimension = ToolchainDimension(available = false),
)

/** One build's KPI inputs; both stores fetch these + [TaskRow]s and call [BottleneckCalculator]. */
data class BuildKpiRow(val outcome: String, val durationMs: Long, val hitRate: Double?)

/**
 * One toolchain observation for a dimension; version is null when the build didn't carry it.
 * [durationMs] (plan 065) is the build wall-clock, fed by both stores for the **jdk** dimension
 * only (the daemon-JDK comparison's input); null elsewhere → [ToolchainVersionRow.durationP50Ms]
 * stays null for those dimensions.
 */
data class ToolchainSample(
    val version: String?,
    val userId: String?,
    val startedAt: Long,
    val durationMs: Long? = null,
)

/**
 * Pure bottleneck/toolchain math (plan 032), the single source both stores defer to (the plan-026
 * parity discipline). Coarse window diff, honest about small samples: a group needs [MIN_SAMPLES]
 * occurrences to enter the regressed ranking, and new/vanished groups are flagged rather than
 * divided.
 */
object BottleneckCalculator {

    const val TOP_N = RollupCalculator.TOP_N
    const val MIN_SAMPLES = 2

    private data class Group(val avgMs: Long, val count: Int, val module: String?)

    // This pure calculator keeps the current/prior classifications together so each output is derived
    // from the same bounded input set.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun compute(
        currentTasks: List<TaskRow>,
        priorTasks: List<TaskRow>,
        currentBuilds: List<BuildKpiRow>,
        priorBuilds: List<BuildKpiRow>,
        period: Int,
        budgetBreaches: Int? = null,
        trendRegressions: Int? = null,
    ): BottlenecksRollup {
        // Regressions compare EXECUTED work only: a task flipping between cached and executed would
        // otherwise show a spurious swing, and cache-hit/up-to-date time is negative avoidance, not
        // "compute got slower". Slowest work below is total wall over every outcome.
        val current = executedGroups(currentTasks)
        val prior = executedGroups(priorTasks)

        val regressed =
            (current.keys + prior.keys)
                .mapNotNull { key ->
                    val c = current[key]
                    val p = prior[key]
                    when {
                        c != null &&
                            c.count >= MIN_SAMPLES &&
                            p != null &&
                            p.count >= MIN_SAMPLES -> {
                            val delta = c.avgMs - p.avgMs
                            BottleneckRow(
                                key = key,
                                module = c.module,
                                currentMs = c.avgMs,
                                priorMs = p.avgMs,
                                deltaMs = delta,
                                deltaPct =
                                    if (p.avgMs > 0) roundTo6(delta.toDouble() / p.avgMs) else null,
                                count = c.count,
                            )
                        }
                        c != null && c.count >= MIN_SAMPLES && p == null ->
                            BottleneckRow(
                                key = key,
                                module = c.module,
                                currentMs = c.avgMs,
                                deltaMs = c.avgMs,
                                isNew = true,
                                count = c.count,
                            )
                        // "Vanished" means it did not execute at all this window (c == null). A
                        // group that ran
                        // below the min-sample threshold is dropped, not zeroed-and-flagged-gone
                        // (honesty).
                        c == null && p != null && p.count >= MIN_SAMPLES ->
                            BottleneckRow(
                                key = key,
                                module = p.module,
                                currentMs = 0,
                                priorMs = p.avgMs,
                                deltaMs = -p.avgMs,
                                isVanished = true,
                                count = 0,
                            )
                        else -> null
                    }
                }
                .filter { it.deltaMs > 0 || it.isNew || it.isVanished }
                .sortedWith(compareByDescending<BottleneckRow> { it.deltaMs }.thenBy { it.key })
                .take(TOP_N)

        val slowest =
            currentTasks
                .groupBy { it.type ?: it.name }
                .map { (key, group) ->
                    BottleneckRow(
                        key = key,
                        module = group.map { it.module }.distinct().singleOrNull(),
                        currentMs = group.sumOf { it.durationMs },
                        count = group.size,
                    )
                }
                .sortedWith(compareByDescending<BottleneckRow> { it.currentMs }.thenBy { it.key })
                .take(TOP_N)

        val negativeAvoidance =
            RollupCalculator.negativeAvoidance(currentTasks).map {
                BottleneckRow(key = it.key, currentMs = it.totalExcessMs, count = it.count)
            }

        val misses = currentTasks.filter { it.cacheable == true && it.outcome == "EXECUTED" }
        val cacheMiss =
            misses
                .groupBy { it.type ?: it.name }
                .map { (key, group) ->
                    BottleneckRow(
                        key = key,
                        module = group.map { it.module }.distinct().singleOrNull(),
                        currentMs = group.sumOf { it.durationMs },
                        count = group.size,
                    )
                }
                .sortedWith(compareByDescending<BottleneckRow> { it.currentMs }.thenBy { it.key })
                .take(TOP_N)

        // Owning-plugin ranking (plan 058): folds the same currentTasks slowestWork uses, so it
        // inherits the period window + benchmark exclusion with no extra store call. module is left
        // null — a plugin's tasks span many modules, so a single "owning module" would be
        // misleading.
        val topPlugins =
            currentTasks
                .groupBy { PluginAttribution.owningPlugin(it.type) }
                .map { (plugin, group) ->
                    BottleneckRow(
                        key = plugin,
                        currentMs = group.sumOf { it.durationMs },
                        count = group.size,
                    )
                }
                .sortedWith(compareByDescending<BottleneckRow> { it.currentMs }.thenBy { it.key })
                .take(TOP_N)

        return BottlenecksRollup(
            period = period,
            buildCount = kpi(currentBuilds.size.toDouble(), priorBuilds.size.toDouble()),
            successRate = kpi(successRate(currentBuilds), successRate(priorBuilds)),
            avgDurationMs = kpi(avgDuration(currentBuilds), avgDuration(priorBuilds)),
            hitRate = kpi(avgHitRate(currentBuilds), avgHitRate(priorBuilds)),
            regressedTasks = regressed,
            slowestWork = slowest,
            negativeAvoidance = negativeAvoidance,
            cacheMissHotspots = cacheMiss,
            cacheDataAvailable = currentTasks.any { it.cacheable != null },
            budgetBreaches = budgetBreaches,
            trendRegressions = trendRegressions,
            topPlugins = topPlugins,
            topPluginsAvailable = currentTasks.any { it.type != null },
        )
    }

    /**
     * Average + count over EXECUTED tasks only, grouped by type ?: name — the regression baseline.
     */
    private fun executedGroups(rows: List<TaskRow>): Map<String, Group> =
        rows
            .filter { it.outcome == "EXECUTED" }
            .groupBy { it.type ?: it.name }
            .mapValues { (_, group) ->
                Group(
                    avgMs = group.sumOf { it.durationMs } / group.size,
                    count = group.size,
                    module = group.map { it.module }.distinct().singleOrNull(),
                )
            }

    // Round current/prior (not just deltaPct) so both stores agree exactly regardless of the order
    // their rows arrive in — the same discipline RollupCalculator uses for its float outputs.
    private fun kpi(currentRaw: Double?, priorRaw: Double?): KpiDelta {
        val current = currentRaw?.let { roundTo6(it) }
        val prior = priorRaw?.let { roundTo6(it) }
        return KpiDelta(
            current,
            prior,
            if (current != null && prior != null && prior != 0.0)
                roundTo6((current - prior) / prior)
            else null,
        )
    }

    private fun successRate(builds: List<BuildKpiRow>): Double? =
        if (builds.isEmpty()) null
        else builds.count { it.outcome == "SUCCESS" }.toDouble() / builds.size

    private fun avgDuration(builds: List<BuildKpiRow>): Double? =
        if (builds.isEmpty()) null else builds.sumOf { it.durationMs }.toDouble() / builds.size

    // sorted() before average() makes the float fold order-independent: both stores hold the same
    // multiset of hit rates but feed rows in different orders, so an unsorted sum could differ in
    // the
    // last bit and break byte-for-byte parity. (successRate/avgDuration sum integers → already
    // exact.)
    private fun avgHitRate(builds: List<BuildKpiRow>): Double? =
        builds.mapNotNull { it.hitRate }.takeIf { it.isNotEmpty() }?.sorted()?.average()

    private fun roundTo6(value: Double): Double =
        Math.round(value * SIX_DECIMAL_FACTOR) / SIX_DECIMAL_FACTOR
}

/** Pure toolchain-adoption math (plan 032). Both stores fetch [ToolchainSample]s and call this. */
object ToolchainCalculator {

    /**
     * One dimension's adoption + p50 duration. [versionKey] maps each observed version onto its
     * grouping key — identity for every dimension except **jdk**, which since plan 065 groups by
     * JDK **major** (see [jdkMajor]) so the fleet comparison reads "JDK 17 vs JDK 21", not per
     * patch release. p50 is nearest-rank over the *sorted* durations (order-invariant, so the two
     * stores' differing row orders keep byte-for-byte parity — the plan-026 discipline).
     */
    fun dimension(
        samples: List<ToolchainSample>,
        versionKey: (String) -> String = { it },
    ): ToolchainDimension {
        val present = samples.filter { it.version != null }
        if (present.isEmpty()) return ToolchainDimension(available = false)
        val total = present.size
        val versions =
            present
                .groupBy { versionKey(it.version!!) }
                .map { (version, group) ->
                    val durations = group.mapNotNull { it.durationMs }.sorted()
                    ToolchainVersionRow(
                        version = version,
                        builds = group.size,
                        sharePct = roundTo6(group.size.toDouble() / total),
                        distinctUsers = group.mapNotNull { it.userId }.distinct().size,
                        lastSeenMs = group.maxOf { it.startedAt },
                        durationP50Ms =
                            if (durations.isEmpty()) null
                            else NearestRankPercentile.ofSorted(durations, P50_QUANTILE),
                    )
                }
                .sortedWith(
                    compareByDescending<ToolchainVersionRow> { it.builds }
                        .thenByDescending(VERSION) { it.version }
                )
        val latest = versions.maxWithOrNull(compareBy(VERSION) { it.version })?.version
        // Compare with VERSION (not string inequality) so a version equal-to-latest under the
        // comparator is never mislabeled "behind" on a bare string difference (e.g. "8.0" vs
        // "8.0.0").
        val behind =
            if (latest == null) emptyList()
            else versions.filter { VERSION.compare(it.version, latest) < 0 }
        return ToolchainDimension(available = true, versions = versions, behind = behind)
    }

    /**
     * The jdk dimension's grouping key (plan 065): the leading numeric segment ("21.0.10" → "21",
     * "17" → "17"). A version whose leading segment is not numeric groups under itself, unchanged —
     * honest fallback, never a guessed major.
     */
    fun jdkMajor(version: String): String =
        version.trim().split('.', '-', '_', '+').firstOrNull()?.takeIf { seg ->
            seg.isNotEmpty() && seg.all { it.isDigit() }
        } ?: version

    /**
     * Best-effort version ordering: compare dotted/dashed segments numerically, non-numeric as
     * strings.
     */
    private val VERSION =
        Comparator<String> { a, b ->
            val pa = a.split('.', '-')
            val pb = b.split('.', '-')
            var result = 0
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i)
                val y = pb.getOrNull(i)
                val cmp =
                    when {
                        x == null -> -1
                        y == null -> 1
                        else -> {
                            val xi = x.toIntOrNull()
                            val yi = y.toIntOrNull()
                            when {
                                xi != null && yi != null -> xi.compareTo(yi)
                                xi != null ->
                                    1 // a numeric segment sorts above a non-numeric one ("9" >
                                      // "beta")
                                yi != null -> -1
                                else -> x.compareTo(y)
                            }
                        }
                    }
                if (cmp != 0) {
                    result = cmp
                    break
                }
            }
            result
        }

    private fun roundTo6(value: Double): Double =
        Math.round(value * SIX_DECIMAL_FACTOR) / SIX_DECIMAL_FACTOR
}
