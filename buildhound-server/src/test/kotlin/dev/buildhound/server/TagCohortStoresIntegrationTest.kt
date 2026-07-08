package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
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
 * Real-Postgres checks for plan 057: the `V11` GIN-index migration applies cleanly, the tag
 * equality filter (`@>` containment) and the tag-cohort `GROUP BY` rollup agree byte-for-byte with
 * the in-memory store on the same fixtures (the plan-026/032 parity discipline), and `/v1/tags`'
 * key/value ranking agrees too. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagCohortStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var inMemory: InMemoryBuildStore
    private lateinit var projectId: String

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource) // must apply V11 cleanly, or this throws
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        projectId = PostgresTokenStore(dataSource).ensureProjectWithToken("tag-cohorts", sha256Hex("t"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures(): List<dev.buildhound.commons.payload.BuildPayload> = buildList {
        // hitRate is set here (unlike most other server fixtures) specifically so the parity
        // assertion below exercises TagCohortCalculator's avgHitRate aggregation across both
        // stores, not just duration — a plain unsorted average would be order-sensitive (the
        // BottleneckCalculator.avgHitRate lesson) and Postgres/in-memory feed rows in different
        // orders (no ORDER BY on the raw-rows query).
        listOf(58_000L to 0.61, 59_000L to 0.62, 60_000L to 0.63, 61_000L to 0.64, 62_000L to 0.65).forEachIndexed { i, (duration, hitRate) ->
            add(TestPayloads.build(buildId = "false-$i", startedAt = recent + i * 1000, durationMs = duration, hitRate = hitRate, tags = mapOf("R8" to "false", "env" to "prod")))
        }
        listOf(98_000L to 0.71, 100_000L to 0.72, 102_000L to 0.73).forEachIndexed { i, (duration, hitRate) ->
            add(TestPayloads.build(buildId = "true-$i", startedAt = recent + i * 1000, durationMs = duration, hitRate = hitRate, tags = mapOf("R8" to "true", "env" to "staging")))
        }
        add(TestPayloads.build(buildId = "bench-true", startedAt = recent, durationMs = 200_000, mode = BuildMode.BENCHMARK, tags = mapOf("R8" to "true")))
        add(TestPayloads.build(buildId = "no-tags", startedAt = recent))
    }

    @Test
    fun `the V11 GIN index over payload tags exists`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM pg_indexes WHERE indexname = 'builds_tags_gin_idx'").use { statement ->
                statement.executeQuery().use { rows -> assertTrue(rows.next(), "the plan-057 GIN index must exist after migration") }
            }
        }
    }

    @Test
    fun `the tag equality filter matches the in-memory store`() {
        val filter = BuildFilter(tags = mapOf("R8" to "true"))
        val pg = postgresStore.list(projectId, filter, 50, 0).map { it.buildId }.toSet()
        val mem = inMemory.list(projectId, filter, 50, 0).map { it.buildId }.toSet()
        assertEquals(mem, pg, "the @> tag filter must match byte-for-byte between stores")
        // No mode exclusion applied here (unlike the route's default), so the R8=true benchmark
        // fixture matches too — this test is about the @> containment, not the fleet-view convention.
        assertEquals(setOf("true-0", "true-1", "true-2", "bench-true"), pg)

        val trendsPg = postgresStore.trends(projectId, filter, 30, now)
        val trendsMem = inMemory.trends(projectId, filter, 30, now)
        assertEquals(trendsMem, trendsPg, "/v1/trends with a tag filter must agree between stores")
    }

    @Test
    fun `tag-cohort trends and the comparator agree between stores`() {
        // Mirrors what the route's buildFilterOrNull() passes by default: benchmark builds excluded.
        val filter = BuildFilter(excludeModes = setOf(BuildMode.BENCHMARK.name))
        val pgRaw = postgresStore.tagCohortTrends(projectId, "R8", filter, 30, now)
        val memRaw = inMemory.tagCohortTrends(projectId, "R8", filter, 30, now)
        // Row order within a cohort (durationsMs) is incidental to how each store's connection
        // returns rows (neither query sorts within a day) — normalize before comparing, the same
        // "same multiset, different fold order" allowance BottleneckCalculator's tests rely on. The
        // downstream CohortComparator result (asserted below) is what must be byte-for-byte, since
        // its median/MAD are already order-independent.
        fun normalize(raw: List<TagCohortRaw>) = raw.map { it.copy(durationsMs = it.durationsMs.sorted()) }.sortedBy { it.value }
        assertEquals(normalize(memRaw), normalize(pgRaw), "raw per-cohort rows must agree between stores (up to row order)")

        val pgComparison = CohortComparator.compare("R8", pgRaw)
        val memComparison = CohortComparator.compare("R8", memRaw)
        assertEquals(memComparison, pgComparison, "the CohortComparator result must be identical over either store's raw rows")
        assertEquals("false", pgComparison.delta?.referenceValue)
        assertEquals(2, pgComparison.cohorts.size) // benchmark's R8=true build is excluded; no-tags has no R8 key
    }

    @Test
    fun `v1 tags key-value ranking agrees between stores`() {
        val pg = postgresStore.tagKeys(projectId, 30, now)
        val mem = inMemory.tagKeys(projectId, 30, now)
        assertEquals(mem, pg, "/v1/tags ranking must agree between stores")
        val r8 = pg.single { it.key == "R8" }
        assertEquals(listOf(TagValueCount("false", 5), TagValueCount("true", 3)), r8.values)
    }
}
