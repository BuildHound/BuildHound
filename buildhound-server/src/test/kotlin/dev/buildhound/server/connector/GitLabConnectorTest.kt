package dev.buildhound.server.connector

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabConnectorTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val pipelineJson = """
        {
          "id": 46,
          "status": "success",
          "created_at": "2024-01-01T10:00:00.000Z",
          "started_at": "2024-01-01T10:00:05.000Z",
          "finished_at": "2024-01-01T10:05:00.000Z",
          "queued_duration": 12.5,
          "web_url": "https://gitlab.com/acme/app/-/pipelines/46"
        }
    """.trimIndent()

    // GitLab returns jobs as a bare JSON array.
    private val jobsJson = """
        [
          {"id":6,"name":"compile","stage":"build","status":"success","started_at":"2024-01-01T10:00:05.000Z","finished_at":"2024-01-01T10:02:00.000Z","runner":{"description":"shared-runner-1"}},
          {"id":7,"name":"unit","stage":"test","status":"failed","started_at":"2024-01-01T10:02:05.000Z","finished_at":"2024-01-01T10:04:00.000Z","runner":{"description":"shared-runner-2"}},
          {"id":8,"name":"integration","stage":"test","status":"success","started_at":"2024-01-01T10:02:05.000Z","finished_at":"2024-01-01T10:04:30.000Z"}
        ]
    """.trimIndent()

    /** Routes by path: `…/jobs` → jobs array, otherwise the pipeline JSON. */
    private fun connector(): Pair<GitLabConnector, MockEngine> {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/jobs")) respond(jobsJson, HttpStatusCode.OK, jsonHeaders)
            else respond(pipelineJson, HttpStatusCode.OK, jsonHeaders)
        }
        return GitLabConnector(ConnectorHttpClient.create(engine)) to engine
    }

    private val config = ConnectorConfig(
        baseUrl = "https://gitlab.com/api/v4",
        credential = Credential.Pat("gltoken"),
        allowedHosts = setOf("gitlab.com"),
    )

    private fun ref(): CiRunRef =
        GitLabConnector(ConnectorHttpClient.create(MockEngine { respond("") }))
            .refFrom("gitlab", "46", "https://gitlab.com/acme/app/-/pipelines/46")

    @Test
    fun `fetchRun synthesizes stages parenting jobs and maps timing`() = runBlocking {
        val (gitlab, _) = connector()
        val run = gitlab.fetchRun(ref(), config)!!

        assertEquals(12_500L, run.queuedMs) // 12.5s
        assertEquals(1_704_103_205_000L, run.startedAt) // 10:00:05
        assertEquals(1_704_103_500_000L, run.finishedAt) // 10:05:00
        assertEquals(5, run.spans.size) // 2 stages + 3 jobs

        val testStage = run.spans.single { it.id == "stage:test" }
        assertEquals(SpanKind.STAGE, testStage.kind)
        assertEquals(SpanResult.FAILED, testStage.result) // a job failed ⇒ stage failed
        assertEquals(1_704_103_325_000L, testStage.startMs) // min job start 10:02:05
        assertEquals(1_704_103_470_000L, testStage.finishMs) // max job finish 10:04:30

        val compile = run.spans.single { it.id == "6" }
        assertEquals(SpanKind.JOB, compile.kind)
        assertEquals("stage:build", compile.parentId)
        assertEquals("shared-runner-1", compile.workerName)
        assertEquals(SpanResult.SUCCEEDED, compile.result)

        val integration = run.spans.single { it.id == "8" }
        assertNull(integration.workerName) // no runner block
    }

    @Test
    fun `fetchRun sends the token as a PRIVATE-TOKEN header and url-encodes the project path`() = runBlocking {
        val (gitlab, engine) = connector()
        gitlab.fetchRun(ref(), config)
        assertTrue(engine.requestHistory.isNotEmpty())
        engine.requestHistory.forEach { req ->
            assertEquals("gltoken", req.headers["PRIVATE-TOKEN"])
            assertTrue(req.url.encodedPath.contains("projects/acme%2Fapp"), req.url.encodedPath)
        }
    }

    @Test
    fun `fetchRun refuses a host outside the allowlist and makes no request`() = runBlocking {
        val (gitlab, engine) = connector()
        assertNull(gitlab.fetchRun(ref(), config.copy(allowedHosts = emptySet())))
        assertTrue(engine.requestHistory.isEmpty(), "SSRF guard must short-circuit before any outbound call")
    }

    @Test
    fun `fetchRun refuses a non-https base url`() = runBlocking {
        val (gitlab, engine) = connector()
        assertNull(gitlab.fetchRun(ref(), config.copy(baseUrl = "http://gitlab.com/api/v4", allowedHosts = setOf("gitlab.com"))))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun returns null without a credential`() = runBlocking {
        val (gitlab, engine) = connector()
        assertNull(gitlab.fetchRun(ref(), config.copy(credential = null)))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun rejects a non-numeric pipeline id and makes no request`() = runBlocking {
        val (gitlab, engine) = connector()
        val evil = CiRunRef(provider = "gitlab", runId = "46/../projects/victim/variables", project = "acme/app")
        assertNull(gitlab.fetchRun(evil, config))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun returns null when a finished pipeline's jobs fetch fails`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/jobs")) respond("oops", HttpStatusCode.InternalServerError, jsonHeaders)
            else respond(pipelineJson, HttpStatusCode.OK, jsonHeaders)
        }
        val gitlab = GitLabConnector(ConnectorHttpClient.create(engine))
        assertNull(gitlab.fetchRun(ref(), config))
    }

    @Test
    fun `a running pipeline tolerates a jobs failure and reports no finish`() = runBlocking {
        val running = """{"id":46,"status":"running","created_at":"2024-01-01T10:00:00.000Z","started_at":"2024-01-01T10:00:05.000Z","queued_duration":1.0}"""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/jobs")) respond("boom", HttpStatusCode.InternalServerError, jsonHeaders)
            else respond(running, HttpStatusCode.OK, jsonHeaders)
        }
        val gitlab = GitLabConnector(ConnectorHttpClient.create(engine))
        val run = gitlab.fetchRun(ref(), config)!!
        assertNull(run.finishedAt)
        assertEquals(1_000L, run.queuedMs)
    }

    @Test
    fun `refFrom parses host and namespaced project path`() {
        val (gitlab, _) = connector()
        val parsed = gitlab.refFrom("gitlab", "46", "https://gitlab.com/group/sub/app/-/pipelines/46")
        assertEquals("gitlab.com", parsed.collectionUri)
        assertEquals("group/sub/app", parsed.project)
    }

    @Test
    fun `refFrom parses the project path from an ingested job url`() {
        // The plugin ingests CI_JOB_URL (a /-/jobs/ URL), not the pipeline URL; the pipeline id is runId.
        val (gitlab, _) = connector()
        val parsed = gitlab.refFrom("gitlab", "46", "https://gitlab.com/group/sub/app/-/jobs/999")
        assertEquals("group/sub/app", parsed.project)
        assertEquals("46", parsed.runId)
    }

    @Test
    fun `refFrom leaves project null for a url with no gitlab route separator`() {
        val (gitlab, _) = connector()
        val parsed = gitlab.refFrom("gitlab", "1", "https://gitlab.com/acme/app")
        assertNull(parsed.project)
    }

    @Test
    fun `buildLink builds the pipeline page url`() {
        val (gitlab, _) = connector()
        assertEquals("https://gitlab.com/acme/app/-/pipelines/46", gitlab.buildLink(ref(), config))
    }

    @Test
    fun `parseWebhook is a no-op in v1`() {
        val (gitlab, _) = connector()
        assertNull(gitlab.parseWebhook(emptyMap(), """{"object_kind":"pipeline"}""", config))
    }
}
