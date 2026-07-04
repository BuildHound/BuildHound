package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres flaky detection (plan 036): the projected `test_class_outcomes` rows feed the same
 * `FlakyDetector` the in-memory store flattens payloads into, so the two agree byte-for-byte;
 * re-ingest is idempotent; tenants are isolated. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresFlakyStoreIntegrationTest {

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
        // FooTest: cross-run divergence at sha-a. BarTest: a retry flake. BazTest: always green (not flaky).
        TestPayloads.build(
            buildId = "f-1", startedAt = recent, sha = "sha-a",
            tests = listOf(
                TestPayloads.testTask(className = "com.example.FooTest", passed = 5, failed = 0),
                TestPayloads.testTask(module = ":lib", className = "com.example.BarTest", passed = 3),
                TestPayloads.testTask(className = "com.example.BazTest", passed = 2),
            ),
        ),
        TestPayloads.build(
            buildId = "f-2", startedAt = recent + 1000, sha = "sha-a",
            tests = listOf(
                TestPayloads.testTask(className = "com.example.FooTest", passed = 4, failed = 1), // red → cross-run
                TestPayloads.testTask(module = ":lib", className = "com.example.BarTest", passed = 3, retriedCases = listOf("flaky()")),
                TestPayloads.testTask(className = "com.example.BazTest", passed = 2),
            ),
        ),
        TestPayloads.build(
            buildId = "f-3", startedAt = recent + 2000, sha = "sha-a", userId = null,
            tests = listOf(
                TestPayloads.testTask(className = "com.example.FooTest", passed = 5, failed = 0),
                TestPayloads.testTask(module = ":lib", className = "com.example.BarTest", passed = 3),
                TestPayloads.testTask(className = "com.example.BazTest", passed = 2),
            ),
        ),
    )

    @Test
    fun `flaky detection agrees byte-for-byte with the in-memory store`() {
        val project = tokens.ensureProjectWithToken("flaky-parity", sha256Hex("fp"))
        val inMemory = InMemoryBuildStore()
        for (build in fixtures()) {
            postgresStore.save(project.id, build)
            inMemory.save(project.id, build)
        }
        assertEquals(inMemory.flaky(project.id, 30, now), postgresStore.flaky(project.id, 30, now), "flaky records")
    }

    @Test
    fun `the expected signals and shape are computed and re-ingest is idempotent`() {
        val project = tokens.ensureProjectWithToken("flaky-shape", sha256Hex("fs"))
        for (build in fixtures()) {
            assertTrue(postgresStore.save(project.id, build))
            assertEquals(false, postgresStore.save(project.id, build), "re-ingest of the same build is a no-op")
        }
        val records = postgresStore.flaky(project.id, 30, now)
        val foo = records.single { it.className == "com.example.FooTest" }
        assertEquals(FlakySignal.CROSS_RUN.name, foo.signal)
        val bar = records.single { it.className == "com.example.BarTest" }
        assertEquals(FlakySignal.RETRY.name, bar.signal)
        assertEquals(":lib", bar.module)
        assertTrue(records.none { it.className == "com.example.BazTest" }, "an always-green class is not flaky")
    }

    @Test
    fun `flaky records are tenant-isolated`() {
        val a = tokens.ensureProjectWithToken("flaky-tenant-a", sha256Hex("fta"))
        val b = tokens.ensureProjectWithToken("flaky-tenant-b", sha256Hex("ftb"))
        for (build in fixtures()) postgresStore.save(a.id, build)
        assertTrue(postgresStore.flaky(b.id, 30, now).isEmpty(), "tenant B sees none of A's flaky records")
    }

    @Test
    fun `a class split across two Test tasks in one build stays parity-safe and is not a false flake`() {
        // Each build reports FooTest twice — a green shard (5/0) and a red shard (0/1). The Postgres PK
        // (project, build, module, class) physically holds only one such row; classOutcomesOf must sum
        // to that same single row so the in-memory store (which would otherwise keep both) agrees, and
        // so one build never satisfies the cross-run green+red predicate on its own.
        val project = tokens.ensureProjectWithToken("flaky-split", sha256Hex("fsp"))
        val inMemory = InMemoryBuildStore()
        val split = listOf(
            TestPayloads.testTask(className = "com.example.FooTest", passed = 5, failed = 0),
            TestPayloads.testTask(className = "com.example.FooTest", passed = 0, failed = 1),
        )
        for (i in 0..2) {
            val build = TestPayloads.build(buildId = "sp-$i", startedAt = recent + i * 1000L, sha = "sha-a", tests = split)
            postgresStore.save(project.id, build)
            inMemory.save(project.id, build)
        }
        assertEquals(inMemory.flaky(project.id, 30, now), postgresStore.flaky(project.id, 30, now), "split parity")
        assertTrue(postgresStore.flaky(project.id, 30, now).isEmpty(), "a within-build shard split is not cross-run flakiness")
    }
}
