package dev.buildhound.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class RateLimitTest {

    private fun payloadJson(buildId: String) = """
        {
          "schemaVersion": 1,
          "buildId": "$buildId",
          "startedAt": 1751450000000,
          "finishedAt": 1751450042000,
          "outcome": "SUCCESS",
          "requestedTasks": ["build"],
          "mode": "ci"
        }
    """.trimIndent()

    private fun stores(vararg tenants: Pair<String, String>): ServerStores {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        tenants.forEach { (projectKey, token) -> stores.tokens.ensureProjectWithToken(projectKey, sha256Hex(token)) }
        return stores
    }

    private suspend fun ApplicationTestBuilder.ingest(token: String?, buildId: String): HttpResponse =
        client.post("/v1/builds") {
            token?.let { header("Authorization", "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(payloadJson(buildId))
        }

    // Throttle tests share a 60 s determinism window: a stall longer than the refill
    // period between two requests refills (or evicts) the bucket and un-throttles the
    // last assertion. Accepted: Ktor 3.2.2's eviction compares against hardwired wall
    // time, so an injectable limiter clock cannot close this window (and a fake clock
    // makes eviction fire instantly, silently disabling throttling).

    @Test
    fun `ingest is throttled per token with a Retry-After`() = testApplication {
        application {
            buildHoundModule(stores("pilot" to "tok-a"), RateLimits(ingestPerMinute = 2, queryPerMinute = 0))
        }

        assertEquals(HttpStatusCode.Accepted, ingest("tok-a", "00000000-0000-0000-0000-000000000001").status)
        assertEquals(HttpStatusCode.Accepted, ingest("tok-a", "00000000-0000-0000-0000-000000000002").status)
        val throttled = ingest("tok-a", "00000000-0000-0000-0000-000000000003")
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertNotNull(throttled.headers["Retry-After"], "429 must tell the plugin when to retry (it spools until then)")
    }

    @Test
    fun `tokens do not share a bucket`() = testApplication {
        application {
            buildHoundModule(
                stores("alpha" to "tok-a", "beta" to "tok-b"),
                RateLimits(ingestPerMinute = 2, queryPerMinute = 0),
            )
        }

        repeat(3) { ingest("tok-a", "00000000-0000-0000-0000-00000000000$it") }
        val other = ingest("tok-b", "00000000-0000-0000-0000-000000000009")
        assertEquals(HttpStatusCode.Accepted, other.status, "a throttled tenant must not starve another")
    }

    @Test
    fun `query limiter is independent of the exhausted ingest limiter`() = testApplication {
        application {
            buildHoundModule(stores("pilot" to "tok-a"), RateLimits(ingestPerMinute = 1, queryPerMinute = 10))
        }

        repeat(2) { ingest("tok-a", "00000000-0000-0000-0000-00000000000$it") }
        val read = client.get("/v1/builds") { header("Authorization", "Bearer tok-a") }
        assertEquals(HttpStatusCode.OK, read.status)
    }

    @Test
    fun `limit zero disables the limiter`() = testApplication {
        application {
            buildHoundModule(
                stores("pilot" to "tok-a"),
                RateLimits(ingestPerMinute = 0, queryPerMinute = 0, perHostPerMinute = 0),
            )
        }

        repeat(5) {
            assertNotEquals(HttpStatusCode.TooManyRequests, ingest("tok-a", "00000000-0000-0000-0000-00000000000$it").status)
        }
    }

    @Test
    fun `credential-less requests are throttled before token resolution`() = testApplication {
        application {
            buildHoundModule(stores("pilot" to "tok-a"), RateLimits(ingestPerMinute = 2, queryPerMinute = 0))
        }

        assertEquals(HttpStatusCode.Unauthorized, ingest(null, "x").status)
        assertEquals(HttpStatusCode.Unauthorized, ingest(null, "x").status)
        assertEquals(HttpStatusCode.TooManyRequests, ingest(null, "x").status)
    }

    @Test
    fun `rotating garbage tokens are capped by the per-host layer`() = testApplication {
        application {
            // Per-token limits are generous; only the host layer can stop a flood
            // where every request mints a fresh token bucket.
            buildHoundModule(
                stores("pilot" to "tok-a"),
                RateLimits(ingestPerMinute = 100, queryPerMinute = 0, perHostPerMinute = 2),
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, ingest("garbage-1", "x").status)
        assertEquals(HttpStatusCode.Unauthorized, ingest("garbage-2", "x").status)
        val flooded = ingest("garbage-3", "x")
        assertEquals(HttpStatusCode.TooManyRequests, flooded.status)
        assertNotNull(flooded.headers["Retry-After"])
    }

    @Test
    fun `host layer also bounds authenticated traffic`() = testApplication {
        application {
            buildHoundModule(
                stores("pilot" to "tok-a"),
                RateLimits(ingestPerMinute = 100, queryPerMinute = 0, perHostPerMinute = 2),
            )
        }

        assertEquals(HttpStatusCode.Accepted, ingest("tok-a", "00000000-0000-0000-0000-000000000001").status)
        assertEquals(HttpStatusCode.Accepted, ingest("tok-a", "00000000-0000-0000-0000-000000000002").status)
        assertEquals(HttpStatusCode.TooManyRequests, ingest("tok-a", "00000000-0000-0000-0000-000000000003").status)
    }

    @Test
    fun `health and dashboard stay unlimited`() = testApplication {
        application { buildHoundModule(stores("pilot" to "tok-a"), RateLimits(ingestPerMinute = 1, queryPerMinute = 1)) }

        repeat(5) {
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.OK, client.get("/").status)
        }
    }

    @Test
    fun `defaults come from the environment with a disable escape hatch`() {
        assertEquals(RateLimits(60, 120, 600), rateLimitsFromEnvironment(emptyMap()))
        assertEquals(
            RateLimits(5, 0, 30),
            rateLimitsFromEnvironment(
                mapOf("BUILDHOUND_INGEST_RPM" to "5", "BUILDHOUND_QUERY_RPM" to "0", "BUILDHOUND_HOST_RPM" to "30"),
            ),
        )
    }

    @Test
    fun `invalid environment values fall back to the default, never to disabled`() {
        // A typo must not silently turn limiting off; only an explicit 0 disables.
        assertEquals(
            RateLimits(60, 120, 600),
            rateLimitsFromEnvironment(
                mapOf("BUILDHOUND_INGEST_RPM" to "abc", "BUILDHOUND_QUERY_RPM" to "-5", "BUILDHOUND_HOST_RPM" to ""),
            ),
        )
    }
}
