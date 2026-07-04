package dev.buildhound.server.connector

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubActionsConnectorTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val runJson = """
        {
          "id": 30433642,
          "status": "completed",
          "conclusion": "success",
          "run_attempt": 1,
          "created_at": "2024-01-01T10:00:00Z",
          "run_started_at": "2024-01-01T10:00:20Z"
        }
    """.trimIndent()

    private val jobsJson = """
        {
          "total_count": 1,
          "jobs": [
            {
              "id": 111,
              "name": "build",
              "status": "completed",
              "conclusion": "success",
              "started_at": "2024-01-01T10:00:20Z",
              "completed_at": "2024-01-01T10:05:00Z",
              "runner_name": "gh-runner-3",
              "steps": [
                {"name":"Set up job","status":"completed","conclusion":"success","number":1,"started_at":"2024-01-01T10:00:20Z","completed_at":"2024-01-01T10:00:22Z"},
                {"name":"Compile","status":"completed","conclusion":"failure","number":2,"started_at":"2024-01-01T10:00:22Z","completed_at":"2024-01-01T10:04:00Z"}
              ]
            }
          ]
        }
    """.trimIndent()

    /** Routes by path: `…/jobs` → jobs JSON, otherwise the run JSON. */
    private fun connector(): Pair<GitHubActionsConnector, MockEngine> {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/jobs")) respond(jobsJson, HttpStatusCode.OK, jsonHeaders)
            else respond(runJson, HttpStatusCode.OK, jsonHeaders)
        }
        return GitHubActionsConnector(ConnectorHttpClient.create(engine)) to engine
    }

    private val config = ConnectorConfig(
        baseUrl = "https://api.github.com",
        credential = Credential.Pat("ghtoken"),
        allowedHosts = setOf("api.github.com"),
    )

    /** A ref built the way the enricher does — owner/repo + attempt parsed from the ingested build URL. */
    private fun ref(attempt: Int? = null): CiRunRef =
        GitHubActionsConnector(ConnectorHttpClient.create(MockEngine { respond("") })).refFrom(
            "github-actions",
            "30433642",
            "https://github.com/acme/app/actions/runs/30433642" + (attempt?.let { "/attempts/$it" } ?: ""),
        )

    @Test
    fun `fetchRun maps run timing and jobs to a JOB then STEP tree`() = runBlocking {
        val (gha, _) = connector()
        val run = gha.fetchRun(ref(), config)!!

        assertEquals(20_000L, run.queuedMs) // run_started_at - created_at
        assertEquals(1_704_103_500_000L, run.finishedAt) // 2024-01-01T10:05:00Z
        assertEquals(3, run.spans.size)

        val job = run.spans.single { it.kind == SpanKind.JOB }
        assertEquals("111", job.id)
        assertEquals("build", job.name)
        assertEquals(SpanResult.SUCCEEDED, job.result)
        assertEquals("gh-runner-3", job.workerName)

        val failedStep = run.spans.single { it.id == "111#2" }
        assertEquals(SpanKind.STEP, failedStep.kind)
        assertEquals("111", failedStep.parentId)
        assertEquals(SpanResult.FAILED, failedStep.result)
    }

    @Test
    fun `fetchRun sends the PAT as a bearer token and the api-version header`() = runBlocking {
        val (gha, engine) = connector()
        gha.fetchRun(ref(), config)
        assertTrue(engine.requestHistory.isNotEmpty())
        engine.requestHistory.forEach { req ->
            assertEquals("Bearer ghtoken", req.headers[HttpHeaders.Authorization])
            assertEquals("2022-11-28", req.headers["X-GitHub-Api-Version"])
        }
    }

    @Test
    fun `a re-run attempt fetches the attempt-scoped jobs endpoint`() = runBlocking {
        val (gha, engine) = connector()
        gha.fetchRun(ref(attempt = 2), config)
        assertTrue(
            engine.requestHistory.any { it.url.encodedPath.endsWith("/attempts/2/jobs") },
            engine.requestHistory.map { it.url.encodedPath }.toString(),
        )
    }

    @Test
    fun `fetchRun refuses a host outside the allowlist and makes no request`() = runBlocking {
        val (gha, engine) = connector()
        assertNull(gha.fetchRun(ref(), config.copy(allowedHosts = emptySet())))
        assertTrue(engine.requestHistory.isEmpty(), "SSRF guard must short-circuit before any outbound call")
    }

    @Test
    fun `fetchRun refuses a non-https base url`() = runBlocking {
        val (gha, engine) = connector()
        assertNull(gha.fetchRun(ref(), config.copy(baseUrl = "http://api.github.com", allowedHosts = setOf("api.github.com"))))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun returns null without a credential`() = runBlocking {
        val (gha, engine) = connector()
        assertNull(gha.fetchRun(ref(), config.copy(credential = null)))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun rejects a non-numeric run id and makes no request`() = runBlocking {
        val (gha, engine) = connector()
        // A crafted runId must not inject an extra path/query segment into the outbound URL.
        val evil = CiRunRef(provider = "github-actions", runId = "1/../../orgs/victim/actions/secrets", project = "acme/app")
        assertNull(gha.fetchRun(evil, config))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `fetchRun returns null when a concluded run's jobs fetch fails`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/jobs")) respond("oops", HttpStatusCode.InternalServerError, jsonHeaders)
            else respond(runJson, HttpStatusCode.OK, jsonHeaders)
        }
        val gha = GitHubActionsConnector(ConnectorHttpClient.create(engine))
        assertNull(gha.fetchRun(ref(), config))
    }

    @Test
    fun `an in-progress run leaves result null and does not require jobs`() = runBlocking {
        val runningRun = """{"id":1,"status":"in_progress","conclusion":null,"created_at":"2024-01-01T10:00:00Z","run_started_at":"2024-01-01T10:00:05Z"}"""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/jobs")) respond("boom", HttpStatusCode.InternalServerError, jsonHeaders)
            else respond(runningRun, HttpStatusCode.OK, jsonHeaders)
        }
        val gha = GitHubActionsConnector(ConnectorHttpClient.create(engine))
        val run = gha.fetchRun(ref(), config)!! // no conclusion ⇒ a jobs failure is tolerated, not a retry
        assertNull(run.finishedAt)
        assertEquals(5_000L, run.queuedMs)
    }

    @Test
    fun `a concluded run with no timestamped jobs still reports a finish so it is never stuck PENDING`() = runBlocking {
        // startup_failure / cancelled-before-any-job: conclusion set, jobs:[] (200, not a fetch failure).
        // finishedAt must fall back to the run envelope so the enricher records OK, not perpetual PENDING.
        val concluded = """{"id":1,"status":"completed","conclusion":"failure","created_at":"2024-01-01T10:00:00Z","run_started_at":"2024-01-01T10:00:05Z","updated_at":"2024-01-01T10:00:09Z"}"""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/jobs")) respond("""{"total_count":0,"jobs":[]}""", HttpStatusCode.OK, jsonHeaders)
            else respond(concluded, HttpStatusCode.OK, jsonHeaders)
        }
        val gha = GitHubActionsConnector(ConnectorHttpClient.create(engine))
        val run = gha.fetchRun(ref(), config)!!
        assertEquals(1_704_103_209_000L, run.finishedAt) // updated_at 10:00:09
        assertTrue(run.spans.isEmpty())
    }

    @Test
    fun `refFrom parses owner-repo and attempt from an ingested run url`() {
        val (gha, _) = connector()
        val parsed = gha.refFrom("github-actions", "30433642", "https://github.com/acme/app/actions/runs/30433642/attempts/3")
        assertEquals("acme/app", parsed.project)
        assertEquals("github.com", parsed.collectionUri)
        assertEquals(3, parsed.attempt)
    }

    @Test
    fun `refFrom leaves repo null for an unrelated url`() {
        val (gha, _) = connector()
        val parsed = gha.refFrom("github-actions", "1", "https://example.com/whatever")
        assertNull(parsed.project)
        assertNull(parsed.attempt)
    }

    @Test
    fun `buildLink builds the run page url with an attempt suffix`() {
        val (gha, _) = connector()
        assertEquals("https://github.com/acme/app/actions/runs/30433642", gha.buildLink(ref(), config))
        assertEquals("https://github.com/acme/app/actions/runs/30433642/attempts/2", gha.buildLink(ref(attempt = 2), config))
    }

    @Test
    fun `parseWebhook is a no-op in v1`() {
        val (gha, _) = connector()
        assertNull(gha.parseWebhook(emptyMap(), """{"action":"completed"}""", config))
    }
}
