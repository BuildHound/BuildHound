package dev.buildhound.server

import dev.buildhound.commons.payload.BenchmarkInfo
import dev.buildhound.commons.payload.BuildMode
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
 * Real-Postgres checks for plan 030: benchmark builds are excluded from fleet trends/list by default
 * and the jsonb-backed `benchmarkSeries` agrees with the in-memory store byte-for-byte (both defer to
 * the commons percentile calculator). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BenchmarkStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgres_: PostgresBuildStore
    private lateinit var inMemory: InMemoryBuildStore
    private lateinit var projectId: String

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgres_ = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        projectId = PostgresTokenStore(dataSource).ensureProjectWithToken("bench", sha256Hex("t"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgres_.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        bench("c-1", 3_000, 1, "clean", "no_build_cache"),
        bench("c-2", 1_000, 2, "clean", "no_build_cache"),
        bench("c-3", 2_000, 3, "clean", "no_build_cache"),
        bench("f-1", 5_000, 1, "clean", "full_cache"),
        bench("n-1", 500, 1, "no_op", null),
        // A normal CI build that must never appear in a benchmark series or a default fleet trend.
        TestPayloads.build(buildId = "ci-1", durationMs = 9_000, startedAt = recent, mode = BuildMode.CI),
    )

    private fun bench(id: String, durationMs: Long, iteration: Int, scenario: String, isolation: String?) =
        TestPayloads.build(
            buildId = id, durationMs = durationMs, startedAt = recent + iteration * 1000L,
            mode = BuildMode.BENCHMARK,
            benchmark = BenchmarkInfo(scenario = scenario, iteration = iteration, isolationMode = isolation),
        )

    @Test
    fun `benchmark builds are excluded from default fleet trends but included with the flag`() {
        val default = postgres_.trends(projectId, BuildFilter(excludeModes = setOf("BENCHMARK")), 30, now)
        assertEquals(1, default.sumOf { it.builds }, "only the CI build counts by default")
        val all = postgres_.trends(projectId, BuildFilter(), 30, now)
        assertEquals(6, all.sumOf { it.builds }, "every build counts with no exclusion")
    }

    @Test
    fun `benchmark series groups by scenario and isolation, matching the in-memory store`() {
        val pg = postgres_.benchmarkSeries(projectId, null, null, null, 30, now)
        val mem = inMemory.benchmarkSeries(projectId, null, null, null, 30, now)
        assertEquals(mem, pg, "Postgres jsonb series must equal the in-memory series byte-for-byte")

        // Three groups: (clean, no_build_cache), (clean, full_cache), (no_op, null). CI is absent.
        assertEquals(3, pg.size)
        val cleanNoCache = pg.single { it.scenario == "clean" && it.isolationMode == "no_build_cache" }
        assertEquals(3, cleanNoCache.summary.count)
        assertEquals(2_000, cleanNoCache.summary.p50) // durations 1s/2s/3s
        assertEquals(1_000, cleanNoCache.summary.min)
        assertTrue(cleanNoCache.points.map { it.buildId }.containsAll(listOf("c-1", "c-2", "c-3")))
    }

    @Test
    fun `benchmark series narrows by scenario`() {
        val onlyNoOp = postgres_.benchmarkSeries(projectId, scenario = "no_op", isolationMode = null, branch = null, days = 30, nowMs = now)
        assertEquals(1, onlyNoOp.size)
        assertEquals("no_op", onlyNoOp.single().scenario)
    }
}
