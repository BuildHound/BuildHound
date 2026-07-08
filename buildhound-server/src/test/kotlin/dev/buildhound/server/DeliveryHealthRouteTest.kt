package dev.buildhound.server

import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.server.connector.CiRun
import dev.buildhound.server.connector.CiRunStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-level tests for `GET /v1/rollups/delivery-health` (plan 059): auth/scope gating, tenant
 * isolation, the days clamp, the happy path (CFR + time-to-green + both rerun signals), the honest
 * connector-absent degradation (`connectorDataAvailable=false`, queue/share omitted), the ci_runs
 * enrichment fill (queue median + pipeline-wall retry pricing), the flaky-rerun candidate join
 * (plan 036), and the wire invisibility of the transient enrichment handoffs.
 */
class DeliveryHealthRouteTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String = "read-token") =
        client.get(path) { header("Authorization", "Bearer $token") }

    private val recent = System.currentTimeMillis() - 3_600_000

    /**
     * Seeds one cohort (main/android-ci) with a FAILED→SUCCESS recovery, a GHA `runAttempt=2` rerun,
     * a sequential same-key candidate rerun, and — via three test-carrying builds with an intra-run
     * retry on the rerun build — a plan-036 flaky record whose `affectedBuildIds` contains that rerun.
     */
    private fun seed(fx: Fx) {
        // Recovery episode: red at f-1, green again at s-1.
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "f-1", outcome = BuildOutcome.FAILED, startedAt = recent, durationMs = 60_000, sha = "abc"))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "s-1", startedAt = recent + 300_000, durationMs = 60_000, sha = "def"))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "s-2", startedAt = recent + 400_000, durationMs = 60_000, sha = "ghi"))

        // Authoritative GHA rerun (runAttempt=2) — no sha, so it chains solo.
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "gha-rerun", startedAt = recent + 500_000, durationMs = 60_000,
                provider = "github-actions", ciAttributes = mapOf("runAttempt" to "2"), branch = "main",
            ),
        )

        // Sequential same-key candidate: prior FAILED on sha rerun-sha, rerun starts after it finished.
        // The rerun build carries a failed-then-passed retry, so FlakyDetector's retry signal flags
        // com.example.CartTest with this build in affectedBuildIds (sampleCount needs 3 builds).
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "sk-fail", outcome = BuildOutcome.FAILED, startedAt = recent + 600_000, durationMs = 60_000,
                sha = "rerun-sha", tests = listOf(TestPayloads.testTask(className = "com.example.CartTest")),
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "sk-rerun", startedAt = recent + 700_000, durationMs = 120_000,
                sha = "rerun-sha",
                tests = listOf(TestPayloads.testTask(className = "com.example.CartTest", retriedCases = listOf("flakyCase()"))),
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "s-3", startedAt = recent + 800_000, durationMs = 60_000, sha = "jkl",
                tests = listOf(TestPayloads.testTask(className = "com.example.CartTest")),
            ),
        )
    }

    @Test
    fun `delivery-health needs a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rollups/delivery-health").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/rollups/delivery-health", token = "ingest-token").status)
    }

    @Test
    fun `days is clamped to 1-365 and defaults to 30`() = testApplication {
        val fx = fx(); appWith(fx)
        assertTrue(get("/v1/rollups/delivery-health?days=9999").bodyAsText().contains("\"period\":365"))
        assertTrue(get("/v1/rollups/delivery-health?days=0").bodyAsText().contains("\"period\":1"))
        assertTrue(get("/v1/rollups/delivery-health?days=abc").bodyAsText().contains("\"period\":30"))
    }

    @Test
    fun `happy path returns CFR, time-to-green, and the retry tax with both signals split`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/rollups/delivery-health?days=30").bodyAsText()

        assertTrue(body.contains("\"branch\":\"main\""), body)
        assertTrue(body.contains("\"pipelineName\":\"android-ci\""), body)
        assertTrue(body.contains("\"changeFailureRate\""), body)
        // Recovery: f-1 → s-1 and sk-fail → sk-rerun closed two episodes on main/android-ci.
        assertTrue(body.contains("\"recoveries\":2"), body)
        // Signal split: one authoritative GHA rerun, one same-key candidate.
        assertTrue(body.contains("\"runAttemptReruns\":1"), body)
        assertTrue(body.contains("\"sameKeyCandidates\":1"), body)
        assertTrue(body.contains("\"gha-rerun\""), body)
        assertTrue(body.contains("\"sk-rerun\""), body)
        assertTrue(body.contains("\"chainCount\":2"), body)
        // Wasted: 60s (gha-rerun) + 120s (sk-rerun) = 3.0 minutes, Gradle wall-clock lower bound.
        assertTrue(body.contains("\"wastedCiMinutesLowerBound\":3.0"), body)
        // The transient in-process enrichment handoffs must never reach the wire.
        assertTrue(!body.contains("enrichmentSamples"), body)
        assertTrue(!body.contains("wastedMsLowerBound"), body)
        assertTrue(!body.contains("rerunSamples"), body)
    }

    @Test
    fun `without ci_runs data the response degrades honestly - connector fields absent, never zeros`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/rollups/delivery-health?days=30").bodyAsText()
        assertTrue(body.contains("\"connectorDataAvailable\":false"), body)
        // BuildHoundJson omits nulls (explicitNulls=false): queue/share keys are absent, not 0.
        assertTrue(!body.contains("medianQueuedMs"), body)
        assertTrue(!body.contains("medianGradleSharePct"), body)
        // Build-duration lead time still renders.
        assertTrue(body.contains("\"medianDurationMs\""), body)
    }

    @Test
    fun `a seeded ci_runs row fills queue-share medians, upgrades retry pricing, and flips connectorDataAvailable`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        // Enrich the same-key rerun build: 42s queue, pipeline wall 300s vs its 120s Gradle wall.
        fx.stores.ciSpans.saveRun(
            fx.project.id, "sk-rerun", "azure-devops", "run-1",
            CiRun(queuedMs = 42_000, startedAt = recent + 700_000, finishedAt = recent + 1_000_000),
            CiRunStatus.OK,
        )
        val body = get("/v1/rollups/delivery-health?days=30").bodyAsText()
        assertTrue(body.contains("\"connectorDataAvailable\":true"), body)
        assertTrue(body.contains("\"medianQueuedMs\":42000"), body)
        assertTrue(body.contains("\"medianGradleSharePct\""), body)
        // Retry pricing upgraded for the enriched rerun: 60s (gha-rerun, Gradle wall) + 300s
        // (sk-rerun, pipeline wall) = 6.0 minutes — still labeled a lower bound.
        assertTrue(body.contains("\"wastedCiMinutesLowerBound\":6.0"), body)
    }

    @Test
    fun `flaky-rerun candidates populate when a rerun build id is in a flaky record's affected builds`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/rollups/delivery-health?days=30").bodyAsText()
        assertTrue(body.contains("\"flakyRerunTax\""), body)
        assertTrue(body.contains("\"className\":\"com.example.CartTest\""), body)
        assertTrue(body.contains("\"rerunBuildCount\":1"), body)
    }

    @Test
    fun `delivery-health is tenant-scoped - a foreign token sees none of it`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        val body = get("/v1/rollups/delivery-health?days=30", token = "other-token").bodyAsText()
        assertTrue(body.contains("\"changeFailureRate\":[]"), body)
        assertTrue(body.contains("\"chainCount\":0"), body)
        assertTrue(!body.contains("sk-rerun"), "a foreign token must never see another tenant's build ids: $body")
    }
}
