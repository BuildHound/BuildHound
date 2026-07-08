package dev.buildhound.server

import dev.buildhound.commons.payload.BenchmarkSeriesCalculator
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.DerivedMetricsCalculator
import dev.buildhound.commons.payload.TestUnitKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

/** The addon id under which plan 038 contributes its opaque `extensions` section (plan 039). */
private const val INTERNAL_ADAPTERS_EXTENSION_KEY = "internalAdapters"

/**
 * Flatten one payload's `extensions["internalAdapters"].tasks[]` (plan 038) — origin only — joined by
 * path to the core `tasks[]` (module/cacheable/durationMs) for [RelocatabilityDetector] (plan 068). The
 * whole navigation is `runCatching`-guarded: server keeps **no** dependency on `buildhound-internal-
 * adapters` (plan 039 decoupling invariant — it treats `extensions` as opaque `JsonElement`), so a
 * shape the server doesn't expect (a future schema bump, or outright malformed JSON) degrades to an
 * empty list for this build rather than a crash — never fatal to the whole rollup. The wire `origin`
 * string is parsed to [RelocatabilityOrigin] here — the one boundary — so every downstream consumer
 * compares an enum, never a raw string literal; an unrecognized wire value degrades to [OTHER].
 */
internal fun relocatabilityRowsOf(payload: BuildPayload): List<RelocatabilityRow> {
    val hostnameHash = payload.environment?.hostnameHash
    val tasksByPath = payload.tasks.associateBy { it.path }
    val originsByPath: List<Pair<String, RelocatabilityOrigin>> = runCatching {
        val element = payload.extensions[INTERNAL_ADAPTERS_EXTENSION_KEY]
            ?: return@runCatching emptyList<Pair<String, RelocatabilityOrigin>>()
        element.jsonObject["tasks"]?.jsonArray.orEmpty().mapNotNull { taskElement ->
            val obj = taskElement.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val origin = obj["origin"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val parsedOrigin = RelocatabilityOrigin.entries.firstOrNull { it.name == origin } ?: RelocatabilityOrigin.OTHER
            path to parsedOrigin
        }
    }.getOrElse { emptyList() }

    return originsByPath.mapNotNull { (path, origin) ->
        val task = tasksByPath[path] ?: return@mapNotNull null
        RelocatabilityRow(
            taskPath = path,
            module = task.module,
            hostnameHash = hostnameHash,
            origin = origin,
            cacheable = task.cacheable,
            durationMs = task.durationMs,
        )
    }
}

/**
 * One payload's fingerprint-stream row for [FingerprintVolatilityDetector] (plan 068, plan 022); null
 * when either half of the join is absent — no `hostnameHash` (no salt-stream key to group by, `strict`
 * mode or a legacy payload) or no `fingerprints` block at all (uncaptured — distinct from "captured but
 * empty," which still participates so a key that vanished mid-stream reads as a real transition).
 */
internal fun fingerprintStreamRowOf(payload: BuildPayload): FingerprintStreamRow? {
    val hostnameHash = payload.environment?.hostnameHash ?: return null
    val fingerprints = payload.fingerprints ?: return null
    return FingerprintStreamRow(hostnameHash = hostnameHash, startedAt = payload.startedAt, buildId = payload.buildId, fingerprints = fingerprints.build)
}

/** True when a payload could feed either cache-miss-diagnostics detector (plan 068's "gated to builds carrying the block"). */
internal fun carriesCacheMissDiagnosticsBlock(payload: BuildPayload): Boolean =
    payload.extensions.containsKey(INTERNAL_ADAPTERS_EXTENSION_KEY) || payload.fingerprints != null

/**
 * Flatten one payload's `extensions["internalAdapters"].tasks[]` (plan 038) — origin only — into
 * [CacheRoiRow]s tagged with the build mode, for [CacheRoiCalculator] (plan 067). Same opaque-`extensions`
 * decoupling + `runCatching` guard as [relocatabilityRowsOf]: the server keeps **no** dependency on
 * `buildhound-internal-adapters`, so an unexpected shape (a future schema bump, malformed JSON) degrades
 * to an empty list for this build rather than crashing the whole rollup. The wire `origin` string is
 * parsed to [CacheRoiOrigin] here — the one boundary — so every downstream comparison is an enum, never a
 * raw literal; an unrecognized value degrades to [CacheRoiOrigin.OTHER].
 */
internal fun cacheRoiRowsOf(payload: BuildPayload): List<CacheRoiRow> {
    val mode = payload.mode.name
    return runCatching {
        val element = payload.extensions[INTERNAL_ADAPTERS_EXTENSION_KEY]
            ?: return@runCatching emptyList<CacheRoiRow>()
        element.jsonObject["tasks"]?.jsonArray.orEmpty().mapNotNull { taskElement ->
            val origin = taskElement.jsonObject["origin"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            CacheRoiRow(mode, CacheRoiOrigin.entries.firstOrNull { it.name == origin } ?: CacheRoiOrigin.OTHER)
        }
    }.getOrElse { emptyList() }
}

/**
 * One build's committed build-cache config fact (plan 067) for the config-snapshot summary: null when the
 * payload carries no `environment.buildCache` block (a pre-067 plugin), so it never counts toward
 * [CacheRoiRollup.buildsWithConfig]. `remoteEnabled` is `true` only when a remote backend was configured
 * **and** enabled — the plan's `remoteEnabled` share.
 */
internal fun cacheConfigRowOf(payload: BuildPayload): CacheConfigRow? {
    val buildCache = payload.environment?.buildCache ?: return null
    return CacheConfigRow(mode = payload.mode.name, remoteEnabled = buildCache.remoteEnabled == true)
}

/** True when a payload feeds the cache-ROI rollup (plan 067): it carries origin data OR the config snapshot. */
internal fun carriesCacheRoiBlock(payload: BuildPayload): Boolean =
    payload.extensions.containsKey(INTERNAL_ADAPTERS_EXTENSION_KEY) || payload.environment?.buildCache != null

/** Defensive ceiling on builds scanned for one `/rollups/cache-roi` query (plan 067), mirroring [RelocatabilityDetector.MAX_DIAGNOSTIC_ROWS]. */
internal const val MAX_CACHE_ROI_ROWS: Int = 20_000

/**
 * Server-local minimal view of `extensions["internalAdapters"]` (plan 062) carrying only
 * [dependencyEdges] — the plan-039 decoupling principle applied to a second reader: the server decodes
 * this narrow shape through the commons [BuildHoundJson] (`ignoreUnknownKeys`) rather than depending on
 * the real `InternalAdaptersPayload`, which lives in the Gradle-API `buildhound-internal-adapters`
 * module and would drag Gradle onto the server classpath (architecture §5 break).
 */
@Serializable
private data class InternalAdaptersView(val dependencyEdges: Map<String, List<String>> = emptyMap())

/**
 * A build's `extensions["internalAdapters"].dependencyEdges` (plan 038), for [GraphCentrality]/
 * [GraphExporter] (plan 062). Null for every absent-case the plan names as one honest "no graph"
 * signal, never a false empty-but-complete graph: the extension key itself is missing (module never
 * applied), decoding it throws (a shape the server doesn't expect — never fatal, degrade), or its
 * `dependencyEdges` map is empty (isolated-projects' empty `whenReady` walk, or a `PayloadCapper`
 * byte-drop of the whole `internalAdapters` blob).
 */
internal fun dependencyEdgesOf(payload: BuildPayload): Map<String, List<String>>? {
    val element = payload.extensions[INTERNAL_ADAPTERS_EXTENSION_KEY] ?: return null
    return runCatching { BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersView.serializer(), element) }
        .getOrNull()
        ?.dependencyEdges
        ?.takeIf { it.isNotEmpty() }
}

/** Query-API filters (plan 010); values are validated at the route, bound in SQL. */
data class BuildFilter(
    val branch: String? = null,
    val mode: String? = null,
    val outcome: String? = null,
    /** Modes excluded from fleet views (plan 030): benchmark builds are excluded by default. */
    val excludeModes: Set<String> = emptySet(),
    /**
     * Tag equality filter (plan 057): a build matches only when every entry here equals the
     * build's own `tags[key]`. Additive, default empty (no narrowing). Postgres binds this as a
     * `payload -> 'tags' @> ?::jsonb` containment param per entry (GIN-indexed, V11) — key and
     * value are always bound, never interpolated, since tags are user-controlled strings.
     */
    val tags: Map<String, String> = emptyMap(),
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
     * Owning-plugin cost rollup over the last [days] (plan 058, research F8 Layer 1): the same
     * days-window, **benchmark-included** convention as [taskDuration]/[projectCost]/[negativeAvoidance]
     * (its sibling) — not [bottlenecks]' period-window, benchmark-excluded convention. Both stores
     * fetch the identical windowed [TaskRow]s and defer to [RollupCalculator.pluginCost], so in-memory
     * and Postgres agree byte-for-byte (the plan-026 parity discipline).
     */
    fun pluginCost(projectId: String, days: Int, nowMs: Long): PluginCostRollup

    /**
     * Benchmark series over the last [days] (plan 030): `mode=BENCHMARK` builds grouped by
     * (scenario, isolationMode), optionally narrowed by [scenario]/[isolationMode]/[branch]/
     * [workersMax] (plan 065 — an exact match on the plaintext `environment.workersMax`). Each
     * group carries oldest-first points + a percentile summary. Empty when no benchmark builds match.
     */
    fun benchmarkSeries(
        projectId: String,
        scenario: String?,
        isolationMode: String?,
        branch: String?,
        days: Int,
        nowMs: Long,
        workersMax: Int? = null,
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

    /**
     * Raw per-cohort material for [tagKey]'s distinct values over [filter]+[days] (plan 057): the
     * pure [CohortComparator] (called by the route) folds this into per-cohort trend series and a
     * median/MAD delta. A build missing [tagKey] entirely contributes to no cohort — never a
     * synthetic "null" bucket. Both stores fetch raw per-build rows and defer to
     * [TagCohortCalculator.groupByCohort], so in-memory and Postgres agree byte-for-byte (the
     * plan-026 parity discipline).
     */
    fun tagCohortTrends(projectId: String, tagKey: String, filter: BuildFilter, days: Int, nowMs: Long): List<TagCohortRaw>

    /**
     * Distinct tag keys observed over [days], each with its top-N most-frequent values (plan 057) —
     * populates the dashboard's tag-key split picker. Benchmark builds are excluded (the same
     * fleet-view convention `bottlenecks`/`toolchainAdoption` use; benchmark has its own dedicated
     * view, plan 030). Capped ([TagCohortCalculator.MAX_KEYS] keys, [TagCohortCalculator.MAX_VALUES_PER_KEY]
     * values each) so a misused high-cardinality tag can't blow up the response.
     */
    fun tagKeys(projectId: String, days: Int, nowMs: Long): List<TagKeySummary>

    /**
     * Rerun-cause taxonomy over `executionReasons` (plan 061, research F11): per-bucket coverage of
     * executed task-hours + a build-level cascade rate + an optional build-logic-storm candidate over
     * the last [days]. Benchmark builds excluded (the `bottlenecks`/`toolchainAdoption` fleet-view
     * convention) — both stores fetch the same windowed [TaskRow]s and defer to
     * [RerunCauseRollupCalculator], so in-memory and Postgres agree byte-for-byte.
     */
    fun rerunCauses(projectId: String, days: Int, nowMs: Long): RerunCauseRollup

    /**
     * Build-Analyzer-style warning taxonomy (plan 060, research F10): three rule-based candidate
     * families (ALWAYS_RUN / NON_INCREMENTAL_AP / DYNAMIC_DEBUG_VALUES) over the last [period] days.
     * Read straight from the payload jsonb (`builds.payload->'tasks'`), never `task_executions` — that
     * table has no `incremental` column, and even for `executionReasons` (which it does carry, as of
     * V12) a fresh column is NULL for every build ingested before the column existed, while the jsonb
     * payload has carried both fields since schema v1. No migration (deliberately dodges the
     * plan-032-documented unpinned-`V{n}` Flyway race). Benchmark builds excluded (the
     * `bottlenecks`/`toolchainAdoption`/`rerunCauses` fleet-view convention) — both stores fetch the
     * same windowed [TaskRow]s (Postgres via its own `jsonb_array_elements` scan, kept separate from
     * [rerunCauses]'s indexed-table scan so `/rollups/bottlenecks` stays fast) and defer to
     * [WarningCalculator], so in-memory and Postgres agree byte-for-byte.
     */
    fun warnings(projectId: String, period: Int, nowMs: Long): WarningsRollup

    /**
     * Cache-miss diagnostics over the last [days] (plan 068, research F18): non-relocatable-task
     * candidates (self-gated on the plan-038 origin enum — silent when the window never observed a
     * `REMOTE_HIT`) plus per-salt-stream fingerprint volatility scoring (plan 022). Both stores read the
     * same windowed builds — gated to those carrying either the opaque `internalAdapters` extension or a
     * `fingerprints` block, most-recent-first, capped at [RelocatabilityDetector.MAX_DIAGNOSTIC_ROWS] —
     * flatten to [RelocatabilityRow]/[FingerprintStreamRow] via guarded JsonElement navigation, and defer
     * to the two pure detectors, so in-memory and Postgres agree byte-for-byte (the plan-026/032/036
     * parity discipline). Benchmark builds are excluded (the bottlenecks/toolchain/rerun-causes/warnings
     * fleet-view convention — a repeated same-scenario benchmark build on a fixed runner would otherwise
     * inflate both the cross-host count and the fingerprint-volatility signal).
     */
    fun cacheMissDiagnostics(projectId: String, days: Int, nowMs: Long): CacheMissDiagnostics

    /**
     * Fleet remote-cache ROI over the last [days] (plan 067, research F17): per-build-mode remote/local
     * cache-hit rate (from the opt-in plan-038 `origin`, denominator `LOCAL_HIT+REMOTE_HIT+MISS`) plus a
     * config-snapshot summary (share of window builds with a configured remote, from the always-on plan-067
     * `environment.buildCache`) and a ranked near-zero-CI-reuse candidate. Two-tier: with the opt-in module
     * off there is no remote-hit rate at all — [CacheRoiRollup.remoteHitRateAvailable] is false and the rollup
     * degrades to the config-snapshot summary (never synthesizing a rate from `cacheableHitRate`). Both stores
     * read the same windowed builds — gated to those carrying either the opaque `internalAdapters` extension
     * or the `buildCache` snapshot, most-recent-first, capped at [MAX_CACHE_ROI_ROWS] — flatten via guarded
     * JsonElement navigation, and defer to the pure [CacheRoiCalculator], so in-memory and Postgres agree
     * byte-for-byte (the plan-026/032/068 parity discipline). Benchmark builds excluded (the fleet-view
     * convention — a repeated same-scenario benchmark build on a fixed runner would skew the fleet rate).
     */
    fun cacheRoi(projectId: String, days: Int, nowMs: Long): CacheRoiRollup

    /**
     * Delivery-health **proxies** over the last [days] (plan 059, research F9): change-failure rate
     * per (branch, pipeline), time-to-green (a CI-recovery proxy, not production MTTR), build-only
     * lead time, and the retry tax — all from build rows already ingested, zero new collection (the
     * spec-§1 Git/DORA non-goal stays intact). Benchmark builds excluded (the bottlenecks/toolchain/
     * rerun-causes fleet-view convention — CFR is the per-cohort refinement of plan-032's fleet
     * `successRate`, so it inherits that KPI's population). Both stores fetch the same windowed
     * [DeliveryBuildRow]s and defer to [DeliveryHealthCalculator], so in-memory and Postgres agree
     * byte-for-byte (the plan-026/032 parity discipline). Connector/flaky enrichment happens at the
     * route, never here — the parity core stays build-only (no [dev.buildhound.server.connector.CiSpanStore]
     * in any store constructor).
     */
    fun deliveryHealth(projectId: String, days: Int, nowMs: Long): DeliveryHealthRollup

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
     * The `"scope name"` keys already stored for one logical run (matched by {provider,runId}
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
            .filter { payload -> filter.tags.all { (key, value) -> payload.tags[key] == value } }

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

    override fun tagCohortTrends(projectId: String, tagKey: String, filter: BuildFilter, days: Int, nowMs: Long): List<TagCohortRaw> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        val rows = matching(projectId, filter)
            .filter { it.startedAt >= cutoff }
            .mapNotNull { payload ->
                val value = payload.tags[tagKey] ?: return@mapNotNull null
                TagCohortBuildRow(
                    value = value,
                    day = LocalDate.ofInstant(Instant.ofEpochMilli(payload.startedAt), ZoneOffset.UTC).toString(),
                    outcome = payload.outcome.name,
                    durationMs = payload.finishedAt - payload.startedAt,
                    hitRate = payload.derived?.cacheableHitRate,
                )
            }
        return TagCohortCalculator.groupByCohort(rows)
    }

    override fun tagKeys(projectId: String, days: Int, nowMs: Long): List<TagKeySummary> {
        val payloads = payloadsBetween(projectId, nowMs - days.toLong() * 86_400_000, nowMs)
        return TagCohortCalculator.tagKeySummaries(payloads.map { it.tags })
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

    override fun pluginCost(projectId: String, days: Int, nowMs: Long): PluginCostRollup =
        RollupCalculator.pluginCost(taskRowsInWindow(projectId, days, nowMs))

    override fun benchmarkSeries(
        projectId: String,
        scenario: String?,
        isolationMode: String?,
        branch: String?,
        days: Int,
        nowMs: Long,
        workersMax: Int?,
    ): List<BenchmarkSeries> {
        val cutoff = nowMs - days.toLong() * 86_400_000
        return builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { it.mode.name == "BENCHMARK" && it.benchmark != null && it.startedAt >= cutoff }
            .filter { branch == null || it.vcs?.branch == branch }
            .filter { scenario == null || it.benchmark?.scenario == scenario }
            .filter { isolationMode == null || it.benchmark?.isolationMode == isolationMode }
            // workersMax slicing (plan 065): exact match on the plaintext environment scalar; a
            // build that never carried one matches no workersMax filter (honest, never a guess).
            .filter { workersMax == null || it.environment?.workersMax == workersMax }
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
        // The jdk dimension (plan 065): duration-carrying samples, grouped by JDK major — the
        // daemon-JDK fleet comparison; the other four dimensions stay per full version, no duration.
        val jdkSamples = payloads.map {
            ToolchainSample(it.toolchain?.jdk, it.environment?.userId, it.startedAt, durationMs = it.finishedAt - it.startedAt)
        }
        return ToolchainRollup(
            gradle = ToolchainCalculator.dimension(samples { it.toolchain?.gradle }),
            jdk = ToolchainCalculator.dimension(jdkSamples, ToolchainCalculator::jdkMajor),
            agp = ToolchainCalculator.dimension(samples { it.toolchain?.agp }),
            kgp = ToolchainCalculator.dimension(samples { it.toolchain?.kgp }),
            ksp = ToolchainCalculator.dimension(samples { it.toolchain?.ksp }),
            // Spring Boot adoption (plan 072, research F22): no duration, per full version — like
            // agp/kgp/ksp; only the jdk dimension carries a p50 duration.
            springBoot = ToolchainCalculator.dimension(samples { it.toolchain?.springBoot }),
        )
    }

    override fun rerunCauses(projectId: String, days: Int, nowMs: Long): RerunCauseRollup {
        // Same half-open [cutoff, now) window + benchmark exclusion as bottlenecks/toolchainAdoption
        // (payloadsBetween), NOT the projectCost/taskDuration/negativeAvoidance convention
        // (taskRowsInWindow, which does not exclude benchmark) — a rerun-cause signal is about
        // real-build rework, so repeated same-scenario benchmark reruns must not skew the fleet share.
        val windowed = payloadsBetween(projectId, nowMs - days.toLong() * 86_400_000, nowMs)
        return RerunCauseRollupCalculator.compute(windowed.flatMap { taskRowsOf(it) })
    }

    override fun warnings(projectId: String, period: Int, nowMs: Long): WarningsRollup {
        // Same half-open [cutoff, now) fleet-view window + benchmark exclusion as bottlenecks/
        // toolchainAdoption/rerunCauses (payloadsBetween) — a warning candidate is about real-build
        // behavior, so repeated same-scenario benchmark reruns must not skew the fleet share.
        val windowed = payloadsBetween(projectId, nowMs - period.toLong() * 86_400_000, nowMs)
        return WarningCalculator.compute(windowed.flatMap { taskRowsOf(it) }, period)
    }

    override fun cacheMissDiagnostics(projectId: String, days: Int, nowMs: Long): CacheMissDiagnostics {
        // Same fleet-view window as bottlenecks/toolchainAdoption/rerunCauses/warnings (benchmark
        // excluded), further gated to builds carrying either block and capped/tie-broken identically to
        // the Postgres store's `ORDER BY started_at DESC, build_id DESC LIMIT` so the two agree
        // byte-for-byte even above the cap (plan 068).
        val windowed = payloadsBetween(projectId, nowMs - days.toLong() * 86_400_000, nowMs)
            .filter { carriesCacheMissDiagnosticsBlock(it) }
            .sortedWith(compareByDescending<BuildPayload> { it.startedAt }.thenByDescending { it.buildId })
            .take(RelocatabilityDetector.MAX_DIAGNOSTIC_ROWS)
        val relocatabilityRows = windowed.flatMap { relocatabilityRowsOf(it) }
        val streamRows = windowed.mapNotNull { fingerprintStreamRowOf(it) }
        return CacheMissDiagnostics(
            remoteCacheObserved = RelocatabilityDetector.remoteCacheObserved(relocatabilityRows),
            nonRelocatable = RelocatabilityDetector.detect(relocatabilityRows),
            volatileInputs = FingerprintVolatilityDetector.detect(streamRows),
        )
    }

    override fun cacheRoi(projectId: String, days: Int, nowMs: Long): CacheRoiRollup {
        // Same fleet-view window as bottlenecks/toolchainAdoption/cacheMissDiagnostics (benchmark
        // excluded), further gated to builds carrying either block and capped/tie-broken identically to
        // the Postgres store's `ORDER BY started_at DESC, build_id DESC LIMIT` so the two agree
        // byte-for-byte even above the cap (plan 067).
        val windowed = payloadsBetween(projectId, nowMs - days.toLong() * 86_400_000, nowMs)
            .filter { carriesCacheRoiBlock(it) }
            .sortedWith(compareByDescending<BuildPayload> { it.startedAt }.thenByDescending { it.buildId })
            .take(MAX_CACHE_ROI_ROWS)
        return CacheRoiCalculator.compute(
            originRows = windowed.flatMap { cacheRoiRowsOf(it) },
            configRows = windowed.mapNotNull { cacheConfigRowOf(it) },
        )
    }

    override fun deliveryHealth(projectId: String, days: Int, nowMs: Long): DeliveryHealthRollup {
        // Same fleet-view window + benchmark exclusion as bottlenecks/toolchainAdoption/rerunCauses
        // (payloadsBetween) — CFR refines plan-032's fleet successRate, so it inherits its population.
        val windowed = payloadsBetween(projectId, nowMs - days.toLong() * 86_400_000, nowMs)
        return DeliveryHealthCalculator.compute(windowed.map { deliveryRowOf(it) }, days)
    }

    /** Flatten one payload into the plan-059 delivery row — the exact shape the Postgres SQL mirrors. */
    private fun deliveryRowOf(payload: BuildPayload): DeliveryBuildRow =
        DeliveryBuildRow(
            buildId = payload.buildId,
            branch = payload.vcs?.branch,
            pipelineName = payload.ci?.pipelineName,
            provider = payload.ci?.provider,
            outcome = payload.outcome.name,
            startedAtMs = payload.startedAt,
            finishedAtMs = payload.finishedAt,
            sha = payload.vcs?.sha,
            projectKey = payload.projectKey,
            requestedTasksSig = RegressionEngine.requestedTasksSignature(payload.requestedTasks),
            runAttempt = DeliveryHealthCalculator.parseRunAttempt(payload.ci?.attributes?.get("runAttempt")),
        )

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
                executionReasons = task.executionReasons,
                incremental = task.incremental,
            )
        }
    }
}

class InMemoryMetricStore : MetricStore {
    // key: (projectId, coalesced build/provider/run, scope, name) → record
    private val metrics = ConcurrentHashMap<String, Pair<String, MetricRecord>>()

    private fun key(projectId: String, m: MetricRecord): String =
        listOf(projectId, m.buildId ?: "", m.provider ?: "", m.runId ?: "", m.scope, m.name).joinToString("\u0000")

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
