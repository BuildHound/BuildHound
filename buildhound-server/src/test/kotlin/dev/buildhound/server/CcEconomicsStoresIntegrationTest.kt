package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.FingerprintInfo
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres checks for plan 064: the V14 `cc_state` extract feeds the `/trends` per-day CC counters,
 * and `ccEconomics` reads the whole `payload` jsonb (configurationMs/ccLoadMs/entry size + fingerprints +
 * salt-stream identity live there). Both surfaces must agree byte-for-byte between the in-memory and
 * Postgres stores over fixtures covering a flip-flop pair, a DISABLED build, a no-CC build, a benchmark
 * build (fleet-view exclusion), and a build outside the window. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CcEconomicsStoresIntegrationTest {

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

    private val fpX = FingerprintInfo(build = mapOf("jdk.home" to "aaaa1111", "env-CI" to "bbbb2222"))
    private val fpY = FingerprintInfo(build = mapOf("jdk.home" to "cccc3333", "env-CI" to "bbbb2222"))

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        tokens = PostgresTokenStore(dataSource)
        projectId = tokens.ensureProjectWithToken("cc-economics", sha256Hex("cce"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures() = listOf(
        // A flip-flop pair on one machine: b1 HIT (fp X), b2 MISS_STORED (fp X) → b2 flags prior b1.
        TestPayloads.build(
            buildId = "b1", startedAt = recent, hostnameHash = "h1", userId = "u1",
            configurationCache = ConfigurationCacheState.HIT, ccEntrySizeBytes = 1000, fingerprints = fpX,
        ),
        TestPayloads.build(
            buildId = "b2", startedAt = recent + 1000, hostnameHash = "h1", userId = "u1",
            configurationCache = ConfigurationCacheState.MISS_STORED, configurationMs = 800, ccEntrySizeBytes = 2000, fingerprints = fpX,
        ),
        // A HIT with different inputs — no flip-flop; carries an entry size.
        TestPayloads.build(
            buildId = "b3", startedAt = recent + 2000, hostnameHash = "h1", userId = "u1",
            configurationCache = ConfigurationCacheState.HIT, ccEntrySizeBytes = 3000, fingerprints = fpY,
        ),
        // A DISABLED build — CC-observed but not requested; its configurationMs never enters the store-cost p50.
        TestPayloads.build(
            buildId = "b4", startedAt = recent + 3000, hostnameHash = "h1", userId = "u1",
            configurationCache = ConfigurationCacheState.DISABLED, configurationMs = 9999,
        ),
        // A build with no CC state at all (pre-064 style) — CC-unobserved.
        TestPayloads.build(buildId = "b5", startedAt = recent + 4000),
        // A benchmark build carrying a flip-flop-shaped MISS_STORED — must be excluded from ccEconomics.
        TestPayloads.build(
            buildId = "bench-1", startedAt = recent + 5000, mode = BuildMode.BENCHMARK, hostnameHash = "h1", userId = "u1",
            configurationCache = ConfigurationCacheState.MISS_STORED, configurationMs = 111, fingerprints = fpX,
        ),
        // A build well outside the window — excluded from both surfaces.
        TestPayloads.build(
            buildId = "old-1", startedAt = now - (days + 5).toLong() * 86_400_000, hostnameHash = "h1", userId = "u1",
            configurationCache = ConfigurationCacheState.HIT, fingerprints = fpX,
        ),
    )

    @Test
    fun `ccEconomics agrees byte-for-byte between stores`() {
        val pg = postgresStore.ccEconomics(projectId, days, now)
        val mem = inMemory.ccEconomics(projectId, days, now)
        assertEquals(mem, pg, "ccEconomics must agree byte-for-byte between stores")

        // b1(HIT) + b2(MISS) + b3(HIT) requested; b4 DISABLED observed-not-requested; b5 unobserved;
        // the benchmark + old build excluded.
        assertEquals(4, pg.ccObservedBuilds, "b1,b2,b3,b4 carry a CC state; b5 does not; bench/old are out of window/excluded")
        assertEquals(3, pg.ccRequestedBuilds)
        assertEquals(2, pg.ccHitBuilds)
        assertEquals(1, pg.ccMissStoredBuilds)
        // Store-cost p50 over MISS_STORED only — b2's 800, never b4's DISABLED 9999 nor the benchmark's 111.
        assertEquals(800, pg.storeCostMsP50)
        val flip = pg.flipFlops.single()
        assertEquals("b2", flip.buildId)
        assertEquals("b1", flip.priorBuildId)
    }

    @Test
    fun `trends CC counters agree byte-for-byte between stores`() {
        val pg = postgresStore.trends(projectId, BuildFilter(), days, now)
        val mem = inMemory.trends(projectId, BuildFilter(), days, now)
        assertEquals(mem, pg, "trends (incl. the CC counters) must agree byte-for-byte between stores")
        // All in-window builds fall on one UTC day (old-1 is 35 days back, a different day, outside 30d).
        // /trends does NOT exclude benchmark by default, so bench-1's MISS_STORED counts here.
        val day = pg.single()
        assertEquals(2, day.ccHit, "b1 + b3")
        assertEquals(2, day.ccMissStored, "b2 + bench-1 (not excluded by /trends)")
        assertEquals(4, day.ccRequested, "b1,b2,b3,bench-1 — the DISABLED b4 is observed-not-requested, b5 has no CC")
    }

    @Test
    fun `the window boundary holds at parity across widths`() {
        assertEquals(inMemory.ccEconomics(projectId, 30, now), postgresStore.ccEconomics(projectId, 30, now))
        assertEquals(inMemory.ccEconomics(projectId, 40, now), postgresStore.ccEconomics(projectId, 40, now))
        assertEquals(2, postgresStore.ccEconomics(projectId, 30, now).ccHitBuilds, "b1 + b3 within 30d")
        assertEquals(3, postgresStore.ccEconomics(projectId, 40, now).ccHitBuilds, "old-1's HIT (35d back) joins at 40d → 3 HITs")
    }
}
