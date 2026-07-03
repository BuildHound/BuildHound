package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegressionRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef, val alerts: RecordingAlertDispatcher)

    private fun fx(): Fx {
        val alerts = RecordingAlertDispatcher()
        val stores = ServerStores(
            builds = InMemoryBuildStore(),
            tokens = InMemoryTokenStore(),
            metrics = InMemoryMetricStore(),
            verdicts = InMemoryVerdictStore(),
            settings = InMemorySettingsStore(),
            alerts = alerts,
            dashboardBaseUrl = "https://dash.example.com",
        )
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("all-token"), TokenScope.ALL)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project, alerts)
    }

    // Rate limits off: these tests post many metrics/builds and are not exercising throttling.
    private fun ApplicationTestBuilder.appWith(fx: Fx) =
        application { buildHoundModule(fx.stores, RateLimits(ingestPerMinute = 0, queryPerMinute = 0, perHostPerMinute = 0)) }

    private fun body(payload: BuildPayload) = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)

    private suspend fun ApplicationTestBuilder.ingest(token: String, payload: BuildPayload) =
        client.post("/v1/builds") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body(payload))
        }

    private suspend fun ApplicationTestBuilder.metric(token: String, json: String) =
        client.post("/v1/metrics") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    // ---- metrics ----

    @Test
    fun `metrics endpoint requires an ingest-capable token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/metrics").status)
        assertEquals(HttpStatusCode.Forbidden, metric("read-token", """{"correlation":{"buildId":"b"},"scope":"s","name":"n","value":1}""").status)
    }

    @Test
    fun `a metric correlates to a build by provider and run id`() = testApplication {
        val fx = fx(); appWith(fx)
        ingest("ingest-token", TestPayloads.build(buildId = "run-build", provider = "azure-devops", runId = "run-42"))

        val response = metric("ingest-token", """{"correlation":{"provider":"azure-devops","runId":"run-42"},"scope":"apk","name":"size","value":12.5,"unit":"MB"}""")
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("run-build"), response.bodyAsText())
        assertEquals(1, fx.stores.metrics.forBuild(fx.project.id, "run-build").size)
    }

    @Test
    fun `over-cap metric submissions are rejected 422`() = testApplication {
        val fx = fx(); appWith(fx)
        val longName = "n".repeat(MAX_METRIC_NAME_CHARS + 1)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            metric("ingest-token", """{"correlation":{"buildId":"b"},"scope":"s","name":"$longName","value":1}""").status,
        )
        // The 101st distinct measure for one run is rejected; the first 100 are accepted.
        repeat(MAX_METRICS_PER_RUN) { i ->
            assertEquals(HttpStatusCode.Accepted, metric("ingest-token", """{"correlation":{"buildId":"cap"},"scope":"s","name":"m$i","value":1}""").status)
        }
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            metric("ingest-token", """{"correlation":{"buildId":"cap"},"scope":"s","name":"overflow","value":1}""").status,
        )
    }

    @Test
    fun `re-posting the same measure is idempotent`() = testApplication {
        val fx = fx(); appWith(fx)
        val json = """{"correlation":{"buildId":"b"},"scope":"s","name":"dup","value":1}"""
        assertEquals(HttpStatusCode.Accepted, metric("ingest-token", json).status)
        assertEquals(HttpStatusCode.Accepted, metric("ingest-token", json).status)
        assertEquals(1, fx.stores.metrics.forBuild(fx.project.id, "b").size)
    }

    // ---- verdict ----

    @Test
    fun `a cold baseline key yields INSUFFICIENT_DATA, never FAIL`() = testApplication {
        val fx = fx(); appWith(fx)
        ingest("ingest-token", TestPayloads.build(buildId = "cold", durationMs = 1000))
        val verdict = client.get("/v1/builds/cold/verdict") { header("Authorization", "Bearer read-token") }
        assertEquals(HttpStatusCode.OK, verdict.status)
        assertTrue(verdict.bodyAsText().contains("INSUFFICIENT_DATA"), verdict.bodyAsText())
    }

    @Test
    fun `a deliberately slowed build is flagged FAIL against its baseline`() = testApplication {
        val fx = fx(); appWith(fx)
        // Seed a low-noise baseline on the default branch, then ingest a far-slower build.
        repeat(6) { i -> ingest("ingest-token", TestPayloads.build(buildId = "base-$i", durationMs = 1000, startedAt = 1_000_000L + i * 1000)) }
        ingest("ingest-token", TestPayloads.build(buildId = "slow", durationMs = 60_000, startedAt = 2_000_000L))

        val verdict = client.get("/v1/builds/slow/verdict") { header("Authorization", "Bearer read-token") }.bodyAsText()
        assertTrue(verdict.contains("\"status\":\"FAIL\""), verdict)
        assertTrue(verdict.contains("durationMs"), verdict)
    }

    @Test
    fun `a robust z-score regression is flagged end-to-end with a non-null z and an evaluatedAt`() = testApplication {
        val fx = fx(); appWith(fx)
        // A spread baseline (non-zero MAD) so the verdict comes from the robust-z rule, not the
        // zero-MAD fallback — exercises the baseline SQL → z-score → persisted FAIL wiring.
        val durations = listOf(900L, 950L, 1000L, 1050L, 1100L, 1000L)
        durations.forEachIndexed { i, d -> ingest("ingest-token", TestPayloads.build(buildId = "sp-$i", durationMs = d, startedAt = 1_000_000L + i * 1000)) }
        ingest("ingest-token", TestPayloads.build(buildId = "z-slow", durationMs = 5000, startedAt = 2_000_000L))

        val verdict = client.get("/v1/builds/z-slow/verdict") { header("Authorization", "Bearer read-token") }.bodyAsText()
        assertTrue(verdict.contains("\"status\":\"FAIL\""), verdict)
        assertTrue(verdict.contains("\"z\":"), "the robust z-score must be populated (not the zero-MAD fallback): $verdict")
        assertTrue(verdict.contains("\"evaluatedAt\":"), "evaluatedAt must be present in dev/in-memory too: $verdict")
    }

    @Test
    fun `verdict is read-scoped and 404s an unknown build`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/builds/x/verdict").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/builds/no-such/verdict") { header("Authorization", "Bearer read-token") }.status,
        )
    }

    // ---- settings ----

    @Test
    fun `settings default, round-trip, and writing needs the all-scope token`() = testApplication {
        val fx = fx(); appWith(fx)
        val defaults = client.get("/v1/settings") { header("Authorization", "Bearer read-token") }
        assertEquals(HttpStatusCode.OK, defaults.status)
        assertTrue(defaults.bodyAsText().contains("\"baselineN\":20"))

        val putJson = """{"baselineN":15,"defaultBranch":"main","warnZ":3.0,"failZ":6.0,"budgets":{"durationMs":5000.0},"alertChannels":[]}"""
        assertEquals(
            HttpStatusCode.Forbidden,
            client.put("/v1/settings") { header("Authorization", "Bearer read-token"); contentType(ContentType.Application.Json); setBody(putJson) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.put("/v1/settings") { header("Authorization", "Bearer all-token"); contentType(ContentType.Application.Json); setBody(putJson) }.status,
        )
        val after = client.get("/v1/settings") { header("Authorization", "Bearer read-token") }.bodyAsText()
        assertTrue(after.contains("\"baselineN\":15"), after)
    }

    @Test
    fun `invalid settings are rejected 422`() = testApplication {
        val fx = fx(); appWith(fx)
        val bad = """{"baselineN":1,"defaultBranch":"main","warnZ":3.5,"failZ":5.0}"""
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.put("/v1/settings") { header("Authorization", "Bearer all-token"); contentType(ContentType.Application.Json); setBody(bad) }.status,
        )
    }

    // ---- alerts (de-dup via the evaluator) ----

    @Test
    fun `a fresh FAIL dispatches one alert and a repeat FAIL does not re-spam`() = testApplication {
        val fx = fx(); appWith(fx)
        val settings = """{"baselineN":20,"defaultBranch":"main","warnZ":3.5,"failZ":5.0,"budgets":{},"alertChannels":[{"kind":"slack","url":"https://hooks.slack.com/services/x"}]}"""
        client.put("/v1/settings") { header("Authorization", "Bearer all-token"); contentType(ContentType.Application.Json); setBody(settings) }

        repeat(6) { i -> ingest("ingest-token", TestPayloads.build(buildId = "b-$i", durationMs = 1000, startedAt = 1_000_000L + i * 1000)) }
        ingest("ingest-token", TestPayloads.build(buildId = "slow-1", durationMs = 60_000, startedAt = 2_000_000L))
        assertEquals(1, fx.alerts.sent.size, "a fresh FAIL dispatches exactly one alert")

        ingest("ingest-token", TestPayloads.build(buildId = "slow-2", durationMs = 61_000, startedAt = 2_001_000L))
        assertEquals(1, fx.alerts.sent.size, "a repeat FAIL for the same key must not re-spam")
    }
}
