package dev.buildhound.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject

/** Hikari pool + Flyway migration on boot (architecture §5). */
fun createDataSource(jdbcUrl: String, user: String, password: String): DataSource {
    val config = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        username = user
        this.password = password
        maximumPoolSize = 10
        poolName = "buildhound"
    }
    return HikariDataSource(config)
}

fun migrate(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}

class PostgresBuildStore(private val dataSource: DataSource) : BuildStore {

    override fun save(projectId: String, payload: BuildPayload): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO builds (project_id, build_id, started_at, finished_at, outcome,
                                    mode, branch, duration_ms, hit_rate, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, build_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, payload.buildId)
                statement.setObject(3, OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.startedAt), ZoneOffset.UTC))
                statement.setObject(4, OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.finishedAt), ZoneOffset.UTC))
                statement.setString(5, payload.outcome.name)
                statement.setString(6, payload.mode.name)
                statement.setString(7, payload.vcs?.branch)
                statement.setLong(8, payload.finishedAt - payload.startedAt)
                payload.derived?.cacheableHitRate
                    ?.let { statement.setDouble(9, it) }
                    ?: statement.setNull(9, java.sql.Types.DOUBLE)
                statement.setObject(
                    10,
                    PGobject().apply {
                        type = "jsonb"
                        value = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
                    },
                )
                statement.executeUpdate() == 1
            }
        }

    override fun findById(projectId: String, buildId: String): BuildPayload? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT payload FROM builds WHERE project_id = ? AND build_id = ?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, buildId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), rows.getString(1))
                }
            }
        }

    override fun count(projectId: String, filter: BuildFilter): Long =
        dataSource.connection.use { connection ->
            val (clauses, params) = filterSql(filter)
            connection.prepareStatement("SELECT count(*) FROM builds WHERE project_id = ?$clauses").use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                params.forEachIndexed { index, value -> statement.setString(index + 2, value) }
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
        }

    /** Fixed column comparisons only; every value is a bound parameter. */
    private fun filterSql(filter: BuildFilter): Pair<String, List<String>> {
        val clauses = StringBuilder()
        val params = mutableListOf<String>()
        filter.branch?.let { clauses.append(" AND branch = ?"); params.add(it) }
        filter.mode?.let { clauses.append(" AND mode = ?"); params.add(it) }
        filter.outcome?.let { clauses.append(" AND outcome = ?"); params.add(it) }
        return clauses.toString() to params
    }

    override fun list(projectId: String, filter: BuildFilter, limit: Int, offset: Int): List<BuildSummary> =
        dataSource.connection.use { connection ->
            val (clauses, params) = filterSql(filter)
            connection.prepareStatement(
                """
                SELECT build_id, started_at, duration_ms, outcome, mode, branch, hit_rate
                FROM builds WHERE project_id = ?$clauses
                ORDER BY started_at DESC, build_id DESC LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                params.forEachIndexed { index, value -> statement.setString(index + 2, value) }
                statement.setInt(params.size + 2, limit)
                statement.setInt(params.size + 3, offset)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                BuildSummary(
                                    buildId = rows.getString("build_id"),
                                    startedAt = rows.getObject("started_at", OffsetDateTime::class.java)
                                        .toInstant().toEpochMilli(),
                                    durationMs = rows.getLong("duration_ms"),
                                    outcome = rows.getString("outcome"),
                                    mode = rows.getString("mode"),
                                    branch = rows.getString("branch"),
                                    hitRate = rows.getDouble("hit_rate").takeUnless { rows.wasNull() },
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun trends(projectId: String, filter: BuildFilter, days: Int, nowMs: Long): List<TrendPoint> =
        dataSource.connection.use { connection ->
            val (clauses, params) = filterSql(filter)
            connection.prepareStatement(
                """
                SELECT (started_at AT TIME ZONE 'UTC')::date AS day,
                       count(*) AS builds,
                       count(*) FILTER (WHERE outcome = 'FAILED') AS failures,
                       avg(duration_ms)::bigint AS avg_duration,
                       max(duration_ms) AS max_duration,
                       avg(hit_rate) AS avg_hit_rate
                FROM builds
                WHERE project_id = ? AND started_at >= ?$clauses
                GROUP BY day ORDER BY day
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(
                    2,
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs - days.toLong() * 86_400_000), ZoneOffset.UTC),
                )
                params.forEachIndexed { index, value -> statement.setString(index + 3, value) }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                TrendPoint(
                                    day = rows.getDate("day").toLocalDate().toString(),
                                    builds = rows.getInt("builds"),
                                    failures = rows.getInt("failures"),
                                    avgDurationMs = rows.getLong("avg_duration"),
                                    maxDurationMs = rows.getLong("max_duration"),
                                    avgHitRate = rows.getDouble("avg_hit_rate").takeUnless { rows.wasNull() },
                                ),
                            )
                        }
                    }
                }
            }
        }
}

class PostgresTokenStore(private val dataSource: DataSource) : TokenStore {

    override fun resolve(tokenHash: String): TokenPrincipal? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT p.id, p.project_key, t.scope FROM api_tokens t
                JOIN projects p ON p.id = t.project_id
                WHERE t.token_hash = ? AND t.revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else TokenPrincipal(
                        project = ProjectRef(id = rows.getString(1), key = rows.getString(2)),
                        scope = rows.getString(3),
                    )
                }
            }
        }

    override fun ensureProjectWithToken(projectKey: String, tokenHash: String, scope: String): ProjectRef =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO projects (project_key) VALUES (?) ON CONFLICT (project_key) DO NOTHING",
            ).use { statement ->
                statement.setString(1, projectKey)
                statement.executeUpdate()
            }
            val project = connection.prepareStatement(
                "SELECT id, project_key FROM projects WHERE project_key = ?",
            ).use { statement ->
                statement.setString(1, projectKey)
                statement.executeQuery().use { rows ->
                    rows.next()
                    ProjectRef(id = rows.getString(1), key = rows.getString(2))
                }
            }
            val inserted = connection.prepareStatement(
                "INSERT INTO api_tokens (project_id, token_hash, scope) VALUES (?, ?, ?) ON CONFLICT (token_hash) DO NOTHING",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(project.id))
                statement.setString(2, tokenHash)
                statement.setString(3, scope)
                statement.executeUpdate() == 1
            }
            if (!inserted) {
                // The hash exists — it must belong to THIS project, or boot must fail:
                // silently resolving another tenant would be cross-tenant misdirection.
                val owner = connection.prepareStatement(
                    "SELECT project_id FROM api_tokens WHERE token_hash = ?",
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
                check(owner == project.id) {
                    "token is already bound to another project — refusing silent cross-tenant reuse"
                }
            }
            project
        }
}
