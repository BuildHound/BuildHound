package dev.buildhound.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.DerivedMetricsCalculator
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
            // The build row and its normalized task rows go in as one all-or-nothing unit (plan 026),
            // so a partial failure never leaves task rows without their build (idempotency at the
            // build level, no PK on task rows). autoCommit is restored in finally.
            connection.autoCommit = false
            try {
                val inserted = insertBuild(connection, projectId, payload)
                // Task + artifact rows only when the build was newly inserted — a duplicate adds none.
                if (inserted && payload.tasks.isNotEmpty()) insertTaskRows(connection, projectId, payload)
                if (inserted && payload.artifacts?.android?.isNotEmpty() == true) {
                    insertArtifactRows(connection, projectId, payload)
                }
                connection.commit()
                inserted
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }

    private fun insertBuild(connection: java.sql.Connection, projectId: String, payload: BuildPayload): Boolean =
        connection.prepareStatement(
            """
            INSERT INTO builds (project_id, build_id, started_at, finished_at, outcome,
                                mode, branch, duration_ms, hit_rate, ci_provider, ci_run_id,
                                pipeline_name, requested_tasks_sig, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            // Extracted hot columns for baseline keying + metric correlation (plan 025).
            statement.setString(10, payload.ci?.provider)
            statement.setString(11, payload.ci?.runId)
            statement.setString(12, payload.ci?.pipelineName)
            statement.setString(13, RegressionEngine.requestedTasksSignature(payload.requestedTasks))
            statement.setObject(
                14,
                PGobject().apply {
                    type = "jsonb"
                    value = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
                },
            )
            statement.executeUpdate() == 1
        }

    /** Batch-insert the normalized task rows (plan 026), denormalizing user_id + started_at. */
    private fun insertTaskRows(connection: java.sql.Connection, projectId: String, payload: BuildPayload) {
        connection.prepareStatement(
            """
            INSERT INTO task_executions
                (project_id, build_id, started_at, user_id, path, module, name, type, outcome, cacheable, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            val startedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.startedAt), ZoneOffset.UTC)
            val userId = payload.environment?.userId
            for (task in payload.tasks) {
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, payload.buildId)
                statement.setObject(3, startedAt)
                statement.setString(4, userId)
                statement.setString(5, task.path)
                statement.setString(6, task.module)
                statement.setString(7, DerivedMetricsCalculator.taskName(task))
                statement.setString(8, task.type)
                statement.setString(9, task.outcome.name)
                task.cacheable?.let { statement.setBoolean(10, it) } ?: statement.setNull(10, java.sql.Types.BOOLEAN)
                statement.setLong(11, task.durationMs)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /** Batch-insert the Android artifact-size rows (plan 031), denormalizing started_at. */
    private fun insertArtifactRows(connection: java.sql.Connection, projectId: String, payload: BuildPayload) {
        connection.prepareStatement(
            """
            INSERT INTO apk_sizes (project_id, build_id, started_at, module, variant, type, size_bytes)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            val startedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.startedAt), ZoneOffset.UTC)
            for (artifact in payload.artifacts?.android.orEmpty()) {
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, payload.buildId)
                statement.setObject(3, startedAt)
                statement.setString(4, artifact.module)
                statement.setString(5, artifact.variant)
                statement.setString(6, artifact.type.name)
                statement.setLong(7, artifact.sizeBytes)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    override fun resolveBuildId(projectId: String, provider: String?, runId: String?): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT build_id FROM builds
                WHERE project_id = ? AND ci_provider IS NOT DISTINCT FROM ? AND ci_run_id IS NOT DISTINCT FROM ?
                ORDER BY started_at DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, provider)
                statement.setString(3, runId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    override fun baselineWindow(
        projectId: String,
        defaultBranch: String,
        query: BaselineQuery,
        excludingBuildId: String,
        n: Int,
    ): List<BaselinePoint> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT duration_ms, hit_rate FROM builds
                WHERE project_id = ? AND outcome = 'SUCCESS' AND branch = ?
                  AND mode = ? AND pipeline_name IS NOT DISTINCT FROM ? AND requested_tasks_sig = ?
                  AND build_id <> ?
                ORDER BY started_at DESC LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, defaultBranch)
                statement.setString(3, query.mode)
                statement.setString(4, query.pipelineName)
                statement.setString(5, query.requestedTasksSig)
                statement.setString(6, excludingBuildId)
                statement.setInt(7, n)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                BaselinePoint(
                                    durationMs = rows.getLong("duration_ms"),
                                    hitRate = rows.getDouble("hit_rate").takeUnless { rows.wasNull() },
                                ),
                            )
                        }
                    }
                }
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
        // Fleet-view exclusion (plan 030): NOT IN over bound params, order-matched with the values.
        val excluded = filter.excludeModes.toList()
        if (excluded.isNotEmpty()) {
            clauses.append(" AND mode NOT IN (${excluded.joinToString(",") { "?" }})")
            params.addAll(excluded)
        }
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

    private fun cutoff(days: Int, nowMs: Long): OffsetDateTime =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs - days.toLong() * 86_400_000), ZoneOffset.UTC)

    override fun projectCost(projectId: String, days: Int, nowMs: Long): List<ProjectCostRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                // Integer division (sum/count) matches RollupCalculator's truncating averageOrZero;
                // trunc() matches its .toInt() on the executed percentage (the eBay quirk).
                """
                WITH win AS (
                    SELECT te.build_id, te.module, te.outcome, te.duration_ms, te.user_id, b.duration_ms AS wall
                    FROM task_executions te
                    JOIN builds b ON b.project_id = te.project_id AND b.build_id = te.build_id
                    WHERE te.project_id = ? AND te.started_at >= ?
                ),
                total AS (SELECT count(DISTINCT build_id) AS n FROM win),
                mb AS (
                    SELECT module, build_id, max(wall) AS wall, bool_or(outcome = 'EXECUTED') AS executed
                    FROM win GROUP BY module, build_id
                ),
                tallies AS (
                    SELECT module, count(DISTINCT user_id) AS impacted_users, sum(duration_ms) AS serial_ms
                    FROM win GROUP BY module
                )
                SELECT m.module,
                    count(*) AS builds,
                    count(*) FILTER (WHERE m.executed) AS executed_builds,
                    t.impacted_users, t.serial_ms,
                    (sum(m.wall) / count(*)) AS build_avg,
                    round(count(*)::numeric / tot.n, 6)::float8 AS build_pct,
                    (CASE WHEN count(*) FILTER (WHERE m.executed) = 0 THEN 0
                          ELSE sum(m.wall) FILTER (WHERE m.executed) / count(*) FILTER (WHERE m.executed) END
                    ) * trunc(count(*) FILTER (WHERE m.executed)::float8 / tot.n * 100)::int AS cost_scalar
                FROM mb m
                JOIN tallies t ON t.module IS NOT DISTINCT FROM m.module
                CROSS JOIN total tot
                GROUP BY m.module, t.impacted_users, t.serial_ms, tot.n
                ORDER BY cost_scalar DESC, coalesce(m.module, '') ASC
                LIMIT ${RollupCalculator.TOP_N}
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                ProjectCostRow(
                                    module = rows.getString("module"),
                                    builds = rows.getInt("builds"),
                                    executedBuilds = rows.getInt("executed_builds"),
                                    buildImpactedUsers = rows.getInt("impacted_users"),
                                    serialTaskMs = rows.getLong("serial_ms"),
                                    buildAvgDurationMs = rows.getLong("build_avg"),
                                    buildPercentage = rows.getDouble("build_pct"),
                                    buildCostScalar = rows.getLong("cost_scalar"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun taskDuration(projectId: String, days: Int, nowMs: Long): TaskDurationRollup =
        dataSource.connection.use { connection ->
            fun rank(byType: Boolean): List<TaskDurationRow> {
                // SQL-injection safety: `column`/`typeFilter` are chosen ONLY by this Boolean and are
                // fixed literals — never request/payload input. Keep it that way (no String column arg).
                val column = if (byType) "type" else "name"
                val typeFilter = if (byType) "AND type IS NOT NULL " else ""
                return connection.prepareStatement(
                    """
                    SELECT $column AS k, count(*) AS cnt, sum(duration_ms) AS total,
                           sum(duration_ms) / count(*) AS avg, min(duration_ms) AS mn, max(duration_ms) AS mx
                    FROM task_executions WHERE project_id = ? AND started_at >= ? $typeFilter
                    GROUP BY $column ORDER BY total DESC, k ASC LIMIT ${RollupCalculator.TOP_N}
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(projectId))
                    statement.setObject(2, cutoff(days, nowMs))
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    TaskDurationRow(
                                        key = rows.getString("k"), count = rows.getInt("cnt"),
                                        totalMs = rows.getLong("total"), avgMs = rows.getLong("avg"),
                                        minMs = rows.getLong("mn"), maxMs = rows.getLong("mx"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            val available = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM task_executions WHERE project_id = ? AND started_at >= ? AND type IS NOT NULL)",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                statement.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
            }
            TaskDurationRollup(byName = rank(byType = false), byType = rank(byType = true), byTypeAvailable = available)
        }

    override fun negativeAvoidance(projectId: String, days: Int, nowMs: Long): List<NegativeAvoidanceRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                // percentile_cont(0.5) == RollupCalculator.medianDouble; trunc() matches its .toLong().
                """
                WITH win AS (
                    SELECT coalesce(type, name) AS grp, outcome, duration_ms
                    FROM task_executions WHERE project_id = ? AND started_at >= ?
                ),
                med AS (
                    SELECT grp, percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS median
                    FROM win WHERE outcome = 'EXECUTED' GROUP BY grp
                ),
                excess AS (
                    SELECT w.grp, (w.duration_ms - m.median) AS ex
                    FROM win w JOIN med m ON m.grp = w.grp
                    WHERE w.outcome IN ('UP_TO_DATE', 'FROM_CACHE') AND w.duration_ms > m.median
                )
                SELECT grp AS k, count(*) AS cnt, trunc(sum(ex))::bigint AS total_excess, trunc(max(ex))::bigint AS worst
                FROM excess GROUP BY grp
                ORDER BY total_excess DESC, k ASC LIMIT ${RollupCalculator.TOP_N}
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                NegativeAvoidanceRow(
                                    key = rows.getString("k"), count = rows.getInt("cnt"),
                                    totalExcessMs = rows.getLong("total_excess"), worstExcessMs = rows.getLong("worst"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun benchmarkSeries(
        projectId: String,
        scenario: String?,
        isolationMode: String?,
        branch: String?,
        days: Int,
        nowMs: Long,
    ): List<BenchmarkSeries> =
        dataSource.connection.use { connection ->
            // Benchmark keys live in the jsonb payload (no hot columns, no migration). Optional
            // narrowing filters are bound params; grouping + percentiles happen in Kotlin over the
            // shared calculator so in-memory and Postgres agree byte-for-byte.
            val clauses = StringBuilder()
            val strParams = mutableListOf<String>()
            scenario?.let { clauses.append(" AND payload->'benchmark'->>'scenario' = ?"); strParams.add(it) }
            isolationMode?.let { clauses.append(" AND payload->'benchmark'->>'isolationMode' = ?"); strParams.add(it) }
            branch?.let { clauses.append(" AND branch = ?"); strParams.add(it) }
            connection.prepareStatement(
                """
                SELECT build_id, started_at, duration_ms, hit_rate,
                       payload->'benchmark'->>'scenario' AS scenario,
                       payload->'benchmark'->>'isolationMode' AS isolation,
                       payload->'benchmark'->>'iteration' AS iteration
                FROM builds
                WHERE project_id = ? AND mode = 'BENCHMARK' AND started_at >= ?
                  AND payload->'benchmark'->>'scenario' IS NOT NULL$clauses
                ORDER BY scenario, isolation NULLS FIRST, started_at
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                strParams.forEachIndexed { index, value -> statement.setString(index + 3, value) }
                statement.executeQuery().use { rows ->
                    val grouped = LinkedHashMap<Pair<String, String?>, MutableList<BenchmarkPoint>>()
                    while (rows.next()) {
                        val point = BenchmarkPoint(
                            startedAt = rows.getObject("started_at", OffsetDateTime::class.java).toInstant().toEpochMilli(),
                            buildId = rows.getString("build_id"),
                            iteration = rows.getString("iteration")?.toIntOrNull(),
                            durationMs = rows.getLong("duration_ms"),
                            hitRate = rows.getDouble("hit_rate").takeUnless { rows.wasNull() },
                        )
                        grouped.getOrPut(rows.getString("scenario") to rows.getString("isolation")) { mutableListOf() }.add(point)
                    }
                    grouped.map { (key, points) -> BenchmarkSeries(key.first, key.second, points, summarize(points)) }
                }
            }
        }

    override fun artifactTrends(projectId: String, filter: BuildFilter, days: Int, nowMs: Long): List<ArtifactTrendPoint> =
        dataSource.connection.use { connection ->
            // Join to builds so the same branch/mode/exclusion filter as /trends applies; every value
            // is a bound param. `branch`/`mode`/`outcome` are unambiguous (only `builds` has them).
            val (clauses, params) = filterSql(filter)
            connection.prepareStatement(
                """
                SELECT (a.started_at AT TIME ZONE 'UTC')::date AS day, a.module, a.variant, a.type,
                       avg(a.size_bytes)::bigint AS avg_size, max(a.size_bytes) AS max_size,
                       count(DISTINCT a.build_id) AS builds
                FROM apk_sizes a
                JOIN builds b ON b.project_id = a.project_id AND b.build_id = a.build_id
                WHERE a.project_id = ? AND a.started_at >= ?$clauses
                GROUP BY day, a.module, a.variant, a.type
                ORDER BY day, a.variant, a.type, a.module NULLS FIRST
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                params.forEachIndexed { index, value -> statement.setString(index + 3, value) }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                ArtifactTrendPoint(
                                    day = rows.getDate("day").toLocalDate().toString(),
                                    module = rows.getString("module"),
                                    variant = rows.getString("variant"),
                                    type = rows.getString("type"),
                                    avgSizeBytes = rows.getLong("avg_size"),
                                    maxSizeBytes = rows.getLong("max_size"),
                                    builds = rows.getInt("builds"),
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

private fun jsonb(value: String): PGobject = PGobject().apply { type = "jsonb"; this.value = value }

class PostgresMetricStore(private val dataSource: DataSource) : MetricStore {

    override fun upsert(projectId: String, metric: MetricRecord) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO custom_metrics (project_id, build_id, provider, run_id, scope, name, value, text_value, unit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, coalesce(build_id, ''), coalesce(provider, ''), coalesce(run_id, ''), scope, name)
                DO UPDATE SET value = EXCLUDED.value, text_value = EXCLUDED.text_value, unit = EXCLUDED.unit, created_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, metric.buildId)
                statement.setString(3, metric.provider)
                statement.setString(4, metric.runId)
                statement.setString(5, metric.scope)
                statement.setString(6, metric.name)
                metric.value?.let { statement.setDouble(7, it) } ?: statement.setNull(7, java.sql.Types.DOUBLE)
                statement.setString(8, metric.text)
                statement.setString(9, metric.unit)
                statement.executeUpdate()
            }
        }
    }

    override fun forBuild(projectId: String, buildId: String): List<MetricRecord> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT scope, name, value, text_value, unit, build_id, provider, run_id FROM custom_metrics WHERE project_id = ? AND build_id = ?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, buildId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                MetricRecord(
                                    scope = rows.getString("scope"),
                                    name = rows.getString("name"),
                                    value = rows.getDouble("value").takeUnless { rows.wasNull() },
                                    text = rows.getString("text_value"),
                                    unit = rows.getString("unit"),
                                    buildId = rows.getString("build_id"),
                                    provider = rows.getString("provider"),
                                    runId = rows.getString("run_id"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun correlate(projectId: String, provider: String?, runId: String?, buildId: String) {
        if (provider == null && runId == null) return
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE custom_metrics SET build_id = ?
                WHERE project_id = ? AND build_id IS NULL
                  AND provider IS NOT DISTINCT FROM ? AND run_id IS NOT DISTINCT FROM ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, buildId)
                statement.setObject(2, UUID.fromString(projectId))
                statement.setString(3, provider)
                statement.setString(4, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun correlationKeys(projectId: String, buildId: String?, provider: String?, runId: String?): Set<String> {
        val byRun = provider != null || runId != null
        val where = if (byRun) "provider IS NOT DISTINCT FROM ? AND run_id IS NOT DISTINCT FROM ?" else "build_id IS NOT DISTINCT FROM ?"
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT scope, name FROM custom_metrics WHERE project_id = ? AND $where").use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                if (byRun) {
                    statement.setString(2, provider)
                    statement.setString(3, runId)
                } else {
                    statement.setString(2, buildId)
                }
                statement.executeQuery().use { rows ->
                    buildSet { while (rows.next()) add("${rows.getString("scope")} ${rows.getString("name")}") }
                }
            }
        }
    }
}

class PostgresVerdictStore(private val dataSource: DataSource) : VerdictStore {

    private val metricsSerializer = ListSerializer(MetricVerdict.serializer())

    override fun save(projectId: String, buildId: String, verdict: Verdict) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO build_verdicts (project_id, build_id, status, baseline_key, detail, evaluated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (project_id, build_id)
                DO UPDATE SET status = EXCLUDED.status, baseline_key = EXCLUDED.baseline_key,
                              detail = EXCLUDED.detail, evaluated_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, buildId)
                statement.setString(3, verdict.status)
                statement.setString(4, verdict.baselineKey)
                statement.setObject(5, jsonb(BuildHoundJson.payload.encodeToString(metricsSerializer, verdict.metrics)))
                statement.executeUpdate()
            }
        }
    }

    override fun find(projectId: String, buildId: String): Verdict? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT status, baseline_key, detail, (extract(epoch from evaluated_at) * 1000)::bigint AS ms
                FROM build_verdicts WHERE project_id = ? AND build_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, buildId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else Verdict(
                        status = rows.getString("status"),
                        metrics = BuildHoundJson.payload.decodeFromString(metricsSerializer, rows.getString("detail")),
                        baselineKey = rows.getString("baseline_key"),
                        evaluatedAt = rows.getLong("ms"),
                    )
                }
            }
        }

    override fun latestStatusForKey(projectId: String, baselineKey: String, excludingBuildId: String): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT status FROM build_verdicts
                WHERE project_id = ? AND baseline_key = ? AND build_id <> ?
                ORDER BY evaluated_at DESC, build_id DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, baselineKey)
                statement.setString(3, excludingBuildId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
}

class PostgresSettingsStore(private val dataSource: DataSource) : SettingsStore {

    private val budgetsSerializer = MapSerializer(String.serializer(), Double.serializer())
    private val channelsSerializer = ListSerializer(AlertChannel.serializer())

    override fun get(projectId: String): ProjectSettings? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT baseline_n, default_branch, warn_z, fail_z, budgets, alert_channels FROM project_settings WHERE project_id = ?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else ProjectSettings(
                        baselineN = rows.getInt("baseline_n"),
                        defaultBranch = rows.getString("default_branch"),
                        warnZ = rows.getDouble("warn_z"),
                        failZ = rows.getDouble("fail_z"),
                        budgets = BuildHoundJson.payload.decodeFromString(budgetsSerializer, rows.getString("budgets")),
                        alertChannels = BuildHoundJson.payload.decodeFromString(channelsSerializer, rows.getString("alert_channels")),
                    )
                }
            }
        }

    override fun put(projectId: String, settings: ProjectSettings) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO project_settings (project_id, baseline_n, default_branch, warn_z, fail_z, budgets, alert_channels, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (project_id)
                DO UPDATE SET baseline_n = EXCLUDED.baseline_n, default_branch = EXCLUDED.default_branch,
                              warn_z = EXCLUDED.warn_z, fail_z = EXCLUDED.fail_z, budgets = EXCLUDED.budgets,
                              alert_channels = EXCLUDED.alert_channels, updated_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setInt(2, settings.baselineN)
                statement.setString(3, settings.defaultBranch)
                statement.setDouble(4, settings.warnZ)
                statement.setDouble(5, settings.failZ)
                statement.setObject(6, jsonb(BuildHoundJson.payload.encodeToString(budgetsSerializer, settings.budgets)))
                statement.setObject(7, jsonb(BuildHoundJson.payload.encodeToString(channelsSerializer, settings.alertChannels)))
                statement.executeUpdate()
            }
        }
    }
}
