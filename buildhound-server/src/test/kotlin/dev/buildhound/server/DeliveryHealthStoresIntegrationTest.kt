package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.server.connector.CiRun
import dev.buildhound.server.connector.CiRunStatus
import dev.buildhound.server.connector.PostgresCiSpanStore
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
 * Real-Postgres checks for plan 059: `deliveryHealth` agrees **byte-for-byte** between the in-memory
 * and Postgres stores over identical fixtures — multi-branch builds incl. a FAILED→SUCCESS recovery,
 * a GHA `runAttempt=2` rerun, a sequential same-key rerun, a concurrent same-sha matrix pair
 * (overlapping times) that must NOT count as reruns, a garbage `runAttempt` attribute (degrades to
 * null, never a throw), and a benchmark build both stores must exclude (the plan-032 fleet-view
 * convention). Plus: a seeded `ci_runs` row (real [PostgresCiSpanStore]) fills `queuedMs` through the
 * route-layer [enrichDeliveryHealth] against the Postgres store. Docker-gated. No migration — the
 * plan-059 default; `builds_project_started_idx` covers the window scan.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryHealthStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var inMemory: InMemoryBuildStore
    private lateinit var ciSpans: PostgresCiSpanStore
    private lateinit var projectId: String

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        ciSpans = PostgresCiSpanStore(dataSource)
        val tokens = PostgresTokenStore(dataSource)
        projectId = tokens.ensureProjectWithToken("delivery-health", sha256Hex("dh"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        // main: a FAILED→SUCCESS recovery episode (red at recent+60s, green at recent+360s).
        TestPayloads.build(buildId = "main-fail", outcome = BuildOutcome.FAILED, startedAt = recent, durationMs = 60_000, sha = "aaa", projectKey = "pilot"),
        TestPayloads.build(buildId = "main-green", startedAt = recent + 300_000, durationMs = 60_000, sha = "bbb", projectKey = "pilot"),
        TestPayloads.build(buildId = "main-green-2", startedAt = recent + 400_000, durationMs = 60_000, sha = "ccc", projectKey = "pilot"),
        // release branch: still red at window end — an open episode, not a recovery.
        TestPayloads.build(buildId = "rel-fail", outcome = BuildOutcome.FAILED, branch = "release", startedAt = recent + 100_000, durationMs = 60_000, sha = "ddd", projectKey = "pilot"),
        // A GHA rerun: authoritative runAttempt=2.
        TestPayloads.build(
            buildId = "gha-rerun", startedAt = recent + 500_000, durationMs = 60_000,
            provider = "github-actions", ciAttributes = mapOf("runAttempt" to "2"), projectKey = "pilot",
        ),
        // A garbage runAttempt attribute must degrade to null (no rerun, no throw) in BOTH stores.
        TestPayloads.build(
            buildId = "garbage-attempt", startedAt = recent + 550_000, durationMs = 60_000,
            provider = "github-actions", ciAttributes = mapOf("runAttempt" to "not-a-number"), projectKey = "pilot",
        ),
        // Sequential same-key rerun: prior FAILED on sha eee, rerun starts strictly after it finished.
        TestPayloads.build(buildId = "sk-fail", outcome = BuildOutcome.FAILED, startedAt = recent + 600_000, durationMs = 60_000, sha = "eee", projectKey = "pilot"),
        TestPayloads.build(buildId = "sk-rerun", startedAt = recent + 700_000, durationMs = 120_000, sha = "eee", projectKey = "pilot"),
        // Concurrent same-sha matrix pair: leg-b starts while leg-a (FAILED) is still running —
        // overlapping, must NOT count as a rerun in either store.
        TestPayloads.build(buildId = "leg-a", outcome = BuildOutcome.FAILED, branch = "matrix", startedAt = recent + 800_000, durationMs = 100_000, sha = "fff", projectKey = "pilot"),
        TestPayloads.build(buildId = "leg-b", branch = "matrix", startedAt = recent + 850_000, durationMs = 100_000, sha = "fff", projectKey = "pilot"),
        // An INTERRUPTED build: excluded from CFR and the episode walk (plan 033).
        TestPayloads.build(buildId = "int-1", outcome = BuildOutcome.INTERRUPTED, startedAt = recent + 900_000, durationMs = 0, projectKey = "pilot"),
        // A benchmark build (FAILED!) — both stores must exclude it, or CFR/retry-tax would skew.
        TestPayloads.build(buildId = "bench-1", outcome = BuildOutcome.FAILED, mode = BuildMode.BENCHMARK, startedAt = recent + 950_000, durationMs = 60_000, sha = "aaa", projectKey = "pilot"),
    )

    @Test
    fun `deliveryHealth agrees byte-for-byte between stores`() {
        val pg = postgresStore.deliveryHealth(projectId, 30, now)
        val mem = inMemory.deliveryHealth(projectId, 30, now)
        // Data-class equality covers every family INCLUDING the transient enrichment samples both
        // stores must derive identically (the plan-026/032 parity discipline).
        assertEquals(mem, pg, "deliveryHealth must agree byte-for-byte between stores")
    }

    @Test
    fun `the rerun signals and their guards hold on real Postgres`() {
        val tax = postgresStore.deliveryHealth(projectId, 30, now).retryTax
        assertEquals(1, tax.runAttemptReruns, "gha-rerun (runAttempt=2) is the one authoritative rerun: $tax")
        assertEquals(1, tax.sameKeyCandidates, "sk-rerun is the one sequential same-key candidate: $tax")
        assertTrue("sk-rerun" in tax.rerunBuildIds, "$tax")
        assertTrue("gha-rerun" in tax.rerunBuildIds, "$tax")
        assertTrue("leg-b" !in tax.rerunBuildIds, "the concurrent matrix leg must never count as a rerun: $tax")
        assertTrue("garbage-attempt" !in tax.rerunBuildIds, "a garbage runAttempt must degrade to null, not a rerun: $tax")
        assertEquals(2, tax.chainCount, "$tax")
    }

    @Test
    fun `benchmark and INTERRUPTED builds are excluded from CFR in both stores`() {
        val pg = postgresStore.deliveryHealth(projectId, 30, now)
        val main = pg.changeFailureRate.single { it.branch == "main" && it.pipelineName == "android-ci" }
        // main/android-ci finished builds: main-fail, main-green, main-green-2, gha-rerun,
        // garbage-attempt, sk-fail, sk-rerun = 2 FAILED / 5 SUCCESS. bench-1 (FAILED, benchmark) and
        // int-1 (INTERRUPTED) must not move either count.
        assertEquals(2, main.failed, "$main")
        assertEquals(5, main.succeeded, "$main")
    }

    @Test
    fun `an open episode on release is counted separately, never as a recovery`() {
        val pg = postgresStore.deliveryHealth(projectId, 30, now)
        val release = pg.timeToGreen.single { it.branch == "release" }
        assertEquals(0, release.recoveries)
        assertTrue(release.openEpisode)
        val main = pg.timeToGreen.single { it.branch == "main" }
        assertEquals(2, main.recoveries, "main-fail→main-green and sk-fail→sk-rerun both closed: $main")
    }

    @Test
    fun `a seeded ci_runs row fills queuedMs and upgrades retry pricing at the route layer`() {
        // Real ci_runs row for the same-key rerun: 42s queue, 300s pipeline wall vs 120s Gradle wall.
        ciSpans.saveRun(
            projectId, "sk-rerun", "azure-devops", "run-1",
            CiRun(queuedMs = 42_000, startedAt = recent + 700_000, finishedAt = recent + 1_000_000),
            CiRunStatus.OK,
        )
        val core = postgresStore.deliveryHealth(projectId, 30, now)
        assertEquals(false, core.connectorDataAvailable, "the store core must stay build-only")

        val enriched = enrichDeliveryHealth(core, projectId, 30, postgresStore, ciSpans, now)
        assertTrue(enriched.connectorDataAvailable, "a found ci_runs row must flip connectorDataAvailable")
        val main = enriched.leadTime.single { it.branch == "main" && it.pipelineName == "android-ci" }
        assertEquals(42_000L, main.medianQueuedMs)
        assertEquals(0.4, main.medianGradleSharePct, "120s Gradle wall of a 300s pipeline")
        // 60s (gha-rerun, Gradle wall) + 300s (sk-rerun, upgraded to pipeline wall) = 6.0 min.
        assertEquals(6.0, enriched.retryTax.wastedCiMinutesLowerBound)
    }

    @Test
    fun `deliveryHealth is tenant-scoped on real Postgres`() {
        val foreign = PostgresTokenStore(dataSource).ensureProjectWithToken("delivery-foreign", sha256Hex("dhf"), TokenScope.ALL).id
        val rollup = postgresStore.deliveryHealth(foreign, 30, now)
        assertTrue(rollup.changeFailureRate.isEmpty())
        assertEquals(0, rollup.retryTax.chainCount)
    }
}
