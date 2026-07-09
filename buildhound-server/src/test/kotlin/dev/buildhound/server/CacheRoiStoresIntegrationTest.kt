package dev.buildhound.server

import dev.buildhound.commons.payload.BuildCacheConfigInfo
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TaskOutcome
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
 * Real-Postgres checks for plan 067: `cacheRoi` reads the whole `builds.payload` jsonb column (origin +
 * the `environment.buildCache` snapshot live only there, never in `task_executions`) and agrees
 * byte-for-byte between the in-memory and Postgres stores over fixtures covering the per-mode rate, the
 * STORED-excluded denominator, the config-snapshot summary, a benchmark build (fleet-view exclusion), a
 * build outside the window (boundary exclusion), and a malformed `internalAdapters` block (guarded skip,
 * never fatal). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheRoiStoresIntegrationTest {

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
    private val remoteConfigured = BuildCacheConfigInfo(localEnabled = true, remoteEnabled = true, remotePush = true, remoteType = "HttpBuildCache")
    private val noRemote = BuildCacheConfigInfo(localEnabled = true)

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        tokens = PostgresTokenStore(dataSource)
        projectId = tokens.ensureProjectWithToken("cache-roi", sha256Hex("roi"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    /**
     * A malformed `internalAdapters` block (068's `malformedInternalAdaptersPayload` precedent, from
     * `CacheMissDiagnosticsStoresIntegrationTest`) with a well-formed `environment.buildCache` alongside
     * it: [cacheRoiRowsOf]'s `runCatching` must guard-skip the origin rows for this build (never fatal to
     * the whole rollup) while [cacheConfigRowOf] — an independent read of `environment.buildCache` — still
     * counts it, exactly like the two-detector independence `CacheMissDiagnosticsStoresIntegrationTest`
     * proves for fingerprints alongside a broken `internalAdapters` block.
     */
    private fun malformedInternalAdaptersPayload(buildId: String, startedAt: Long): BuildPayload =
        BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            """
            {
              "schemaVersion": 1,
              "buildId": "$buildId",
              "startedAt": $startedAt,
              "finishedAt": ${startedAt + 100},
              "outcome": "SUCCESS",
              "mode": "ci",
              "environment": {"buildCache": {"localEnabled": true, "remoteEnabled": true, "remotePush": true, "remoteType": "HttpBuildCache"}},
              "tasks": [{"path": ":app:compileJava", "module": ":app", "startMs": 0, "durationMs": 100, "outcome": "EXECUTED", "cacheable": true}],
              "extensions": {"internalAdapters": "not-an-object"}
            }
            """.trimIndent(),
        )

    private fun fixtures(): List<BuildPayload> = buildList {
        // A CI fleet with a configured remote that rarely reuses it: 60 misses + 1 remote hit.
        for (i in 0 until 60) {
            add(
                TestPayloads.build(
                    buildId = "ci-miss-$i", startedAt = recent + i, mode = BuildMode.CI, buildCache = remoteConfigured,
                    tasks = listOf(TestPayloads.task(":app:compileJava", TaskOutcome.EXECUTED, 100, cacheable = true)),
                    extensions = TestPayloads.internalAdapters(listOf(":app:compileJava" to "MISS")),
                ),
            )
        }
        add(
            TestPayloads.build(
                buildId = "ci-hit", startedAt = recent + 100, mode = BuildMode.CI, buildCache = remoteConfigured,
                tasks = listOf(TestPayloads.task(":lib:compileJava", TaskOutcome.FROM_CACHE, 5, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":lib:compileJava" to "REMOTE_HIT")),
            ),
        )
        // A LOCAL build: a local hit + a STORED task (excluded from the rate denominator).
        add(
            TestPayloads.build(
                buildId = "local-1", startedAt = recent + 200, mode = BuildMode.LOCAL, buildCache = noRemote,
                tasks = listOf(
                    TestPayloads.task(":app:compileJava", TaskOutcome.FROM_CACHE, 5, cacheable = true),
                    TestPayloads.task(":app:jar", TaskOutcome.EXECUTED, 50, cacheable = true),
                ),
                extensions = TestPayloads.internalAdapters(listOf(":app:compileJava" to "LOCAL_HIT", ":app:jar" to "STORED")),
            ),
        )
        // A benchmark build carrying origin data — both stores must exclude it from the fleet rate.
        add(
            TestPayloads.build(
                buildId = "bench-1", startedAt = recent + 300, mode = BuildMode.BENCHMARK, buildCache = remoteConfigured,
                tasks = listOf(TestPayloads.task(":app:compileJava", TaskOutcome.EXECUTED, 100, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":app:compileJava" to "MISS")),
            ),
        )
        // A build well outside the window — must not count.
        add(
            TestPayloads.build(
                buildId = "old-1", startedAt = now - (days + 5).toLong() * 86_400_000, mode = BuildMode.CI, buildCache = remoteConfigured,
                tasks = listOf(TestPayloads.task(":app:compileJava", TaskOutcome.EXECUTED, 100, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":app:compileJava" to "REMOTE_HIT")),
            ),
        )
        // A malformed internalAdapters block (a JSON string, not an object) — must be skipped for the
        // origin rows, never fatal to the whole rollup; its own well-formed buildCache config snapshot
        // still counts toward buildsWithConfig (the two reads are independent, see the KDoc above).
        add(malformedInternalAdaptersPayload("malformed-1", recent + 400))
    }

    @Test
    fun `cacheRoi agrees byte-for-byte between stores`() {
        val pg = postgresStore.cacheRoi(projectId, days, now)
        val mem = inMemory.cacheRoi(projectId, days, now)
        assertEquals(mem, pg, "cacheRoi must agree byte-for-byte between stores")

        assertEquals(true, pg.remoteHitRateAvailable)
        assertEquals(63, pg.buildsWithConfig, "60 CI misses + ci-hit + local-1 + malformed-1's own config snapshot — never the excluded benchmark/old builds")
        val ci = pg.perMode.single { it.mode == "CI" }
        assertEquals(61, ci.consideredExecutions, "the benchmark build's MISS must not inflate the CI denominator; malformed-1 contributes no origin row at all")
        assertEquals(1, ci.remoteHits)
        val local = pg.perMode.single { it.mode == "LOCAL" }
        assertEquals(1, local.consideredExecutions, "the STORED task is excluded from the denominator")
        val candidate = pg.ciReuseCandidate ?: error("expected a near-zero CI reuse candidate")
        assertTrue(candidate.note.contains("investigate", ignoreCase = true), candidate.note)
    }

    @Test
    fun `a malformed internalAdapters block is skipped for origin rows, not fatal — its own buildCache config still counts`() {
        val pg = postgresStore.cacheRoi(projectId, days, now)
        assertEquals(inMemory.cacheRoi(projectId, days, now), pg, "parity holds with the malformed fixture in the window")
        // malformed-1 contributes no CacheRoiRow (cacheRoiRowsOf's runCatching guard-skips the "not-an-object"
        // block), so it does not appear in any perMode count — proven by the CI numbers matching the main
        // test exactly even though malformed-1 is itself a CI-mode build within the window.
        val ci = pg.perMode.single { it.mode == "CI" }
        assertEquals(61, ci.consideredExecutions, "malformed-1 must not contribute a phantom origin row")
        // Its environment.buildCache read is independent (cacheConfigRowOf never touches extensions), so
        // it still counts toward the config-snapshot summary.
        assertEquals(63, pg.buildsWithConfig, "malformed-1's own well-formed buildCache block must still count")
    }

    @Test
    fun `the window boundary excludes an older build until the window widens`() {
        val within30 = postgresStore.cacheRoi(projectId, 30, now)
        val within40 = postgresStore.cacheRoi(projectId, 40, now)
        assertEquals(inMemory.cacheRoi(projectId, 30, now), within30, "parity holds at the narrower window")
        assertEquals(inMemory.cacheRoi(projectId, 40, now), within40, "parity holds at the wider window")
        // old-1 (35 days back) is a CI REMOTE_HIT — its inclusion at 40 days raises the CI remote hit count.
        assertEquals(1, within30.perMode.single { it.mode == "CI" }.remoteHits, "old-1 excluded from a 30-day window")
        assertEquals(2, within40.perMode.single { it.mode == "CI" }.remoteHits, "old-1 included once the window widens past 35 days")
    }
}
