package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hot-column string bounds (plan 078): every payload string feeding a btree index is clamped at
 * storage (`boundForStorage`), so a hostile ingest can never mint a 54000 poison pill; route-level
 * validation rejects over-long filter/correlation values. Postgres acceptance + parity live in
 * PostgresStoresIntegrationTest; this covers the ingest→read path and the evaluator key consistency.
 */
class HotColumnBoundsTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("all-token"), TokenScope.ALL)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) =
        application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String) =
        client.get(path) { header("Authorization", "Bearer all-token") }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String) =
        client.post(path) {
            header("Authorization", "Bearer all-token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private val recent = System.currentTimeMillis() - 3_600_000

    /** A payload with a 3000-char value in every clamped field (plan 078 inventory). */
    private fun hostilePayload(): BuildPayload = TestPayloads.build(
        buildId = "b".repeat(3000),
        startedAt = recent,
        projectKey = "p".repeat(3000),
        branch = "B".repeat(3000),
        sha = "s".repeat(3000),
        provider = "P".repeat(3000),
        runId = "r".repeat(3000),
        pipelineName = "L".repeat(3000),
        tasks = listOf(
            TaskExecution(
                path = ":app:x", module = "m".repeat(3000), type = "T".repeat(3000),
                startMs = 0, durationMs = 1000, outcome = TaskOutcome.EXECUTED,
            ),
        ),
        tests = listOf(TestPayloads.testTask(module = "M".repeat(3000), className = "C".repeat(3000))),
    )

    @Test
    fun `a hostile ingest is accepted, stored clamped, and every read survives`() = testApplication {
        val fx = fx(); appWith(fx)
        val ingest = postJson(
            "/v1/builds",
            BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), hostilePayload()),
        )
        assertEquals(HttpStatusCode.Accepted, ingest.status, ingest.bodyAsText())

        // List: the summary carries the clamped buildId/branch/projectKey (256), never the raw 3000.
        val list = get("/v1/builds").bodyAsText()
        assertTrue(list.contains("b".repeat(256)), "clamped buildId listed")
        assertFalse(list.contains("b".repeat(257)), "raw buildId never stored")
        assertTrue(list.contains("B".repeat(256)), "clamped branch listed")
        assertTrue(list.contains("p".repeat(256)), "clamped projectKey listed")

        // Detail under the clamped id: the stored payload itself carries the clamped strings.
        val detail = get("/v1/builds/${"b".repeat(256)}")
        assertEquals(HttpStatusCode.OK, detail.status, "the build lives under its clamped id")
        val stored = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), detail.bodyAsText())
        assertEquals(256, stored.ci?.provider?.length)
        assertEquals(256, stored.ci?.runId?.length)
        assertEquals(256, stored.ci?.pipelineName?.length)
        assertEquals(64, stored.vcs?.sha?.length)
        assertEquals(256, stored.tasks.single().module?.length)
        assertEquals(512, stored.tasks.single().type?.length)
        assertEquals(256, stored.tests.single().module?.length)
        assertEquals(512, stored.tests.single().classes.single().className.length)

        // Aggregations over the clamped fields don't error.
        assertEquals(HttpStatusCode.OK, get("/v1/flaky").status)
        val rollup = get("/v1/rollups/task-duration")
        assertEquals(HttpStatusCode.OK, rollup.status)
        assertTrue(rollup.bodyAsText().contains("T".repeat(512)), "the clamped type ranks in the rollup")
    }

    @Test
    fun `a metrics correlation with a 3000-char provider is rejected 422`() = testApplication {
        val fx = fx(); appWith(fx)
        val response = postJson(
            "/v1/metrics",
            """{"correlation":{"provider":"${"P".repeat(3000)}","runId":"run-1"},"scope":"build","name":"m","value":1.0}""",
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("provider"), response.bodyAsText())
    }

    @Test
    fun `an over-long branch filter param is a 400`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/builds?branch=${"x".repeat(257)}").status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/trends?branch=${"x".repeat(257)}").status)
        // 256 is the boundary and stays valid (matches the stored clamp, so it can still match rows).
        assertEquals(HttpStatusCode.OK, get("/v1/builds?branch=${"x".repeat(256)}").status)
    }

    @Test
    fun `an oversized pipelineName build evaluates with the clamped baseline key`() {
        // The evaluator receives the same bounded instance the store persisted (ingest passes
        // `bounded` to both), so the baseline key is computed from the clamped pipelineName.
        val builds = InMemoryBuildStore()
        val verdicts = InMemoryVerdictStore()
        val evaluator = VerdictEvaluator(
            builds = builds, metrics = InMemoryMetricStore(), verdicts = verdicts,
            settings = InMemorySettingsStore(), alerts = RecordingAlertDispatcher(), dashboardBaseUrl = null,
        )
        val bounded = boundForStorage(TestPayloads.build(buildId = "v-1", pipelineName = "L".repeat(3000)))
        builds.save("p", bounded)
        evaluator.evaluate("p", "pilot", bounded)
        val verdict = verdicts.find("p", "v-1")
        assertNotNull(verdict, "evaluation completed and persisted (nothing thrown into the runCatching)")
        assertTrue(verdict.baselineKey.startsWith("L".repeat(256) + "|"), "baseline key uses the clamped pipelineName")
        assertFalse(verdict.baselineKey.contains("L".repeat(257)), "never the raw value")
    }
}
