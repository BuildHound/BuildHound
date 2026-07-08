package dev.buildhound.server

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
 * Real-Postgres checks for plan 068: `cacheMissDiagnostics` reads the whole `builds.payload` jsonb
 * column (origin/fingerprints live only there, never in `task_executions`) and agrees byte-for-byte
 * between the in-memory and Postgres stores over fixtures covering both detectors together, a
 * benchmark build (fleet-view exclusion, cf. [WarningStoresIntegrationTest]), a build outside the
 * window (boundary exclusion), and a malformed `internalAdapters` block (guarded skip, never fatal).
 * Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheMissDiagnosticsStoresIntegrationTest {

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
        projectId = tokens.ensureProjectWithToken("cache-miss-diagnostics", sha256Hex("cmd"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun malformedInternalAdaptersPayload(buildId: String, startedAt: Long, hostnameHash: String): BuildPayload =
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
              "environment": {"hostnameHash": "$hostnameHash"},
              "tasks": [{"path": ":app:compileDebugKotlin", "module": ":app", "startMs": 0, "durationMs": 100, "outcome": "EXECUTED", "cacheable": true}],
              "extensions": {"internalAdapters": "not-an-object"},
              "fingerprints": {"build": {"env-GITHUB_TOKEN": "deadbeef"}}
            }
            """.trimIndent(),
        )

    private fun fixtures(): List<BuildPayload> = listOf(
        // Detector 1 (relocatability): :app:compileDebugKotlin STORED on 2 distinct hosts, 0 REMOTE_HIT.
        TestPayloads.build(
            buildId = "b1", durationMs = 400, startedAt = recent, hostnameHash = "h1",
            tasks = listOf(TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 400, cacheable = true)),
            extensions = TestPayloads.internalAdapters(listOf(":app:compileDebugKotlin" to "STORED")),
            fingerprints = TestPayloads.fingerprints(mapOf("env-GITHUB_TOKEN" to "aaaa1111")),
        ),
        TestPayloads.build(
            buildId = "b2", durationMs = 600, startedAt = recent + 1000, hostnameHash = "h2",
            tasks = listOf(TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 600, cacheable = true)),
            extensions = TestPayloads.internalAdapters(listOf(":app:compileDebugKotlin" to "STORED")),
            fingerprints = TestPayloads.fingerprints(mapOf("env-GITHUB_TOKEN" to "bbbb2222")),
        ),
        // A second h1 build: satisfies both the fleet gate (a REMOTE_HIT exists) and detector 2's
        // MIN_STREAM (h1 now carries 2 builds) — env-GITHUB_TOKEN changes aaaa1111 -> cccc3333 on h1.
        TestPayloads.build(
            buildId = "b3", durationMs = 50, startedAt = recent + 2000, hostnameHash = "h1",
            tasks = listOf(TestPayloads.task(":lib:test", TaskOutcome.EXECUTED, 50, cacheable = true)),
            extensions = TestPayloads.internalAdapters(listOf(":lib:test" to "REMOTE_HIT")),
            fingerprints = TestPayloads.fingerprints(mapOf("env-GITHUB_TOKEN" to "cccc3333")),
        ),
        // A benchmark build carrying the same non-relocatable shape — both stores must exclude it.
        TestPayloads.build(
            buildId = "bench-1", durationMs = 999, startedAt = recent + 3000, mode = BuildMode.BENCHMARK, hostnameHash = "h3",
            tasks = listOf(TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 999, cacheable = true)),
            extensions = TestPayloads.internalAdapters(listOf(":app:compileDebugKotlin" to "STORED")),
        ),
        // A build well outside the window — must not count toward either detector.
        TestPayloads.build(
            buildId = "old-1", durationMs = 100, startedAt = now - (days + 5).toLong() * 86_400_000, hostnameHash = "h4",
            tasks = listOf(TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 100, cacheable = true)),
            extensions = TestPayloads.internalAdapters(listOf(":app:compileDebugKotlin" to "STORED")),
        ),
        // A malformed internalAdapters block (a JSON string, not an object) — must be skipped for
        // detector 1, never fatal to the whole rollup; its fingerprints block still feeds detector 2.
        malformedInternalAdaptersPayload("malformed-1", recent + 4000, "h1"),
    )

    @Test
    fun `cacheMissDiagnostics agrees byte-for-byte between stores across both detectors`() {
        val pg = postgresStore.cacheMissDiagnostics(projectId, days, now)
        val mem = inMemory.cacheMissDiagnostics(projectId, days, now)
        assertEquals(mem, pg, "cacheMissDiagnostics must agree byte-for-byte between stores")

        assertTrue(pg.remoteCacheObserved, "b3's REMOTE_HIT must satisfy the fleet gate")
        val candidate = pg.nonRelocatable.single()
        assertEquals(":app:compileDebugKotlin", candidate.taskPath)
        assertEquals(2, candidate.crossHostCount, "the benchmark/old/malformed builds' h3/h4 hosts must not count")
        assertEquals(1000L, candidate.wastedMs, "only b1(400)+b2(600) — never the benchmark build's 999ms")

        val volatileKey = pg.volatileInputs.single { it.key == "env-GITHUB_TOKEN" }
        assertEquals(1.0, volatileKey.volatility, "h1's stream (b1 -> b3, ignoring the malformed build's own extension issue) changed every build")
        assertEquals(1, volatileKey.contributingStreams, "h2 has only one build in the window — excluded by MIN_STREAM")
    }

    @Test
    fun `the malformed internalAdapters block is skipped, not fatal — the rest of the fixture set is unaffected`() {
        val pg = postgresStore.cacheMissDiagnostics(projectId, days, now)
        // malformed-1 contributes no relocatability row of its own, but its fingerprints DO feed
        // detector 2 (the two decode paths are independent) — proven by contributingStreams staying 1
        // for h1 (b1+b3+malformed-1 are all on h1, still one stream) rather than throwing.
        assertEquals(2, pg.nonRelocatable.single().crossHostCount, "malformed-1 must not manufacture a phantom third host")
    }

    @Test
    fun `the window boundary excludes an older build until the window widens enough to include it`() {
        val within30Days = postgresStore.cacheMissDiagnostics(projectId, 30, now)
        val within40Days = postgresStore.cacheMissDiagnostics(projectId, 40, now)
        assertEquals(inMemory.cacheMissDiagnostics(projectId, 30, now), within30Days, "byte-for-byte parity holds at the narrower window too")
        assertEquals(inMemory.cacheMissDiagnostics(projectId, 40, now), within40Days, "byte-for-byte parity holds at the wider window too")

        assertEquals(2, within30Days.nonRelocatable.single().crossHostCount, "old-1 (35 days back) must be excluded from a 30-day window")
        assertEquals(3, within40Days.nonRelocatable.single().crossHostCount, "old-1 must be included once the window widens past 35 days")
    }
}
