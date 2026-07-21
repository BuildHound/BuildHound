@file:Suppress("MagicNumber") // JDBC parameter positions mirror the adjacent SQL column order.

package dev.buildhound.server.connector

import dev.buildhound.commons.payload.BuildHoundJson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import org.postgresql.util.PGobject

/** The lifecycle of a CI run's enrichment (plan 028). */
@Serializable
enum class CiRunStatus {
    /** The normalized tree was fetched and stored. */
    OK,

    /** No connector credential is configured for this project — nothing was fetched. */
    UNCONFIGURED,

    /** The build had not finished within the poll budget; any partial tree is stored. */
    PENDING,

    /** The fetch or parse failed; degraded, never fatal to ingest. */
    FAILED,
}

/** A stored CI run: its [status] plus the normalized [run] tree (null for UNCONFIGURED/FAILED). */
data class StoredCiRun(val status: CiRunStatus, val run: CiRun? = null)

/**
 * Persistence boundary for enriched CI runs (plan 028), tenant-scoped like [BuildStore][dev.buildhound.server.BuildStore].
 * `saveRun` upserts idempotently on `(project, build)` so a re-fetch (poll retry, replica, or hook)
 * overwrites rather than duplicates.
 */
interface CiSpanStore {
    fun saveRun(
        projectId: String,
        buildId: String,
        provider: String?,
        runId: String?,
        run: CiRun?,
        status: CiRunStatus,
    )

    fun findRun(projectId: String, buildId: String): StoredCiRun?
}

class InMemoryCiSpanStore : CiSpanStore {
    private val runs = ConcurrentHashMap<Pair<String, String>, StoredCiRun>()

    override fun saveRun(
        projectId: String,
        buildId: String,
        provider: String?,
        runId: String?,
        run: CiRun?,
        status: CiRunStatus,
    ) {
        runs[projectId to buildId] = StoredCiRun(status, run)
    }

    override fun findRun(projectId: String, buildId: String): StoredCiRun? = runs[projectId to buildId]
}

class PostgresCiSpanStore(private val dataSource: DataSource) : CiSpanStore {

    private val runSerializer = CiRun.serializer()

    override fun saveRun(
        projectId: String,
        buildId: String,
        provider: String?,
        runId: String?,
        run: CiRun?,
        status: CiRunStatus,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO ci_runs (project_id, build_id, provider, run_id, queued_ms, started_ms,
                                     finished_ms, status, run, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (project_id, build_id)
                DO UPDATE SET provider = EXCLUDED.provider, run_id = EXCLUDED.run_id,
                              queued_ms = EXCLUDED.queued_ms, started_ms = EXCLUDED.started_ms,
                              finished_ms = EXCLUDED.finished_ms, status = EXCLUDED.status,
                              run = EXCLUDED.run, fetched_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, buildId)
                statement.setString(3, provider)
                statement.setString(4, runId)
                setNullableLong(statement, 5, run?.queuedMs)
                setNullableLong(statement, 6, run?.startedAt)
                setNullableLong(statement, 7, run?.finishedAt)
                statement.setString(8, status.name)
                run?.let {
                    statement.setObject(
                        9,
                        PGobject().apply {
                            type = "jsonb"
                            value = BuildHoundJson.payload.encodeToString(runSerializer, it)
                        },
                    )
                } ?: statement.setNull(9, java.sql.Types.OTHER)
                statement.executeUpdate()
            }
        }
    }

    override fun findRun(projectId: String, buildId: String): StoredCiRun? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT status, run FROM ci_runs WHERE project_id = ? AND build_id = ?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, buildId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    val status = CiRunStatus.valueOf(rows.getString("status"))
                    val runJson = rows.getString("run")
                    StoredCiRun(
                        status = status,
                        run = runJson?.let { BuildHoundJson.payload.decodeFromString(runSerializer, it) },
                    )
                }
            }
        }

    private fun setNullableLong(statement: java.sql.PreparedStatement, index: Int, value: Long?) {
        value?.let { statement.setLong(index, it) } ?: statement.setNull(index, java.sql.Types.BIGINT)
    }
}
