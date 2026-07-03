package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/** The tenant a request acts as — resolved from its token, never from the payload. */
data class ProjectRef(val id: String, val key: String)

/** Query-API filters (plan 010); values are validated at the route, bound in SQL. */
data class BuildFilter(
    val branch: String? = null,
    val mode: String? = null,
    val outcome: String? = null,
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
}

/** Token scopes (spec §5): a leaked CI ingest token must not read history. */
object TokenScope {
    const val INGEST = "ingest"
    const val READ = "read"
    const val ALL = "all"

    fun allowsIngest(scope: String): Boolean = scope == INGEST || scope == ALL
    fun allowsRead(scope: String): Boolean = scope == READ || scope == ALL
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

    override fun count(projectId: String, filter: BuildFilter): Long =
        matching(projectId, filter).size.toLong()

    private fun matching(projectId: String, filter: BuildFilter): List<BuildPayload> =
        builds.entries
            .filter { it.key.first == projectId }
            .map { it.value }
            .filter { filter.branch == null || it.vcs?.branch == filter.branch }
            .filter { filter.mode == null || it.mode.name == filter.mode }
            .filter { filter.outcome == null || it.outcome.name == filter.outcome }

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
                val durations = dayBuilds.map { it.finishedAt - it.startedAt }
                val hitRates = dayBuilds.mapNotNull { it.derived?.cacheableHitRate }
                TrendPoint(
                    day = day.toString(),
                    builds = dayBuilds.size,
                    failures = dayBuilds.count { it.outcome.name == "FAILED" },
                    avgDurationMs = Math.round(durations.average()),
                    maxDurationMs = durations.max(),
                    avgHitRate = hitRates.takeIf { it.isNotEmpty() }?.average(),
                )
            }
    }
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
