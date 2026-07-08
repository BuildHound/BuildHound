package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres parity for the plan-070 metrics snapshot: both stores fetch the same windowed
 * [BuildKpiRow]s + `derived.avoidedMs` values and defer to [MetricsSnapshotCalculator], so the output
 * must agree byte-for-byte over the same fixtures (the plan-026/032 parity discipline). `avoidedMs` is
 * read from the Postgres jsonb payload (no hot column for it), same pattern `toolchainAdoption` uses.
 * Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsSnapshotStoresIntegrationTest {

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
        TestPayloads.build(buildId = "m-1", durationMs = 4_000, startedAt = recent, hitRate = 0.6, avoidedMs = 1_000),
        TestPayloads.build(buildId = "m-2", durationMs = 6_000, startedAt = recent + 1_000, hitRate = 0.8, avoidedMs = 2_000),
        TestPayloads.build(buildId = "m-3", durationMs = 5_000, startedAt = recent + 2_000, outcome = BuildOutcome.FAILED),
        // A benchmark build in the window — excluded from the fleet snapshot in both stores.
        TestPayloads.build(buildId = "m-bench", durationMs = 30_000, startedAt = recent + 3_000, mode = BuildMode.BENCHMARK),
        // Older than the 30-day window — excluded from both stores.
        TestPayloads.build(buildId = "m-old", durationMs = 1_000, startedAt = now - 40L * 86_400_000),
    )

    private fun seedBoth(project: ProjectRef, inMemory: InMemoryBuildStore) {
        for (build in fixtures()) {
            postgresStore.save(project.id, build)
            inMemory.save(project.id, build)
        }
    }

    @Test
    fun `metricsSnapshot agrees byte-for-byte across the two stores`() {
        val project = tokens.ensureProjectWithToken("metrics-parity", sha256Hex("mp"))
        val inMemory = InMemoryBuildStore()
        seedBoth(project, inMemory)
        assertEquals(
            inMemory.metricsSnapshot(project.id, 30, now),
            postgresStore.metricsSnapshot(project.id, 30, now),
            "metrics snapshot",
        )
    }

    @Test
    fun `the snapshot excludes benchmark and out-of-window builds and sums only present avoidedMs`() {
        val project = tokens.ensureProjectWithToken("metrics-shape", sha256Hex("ms"))
        val inMemory = InMemoryBuildStore()
        seedBoth(project, inMemory)
        val s = postgresStore.metricsSnapshot(project.id, 30, now)

        // Sorted durations over the 3 fleet builds (bench + old excluded): 4000, 5000, 6000.
        assertEquals(5_000, s.p50DurationMs)
        assertEquals(6_000, s.p95DurationMs)
        assertEquals(0.7, s.cacheHitRate) // avg(0.6, 0.8) — m-3 has no hit rate
        assertEquals(2.0 / 3, s.successRate)
        assertEquals(mapOf("SUCCESS" to 2, "FAILED" to 1), s.buildCountsByOutcome)
        assertEquals(3_000, s.avoidedMs) // 1000 + 2000; m-3/bench/old contribute nothing
        assertNull(s.flakyTestCount, "no test data was ever reported in this fixture set")
    }

    @Test
    fun `an empty project's snapshot omits every KPI on both stores`() {
        val project = tokens.ensureProjectWithToken("metrics-empty", sha256Hex("me"))
        val inMemory = InMemoryBuildStore()
        val pg = postgresStore.metricsSnapshot(project.id, 30, now)
        val mem = inMemory.metricsSnapshot(project.id, 30, now)
        assertEquals(mem, pg)
        assertNull(pg.p50DurationMs)
        assertNull(pg.avoidedMs)
        assertNull(pg.flakyTestCount)
        assertEquals(emptyMap(), pg.buildCountsByOutcome)
    }
}
