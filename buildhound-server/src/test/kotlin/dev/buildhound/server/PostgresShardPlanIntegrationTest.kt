package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.TestClassResult
import dev.buildhound.commons.payload.TestTaskResult
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres shard-plan memo + class-timing query (plan 040): the `V9__shard_plans` migration runs
 * on a clean DB, `classTimings` reads per-class CI durations from the payload jsonb, and the plan memo
 * is idempotent + tenant-isolated. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresShardPlanIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var builds: PostgresBuildStore
    private lateinit var shardPlans: PostgresShardPlanStore
    private lateinit var tokens: PostgresTokenStore

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource) // includes V9__shard_plans
        builds = PostgresBuildStore(dataSource)
        shardPlans = PostgresShardPlanStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    private fun ciBuildWithTimings(id: String) = TestPayloads.build(
        buildId = id, startedAt = recent,
        tests = listOf(
            TestTaskResult(
                taskPath = ":app:testDebugUnitTest", module = ":app",
                classes = listOf(
                    TestClassResult(className = "com.example.FooTest", durationMs = 1000),
                    TestClassResult(className = "com.example.BarTest", durationMs = 2000),
                ),
            ),
        ),
    )

    @Test
    fun `classTimings reads per-class CI durations grouped by TestUnitKey`() {
        val project = tokens.ensureProjectWithToken("timings", sha256Hex("t"))
        builds.save(project.id, ciBuildWithTimings("t-1"))
        builds.save(project.id, ciBuildWithTimings("t-2"))

        val timings = builds.classTimings(project.id, 30, now)
        assertEquals(listOf(1000L, 1000L), timings[":app/com.example.FooTest"])
        assertEquals(listOf(2000L, 2000L), timings[":app/com.example.BarTest"])
        assertEquals(1000L, LptBalancer.p90(timings.getValue(":app/com.example.FooTest")))
    }

    @Test
    fun `local builds are excluded from the CI timing window`() {
        val project = tokens.ensureProjectWithToken("timings-local", sha256Hex("tl"))
        builds.save(project.id, ciBuildWithTimings("c-1"))
        builds.save(project.id, TestPayloads.build(buildId = "l-1", startedAt = recent, mode = BuildMode.LOCAL,
            tests = listOf(TestTaskResult(taskPath = ":app:test", module = ":app",
                classes = listOf(TestClassResult(className = "com.example.LocalOnly", durationMs = 9000))))))
        val timings = builds.classTimings(project.id, 30, now)
        assertEquals(null, timings[":app/com.example.LocalOnly"], "a LOCAL build's tests never feed the CI balancer")
    }

    @Test
    fun `the plan memo is idempotent and tenant-isolated`() {
        val a = tokens.ensureProjectWithToken("plan-a", sha256Hex("pa"))
        val b = tokens.ensureProjectWithToken("plan-b", sha256Hex("pb"))
        val first = shardPlans.planOrCompute(a.id, "ref", 2) { listOf(listOf("x"), listOf("y")) }
        // A second call with a DIFFERENT compute returns the stored plan, not the new computation.
        val again = shardPlans.planOrCompute(a.id, "ref", 2) { listOf(listOf("DIFFERENT")) }
        assertEquals(first, again)
        assertEquals(listOf(listOf("x"), listOf("y")), again)
        // Tenant B's same-key plan is independent.
        val bPlan = shardPlans.planOrCompute(b.id, "ref", 2) { listOf(listOf("b1"), listOf("b2")) }
        assertEquals(listOf(listOf("b1"), listOf("b2")), bPlan)
    }
}
