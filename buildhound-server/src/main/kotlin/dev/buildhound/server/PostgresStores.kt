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
import kotlinx.serialization.json.JsonElement
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

/** Retention purge batch size (plan 042): bounds per-statement lock time on a large delete. */
private const val PURGE_BATCH: Int = 5000

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
                // Per-class test outcomes for flaky detection (plan 036); projected in the same txn.
                if (inserted && payload.tests.isNotEmpty()) insertTestClassOutcomes(connection, projectId, payload)
                connection.commit()
                inserted
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }

    override fun allProjectIds(): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id FROM projects").use { statement ->
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getObject("id").toString()) }
                }
            }
        }

    override fun purgeOlderThan(projectId: String, buildCutoffMs: Long, rawCutoffMs: Long): RetentionPurge {
        val id = UUID.fromString(projectId)
        // Raw per-task rows first (they reference builds by id, not FK, but purging them first keeps a
        // crash from ever leaving a build with orphaned raw rows). Each batch commits on its own so a
        // large purge never holds a long lock (autoCommit is on by default).
        dataSource.connection.use { connection ->
            val rawRows = batchedDeleteByStartedAt(connection, "task_executions", id, rawCutoffMs)
            val builds = batchedDeleteByStartedAt(connection, "builds", id, buildCutoffMs)
            return RetentionPurge(builds = builds, rawRows = rawRows)
        }
    }

    /** Deletes `table` rows for [id] with `started_at` before the cutoff, [PURGE_BATCH] at a time. */
    private fun batchedDeleteByStartedAt(connection: java.sql.Connection, table: String, id: UUID, cutoffMs: Long): Long {
        val cutoff = OffsetDateTime.ofInstant(Instant.ofEpochMilli(cutoffMs), ZoneOffset.UTC)
        // `table` is a compile-time literal ("builds"/"task_executions"), never user input — no injection.
        val sql = "DELETE FROM $table WHERE ctid IN " +
            "(SELECT ctid FROM $table WHERE project_id = ? AND started_at < ? LIMIT $PURGE_BATCH)"
        var total = 0L
        while (true) {
            val deleted = connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, cutoff)
                statement.executeUpdate()
            }
            total += deleted
            if (deleted < PURGE_BATCH) break
        }
        return total
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
                (project_id, build_id, started_at, user_id, path, module, name, type, outcome, cacheable,
                 duration_ms, execution_reasons)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                // Rerun-cause taxonomy source (plan 061): a plain text[], written on ingest. A fresh
                // insert always writes an array (possibly empty) — never NULL; NULL only occurs on rows
                // from before this migration, which taskRowsBetween degrades to UNCLASSIFIED at read time.
                statement.setArray(12, connection.createArrayOf("text", task.executionReasons.toTypedArray()))
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

    /** Batch-insert per-class test outcomes (plan 036); module stored NOT NULL '' for null (PK safety). */
    private fun insertTestClassOutcomes(connection: java.sql.Connection, projectId: String, payload: BuildPayload) {
        connection.prepareStatement(
            """
            INSERT INTO test_class_outcomes
                (project_id, build_id, started_at, sha, module, class_fqcn, passed, failed, retry_flaky_cases)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (project_id, build_id, module, class_fqcn) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            val startedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.startedAt), ZoneOffset.UTC)
            for (row in classOutcomesOf(payload)) {
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, row.buildId)
                statement.setObject(3, startedAt)
                statement.setString(4, row.sha)
                statement.setString(5, row.module ?: "")
                statement.setString(6, row.classFqcn)
                statement.setInt(7, row.passed)
                statement.setInt(8, row.failed)
                statement.setInt(9, row.retryFlakyCases)
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
                  AND (payload->'environment'->'invocation'->>'rerunTasks') IS DISTINCT FROM 'true'
                  AND (payload->'environment'->'invocation'->>'refreshDependencies') IS DISTINCT FROM 'true'
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
        // Tag equality filter (plan 057): one bound {"key":"value"} jsonb containment (`@>`) param
        // per entry — key AND value are always bound (encoded through the same BuildHoundJson used
        // for every other jsonb write), never interpolated, since tags are user-controlled strings.
        // GIN-indexed via V11 (`payload -> 'tags'`).
        filter.tags.forEach { (key, value) ->
            clauses.append(" AND payload -> 'tags' @> ?::jsonb")
            params.add(BuildHoundJson.payload.encodeToString(MapSerializer(String.serializer(), String.serializer()), mapOf(key to value)))
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
                // Interrupted builds (plan 033) are counted separately and excluded from the
                // duration/hit-rate aggregates (their duration is synthetic) — mirrors the in-memory
                // store's filter so the two agree. coalesce keeps an interrupted-only day at 0, not null.
                """
                SELECT (started_at AT TIME ZONE 'UTC')::date AS day,
                       count(*) AS builds,
                       count(*) FILTER (WHERE outcome = 'FAILED') AS failures,
                       count(*) FILTER (WHERE outcome = 'INTERRUPTED') AS interrupted,
                       coalesce(avg(duration_ms) FILTER (WHERE outcome IN ('SUCCESS','FAILED')), 0)::bigint AS avg_duration,
                       coalesce(max(duration_ms) FILTER (WHERE outcome IN ('SUCCESS','FAILED')), 0) AS max_duration,
                       avg(hit_rate) FILTER (WHERE outcome IN ('SUCCESS','FAILED')) AS avg_hit_rate
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
                                    interrupted = rows.getInt("interrupted"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun cutoff(days: Int, nowMs: Long): OffsetDateTime =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs - days.toLong() * 86_400_000), ZoneOffset.UTC)

    private fun atMs(ms: Long): OffsetDateTime =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)

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

    /**
     * Owning-plugin cost rollup (plan 058, research F8 Layer 1): reuses [taskRowsInDaysWindow] — the
     * same days-window, benchmark-included population [taskDuration]/[projectCost]/[negativeAvoidance]
     * read directly above (their SQL just aggregates it differently) — and defers to
     * [RollupCalculator.pluginCost] the way [rerunCauses] defers to its own calculator, since the
     * FQCN-prefix → plugin mapping has no SQL equivalent.
     */
    override fun pluginCost(projectId: String, days: Int, nowMs: Long): PluginCostRollup =
        dataSource.connection.use { connection ->
            RollupCalculator.pluginCost(taskRowsInDaysWindow(connection, projectId, days, nowMs))
        }

    /**
     * Flat task rows for the **days-window, benchmark-included** convention [taskDuration]/
     * [projectCost]/[negativeAvoidance] use (`started_at >= cutoff`, no upper bound, no `builds` join
     * or mode exclusion) — [pluginCost]'s shape (plan 058), as opposed to [taskRowsBetween]'s
     * period-window, benchmark-**excluded** convention ([bottlenecks]/[rerunCauses]). `buildWallMs`
     * isn't fetched (no `builds` join here, and [RollupCalculator.pluginCost] never reads it) — set to
     * 0, a harmless placeholder no consumer reads.
     */
    private fun taskRowsInDaysWindow(connection: java.sql.Connection, projectId: String, days: Int, nowMs: Long): List<TaskRow> =
        connection.prepareStatement(
            """
            SELECT build_id, user_id, module, name, type, outcome, duration_ms, cacheable, execution_reasons
            FROM task_executions WHERE project_id = ? AND started_at >= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(projectId))
            statement.setObject(2, cutoff(days, nowMs))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TaskRow(
                                buildId = rows.getString("build_id"),
                                userId = rows.getString("user_id"),
                                module = rows.getString("module"),
                                name = rows.getString("name"),
                                type = rows.getString("type"),
                                outcome = rows.getString("outcome"),
                                durationMs = rows.getLong("duration_ms"),
                                buildWallMs = 0L,
                                cacheable = rows.getBoolean("cacheable").takeUnless { rows.wasNull() },
                                executionReasons = executionReasonsOf(rows),
                            ),
                        )
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

    override fun bottlenecks(projectId: String, period: Int, nowMs: Long): BottlenecksRollup =
        dataSource.connection.use { connection ->
            // Both windows are fetched as raw rows and handed to the shared BottleneckCalculator, so
            // Postgres and in-memory agree byte-for-byte (the plan-026 parity discipline). Half-open
            // [from, to) windows match the in-memory `startedAt in from until to` filter exactly.
            val windowMs = period.toLong() * 86_400_000
            val now = atMs(nowMs)
            val currentFrom = atMs(nowMs - windowMs)
            val priorFrom = atMs(nowMs - 2 * windowMs)
            BottleneckCalculator.compute(
                currentTasks = taskRowsBetween(connection, projectId, currentFrom, now),
                priorTasks = taskRowsBetween(connection, projectId, priorFrom, currentFrom),
                currentBuilds = kpiRowsBetween(connection, projectId, currentFrom, now),
                priorBuilds = kpiRowsBetween(connection, projectId, priorFrom, currentFrom),
                period = period,
            )
        }

    /** Flat task rows for a half-open window (plan 032); benchmark builds excluded (fleet view). */
    private fun taskRowsBetween(
        connection: java.sql.Connection,
        projectId: String,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ): List<TaskRow> =
        connection.prepareStatement(
            """
            SELECT te.build_id, te.user_id, te.module, te.name, te.type, te.outcome,
                   te.duration_ms, te.cacheable, te.execution_reasons, b.duration_ms AS wall
            FROM task_executions te
            JOIN builds b ON b.project_id = te.project_id AND b.build_id = te.build_id
            WHERE te.project_id = ? AND te.started_at >= ? AND te.started_at < ? AND b.mode <> 'BENCHMARK'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(projectId))
            statement.setObject(2, from)
            statement.setObject(3, to)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TaskRow(
                                buildId = rows.getString("build_id"),
                                userId = rows.getString("user_id"),
                                module = rows.getString("module"),
                                name = rows.getString("name"),
                                type = rows.getString("type"),
                                outcome = rows.getString("outcome"),
                                durationMs = rows.getLong("duration_ms"),
                                buildWallMs = rows.getLong("wall"),
                                cacheable = rows.getBoolean("cacheable").takeUnless { rows.wasNull() },
                                // A pre-V12 row reads NULL here (never written) → empty list → the
                                // classifier's UNCLASSIFIED degradation, never a null-pointer crash.
                                executionReasons = executionReasonsOf(rows),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * `execution_reasons` text[] read back as a `List<String>`; NULL (pre-V12 row) → empty (plan 061).
     * `filterIsInstance` (not a direct `Array<String>` cast) because the JDBC driver's `Array.getArray()`
     * is only guaranteed to return `Array<*>` at the JVM level — casting straight to `Array<String?>`
     * risks a `ClassCastException` if the driver hands back a plain `Object[]`.
     */
    private fun executionReasonsOf(rows: java.sql.ResultSet): List<String> {
        val sqlArray = rows.getArray("execution_reasons") ?: return emptyList()
        return (sqlArray.array as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
    }

    /** Build-level KPI rows for a half-open window (plan 032); benchmark builds excluded. */
    private fun kpiRowsBetween(
        connection: java.sql.Connection,
        projectId: String,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ): List<BuildKpiRow> =
        connection.prepareStatement(
            """
            SELECT outcome, duration_ms, hit_rate FROM builds
            WHERE project_id = ? AND mode <> 'BENCHMARK' AND started_at >= ? AND started_at < ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(projectId))
            statement.setObject(2, from)
            statement.setObject(3, to)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            BuildKpiRow(
                                outcome = rows.getString("outcome"),
                                durationMs = rows.getLong("duration_ms"),
                                hitRate = rows.getDouble("hit_rate").takeUnless { rows.wasNull() },
                            ),
                        )
                    }
                }
            }
        }

    override fun toolchainAdoption(projectId: String, days: Int, nowMs: Long): ToolchainRollup =
        dataSource.connection.use { connection ->
            // Every dimension read from the jsonb payload in one pass; distribution/behind math is the
            // shared ToolchainCalculator so both stores agree. userId is already the pseudonymized u_…
            // HMAC (spec §3.7) — distinctUsers is count(distinct) over hashes, never de-pseudonymized.
            connection.prepareStatement(
                """
                SELECT payload->'environment'->>'userId' AS user_id,
                       (extract(epoch from started_at) * 1000)::bigint AS started_ms,
                       payload->'toolchain'->>'gradle' AS gradle,
                       payload->'toolchain'->>'jdk' AS jdk,
                       payload->'toolchain'->>'agp' AS agp,
                       payload->'toolchain'->>'kgp' AS kgp,
                       payload->'toolchain'->>'ksp' AS ksp
                FROM builds
                WHERE project_id = ? AND mode <> 'BENCHMARK' AND started_at >= ? AND started_at < ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                statement.setObject(3, atMs(nowMs))
                statement.executeQuery().use { rows ->
                    val gradle = mutableListOf<ToolchainSample>()
                    val jdk = mutableListOf<ToolchainSample>()
                    val agp = mutableListOf<ToolchainSample>()
                    val kgp = mutableListOf<ToolchainSample>()
                    val ksp = mutableListOf<ToolchainSample>()
                    while (rows.next()) {
                        val userId = rows.getString("user_id")
                        val startedMs = rows.getLong("started_ms")
                        gradle.add(ToolchainSample(rows.getString("gradle"), userId, startedMs))
                        jdk.add(ToolchainSample(rows.getString("jdk"), userId, startedMs))
                        agp.add(ToolchainSample(rows.getString("agp"), userId, startedMs))
                        kgp.add(ToolchainSample(rows.getString("kgp"), userId, startedMs))
                        ksp.add(ToolchainSample(rows.getString("ksp"), userId, startedMs))
                    }
                    ToolchainRollup(
                        gradle = ToolchainCalculator.dimension(gradle),
                        jdk = ToolchainCalculator.dimension(jdk),
                        agp = ToolchainCalculator.dimension(agp),
                        kgp = ToolchainCalculator.dimension(kgp),
                        ksp = ToolchainCalculator.dimension(ksp),
                    )
                }
            }
        }

    /**
     * Rerun-cause taxonomy (plan 061, research F11): reuses [taskRowsBetween] — the same half-open
     * [cutoff, now) window + `mode <> 'BENCHMARK'` exclusion `bottlenecks` already applies — so both
     * stores fold an identical row population through [RerunCauseRollupCalculator] (parity discipline).
     */
    override fun rerunCauses(projectId: String, days: Int, nowMs: Long): RerunCauseRollup =
        dataSource.connection.use { connection ->
            RerunCauseRollupCalculator.compute(taskRowsBetween(connection, projectId, cutoff(days, nowMs), atMs(nowMs)))
        }

    override fun flaky(projectId: String, days: Int, nowMs: Long): List<FlakyRecord> =
        dataSource.connection.use { connection ->
            // Read the narrow per-class outcome table (indexed on (project, sha, module, class)) and
            // hand it to the shared FlakyDetector — same rows the in-memory store flattens, so parity.
            connection.prepareStatement(
                // ORDER BY … LIMIT bounds the rows read into the JVM (FlakyDetector.MAX_OUTCOME_ROWS,
                // plan §6 "cap rows"): most-recent first, so truncation only ever drops the oldest of a
                // pathological history. Detection is order-invariant, so below the cap the in-memory
                // store (identical sort + take) sees the same set — parity holds.
                """
                SELECT build_id, (extract(epoch from started_at) * 1000)::bigint AS started_ms,
                       sha, module, class_fqcn, passed, failed, retry_flaky_cases
                FROM test_class_outcomes
                WHERE project_id = ? AND started_at >= ?
                ORDER BY started_at DESC, build_id, module, class_fqcn
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                statement.setInt(3, FlakyDetector.MAX_OUTCOME_ROWS)
                statement.executeQuery().use { rows ->
                    val outcomes = buildList {
                        while (rows.next()) {
                            add(
                                ClassOutcome(
                                    buildId = rows.getString("build_id"),
                                    startedAtMs = rows.getLong("started_ms"),
                                    sha = rows.getString("sha"),
                                    module = rows.getString("module").ifEmpty { null }, // '' → null (PK convention)
                                    classFqcn = rows.getString("class_fqcn"),
                                    passed = rows.getInt("passed"),
                                    failed = rows.getInt("failed"),
                                    retryFlakyCases = rows.getInt("retry_flaky_cases"),
                                ),
                            )
                        }
                    }
                    FlakyDetector.detect(outcomes)
                }
            }
        }

    override fun metricsSnapshot(projectId: String, days: Int, nowMs: Long): MetricsSnapshot {
        // Same windowed BuildKpiRows bottlenecks already fetches (benchmark excluded, half-open
        // [from, now) — the plan-026/032 parity discipline), plus the windowed derived.avoidedMs values
        // read from the jsonb payload (no hot column for it, same jsonb-field pattern toolchainAdoption
        // uses). The flaky count reuses the flaky() detector's own output rather than re-deriving it.
        val from = cutoff(days, nowMs)
        val to = atMs(nowMs)
        val (kpiRows, avoidedValues) = dataSource.connection.use { connection ->
            kpiRowsBetween(connection, projectId, from, to) to avoidedMsBetween(connection, projectId, from, to)
        }
        return MetricsSnapshotCalculator.compute(
            windowDays = days,
            builds = kpiRows,
            flakyRecordCount = flaky(projectId, days, nowMs).size,
            avoidedMsValues = avoidedValues,
        )
    }

    /** Windowed, non-null `derived.avoidedMs` values (plan 070); absent unless plan-038 origin timings ran. */
    private fun avoidedMsBetween(connection: java.sql.Connection, projectId: String, from: OffsetDateTime, to: OffsetDateTime): List<Long> =
        connection.prepareStatement(
            """
            SELECT (payload->'derived'->>'avoidedMs')::bigint AS avoided_ms
            FROM builds
            WHERE project_id = ? AND mode <> 'BENCHMARK' AND started_at >= ? AND started_at < ?
              AND payload->'derived'->>'avoidedMs' IS NOT NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(projectId))
            statement.setObject(2, from)
            statement.setObject(3, to)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getLong("avoided_ms")) }
            }
        }

    override fun classTimings(projectId: String, days: Int, nowMs: Long): Map<String, List<Long>> =
        dataSource.connection.use { connection ->
            // Per-class CI durations from the payload jsonb over the window (plan 040); on-demand only
            // (the /plan endpoint isn't hot), so a windowed scan is fine — same shape the in-memory
            // store flattens, both grouped by TestUnitKey and handed to LptBalancer.
            connection.prepareStatement(
                "SELECT payload FROM builds WHERE project_id = ? AND started_at >= ? AND mode = 'CI'",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), rows.getString("payload")))
                        }
                    }
                }
            }.flatMap { classTimingsOf(it) }.groupBy({ it.first }, { it.second })
        }

    override fun tagCohortTrends(projectId: String, tagKey: String, filter: BuildFilter, days: Int, nowMs: Long): List<TagCohortRaw> =
        dataSource.connection.use { connection ->
            // Raw per-build rows (cohort value, day, outcome, duration, hit rate) — no aggregation in
            // SQL. Both stores hand these to the same TagCohortCalculator.groupByCohort, so the daily
            // bucketing + median/MAD math run identically over the same rows either store produces
            // (the plan-026/032 "raw rows -> one pure calculator" discipline). Builds missing the tag
            // key entirely are excluded via the GIN-indexable key-existence operator (`payload -> 'tags'
            // ? tagKey`, escaped as `??` for the JDBC driver, which otherwise parses a bare `?` as a bind
            // placeholder) — no synthetic "null" cohort. The earlier `payload->'tags'->>? IS NOT NULL`
            // form used the `->>` value-extraction operator in the predicate, which the V11 GIN index
            // (jsonb_ops) cannot accelerate; only `@>`/`?`/`?&`/`?|` are indexable, so that form forced a
            // full scan on every default (untagged-filter) `/v1/trends/cohorts` call. The `->>?` in the
            // SELECT list below is projection only, not a filter, so it stays as-is.
            val (clauses, params) = filterSql(filter)
            connection.prepareStatement(
                """
                SELECT payload->'tags'->>? AS cohort_value,
                       (started_at AT TIME ZONE 'UTC')::date AS day,
                       outcome, duration_ms, hit_rate
                FROM builds
                WHERE project_id = ? AND started_at >= ? AND payload -> 'tags' ?? ?$clauses
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tagKey)
                statement.setObject(2, UUID.fromString(projectId))
                statement.setObject(3, cutoff(days, nowMs))
                statement.setString(4, tagKey)
                params.forEachIndexed { index, value -> statement.setString(index + 5, value) }
                statement.executeQuery().use { rows ->
                    val out = buildList {
                        while (rows.next()) {
                            add(
                                TagCohortBuildRow(
                                    value = rows.getString("cohort_value"),
                                    day = rows.getDate("day").toLocalDate().toString(),
                                    outcome = rows.getString("outcome"),
                                    durationMs = rows.getLong("duration_ms"),
                                    hitRate = rows.getDouble("hit_rate").takeUnless { rows.wasNull() },
                                ),
                            )
                        }
                    }
                    TagCohortCalculator.groupByCohort(out)
                }
            }
        }

    override fun tagKeys(projectId: String, days: Int, nowMs: Long): List<TagKeySummary> =
        dataSource.connection.use { connection ->
            // Only the `tags` sub-object (not the whole jsonb payload) over the fleet-view window
            // (benchmark excluded, same convention as toolchainAdoption/bottlenecks); decoded and
            // ranked by the shared TagCohortCalculator so both stores agree.
            connection.prepareStatement(
                """
                SELECT payload->'tags' AS tags FROM builds
                WHERE project_id = ? AND mode <> 'BENCHMARK' AND started_at >= ? AND started_at < ?
                  AND payload->'tags' IS NOT NULL AND payload->'tags' <> '{}'::jsonb
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setObject(2, cutoff(days, nowMs))
                statement.setObject(3, atMs(nowMs))
                statement.executeQuery().use { rows ->
                    val tagMaps = buildList {
                        while (rows.next()) {
                            val json = rows.getString("tags") ?: continue
                            add(BuildHoundJson.payload.decodeFromString(MapSerializer(String.serializer(), String.serializer()), json))
                        }
                    }
                    TagCohortCalculator.tagKeySummaries(tagMaps)
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

    override fun retention(projectId: String): RetentionConfig =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT retention_raw_days, retention_build_days FROM project_settings WHERE project_id = ?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.executeQuery().use { rows ->
                    if (!rows.next()) RetentionConfig.DEFAULT
                    else RetentionConfig(
                        rawDays = rows.getInt("retention_raw_days"),
                        buildDays = rows.getInt("retention_build_days"),
                    )
                }
            }
        }

    override fun setRetention(projectId: String, config: RetentionConfig) {
        // Insert-or-update only the retention columns; the regression columns take their table
        // defaults on a fresh insert and are left untouched on conflict (setRetention must never
        // clobber a project's regression config, and put() must never clobber retention).
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO project_settings (project_id, retention_raw_days, retention_build_days, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (project_id)
                DO UPDATE SET retention_raw_days = EXCLUDED.retention_raw_days,
                              retention_build_days = EXCLUDED.retention_build_days, updated_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setInt(2, config.rawDays)
                statement.setInt(3, config.buildDays)
                statement.executeUpdate()
            }
        }
    }
}

/** Tenant-scoped jsonb addon storage (plan 039); every query carries project_id + addon_id. */
class PostgresAddonStore(private val dataSource: DataSource) : AddonStore {

    private val elementSerializer = JsonElement.serializer()

    override fun get(projectId: String, addonId: String, key: String): JsonElement? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT value FROM addon_data WHERE project_id = ? AND addon_id = ? AND key = ?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, addonId)
                statement.setString(3, key)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else BuildHoundJson.payload.decodeFromString(elementSerializer, rows.getString("value"))
                }
            }
        }

    override fun put(projectId: String, addonId: String, key: String, value: JsonElement) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO addon_data (project_id, addon_id, key, value, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (project_id, addon_id, key)
                DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, addonId)
                statement.setString(3, key)
                statement.setObject(4, jsonb(BuildHoundJson.payload.encodeToString(elementSerializer, value)))
                statement.executeUpdate()
            }
        }
    }

    override fun all(projectId: String, addonId: String): Map<String, JsonElement> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT key, value FROM addon_data WHERE project_id = ? AND addon_id = ? ORDER BY key",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, addonId)
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            put(rows.getString("key"), BuildHoundJson.payload.decodeFromString(elementSerializer, rows.getString("value")))
                        }
                    }
                }
            }
        }
}

/** Idempotent shard-plan memo (plan 040), tenant-scoped, keyed (project_id, reference, total). */
class PostgresShardPlanStore(private val dataSource: DataSource) : ShardPlanStore {

    private val planSerializer = ListSerializer(ListSerializer(String.serializer()))

    override fun planOrCompute(projectId: String, reference: String, total: Int, compute: () -> List<List<String>>): List<List<String>> {
        readPlan(projectId, reference, total)?.let { return it }
        val computed = compute()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO shard_plans (project_id, reference, total, plan) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (project_id, reference, total) DO NOTHING",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, reference)
                statement.setInt(3, total)
                statement.setObject(4, jsonb(BuildHoundJson.payload.encodeToString(planSerializer, computed)))
                statement.executeUpdate()
            }
        }
        // Re-read so a caller that lost the ON CONFLICT race returns the winner's (same-key) plan.
        return readPlan(projectId, reference, total) ?: computed
    }

    private fun readPlan(projectId: String, reference: String, total: Int): List<List<String>>? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT plan FROM shard_plans WHERE project_id = ? AND reference = ? AND total = ?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(projectId))
                statement.setString(2, reference)
                statement.setInt(3, total)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else BuildHoundJson.payload.decodeFromString(planSerializer, rows.getString("plan"))
                }
            }
        }
}
