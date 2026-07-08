package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TaskOutcome
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres checks for plan 061: the `V12` additive `execution_reasons` column applies cleanly,
 * `rerunCauses` agrees byte-for-byte between the in-memory and Postgres stores over identical fixtures
 * (incl. a benchmark build, which both stores must exclude — the plan-032 fleet-view convention), and a
 * NULL `execution_reasons` value (a pre-V12-style row) degrades to `UNCLASSIFIED` without error.
 * Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerunCauseStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var inMemory: InMemoryBuildStore
    private lateinit var tokens: PostgresTokenStore
    private lateinit var projectId: String

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource) // must apply V12 cleanly, or this throws
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        tokens = PostgresTokenStore(dataSource)
        projectId = tokens.ensureProjectWithToken("rerun-causes", sha256Hex("rc"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        // 40% IMPL_CLASSPATH fleet-wide coverage — surfaces the build-logic-storm candidate; also a
        // CASCADE build on its own (700/1000 = 70% classpath+upstream-output).
        TestPayloads.build(
            buildId = "cascade-1", durationMs = 1000, startedAt = recent,
            tasks = listOf(
                TestPayloads.task(
                    ":app:compileJava", TaskOutcome.EXECUTED, 700,
                    executionReasons = listOf("Class path of task ':app:compileJava' has changed from 'a' to 'b'."),
                ),
                TestPayloads.task(
                    ":app:compileKotlin", TaskOutcome.EXECUTED, 300,
                    executionReasons = listOf("Value of input property 'x' has changed."),
                ),
            ),
        ),
        // Pure source change (SOURCE is not a cascade cause) — CONTAINED.
        TestPayloads.build(
            buildId = "contained-1", durationMs = 500, startedAt = recent + 1000,
            tasks = listOf(
                TestPayloads.task(
                    ":lib:compileKotlin", TaskOutcome.EXECUTED, 500,
                    executionReasons = listOf("Value of input property 'z' has changed."),
                ),
            ),
        ),
        // No reasons at all — UNCLASSIFIED, never silently dropped.
        TestPayloads.build(
            buildId = "unclassified-1", durationMs = 200, startedAt = recent + 2000,
            tasks = listOf(TestPayloads.task(":app:lint", TaskOutcome.EXECUTED, 200)),
        ),
        // A non-EXECUTED outcome must never contribute, even with reasons attached.
        TestPayloads.build(
            buildId = "cached-1", durationMs = 300, startedAt = recent + 3000,
            tasks = listOf(
                TestPayloads.task(
                    ":app:compileJava", TaskOutcome.FROM_CACHE, 300,
                    executionReasons = listOf("Value of input property 'y' has changed."),
                ),
            ),
        ),
        // A benchmark build — both stores must exclude it (the bottlenecks/toolchain fleet-view
        // convention rerunCauses adopts), even though it carries a classpath-reason executed task that
        // would otherwise skew the fleet share.
        TestPayloads.build(
            buildId = "bench-1", durationMs = 900, startedAt = recent + 4000, mode = BuildMode.BENCHMARK,
            tasks = listOf(
                TestPayloads.task(
                    ":app:compileJava", TaskOutcome.EXECUTED, 900,
                    executionReasons = listOf("Class path of task ':app:compileJava' has changed from 'a' to 'b'."),
                ),
            ),
        ),
    )

    @Test
    fun `the V12 execution_reasons column exists after migration`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_name = 'task_executions' AND column_name = 'execution_reasons'",
            ).use { statement ->
                statement.executeQuery().use { rows -> assertTrue(rows.next(), "the plan-061 execution_reasons column must exist after migration") }
            }
        }
    }

    @Test
    fun `rerunCauses agrees byte-for-byte between stores, and excludes the benchmark build`() {
        val pg = postgresStore.rerunCauses(projectId, 30, now)
        val mem = inMemory.rerunCauses(projectId, 30, now)
        // Bucket order is deterministic (durationMs desc, then cause name) in both stores, so a plain
        // list equality is the byte-for-byte assertion (the plan-026/032 parity discipline).
        assertEquals(mem, pg, "rerunCauses must agree byte-for-byte between stores")

        // 700 (cascade-1 classpath) + 900 (bench-1, must be EXCLUDED) would be 1600 if the benchmark
        // build leaked in; asserting the exact bucket confirms it didn't.
        val implClasspath = pg.buckets.single { it.cause == RerunCause.IMPL_CLASSPATH.name }
        assertEquals(700L, implClasspath.durationMs, "the benchmark build's classpath-reason task must not leak into the fleet share")

        assertEquals(1, pg.cascadeBuildCount, "cascade-1 alone is CASCADE")
        assertEquals(2, pg.containedBuildCount, "contained-1 (source-only) and unclassified-1 (no reasons) are both CONTAINED; cached-1 has no executed task and isn't scored either way")
        assertTrue(pg.buildLogicStormCandidate != null, "$pg")
    }

    @Test
    fun `a NULL execution_reasons value (a pre-V12-style row) degrades to UNCLASSIFIED without error`() {
        val nullProject = tokens.ensureProjectWithToken("rerun-causes-null", sha256Hex("rcn"), TokenScope.ALL).id
        val build = TestPayloads.build(
            buildId = "pre-v12", durationMs = 1000, startedAt = recent,
            tasks = listOf(
                TestPayloads.task(
                    ":app:compileJava", TaskOutcome.EXECUTED, 1000,
                    // Written with a real reason on ingest; forced back to NULL below to simulate a row
                    // that predates this migration (a fresh insert never itself writes NULL).
                    executionReasons = listOf("Value of input property 'x' has changed."),
                ),
            ),
        )
        postgresStore.save(nullProject, build)
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE task_executions SET execution_reasons = NULL WHERE project_id = ?::uuid AND build_id = ?").use { statement ->
                statement.setString(1, nullProject)
                statement.setString(2, "pre-v12")
                assertEquals(1, statement.executeUpdate())
            }
        }

        val rollup = postgresStore.rerunCauses(nullProject, 30, now)
        assertEquals(RerunCause.UNCLASSIFIED.name, rollup.buckets.single().cause)
        assertEquals(1.0, rollup.unclassifiedSharePct)
        assertNull(rollup.buildLogicStormCandidate)
    }
}
