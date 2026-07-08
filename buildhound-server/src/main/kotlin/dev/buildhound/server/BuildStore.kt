package dev.buildhound.server

import dev.buildhound.commons.payload.BenchmarkSeriesCalculator
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.DerivedMetricsCalculator
import dev.buildhound.commons.payload.TestUnitKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The tenant a request acts as — resolved from its token, never from the payload. */
data class ProjectRef(val id: String, val key: String)

/** Percentiles for one benchmark group via the shared commons calculator (parity across stores). */
internal fun summarize(points: List<BenchmarkPoint>): BenchmarkSummary {
    val s = BenchmarkSeriesCalculator.summarize(points.map { it.durationMs })
        ?: return BenchmarkSummary(p50 = 0, p90 = 0, min = 0, count = 0) // groups are never empty in practice
    return BenchmarkSummary(p50 = s.p50, p90 = s.p90, min = s.min, count = s.count)
}

/**
 * Flatten a payload's `tests` block into **one** per-class outcome row per `(module, class)` (plan
 * 036) — the shape both stores feed [FlakyDetector]. When a class is reported by more than one `Test`
 * task in the same build (e.g. a `test` + `integrationTest` split, or plan-040 shards), its counts are
 * summed into a single row: `PostgresBuildStore`'s PK is `(project, build, module, class)` with
 * `ON CONFLICT DO NOTHING`, so it can physically hold only one such row — aggregating here (rather
 * than emitting duplicates the in-memory store would keep but Postgres would drop) is what makes the
 * two stores agree *by construction*, and keeps the cross-run signal genuinely build-scoped (a single
 * build's merged row is either green or red for a class, never both). The in-memory store computes
 * flaky over these directly; `PostgresBuildStore` inserts them on save and reads them back.
 */
internal fun classOutcomesOf(payload: BuildPayload): List<ClassOutcome> {
    val sha = payload.vcs?.sha
    return payload.tests.flatMap { task ->
        val retryByClass = task.failedOrRetried
            .filter { detail -> FlakyDetector.failedThenPassed(detail.outcomes.map { it.name }) }
            .groupingBy { it.className }.eachCount()
        task.classes.map { cls ->
            ClassOutcome(
                buildId = payload.buildId,
                startedAtMs = payload.startedAt,
                sha = sha,
                module = task.module,
                classFqcn = cls.className,
                passed = cls.passed,
                failed = cls.failed,
                retryFlakyCases = retryByClass[cls.className] ?: 0,
            )
        }
    }.groupBy { TestUnitKey.of(it.module, it.classFqcn) }
        .map { (_, rows) ->
            rows.first().copy(
                passed = rows.sumOf { it.passed },
                failed = rows.sumOf { it.failed },
                retryFlakyCases = rows.sumOf { it.retryFlakyCases },
            )
        }
}

/**
 * Per-test-class durations from a payload's `tests` block (plan 040): `TestUnitKey.of(module, class)`
 * → this build's class duration. The shard balancer's timing source; both stores group these over a
 * CI window and hand them to [LptBalancer], so the plan is computed from the same numbers everywhere.
 */
internal fun classTimingsOf(payload: BuildPayload): List<Pair<String, Long>> =
    payload.tests.flatMap { task ->
        task.classes.map { cls -> TestUnitKey.of(task.module, cls.className) to cls.durationMs }
    }

/** Query-API filters (plan 010); values are validated at the route, bound in SQL. */
data class BuildFilter(
    val branch: String? = null,
    val mode: String? = null,
    val outcome: String? = null,
    /** Modes excluded from fleet views (plan 030): benchmark builds are excluded by default. */
    val excludeModes: Set<String> = emptySet(),
)

/** One point of a benchmark series (plan 030); ordered oldest-first within its (scenario, isolation). */
@Serializable
data class BenchmarkPoint(
    val startedAt: Long,
    val buildId: String,
    val iteration: Int? = null,
    val durationMs: Long,
    val hitRate: Double? = null,
)

/** Percentile summary of one benchmark series (mirrors [dev.buildhound.commons.payload.BenchmarkSeriesCalculator.Summary]). */
@Serializable
data class BenchmarkSummary(val p50: Long, val p90: Long, val min: Long, val count: Int)

/** A benchmark series grouped by (scenario, isolationMode) with its points + percentile summary (plan 030). */
@Serializable
data class BenchmarkSeries(
    val scenario: String,
    val isolationMode: String? = null,
    val points: List<BenchmarkPoint>,
    val summary: BenchmarkSummary,
)

/** One daily point of an artifact-size trend, grouped by (module, variant, type) (plan 031). */
@Serializable
data class ArtifactTrendPoint(
    val day: String, // yyyy-MM-dd, UTC
    val module: String? = null,
    val variant: String,
    val type: String, // APK | AAB | AAR
    val avgSizeBytes: Long,
    val maxSizeBytes: Long,
    val builds: Int,
)

/** One row of the builds list — hot columns only, never the jsonb payload. */
@Serializable
data class BuildSummary(
    val buildId: String,
    val startedAt: Long,
    val durationMs: Long,
    val outcome: String,
    val mode: String,
    val branch: String? = null,
    val hitRate: Double? = null,
)

/** One daily bucket of the on-read rollup (plan 010). */
@Serializable
data class TrendPoint(
    val day: String, // yyyy-MM-dd, UTC
    val builds: Int,
    val failures: Int,
    val avgDurationMs: Long,
    val maxDurationMs: Long,
    val avgHitRate: Double? = null,
    /**
     * Never-finalized builds this day (plan 033). Counted separately and **excluded** from
     * [failures] and the duration/hit-rate aggregates — an interrupted build's duration is synthetic
     * (`finishedAt == startedAt`), so folding it in would skew baselines.
     */
    val interrupted: Int = 0,
)

/** The columns that key a rolling baseline (spec §5, plan 025). */
data class BaselineQuery(
    val pipelineName: String?,
    val requestedTasksSig: String,
    val mode: String,
)

/** One baseline build's metric values, read from the hot columns (no jsonb). */
data class BaselinePoint(val durationMs: Long, val hitRate: Double? = null)

/** A custom measure from the metric CLI (plan 025). `buildId` is null until correlated. */
data class MetricRecord(
    val scope: String,
    val name: String,
    val value: Double? = null,
    val text: String? = null,
    val unit: String? = null,
    val buildId: String? = null,
    val provider: String? = null,
    val runId: String? = null,
)

/**
 * Persistence boundary for ingested builds (architecture §5): every operation carries
 * the tenant. Real implementation is Postgres; in-memory serves tests and DB-less dev.
 */
interface BuildStore {
    /** Idempotent on (project, [BuildPayload.buildId]); false when already stored. */
    fun save(projectId: String, payload: BuildPayload): Boolean

    fun findById(projectId: String, buildId: String): BuildPayload?

    /** Total matching [filter] (default: whole project) — the filter-aware list total (plan 018). */
    fun count(projectId: String, filter: BuildFilter = BuildFilter()): Long

    /** Newest-first summaries. */
    fun list(projectId: String, filter: BuildFilter, limit: Int, offset: Int): List<BuildSummary>

    /** Daily buckets over the last [days], oldest first; empty days are omitted. */
    fun trends(projectId: String, filter: BuildFilter, days: Int, nowMs: Long): List<TrendPoint>

    /** Newest build matching a CI {provider, runId}, for metric correlation; null when none. */
    fun resolveBuildId(projectId: String, provider: String?, runId: String?): String?

    /**
     * The last [n] SUCCESS builds on [defaultBranch] matching [query], excluding [excludingBuildId],
     * newest-first — the rolling baseline window (spec §5). Reads hot columns only.
     */
    fun baselineWindow(
        projectId: String,
        defaultBranch: String,
        query: BaselineQuery,
        excludingBuildId: String,
        n: Int,
    ): List<BaselinePoint>

    /** Per-module Project Cost family over the last [days] (plan 026); top-25 by cost scalar. */
    fun projectCost(projectId: String, days: Int, nowMs: Long): List<ProjectCostRow>

    /** Task duration grouped by name and by type over the last [days] (plan 026); top-25 each. */
    fun taskDuration(projectId: String, days: Int, nowMs: Long): TaskDurationRollup

    /** Negative-avoidance ranking over the last [days] (plan 026); top-25 by total excess. */
    fun negativeAvoidance(projectId: String, days: Int, nowMs: Long): List<NegativeAvoidanceRow>

    /**
     * Benchmark series over the last [days] (plan 030): `mode=BENCHMARK` builds grouped by
     * (scenario, isolationMode), optionally narrowed by [scenario]/[isolationMode]/[branch]. Each
     * group carries oldest-first points + a percentile summary. Empty when no benchmark builds match.
     */
    fun benchmarkSeries(
        projectId: String,
        scenario: String?,
        isolationMode: String?,
        branch: String?,
        days: Int,
        nowMs: Long,
    ): List<BenchmarkSeries>

    /**
     * Daily artifact-size series over the last [days] (plan 031), grouped by (module, variant, type):
     * average + max size and the contributing build count per day. [filter] is applied like `trends`
     * (benchmark builds excluded by default at the route).
     */
    fun artifactTrends(projectId: String, filter: BuildFilter, days: Int, nowMs: Long): List<ArtifactTrendPoint>

    /**
     * "What got worse" landing rollup (plan 032): this [period]-day window vs the prior equal window.
     * Benchmark builds are excluded (fleet view). Both stores fetch raw rows for the two windows and
     * defer to [BottleneckCalculator], so in-memory and Postgres agree byte-for-byte.
     */
    fun bottlenecks(projectId: String, period: Int, nowMs: Long): BottlenecksRollup

    /**
     * Toolchain adoption over the last [days] (plan 032): per-dimension version distribution + hashed
     * distinct-user count + "who is behind". AGP/KGP/KSP report `available=false` until populated.
     */
    fun toolchainAdoption(projectId: String, days: Int, nowMs: Long): ToolchainRollup

    /**
     * Flaky-test records over the last [days] (plan 036): the two-signal [FlakyDetector] over per-class
     * outcomes, ranked by flake rate. Both stores feed the same detector so results agree byte-for-byte.
     */
    fun flaky(projectId: String, days: Int, nowMs: Long): List<FlakyRecord>

    /**
     * Per-`TestUnitKey` CI test-class durations over the last [days] (plan 040): the shard balancer's
     * timing source. Grouped from windowed CI payloads' `tests` blocks; the caller takes a p90 per key.
     */
    fun classTimings(projectId: String, days: Int, nowMs: Long): Map<String, List<Long>>

    /**
     * Per-project metrics snapshot over the last [days] (plan 070): the Prometheus egress endpoint's
     * source. Both stores fetch the same windowed [BuildKpiRow]s the bottlenecks/trends rollups already
     * fetch (benchmark builds excluded, same fleet-view convention) plus the windowed `derived.avoidedMs`
     * values, and defer to [MetricsSnapshotCalculator]; the flaky count reuses [flaky]'s own detector
     * output rather than re-deriving it.
     */
    fun metricsSnapshot(projectId: String, days: Int, nowMs: Long): MetricsSnapshot

    /** Every project id with stored data (retention sweep, plan 042); default empty for a store that has none. */
    fun allProjectIds(): List<String> = emptyList()

    /**
     * Retention purge (plan 042): delete this project's builds started before [buildCutoffMs] and its
     * raw per-task rows before [rawCutoffMs]. Tenant-scoped, batched to bound lock time; returns the
     * counts deleted. Default no-op so a store without raw storage degrades safely.
     */
    fun purgeOlderThan(projectId: String, buildCutoffMs: Long, rawCutoffMs: Long): RetentionPurge = RetentionPurge(0, 0)
}

/** Row counts a single retention sweep deleted for one project (plan 042). */
data class RetentionPurge(val builds: Long, val rawRows: Long)

/** Custom measures (plan 025); idempotent upsert so a retried CI step never duplicates. */
interface MetricStore {
    fun upsert(projectId: String, metric: MetricRecord)

    fun forBuild(projectId: String, buildId: String): List<MetricRecord>

    /**
     * Lazy join: attach [buildId] to metrics posted before the build existed (null build_id,
     * matching {provider, runId}). No-op when both correlation keys are null.
     */
    fun correlate(projectId: String, provider: String?, runId: String?, buildId: String)

    /**
     * The `"scope name"` keys already stored for one logical run (matched by {provider,runId}
     * when given, else by buildId) — for the ≤100-measures-per-run cardinality cap (spec §5).
     */
    fun correlationKeys(projectId: String, buildId: String?, provider: String?, runId: String?): Set<String>
}

/** Persisted per-build verdicts (plan 025). */
interface VerdictStore {
    fun save(projectId: String, buildId: String, verdict: Verdict)

    fun find(projectId: String, buildId: String): Verdict?

    /** Latest status for the same baseline key excluding [buildId] — powers alert de-dup. */
    fun latestStatusForKey(projectId: String, baselineKey: String, excludingBuildId: String): String?
}

/** Per-project regression settings (plan 025); null when a project has no row (use defaults). */
interface SettingsStore {
    fun get(projectId: String): ProjectSettings?

    fun put(projectId: String, settings: ProjectSettings)

    /**
     * Per-project retention windows (plan 042), backed by the same `project_settings` row. Returns
     * the spec defaults when the project has no row (or has one with the default columns). Kept a
     * separate accessor from [get]/[put] so retention writes never disturb the regression columns.
     */
    fun retention(projectId: String): RetentionConfig = RetentionConfig.DEFAULT

    fun setRetention(projectId: String, config: RetentionConfig) {}
}

/**
 * Idempotent shard-plan memo for the test-sharding addon (plan 040). Keyed `(projectId, reference,
 * total)`: the first CI job for a reference computes+stores the LPT plan; every later job reads the
 * same plan, so inter-job suite-discovery drift can't reshuffle shards mid-run. Tenant-scoped.
 */
interface ShardPlanStore {
    /** The stored plan for the key, or the result of [compute] stored atomically on first call. */
    fun planOrCompute(projectId: String, reference: String, total: Int, compute: () -> List<List<String>>): List<List<String>>
}

class InMemoryShardPlanStore : ShardPlanStore {
    private val plans = ConcurrentHashMap<Triple<String, String, Int>, List<List<String>>>()

    override fun planOrCompute(projectId: String, reference: String, total: Int, compute: () -> List<List<String>>): List<List<String>> =
        plans.computeIfAbsent(Triple(projectId, reference, total)) { compute() }
}

/**
 * Tenant-scoped jsonb key/value storage for addon APIs (plan 039). Keyed `(projectId, addonId, key)`;
 * `value` is opaque addon-owned JSON. jsonb keeps ingest schema-stable — no per-addon DDL. Every
 * operation carries `projectId`, so one tenant never sees another's addon data (architecture §5).
 */
interface AddonStore {
    fun get(projectId: String, addonId: String, key: String): JsonElement?

    fun put(projectId: String, addonId: String, key: String, value: JsonElement)

    /** All keys stored for [addonId] within [projectId], key → value. */
    fun all(projectId: String, addonId: String): Map<String, JsonElement>
}

/** Token scopes (spec §5): a leaked CI ingest token must not read history. */
object TokenScope {
    const val INGEST = "ingest"
    const val READ = "read"
    const val ADDON = "addon"
    const val ADMIN = "admin"
    const val ALL = "all"
    const val METRICS = "metrics"

    fun allowsIngest(scope: String): Boolean = scope == INGEST || scope == ALL
    fun allowsRead(scope: String): Boolean = scope == READ || scope == ALL

    /** Addon APIs (plan 039): a dedicated scope walls the `/v1/addons` namespace off from ingest/read tokens. */
    fun allowsAddon(scope: String): Boolean = scope == ADDON || scope == ALL

    /**
     * Admin APIs (plan 042): retention config and future tenant admin. A distinct scope so a leaked CI
     * `ingest` or dashboard `read` token can never reach `/v1/admin` — only an `admin` or `all` token.
     */
    fun allowsAdmin(scope: String): Boolean = scope == ADMIN || scope == ALL

    fun allowsAll(scope: String): Boolean = scope == ALL

    /**
     * Prometheus scrape API (plan 070): a narrow scrape-only token that can mint metrics egress without
     * also granting `/v1/builds` history — `read`/`all` are supersets (an ops team that already trusts a
     * `read` token with history can reuse it for scraping too).
     */
    fun allowsMetrics(scope: String): Boolean = scope == METRICS || scope == READ || scope == ALL
}

data class TokenPrincipal(val project: ProjectRef, val scope: String)

/** Token lookups by hash — plaintext tokens never reach a store (spec §8). */
interface TokenStore {
    fun resolve(tokenHash: String): TokenPrincipal?

    /** Idempotent pilot bootstrap: create the project and attach the token hash. */
    fun ensureProjectWithToken(projectKey: String, tokenHash: String, scope: String = TokenScope.ALL): ProjectRef
}

class InMemoryBuildStore : BuildStore {
    private val builds = ConcurrentHashMap<Pair<String, String>, BuildPayload>()

    override fun save(projectId: String, payload: BuildPayload): Boolean =
        builds.putIfAbsent(projectId to payload.buildId, payload) == null

    override fun findById(projectId: String, buildId: String): BuildPayload? =
        builds[projectId to buildId]

    override fun allProjectIds(): List<String> = builds.keys.map { it.first }.distinct()

    override fun purgeOlderThan(projectId: String, buildCutoffMs: Long, rawCutoffMs: Long): RetentionPurge {
        // In-memory keeps task detail inside the build payload (no separate raw table), so the build
        // window governs everything; rawCutoffMs is honored by the Postgres store's task_executions table.
        val stale = builds.entries.filter { it.key.first == projectId && it.value.startedAt < buildCutoffMs }
        stale.forEach { builds.remove(it.key) }
        return RetentionPurge(builds = stale.size.toLong(), rawRows = 0)
    }

    override fun count(projectId: String, filter: BuildFilter): Long =
        matching(projectId, filter).size.toLong()

    private fun matching(projectId: String, filter: BuildFilter): List<BuildPayload> =
        builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { filter.branch == null || it.vcs?.branch == filter.branch }
            .filter { filter.mode == null || it.mode.name == filter.mode }
            .filter { filter.outcome == null || it.outcome.name == filter.outcome }
            .filter { it.mode.name !in filter.excludeModes }

    override fun list(projectId: String, filter: BuildFilter, limit: Int, offset: Int): List<BuildSummary> =
        matching(projectId, filter)
            // buildId tiebreak keeps pagination deterministic on equal timestamps.
            .sortedWith(compareByDescending<BuildPayload> { it.startedAt }.thenByDescending { it.buildId })
            .drop(offset)
            .take(limit)
            .map { payload ->
                BuildSummary(
                    buildId = payload.buildId,
                    startedAt = payload.startedAt,
                    durationMs = payload.finishedAt - payload.startedAt,
                    outcome = payload.outcome.name,
                    mode = payload.mode.name,
                    branch = payload.vcs?.branch,
                    hitRate = payload.derived?.cacheableHitRate,
                )
            }

    override fun trends(projectId: String, filter: BuildFilter, days: Int, nowMs: Long): List<TrendPoint> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        return matching(projectId, filter)
            .filter { it.startedAt >= cutoff }
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.startedAt), ZoneOffset.UTC) }
            .toSortedMap()
            .map { (day, dayBuilds) ->
                // Interrupted builds (plan 033) are counted but never fed to the duration/hit-rate
                // aggregates (their duration is synthetic) — only SUCCESS/FAILED builds are.
                val finished = dayBuilds.filter { it.outcome.name == "SUCCESS" || it.outcome.name == "FAILED" }
                val durations = finished.map { it.finishedAt - it.startedAt }
                val hitRates = finished.mapNotNull { it.derived?.cacheableHitRate }
                TrendPoint(
                    day = day.toString(),
                    builds = dayBuilds.size,
                    failures = dayBuilds.count { it.outcome.name == "FAILED" },
                    avgDurationMs = if (durations.isEmpty()) 0 else Math.round(durations.average()),
                    maxDurationMs = durations.maxOrNull() ?: 0,
                    avgHitRate = hitRates.takeIf { it.isNotEmpty() }?.average(),
                    interrupted = dayBuilds.count { it.outcome.name == "INTERRUPTED" },
                )
            }
    }

    override fun resolveBuildId(projectId: String, provider: String?, runId: String?): String? =
        builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.ci?.provider == provider && it.ci?.runId == runId }
            .maxByOrNull { it.startedAt }
            ?.buildId

    override fun baselineWindow(
        projectId: String,
        defaultBranch: String,
        query: BaselineQuery,
        excludingBuildId: String,
        n: Int,
    ): List<BaselinePoint> =
        builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.buildId != excludingBuildId }
            .filter { it.outcome.name == "SUCCESS" }
            .filter { it.vcs?.branch == defaultBranch }
            .filter { it.mode.name == query.mode }
            .filter { it.ci?.pipelineName == query.pipelineName }
            .filter { RegressionEngine.requestedTasksSignature(it.requestedTasks) == query.requestedTasksSig }
            // Baseline hygiene (plan 051): rerunTasks/refreshDependencies builds have zero avoidance
            // by design (the same rationale that excluded INTERRUPTED in plan 033) — mirrors the
            // Postgres store's jsonb guard so the two agree byte-for-byte (plan-025 parity).
            .filter { it.environment?.invocation?.rerunTasks != true }
            .filter { it.environment?.invocation?.refreshDependencies != true }
            .sortedByDescending { it.startedAt }
            .take(n)
            .map { BaselinePoint(durationMs = it.finishedAt - it.startedAt, hitRate = it.derived?.cacheableHitRate) }

    override fun projectCost(projectId: String, days: Int, nowMs: Long): List<ProjectCostRow> =
        RollupCalculator.projectCost(taskRowsInWindow(projectId, days, nowMs))

    override fun taskDuration(projectId: String, days: Int, nowMs: Long): TaskDurationRollup =
        RollupCalculator.taskDuration(taskRowsInWindow(projectId, days, nowMs))

    override fun negativeAvoidance(projectId: String, days: Int, nowMs: Long): List<NegativeAvoidanceRow> =
        RollupCalculator.negativeAvoidance(taskRowsInWindow(projectId, days, nowMs))

    override fun benchmarkSeries(
        projectId: String,
        scenario: String?,
        isolationMode: String?,
        branch: String?,
        days: Int,
        nowMs: Long,
    ): List<BenchmarkSeries> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        return builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.mode.name == "BENCHMARK" && it.benchmark != null && it.startedAt >= cutoff }
            .filter { branch == null || it.vcs?.branch == branch }
            .filter { scenario == null || it.benchmark?.scenario == scenario }
            .filter { isolationMode == null || it.benchmark?.isolationMode == isolationMode }
            .groupBy { it.benchmark!!.scenario to it.benchmark!!.isolationMode }
            .map { (key, group) -> benchmarkSeriesOf(key.first, key.second, group) }
            .sortedWith(compareBy({ it.scenario }, { it.isolationMode ?: "" }))
    }

    override fun artifactTrends(projectId: String, filter: BuildFilter, days: Int, nowMs: Long): List<ArtifactTrendPoint> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        return matching(projectId, filter)
            .filter { it.startedAt >= cutoff }
            .flatMap { payload -> payload.artifacts?.android.orEmpty().map { payload to it } }
            .groupBy { (payload, artifact) ->
                val day = LocalDate.ofInstant(Instant.ofEpochMilli(payload.startedAt), ZoneOffset.UTC).toString()
                ArtifactGroupKey(day, artifact.module, artifact.variant, artifact.type.name)
            }
            .map { (key, rows) ->
                val sizes = rows.map { it.second.sizeBytes }
                ArtifactTrendPoint(
                    day = key.day,
                    module = key.module,
                    variant = key.variant,
                    type = key.type,
                    avgSizeBytes = Math.round(sizes.average()),
                    maxSizeBytes = sizes.max(),
                    builds = rows.map { it.first.buildId }.distinct().size,
                )
            }
            .sortedWith(compareBy({ it.day }, { it.variant }, { it.type }, { it.module ?: "" }))
    }

    override fun bottlenecks(projectId: String, period: Int, nowMs: Long): BottlenecksRollup {
        val windowMs = period.toLong() * 86_400_000
        val currentFrom = nowMs - windowMs
        val priorFrom = nowMs - 2 * windowMs
        val current = payloadsBetween(projectId, currentFrom, nowMs)
        val prior = payloadsBetween(projectId, priorFrom, currentFrom)
        return BottleneckCalculator.compute(
            currentTasks = current.flatMap { taskRowsOf(it) },
            priorTasks = prior.flatMap { taskRowsOf(it) },
            currentBuilds = current.map { kpiRowOf(it) },
            priorBuilds = prior.map { kpiRowOf(it) },
            period = period,
        )
    }

    override fun toolchainAdoption(projectId: String, days: Int, nowMs: Long): ToolchainRollup {
        val payloads = payloadsBetween(projectId, nowMs - days.toLong() * 86_400_000, nowMs)
        fun samples(select: (BuildPayload) -> String?): List<ToolchainSample> =
            payloads.map { ToolchainSample(select(it), it.environment?.userId, it.startedAt) }
        return ToolchainRollup(
            gradle = ToolchainCalculator.dimension(samples { it.toolchain?.gradle }),
            jdk = ToolchainCalculator.dimension(samples { it.toolchain?.jdk }),
            agp = ToolchainCalculator.dimension(samples { it.toolchain?.agp }),
            kgp = ToolchainCalculator.dimension(samples { it.toolchain?.kgp }),
            ksp = ToolchainCalculator.dimension(samples { it.toolchain?.ksp }),
        )
    }

    override fun flaky(projectId: String, days: Int, nowMs: Long): List<FlakyRecord> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        val rows = builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.startedAt >= cutoff }
            .flatMap { classOutcomesOf(it) }
            // Same most-recent-first order + cap as the Postgres LIMIT, so the two stores read the
            // identical bounded set (FlakyDetector.MAX_OUTCOME_ROWS). Benchmark builds are deliberately
            // *not* excluded here: unlike the fleet timing rollups (plan 030), same-sha benchmark reruns
            // are legitimate cross-run flakiness evidence, not noise.
            .sortedWith(
                compareByDescending<ClassOutcome> { it.startedAtMs }
                    .thenBy { it.buildId }
                    .thenBy { it.module ?: "" }
                    .thenBy { it.classFqcn },
            )
            .take(FlakyDetector.MAX_OUTCOME_ROWS)
        return FlakyDetector.detect(rows)
    }

    override fun metricsSnapshot(projectId: String, days: Int, nowMs: Long): MetricsSnapshot {
        // Same fleet-view window as bottlenecks/trends (benchmark builds excluded) — reuses the
        // existing payloadsBetween/kpiRowOf helpers so the two stores agree by construction.
        val windowed = payloadsBetween(projectId, nowMs - days.toLong() * 86_400_000, nowMs)
        return MetricsSnapshotCalculator.compute(
            windowDays = days,
            builds = windowed.map { kpiRowOf(it) },
            flakyRecordCount = flaky(projectId, days, nowMs).size,
            avoidedMsValues = windowed.mapNotNull { it.derived?.avoidedMs },
        )
    }

    override fun classTimings(projectId: String, days: Int, nowMs: Long): Map<String, List<Long>> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        return builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.mode.name == "CI" && it.startedAt >= cutoff }
            .flatMap { classTimingsOf(it) }
            .groupBy({ it.first }, { it.second })
    }

    /** Non-benchmark builds whose startedAt ∈ [fromMs, toMs) — the fleet-view window (plan 032). */
    private fun payloadsBetween(projectId: String, fromMs: Long, toMs: Long): List<BuildPayload> =
        builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.mode.name != "BENCHMARK" }
            .filter { it.startedAt in fromMs until toMs }

    private fun kpiRowOf(payload: BuildPayload): BuildKpiRow =
        BuildKpiRow(payload.outcome.name, payload.finishedAt - payload.startedAt, payload.derived?.cacheableHitRate)

    private data class ArtifactGroupKey(val day: String, val module: String?, val variant: String, val type: String)

    private fun benchmarkSeriesOf(scenario: String, isolationMode: String?, group: List<BuildPayload>): BenchmarkSeries {
        val points = group
            .sortedBy { it.startedAt }
            .map {
                BenchmarkPoint(
                    startedAt = it.startedAt,
                    buildId = it.buildId,
                    iteration = it.benchmark?.iteration,
                    durationMs = it.finishedAt - it.startedAt,
                    hitRate = it.derived?.cacheableHitRate,
                )
            }
        return BenchmarkSeries(scenario, isolationMode, points, summarize(points))
    }

    /** Flatten in-window builds into per-task rows, exactly the shape task_executions holds. */
    private fun taskRowsInWindow(projectId: String, days: Int, nowMs: Long): List<TaskRow> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        return builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.startedAt >= cutoff }
            .flatMap { taskRowsOf(it) }
    }

    /** Flatten one payload into per-task rows — the exact shape task_executions holds (incl. cacheable). */
    private fun taskRowsOf(payload: BuildPayload): List<TaskRow> {
        val wall = payload.finishedAt - payload.startedAt
        return payload.tasks.map { task ->
            TaskRow(
                buildId = payload.buildId,
                userId = payload.environment?.userId,
                module = task.module,
                name = DerivedMetricsCalculator.taskName(task),
                type = task.type,
                outcome = task.outcome.name,
                durationMs = task.durationMs,
                buildWallMs = wall,
                cacheable = task.cacheable,
            )
        }
    }
}

class InMemoryMetricStore : MetricStore {
    // key: (projectId, coalesced build/provider/run, scope, name) → record
    private val metrics = ConcurrentHashMap<String, Pair<String, MetricRecord>>()

    private fun key(projectId: String, m: MetricRecord): String =
        listOf(projectId, m.buildId ?: "", m.provider ?: "", m.runId ?: "", m.scope, m.name).joinToString(" ")

    override fun upsert(projectId: String, metric: MetricRecord) {
        metrics[key(projectId, metric)] = projectId to metric
    }

    override fun forBuild(projectId: String, buildId: String): List<MetricRecord> =
        metrics.values.filter { it.first == projectId && it.second.buildId == buildId }.map { it.second }

    override fun correlate(projectId: String, provider: String?, runId: String?, buildId: String) {
        if (provider == null && runId == null) return
        val pending = metrics.entries.filter { (_, v) ->
            v.first == projectId && v.second.buildId == null && v.second.provider == provider && v.second.runId == runId
        }
        for ((oldKey, v) in pending) {
            val updated = v.second.copy(buildId = buildId)
            metrics.remove(oldKey)
            metrics[key(projectId, updated)] = projectId to updated
        }
    }

    override fun correlationKeys(projectId: String, buildId: String?, provider: String?, runId: String?): Set<String> {
        val byRun = provider != null || runId != null
        return metrics.values
            .filter { it.first == projectId }
            .map { it.second }
            .filter { if (byRun) it.provider == provider && it.runId == runId else it.buildId == buildId }
            .map { "${it.scope} ${it.name}" }
            .toSet()
    }
}

class InMemoryVerdictStore : VerdictStore {
    private data class Stored(val buildId: String, val verdict: Verdict, val seq: Long)

    private val verdicts = ConcurrentHashMap<Pair<String, String>, Stored>()
    private val seq = java.util.concurrent.atomic.AtomicLong()

    override fun save(projectId: String, buildId: String, verdict: Verdict) {
        // Stamp evaluatedAt so in-memory (dev/tests) matches Postgres, which sets it from now().
        val stamped = if (verdict.evaluatedAt != null) verdict else verdict.copy(evaluatedAt = System.currentTimeMillis())
        verdicts[projectId to buildId] = Stored(buildId, stamped, seq.incrementAndGet())
    }

    override fun find(projectId: String, buildId: String): Verdict? =
        verdicts[projectId to buildId]?.verdict

    override fun latestStatusForKey(projectId: String, baselineKey: String, excludingBuildId: String): String? =
        verdicts.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.buildId != excludingBuildId && it.verdict.baselineKey == baselineKey }
            .maxByOrNull { it.seq }
            ?.verdict?.status
}

class InMemorySettingsStore : SettingsStore {
    private val settings = ConcurrentHashMap<String, ProjectSettings>()
    private val retention = ConcurrentHashMap<String, RetentionConfig>()

    override fun get(projectId: String): ProjectSettings? = settings[projectId]

    override fun put(projectId: String, settings: ProjectSettings) {
        this.settings[projectId] = settings
    }

    override fun retention(projectId: String): RetentionConfig = retention[projectId] ?: RetentionConfig.DEFAULT

    override fun setRetention(projectId: String, config: RetentionConfig) {
        retention[projectId] = config
    }
}

class InMemoryAddonStore : AddonStore {
    // (projectId, addonId, key) -> jsonb value; the composite key mirrors the Postgres PK.
    private val data = ConcurrentHashMap<Triple<String, String, String>, JsonElement>()

    override fun get(projectId: String, addonId: String, key: String): JsonElement? =
        data[Triple(projectId, addonId, key)]

    override fun put(projectId: String, addonId: String, key: String, value: JsonElement) {
        data[Triple(projectId, addonId, key)] = value
    }

    override fun all(projectId: String, addonId: String): Map<String, JsonElement> =
        data.entries
            .filter { it.key.first == projectId && it.key.second == addonId }
            .associate { it.key.third to it.value }
}

class InMemoryTokenStore : TokenStore {
    private val projects = ConcurrentHashMap<String, ProjectRef>() // key -> project
    private val tokens = ConcurrentHashMap<String, Pair<String, String>>() // tokenHash -> (projectId, scope)

    override fun resolve(tokenHash: String): TokenPrincipal? {
        val (projectId, scope) = tokens[tokenHash] ?: return null
        val project = projects.values.firstOrNull { it.id == projectId } ?: return null
        return TokenPrincipal(project, scope)
    }

    override fun ensureProjectWithToken(projectKey: String, tokenHash: String, scope: String): ProjectRef {
        val project = projects.computeIfAbsent(projectKey) {
            ProjectRef(id = java.util.UUID.randomUUID().toString(), key = projectKey)
        }
        val existing = tokens.putIfAbsent(tokenHash, project.id to scope)
        check(existing == null || existing.first == project.id) {
            "token is already bound to another project — refusing silent cross-tenant reuse"
        }
        return project
    }
}
