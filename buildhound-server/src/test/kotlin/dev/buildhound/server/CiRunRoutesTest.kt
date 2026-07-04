package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.server.connector.AzureDevOpsConnector
import dev.buildhound.server.connector.CiSpanStore
import dev.buildhound.server.connector.ConnectorEnricher
import dev.buildhound.server.connector.ConnectorHttpClient
import dev.buildhound.server.connector.ConnectorRegistry
import dev.buildhound.server.connector.EnrichmentQueue
import dev.buildhound.server.connector.EnvConnectorConfigStore
import dev.buildhound.server.connector.InMemoryCiSpanStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Route + enrichment-wiring coverage for the Azure connector (plan 028), no socket (arch §5). */
class CiRunRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val buildJson = """
        {"id":42,"status":"completed","result":"succeeded",
         "queueTime":"2024-01-01T10:00:00Z","startTime":"2024-01-01T10:00:05Z","finishTime":"2024-01-01T10:05:00Z"}
    """.trimIndent()
    private val timelineJson = """
        {"records":[
          {"id":"r1","type":"Stage","name":"Build","startTime":"2024-01-01T10:00:05Z","finishTime":"2024-01-01T10:05:00Z","result":"succeeded","workerName":"agent-7"},
          {"id":"r2","parentId":"r1","type":"Task","name":"Gradle","result":"failed"}
        ]}
    """.trimIndent()

    private fun mockEngine() = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/timeline")) respond(timelineJson, HttpStatusCode.OK, jsonHeaders)
        else respond(buildJson, HttpStatusCode.OK, jsonHeaders)
    }

    private val azureEnv = mapOf(
        "BUILDHOUND_CONNECTOR_AZURE_PAT" to "pat",
        "BUILDHOUND_CONNECTOR_AZURE_HOSTS" to "dev.azure.com",
    )

    private class Fx(val stores: ServerStores, val project: ProjectRef, val spans: CiSpanStore)

    private fun fx(env: Map<String, String> = azureEnv): Fx {
        val spans = InMemoryCiSpanStore()
        val registry = ConnectorRegistry(listOf(AzureDevOpsConnector(ConnectorHttpClient.create(mockEngine()))))
        val configs = EnvConnectorConfigStore(env)
        val enrichment = EnrichmentQueue(ConnectorEnricher(registry, configs, spans, sleep = {}))
        val stores = ServerStores(
            InMemoryBuildStore(), InMemoryTokenStore(),
            ciSpans = spans, connectors = registry, connectorConfigs = configs, enrichment = enrichment,
        )
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project, spans)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) =
        application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private fun payloadJson(buildId: String = "our-1") = BuildHoundJson.payload.encodeToString(
        BuildPayload.serializer(),
        TestPayloads.build(
            buildId = buildId, durationMs = 60_000, runId = "42",
            buildUrl = "https://dev.azure.com/myorg/proj/_build/results?buildId=42",
        ),
    )

    private suspend fun ApplicationTestBuilder.ingest(buildId: String = "our-1") =
        client.post("/v1/builds") {
            header("Authorization", "Bearer ingest-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson(buildId))
        }

    private suspend fun ApplicationTestBuilder.getCiRun(buildId: String, token: String = "read-token") =
        client.get("/v1/builds/$buildId/ci-run") { header("Authorization", "Bearer $token") }

    private suspend fun ApplicationTestBuilder.postHook(body: String, token: String = "ingest-token") =
        client.post("/v1/connectors/azure-devops/hook") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `ingest enriches the run and the route returns spans, queue time, and gradle share`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Accepted, ingest().status)
        fx.stores.enrichment.drain()

        val response = getCiRun("our-1")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"OK\""), body)
        assertTrue(body.contains("\"queuedMs\":5000"), body) // 10:00:05 − 10:00:00
        assertTrue(body.contains("\"gradleSharePct\":"), body)
        assertTrue(body.contains("\"name\":\"Build\""), body)
        assertTrue(body.contains("\"name\":\"Gradle\""), body)
    }

    @Test
    fun `ci-run requires a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        ingest(); fx.stores.enrichment.drain()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/builds/our-1/ci-run").status)
        assertEquals(HttpStatusCode.Forbidden, getCiRun("our-1", token = "ingest-token").status)
    }

    @Test
    fun `an unenriched or unknown build reads 404`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.NotFound, getCiRun("never-ingested").status)
    }

    @Test
    fun `no PAT configured yields an UNCONFIGURED run, not an error`() = testApplication {
        val fx = fx(env = emptyMap()); appWith(fx)
        ingest(); fx.stores.enrichment.drain()
        val body = getCiRun("our-1").bodyAsText()
        assertTrue(body.contains("\"status\":\"UNCONFIGURED\""), body)
    }

    @Test
    fun `the service hook resolves the build and drives enrichment`() = testApplication {
        val fx = fx(); appWith(fx)
        // Seed the build directly so ONLY the hook drives enrichment (isolates the hook path).
        fx.stores.builds.save(
            fx.project.id,
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), payloadJson("our-1")),
        )
        assertEquals(
            HttpStatusCode.Accepted,
            postHook("""{"eventType":"build.complete","resource":{"id":42}}""").status,
        )
        fx.stores.enrichment.drain()
        assertTrue(getCiRun("our-1").bodyAsText().contains("\"status\":\"OK\""))
    }

    @Test
    fun `the service hook rejects junk, wrong events, oversized bodies, and read tokens`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.BadRequest, postHook("not json").status)
        assertEquals(HttpStatusCode.BadRequest, postHook("""{"eventType":"build.started"}""").status)
        val oversized = """{"eventType":"build.complete","resource":{"id":42,"pad":"${"x".repeat(300 * 1024)}"}}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, postHook(oversized).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            postHook("""{"eventType":"build.complete","resource":{"id":42}}""", token = "read-token").status,
        )
    }

    @Test
    fun `ci-run is tenant-scoped — another project cannot read it`() = testApplication {
        val fx = fx(); appWith(fx)
        ingest(); fx.stores.enrichment.drain()
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertTrue(other.id != fx.project.id)
        assertEquals(HttpStatusCode.NotFound, getCiRun("our-1", token = "other-token").status)
    }
}
