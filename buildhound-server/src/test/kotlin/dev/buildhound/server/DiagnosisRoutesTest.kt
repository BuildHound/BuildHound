package dev.buildhound.server

import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
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
 * Route coverage for `GET /v1/builds/{id}/diagnosis` (plan 071): auth matrix, tenant isolation, and the
 * 200 shape. The synthesis math itself (dominant phase / hit rate / hotspots / deltas) is unit-tested in
 * [BuildDiagnoserTest] — these tests exercise only the route's auth, lookup, and wiring.
 */
class DiagnosisRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore(), verdicts = InMemoryVerdictStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.diagnosis(buildId: String, token: String? = "read-token") =
        client.get("/v1/builds/$buildId/diagnosis") { token?.let { header("Authorization", "Bearer $it") } }

    @Test
    fun `diagnosis requires a bearer token and a read-capable scope`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "b1"))

        assertEquals(HttpStatusCode.Unauthorized, diagnosis("b1", token = null).status)
        assertEquals(HttpStatusCode.Forbidden, diagnosis("b1", token = "ingest-token").status)
        assertEquals(HttpStatusCode.OK, diagnosis("b1", token = "read-token").status)
    }

    @Test
    fun `an unknown build 404s`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.NotFound, diagnosis("no-such-build").status)
    }

    @Test
    fun `a build belonging to another tenant reads as 404, never a cross-tenant peek`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-read-token"), TokenScope.READ)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "tenant-a-build"))

        val response = client.get("/v1/builds/tenant-a-build/diagnosis") {
            header("Authorization", "Bearer other-read-token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `200 shape for a build with tasks and a hit rate, with no verdict evaluated yet`() = testApplication {
        val fx = fx(); appWith(fx)
        val tasks = listOf(
            TaskExecution(path = ":app:compile", module = ":app", type = "Compile", startMs = 0, durationMs = 4000, outcome = TaskOutcome.EXECUTED, cacheable = true),
            TaskExecution(path = ":app:lint", startMs = 0, durationMs = 100, outcome = TaskOutcome.FROM_CACHE, cacheable = true),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "shape-1", tasks = tasks, hitRate = 0.5),
        )

        val body = diagnosis("shape-1").bodyAsText()
        assertTrue(body.contains("\"buildId\":\"shape-1\""), body)
        assertTrue(body.contains("\"cacheHitRate\""), body)
        assertTrue(body.contains("\"belowTarget\":true"), body) // 0.5 < DEFAULT_CACHE_HIT_TARGET (0.8)
        assertTrue(body.contains("\":app:compile\""), body) // ranked hotspot
        // BuildHoundJson.payload has explicitNulls = false — a null field is omitted, not `"deltas":null`.
        assertTrue(!body.contains("\"deltas\""), "no verdict was saved, so deltas must be omitted: $body")
    }

    @Test
    fun `deltas are populated once a verdict has been saved for the build`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "with-verdict"))
        fx.stores.verdicts.save(
            fx.project.id,
            "with-verdict",
            Verdict(
                status = "WARN",
                baselineKey = "k",
                metrics = listOf(MetricVerdict(name = "durationMs", value = 2000.0, baselineMedian = 1000.0, z = 4.0, status = "WARN")),
            ),
        )

        val body = diagnosis("with-verdict").bodyAsText()
        assertTrue(body.contains("\"deltas\""), body)
        assertTrue(body.contains("\"baselineMedian\":1000.0"), body)
    }

    @Test
    fun `no derived metrics degrades dominantPhase and cacheHitRate to null without failing`() = testApplication {
        val fx = fx(); appWith(fx)
        // Default TestPayloads.build has no hitRate/avoidedMs, so derived stays null (honest-null-degrade).
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "bare"))

        val response = diagnosis("bare")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // explicitNulls = false — a null analytic field is omitted entirely, never fabricated as a value.
        assertTrue(!body.contains("\"dominantPhase\""), body)
        assertTrue(!body.contains("\"cacheHitRate\""), body)
    }
}
