package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres checks for the plan-026 rollups: `save` writes normalized task rows once per build
 * (zero on a duplicate), and each SQL rollup agrees byte-for-byte with the in-memory store over the
 * same fixtures — so `RollupCalculator` is the single source of the aggregation rules. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RollupStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var tokens: PostgresTokenStore

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        TestPayloads.build(
            buildId = "f-1", durationMs = 10_000, startedAt = recent, userId = "u_1",
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile"),
                TestPayloads.task(":app:test", TaskOutcome.EXECUTED, 3000, type = "Test"),
                TestPayloads.task(":lib:compileKotlin", TaskOutcome.EXECUTED, 1000, type = "KotlinCompile"),
            ),
        ),
        TestPayloads.build(
            buildId = "f-2", durationMs = 9_000, startedAt = recent + 1000, userId = "u_2",
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.FROM_CACHE, 9000, type = "KotlinCompile"),
                TestPayloads.task(":lib:test", TaskOutcome.UP_TO_DATE, 200, type = "Test"),
            ),
        ),
        TestPayloads.build(
            buildId = "f-3", durationMs = 12_000, startedAt = recent + 2000, userId = "u_1",
            tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 7000, type = "KotlinCompile")),
        ),
        // Edge cases the tiebreakers/medians exist for: a null module, a null user, and an EVEN
        // executed count so the negative-avoidance median interpolates (1000,2000 → 1500.0).
        TestPayloads.build(
            buildId = "f-4", durationMs = 8_000, startedAt = recent + 3000, userId = null,
            tasks = listOf(
                TaskExecution(path = "orphan", module = null, type = "Lint", startMs = 0, durationMs = 1000, outcome = TaskOutcome.EXECUTED),
                TaskExecution(path = "orphan", module = null, type = "Lint", startMs = 0, durationMs = 2000, outcome = TaskOutcome.EXECUTED),
                TaskExecution(path = "orphan", module = null, type = "Lint", startMs = 0, durationMs = 3000, outcome = TaskOutcome.UP_TO_DATE),
            ),
        ),
    )

    private fun taskRowCount(projectId: String, buildId: String): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM task_executions WHERE project_id = ?::uuid AND build_id = ?").use { s ->
                s.setString(1, projectId)
                s.setString(2, buildId)
                s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }

    @Test
    fun `save writes task rows once per build and none on a duplicate`() {
        val project = tokens.ensureProjectWithToken("rows-project", sha256Hex("rp"))
        val build = fixtures()[0]
        assertEquals(true, postgresStore.save(project.id, build))
        assertEquals(3, taskRowCount(project.id, build.buildId), "one row per task")
        assertEquals(false, postgresStore.save(project.id, build), "duplicate build")
        assertEquals(3, taskRowCount(project.id, build.buildId), "a duplicate build adds no task rows")
    }

    @Test
    fun `each rollup matches the in-memory store byte-for-byte`() {
        val project = tokens.ensureProjectWithToken("parity-project", sha256Hex("pp"))
        val inMemory = InMemoryBuildStore()
        for (build in fixtures()) {
            postgresStore.save(project.id, build)
            inMemory.save(project.id, build)
        }

        assertEquals(inMemory.projectCost(project.id, 30, now), postgresStore.projectCost(project.id, 30, now), "projectCost")
        assertEquals(inMemory.taskDuration(project.id, 30, now), postgresStore.taskDuration(project.id, 30, now), "taskDuration")
        assertEquals(inMemory.negativeAvoidance(project.id, 30, now), postgresStore.negativeAvoidance(project.id, 30, now), "negativeAvoidance")
    }

    @Test
    fun `a build older than the window is excluded`() {
        val project = tokens.ensureProjectWithToken("window-project", sha256Hex("wp"))
        val old = TestPayloads.build(
            buildId = "old-1", startedAt = now - 40L * 86_400_000, userId = "u_1",
            tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile")),
        )
        postgresStore.save(project.id, old)
        assertEquals(emptyList(), postgresStore.projectCost(project.id, 30, now), "40-day-old build is outside a 30-day window")
    }
}
