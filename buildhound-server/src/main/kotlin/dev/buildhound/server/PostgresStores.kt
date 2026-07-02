package dev.buildhound.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.sql.Timestamp
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
                statement.setTimestamp(3, Timestamp(payload.startedAt))
                statement.setTimestamp(4, Timestamp(payload.finishedAt))
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

    override fun count(projectId: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM builds WHERE project_id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
        }
}

class PostgresTokenStore(private val dataSource: DataSource) : TokenStore {

    override fun resolveProject(tokenHash: String): ProjectRef? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT p.id, p.project_key FROM api_tokens t
                JOIN projects p ON p.id = t.project_id
                WHERE t.token_hash = ? AND t.revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else ProjectRef(id = rows.getString(1), key = rows.getString(2))
                }
            }
        }

    override fun ensureProjectWithToken(projectKey: String, tokenHash: String): ProjectRef =
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
            connection.prepareStatement(
                "INSERT INTO api_tokens (project_id, token_hash) VALUES (?, ?) ON CONFLICT (token_hash) DO NOTHING",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(project.id))
                statement.setString(2, tokenHash)
                statement.executeUpdate()
            }
            project
        }
}
