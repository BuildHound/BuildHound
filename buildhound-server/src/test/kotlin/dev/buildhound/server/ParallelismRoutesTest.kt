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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Route coverage for `GET /v1/builds/{id}/parallelism` (plan 062): auth matrix, tenant isolation, and
 * the honest null-degrade shape. The detector math itself is unit-tested in [GatingAnalyzerTest] /
 * [GraphCentralityTest] — these tests exercise only the route's auth, lookup, and wiring.
 */
class ParallelismRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.parallelism(buildId: String, token: String? = "read-token") =
        client.get("/v1/builds/$buildId/parallelism") { token?.let { header("Authorization", "Bearer $it") } }

    private fun gatingTasks() = listOf(
        TaskExecution(path = ":app:a", module = ":app", startMs = 0, durationMs = 15, outcome = TaskOutcome.EXECUTED),
        TaskExecution(path = ":app:b", module = ":app", startMs = 0, durationMs = 5, outcome = TaskOutcome.EXECUTED),
        TaskExecution(path = ":app:c", module = ":app", startMs = 15, durationMs = 5, outcome = TaskOutcome.EXECUTED),
    )

    @Test
    fun `parallelism requires a bearer token and a read-capable scope`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "b1", tasks = gatingTasks()))

        assertEquals(HttpStatusCode.Unauthorized, parallelism("b1", token = null).status)
        assertEquals(HttpStatusCode.Forbidden, parallelism("b1", token = "ingest-token").status)
        assertEquals(HttpStatusCode.OK, parallelism("b1", token = "read-token").status)
    }

    @Test
    fun `an unknown build 404s`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.NotFound, parallelism("no-such-build").status)
    }

    @Test
    fun `a build belonging to another tenant reads as 404, never a cross-tenant peek`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-read-token"), TokenScope.READ)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "tenant-a-build", tasks = gatingTasks()))

        val response = client.get("/v1/builds/tenant-a-build/parallelism") {
            header("Authorization", "Bearer other-read-token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `a core-only payload still surfaces gating blockers with centrality honestly null`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "core-only", tasks = gatingTasks()))

        val body = parallelism("core-only").bodyAsText()
        assertTrue(body.contains("\"gatingBlockers\":[{\"taskPath\":\":app:a\""), body)
        assertTrue(body.contains("\"centralityAvailable\":false"), body)
        // explicitNulls = false — a null `centrality` field is omitted entirely, never `"centrality":null`.
        assertFalse(body.contains("\"centrality\":"), body)
    }

    @Test
    fun `a build carrying internal-adapters edges gets a non-null centrality ranking`() = testApplication {
        val fx = fx(); appWith(fx)
        val edges = mapOf(":app:a" to listOf(":app:b"))
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "with-edges", tasks = gatingTasks(), extensions = TestPayloads.internalAdaptersEdges(edges)),
        )

        val body = parallelism("with-edges").bodyAsText()
        assertTrue(body.contains("\"centralityAvailable\":true"), body)
        assertTrue(body.contains("\"centrality\":["), body)
        assertTrue(body.contains("\"taskPath\":\":app:a\""), body)
    }

    @Test
    fun `an isolated-projects build with an empty edge map still degrades centrality to null`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "ip-build", tasks = gatingTasks(), extensions = TestPayloads.internalAdaptersEdges(emptyMap())),
        )

        val body = parallelism("ip-build").bodyAsText()
        assertTrue(body.contains("\"centralityAvailable\":false"), body)
        assertFalse(body.contains("\"centrality\":"), body)
    }
}
