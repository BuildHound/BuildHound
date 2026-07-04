package dev.buildhound.server

import dev.buildhound.commons.payload.BuildOutcome
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
import kotlin.test.assertTrue

/** INTERRUPTED builds (plan 033) ingest, list, filter, and land in trends without skewing them. */
class LostBuildRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.ALL)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String) =
        client.get(path) { header("Authorization", "Bearer read-token") }

    private val recent = System.currentTimeMillis() - 3_600_000

    private val interruptedJson = """
        {
          "schemaVersion": 1,
          "buildId": "int-build-1",
          "startedAt": 1751450000000,
          "finishedAt": 1751450000000,
          "outcome": "INTERRUPTED",
          "mode": "local"
        }
    """.trimIndent()

    @Test
    fun `an INTERRUPTED payload ingests, lists, and filters`() = testApplication {
        val fx = fx(); appWith(fx)
        val ingest = client.post("/v1/builds") {
            header("Authorization", "Bearer read-token")
            contentType(ContentType.Application.Json)
            setBody(interruptedJson)
        }
        assertEquals(HttpStatusCode.Accepted, ingest.status)

        val list = get("/v1/builds").bodyAsText()
        assertTrue(list.contains("int-build-1") && list.contains("INTERRUPTED"), list)

        // outcome=interrupted is a valid filter for free (BuildOutcome.entries).
        val filtered = get("/v1/builds?outcome=interrupted").bodyAsText()
        assertTrue(filtered.contains("int-build-1"), filtered)
        val successOnly = get("/v1/builds?outcome=success").bodyAsText()
        assertTrue(!successOnly.contains("int-build-1"), "an interrupted build is not a success: $successOnly")
    }

    @Test
    fun `trends count interrupted separately and exclude it from failures and duration`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "s", outcome = BuildOutcome.SUCCESS, durationMs = 10_000, startedAt = recent))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "f", outcome = BuildOutcome.FAILED, durationMs = 5_000, startedAt = recent + 1))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "i", outcome = BuildOutcome.INTERRUPTED, durationMs = 0, startedAt = recent + 2))

        val body = get("/v1/trends?days=1").bodyAsText()
        assertTrue(body.contains("\"builds\":3"), body)
        assertTrue(body.contains("\"failures\":1"), "interrupted must not inflate failures: $body")
        assertTrue(body.contains("\"interrupted\":1"), body)
        // Avg over SUCCESS+FAILED only = (10000+5000)/2 = 7500, not (…+0)/3 = 5000.
        assertTrue(body.contains("\"avgDurationMs\":7500"), "interrupted's synthetic 0ms must not skew avg: $body")
    }

    @Test
    fun `a day of only interrupted builds reports zero duration, not an error`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "i1", outcome = BuildOutcome.INTERRUPTED, durationMs = 0, startedAt = recent))
        val body = get("/v1/trends?days=1").bodyAsText()
        assertTrue(body.contains("\"interrupted\":1") && body.contains("\"avgDurationMs\":0"), body)
    }
}
