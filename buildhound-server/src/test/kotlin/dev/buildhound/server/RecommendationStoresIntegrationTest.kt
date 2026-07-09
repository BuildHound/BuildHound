package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.ToolchainInfo
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
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
 * Real-Postgres checks for plan 054: `windowPayloads` — the recommendations engine's whole-payload data
 * source — agrees byte-for-byte between the in-memory and Postgres stores (the plan-026/032/067 parity
 * discipline), so the pure [RecommendationEngine] sees the identical input from either store. Covers
 * benchmark-mode exclusion (the fleet-view convention every rollup shares), the `(started_at, build_id)
 * DESC` cap tie-break above the requested cap, and a malformed/undecodable `payload` row (bypassing
 * [BuildStore.save], which can never itself write one) that must be skipped, never fatal to the whole
 * query — exactly like [CacheRoiStoresIntegrationTest]'s malformed-block precedent, but at the row level
 * this time. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecommendationStoresIntegrationTest {

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
    private val days = 30

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        tokens = PostgresTokenStore(dataSource)
        projectId = tokens.ensureProjectWithToken("recommendations", sha256Hex("rec"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        // An ordinary CI build with the configuration cache disabled — carries a real signal so the
        // end-to-end engine-parity assertion below has something to fire on.
        TestPayloads.build(
            buildId = "a", startedAt = recent, mode = BuildMode.CI,
            configurationCache = ConfigurationCacheState.DISABLED,
        ),
        // A LOCAL build with a KAPT task — exercises whole-payload parity over tasks[]/toolchain, not
        // just the hot columns the other rollups read.
        TestPayloads.build(
            buildId = "b", startedAt = recent + 1000, mode = BuildMode.LOCAL,
            toolchain = ToolchainInfo(kgp = "2.0.0"),
            tasks = listOf(
                TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 4000, type = "org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask"),
                TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 1000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile"),
            ),
        ),
        // A benchmark-mode build — must be EXCLUDED from windowPayloads (the fleet-view convention),
        // even though its CC-disabled state would otherwise inflate the hygiene share if it leaked in.
        TestPayloads.build(
            buildId = "bench-1", startedAt = recent + 2000, mode = BuildMode.BENCHMARK,
            configurationCache = ConfigurationCacheState.DISABLED,
        ),
        // Outside the window — excluded from both stores.
        TestPayloads.build(buildId = "old-1", startedAt = now - (days + 5).toLong() * 86_400_000),
    )

    @Test
    fun `windowPayloads agrees byte-for-byte between stores and excludes benchmark + out-of-window builds`() {
        val pg = postgresStore.windowPayloads(projectId, days, MAX_RECOMMENDATION_ROWS, now)
        val mem = inMemory.windowPayloads(projectId, days, MAX_RECOMMENDATION_ROWS, now)
        assertEquals(mem, pg, "windowPayloads must agree byte-for-byte between stores")
        assertEquals(listOf("b", "a"), pg.map { it.buildId }, "most-recent-first; the benchmark + out-of-window builds are excluded")

        // End-to-end: the pure RecommendationEngine must agree too when fed each store's windowed set
        // (the plan-026/032 parity discipline extended through the engine, not just the raw payload list).
        val pgRecs = RecommendationEngine.compute(pg)
        val memRecs = RecommendationEngine.compute(mem)
        assertEquals(memRecs, pgRecs, "RecommendationEngine.compute must agree end-to-end between stores")
        assertTrue(
            pgRecs.any { it.ruleId == "HYGIENE-CACHE-OFF" },
            "build a's CC-disabled state must trip the hygiene rule, unmasked by the excluded benchmark build",
        )
    }

    @Test
    fun `the cap + (started_at, build_id) DESC tie-break agree across stores above the cap`() {
        val capProject = tokens.ensureProjectWithToken("recommendations-cap", sha256Hex("rec-cap"), TokenScope.ALL).id
        val builds = listOf(
            TestPayloads.build(buildId = "c1", startedAt = recent),
            // c2/c3 share the same startedAt — the tie-break must fall to build_id DESC ("c3" > "c2").
            TestPayloads.build(buildId = "c2", startedAt = recent + 1000),
            TestPayloads.build(buildId = "c3", startedAt = recent + 1000),
            TestPayloads.build(buildId = "c4", startedAt = recent + 2000),
            TestPayloads.build(buildId = "c5", startedAt = recent + 3000),
        )
        builds.forEach { postgresStore.save(capProject, it); inMemory.save(capProject, it) }

        // Cap well below the fixture count (the same mechanism MAX_RECOMMENDATION_ROWS uses in
        // production, sized down here so the test stays cheap rather than inserting 20,000 rows).
        val cap = 3
        val pg = postgresStore.windowPayloads(capProject, days, cap, now)
        val mem = inMemory.windowPayloads(capProject, days, cap, now)
        assertEquals(mem, pg, "the capped + tie-broken set must agree byte-for-byte between stores")
        assertEquals(listOf("c5", "c4", "c3"), pg.map { it.buildId }, "most-recent-first; c3 wins the started_at tie over c2 via build_id DESC")
    }

    @Test
    fun `a malformed, undecodable payload row is skipped, never fatal to the whole query`() {
        val malformedProject = tokens.ensureProjectWithToken("recommendations-malformed", sha256Hex("rec-malformed"), TokenScope.ALL).id
        val good = TestPayloads.build(
            buildId = "good-1", startedAt = recent, mode = BuildMode.CI,
            configurationCache = ConfigurationCacheState.DISABLED,
        )
        postgresStore.save(malformedProject, good)
        inMemory.save(malformedProject, good)

        // A row whose `payload` jsonb cannot decode as a BuildPayload at all — save() can never itself
        // write one (it always serializes a real BuildPayload), so this simulates a corrupt/pre-schema
        // row via a raw insert. windowPayloads' runCatching guard must skip it silently, exactly like
        // cacheRoi/ccEconomics, rather than fail the whole query.
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO builds (project_id, build_id, started_at, finished_at, outcome, mode, duration_ms, payload)
                VALUES (?, 'malformed-1', ?, ?, 'SUCCESS', 'CI', 100, ?::jsonb)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(malformedProject))
                statement.setObject(2, OffsetDateTime.ofInstant(Instant.ofEpochMilli(recent + 500), ZoneOffset.UTC))
                statement.setObject(3, OffsetDateTime.ofInstant(Instant.ofEpochMilli(recent + 600), ZoneOffset.UTC))
                statement.setString(4, "\"not-a-build-payload\"")
                assertEquals(1, statement.executeUpdate())
            }
        }

        val pg = postgresStore.windowPayloads(malformedProject, days, MAX_RECOMMENDATION_ROWS, now)
        val mem = inMemory.windowPayloads(malformedProject, days, MAX_RECOMMENDATION_ROWS, now)
        assertEquals(mem, pg, "windowPayloads must agree byte-for-byte between stores even with a malformed row in Postgres only")
        assertEquals(listOf("good-1"), pg.map { it.buildId }, "the malformed row must be skipped, leaving only the well-formed build — never a thrown exception")

        // The engine still runs cleanly over the surviving payload.
        val recs = RecommendationEngine.compute(pg)
        assertTrue(recs.any { it.ruleId == "HYGIENE-CACHE-OFF" }, "the surviving well-formed build's signal must still reach the engine")
    }
}
