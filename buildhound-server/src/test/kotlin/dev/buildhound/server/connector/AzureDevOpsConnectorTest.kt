package dev.buildhound.server.connector

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzureDevOpsConnectorTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val buildJson = """
        {
          "id": 42,
          "status": "completed",
          "result": "succeeded",
          "queueTime": "2024-01-01T10:00:00Z",
          "startTime": "2024-01-01T10:00:05Z",
          "finishTime": "2024-01-01T10:05:00Z"
        }
    """.trimIndent()

    private val timelineJson = """
        {
          "records": [
            {"id":"r1","type":"Stage","name":"Build","startTime":"2024-01-01T10:00:05Z","finishTime":"2024-01-01T10:03:00Z","result":"succeeded","workerName":"agent-7"},
            {"id":"r2","parentId":"r1","type":"Task","name":"Compile","startTime":"2024-01-01T10:00:06Z","finishTime":"2024-01-01T10:02:00Z","result":"failed"},
            {"id":"r3","parentId":"r1","type":"Task","name":"Skipped","result":"skipped"}
          ]
        }
    """.trimIndent()

    /** Routes by path: `…/timeline` → timeline JSON, otherwise the build JSON. */
    private fun connector(): Pair<AzureDevOpsConnector, MockEngine> {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/timeline")) {
                respond(timelineJson, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond(buildJson, HttpStatusCode.OK, jsonHeaders)
            }
        }
        return AzureDevOpsConnector(ConnectorHttpClient.create(engine)) to engine
    }

    private val config = ConnectorConfig(
        baseUrl = "https://dev.azure.com/myorg",
        project = "proj",
        credential = Credential.Pat("s3cret"),
        allowedHosts = setOf("dev.azure.com"),
    )
    private val ref = CiRunRef(provider = "azure-devops", runId = "42")

    @Test
    fun `fetchRun maps build timing and timeline records to spans`() = runBlocking {
        val (azure, _) = connector()
        val run = azure.fetchRun(ref, config)!!

        assertEquals(5_000L, run.queuedMs) // 10:00:05 - 10:00:00
        assertEquals(3, run.spans.size)

        val stage = run.spans.single { it.id == "r1" }
        assertEquals(SpanKind.STAGE, stage.kind)
        assertEquals(SpanResult.SUCCEEDED, stage.result)
        assertEquals("agent-7", stage.workerName)

        val task = run.spans.single { it.id == "r2" }
        assertEquals(SpanKind.STEP, task.kind)
        assertEquals(SpanResult.FAILED, task.result)
        assertEquals("r1", task.parentId)

        assertEquals(SpanResult.SKIPPED, run.spans.single { it.id == "r3" }.result)
    }

    @Test
    fun `fetchRun sends PAT as basic auth with empty username`() = runBlocking {
        val (azure, engine) = connector()
        azure.fetchRun(ref, config)

        val expected = "Basic " + Base64.getEncoder().encodeToString(":s3cret".encodeToByteArray())
        assertTrue(engine.requestHistory.isNotEmpty())
        engine.requestHistory.forEach { req ->
            assertEquals(expected, req.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `fetchRun refuses a host outside the allowlist and makes no request`() = runBlocking {
        val (azure, engine) = connector()
        val run = azure.fetchRun(ref, config.copy(allowedHosts = emptySet()))
        assertNull(run)
        assertTrue(engine.requestHistory.isEmpty(), "SSRF guard must short-circuit before any outbound call")
    }

    @Test
    fun `fetchRun returns null when a completed build's timeline fetch fails`() = runBlocking {
        // Build endpoint says finished, timeline 500s every retry → returning a finished CiRun with no
        // spans would store a permanent empty OK; instead fetchRun returns null so the poll retries.
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/timeline")) respond("oops", HttpStatusCode.InternalServerError, jsonHeaders)
            else respond(buildJson, HttpStatusCode.OK, jsonHeaders)
        }
        val azure = AzureDevOpsConnector(ConnectorHttpClient.create(engine))
        assertNull(azure.fetchRun(ref, config))
    }

    @Test
    fun `fetchRun rejects a userinfo-smuggled host outside the allowlist`() = runBlocking {
        val (azure, engine) = connector()
        // The real host is evil.example; dev.azure.com is only the userinfo. java.net.URI resolves
        // host = evil.example, so the allowlist rejects it and no request (with the PAT) is made.
        val run = azure.fetchRun(
            ref,
            config.copy(baseUrl = "https://dev.azure.com@evil.example/myorg", allowedHosts = setOf("dev.azure.com")),
        )
        assertNull(run)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun refuses a non-numeric run id and makes no request`() = runBlocking {
        val (azure, engine) = connector()
        // runId is ingest-supplied; a non-numeric value could smuggle an extra path/query segment into
        // the outbound URL (a path pivot on the allowlisted host, authorized by the PAT). Reject first.
        val run = azure.fetchRun(ref.copy(runId = "42/logs/1?api-version=7.1&x="), config)
        assertNull(run)
        assertTrue(engine.requestHistory.isEmpty(), "must short-circuit before any outbound call")
    }

    @Test
    fun `fetchRun refuses a non-https base url`() = runBlocking {
        val (azure, engine) = connector()
        val run = azure.fetchRun(
            ref,
            config.copy(baseUrl = "http://dev.azure.com/myorg", allowedHosts = setOf("dev.azure.com")),
        )
        assertNull(run)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun returns null without a credential`() = runBlocking {
        val (azure, engine) = connector()
        assertNull(azure.fetchRun(ref, config.copy(credential = null)))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `buildLink returns an https results url`() {
        val (azure, _) = connector()
        assertEquals(
            "https://dev.azure.com/myorg/proj/_build/results?buildId=42",
            azure.buildLink(ref, config),
        )
    }

    @Test
    fun `parseWebhook accepts build_complete and pulls the run id`() {
        val (azure, _) = connector()
        val body = """{"eventType":"build.complete","resource":{"id":99}}"""
        val event = azure.parseWebhook(emptyMap(), body, config)
        assertTrue(event is CiEvent.RunCompleted)
        assertEquals("99", event.ref.runId)
    }

    @Test
    fun `parseWebhook ignores other event types`() {
        val (azure, _) = connector()
        assertNull(azure.parseWebhook(emptyMap(), """{"eventType":"build.started"}""", config))
        assertNull(azure.parseWebhook(emptyMap(), "not json", config))
    }

    @Test
    fun `refFrom parses collection and project from an ingested build url`() {
        val (azure, _) = connector()
        val parsed = azure.refFrom("azure-devops", "42", "https://dev.azure.com/myorg/proj/_build/results?buildId=42")
        assertEquals("https://dev.azure.com/myorg", parsed.collectionUri)
        assertEquals("proj", parsed.project)
    }

    @Test
    fun `refFrom leaves collection null for a non-azure url`() {
        val (azure, _) = connector()
        val parsed = azure.refFrom("azure-devops", "42", "https://example.com/whatever")
        assertNull(parsed.collectionUri)
        assertNull(parsed.project)
    }
}
