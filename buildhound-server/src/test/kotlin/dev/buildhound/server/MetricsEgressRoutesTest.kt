package dev.buildhound.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /v1/metrics/prometheus` (plan 070): scope matrix, exposition shape, cross-tenant isolation, and
 * the empty-project honest-empty body — no socket.
 */
class MetricsEgressRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("metrics-token"), TokenScope.METRICS)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("all-token"), TokenScope.ALL)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String = "/v1/metrics/prometheus", token: String? = "metrics-token") =
        client.get(path) { if (token != null) header("Authorization", "Bearer $token") }

    private val recent = System.currentTimeMillis() - 3_600_000

    @Test
    fun `no token is 401, ingest-only is 403`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, get(token = null).status)
        assertEquals(HttpStatusCode.Forbidden, get(token = "ingest-token").status)
    }

    @Test
    fun `metrics, read, and all tokens are all accepted (allowsMetrics matrix)`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "b1", durationMs = 1000, startedAt = recent))
        for (token in listOf("metrics-token", "read-token", "all-token")) {
            assertEquals(HttpStatusCode.OK, get(token = token).status, token)
        }
    }

    @Test
    fun `a successful scrape returns the Prometheus text-exposition content type`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "b1", durationMs = 1000, startedAt = recent))
        val response = get()
        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.headers["Content-Type"].orEmpty()
        assertTrue(contentType.contains("text/plain"), contentType)
        assertTrue(contentType.contains("version=0.0.4"), contentType)
    }

    @Test
    fun `the seeded project's gauges appear in the exposition`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "b1", durationMs = 4_000, startedAt = recent, hitRate = 0.75),
        )
        val body = get().bodyAsText()
        assertTrue(body.contains("buildhound_build_duration_p50_seconds{project=\"pilot\"} 4"), body)
        assertTrue(body.contains("buildhound_build_success_rate{project=\"pilot\"} 1"), body)
        assertTrue(body.contains("buildhound_builds{project=\"pilot\",outcome=\"SUCCESS\"} 1"), body)
        assertTrue(body.contains("buildhound_cache_hit_rate{project=\"pilot\"} 0.75"), body)
    }

    @Test
    fun `a metrics-scoped token never sees another tenant's values`() = testApplication {
        val fx = fx(); appWith(fx)
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-metrics-token"), TokenScope.METRICS)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "pilot-1", durationMs = 4_000, startedAt = recent))
        fx.stores.builds.save(other.id, TestPayloads.build(buildId = "other-1", durationMs = 9_000, startedAt = recent))

        val pilotBody = get(token = "metrics-token").bodyAsText()
        assertTrue(pilotBody.contains("project=\"pilot\""))
        assertFalse(pilotBody.contains("project=\"other\""))
        assertTrue(pilotBody.contains("buildhound_build_duration_p50_seconds{project=\"pilot\"} 4"), pilotBody)

        val otherBody = get(token = "other-metrics-token").bodyAsText()
        assertTrue(otherBody.contains("project=\"other\""))
        assertFalse(otherBody.contains("project=\"pilot\""))
        assertTrue(otherBody.contains("buildhound_build_duration_p50_seconds{project=\"other\"} 9"), otherBody)
    }

    @Test
    fun `an empty project returns 200 with a valid, line-omitted body`() = testApplication {
        val fx = fx(); appWith(fx)
        val response = get()
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Honest-empty: no KPI line for a project with zero builds, but a valid, non-empty body — the
        // window anchor line always survives so the scrape target never reads "down".
        assertFalse(body.contains("buildhound_build_duration_p50_seconds"))
        assertFalse(body.contains("buildhound_builds"))
        assertFalse(body.contains("buildhound_flaky_tests"))
        assertFalse(body.contains("buildhound_avoided_seconds"))
        assertTrue(body.contains("buildhound_scrape_window_days{project=\"pilot\"} 30"), body)
    }

    @Test
    fun `days narrows the window like the other rollups`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "old-1", durationMs = 1000, startedAt = System.currentTimeMillis() - 40L * 86_400_000),
        )
        val body = get(path = "/v1/metrics/prometheus?days=7").bodyAsText()
        assertFalse(body.contains("buildhound_build_duration_p50_seconds"), "a 40-day-old build is outside a 7-day window")
    }
}
