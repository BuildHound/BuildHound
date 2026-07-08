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
 * Route coverage for `GET /v1/builds/{id}/graph` (plan 062): the GEXF/DOT export's auth, tenant
 * isolation, format selection, and absent-edges 404. The escaping/well-formedness of the export bodies
 * themselves is unit-tested in [GraphExporterTest].
 */
class GraphRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.graph(buildId: String, format: String? = null, token: String? = "read-token") =
        client.get("/v1/builds/$buildId/graph${format?.let { "?format=$it" } ?: ""}") {
            token?.let { header("Authorization", "Bearer $it") }
        }

    private fun task(path: String) = TaskExecution(path = path, startMs = 0, durationMs = 100, outcome = TaskOutcome.EXECUTED)

    @Test
    fun `graph requires a bearer token and a read-capable scope`() = testApplication {
        val fx = fx(); appWith(fx)
        val edges = mapOf(":app:a" to listOf(":app:b"))
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "b1", tasks = listOf(task(":app:a"), task(":app:b")), extensions = TestPayloads.internalAdaptersEdges(edges)),
        )

        assertEquals(HttpStatusCode.Unauthorized, graph("b1", token = null).status)
        assertEquals(HttpStatusCode.Forbidden, graph("b1", token = "ingest-token").status)
        assertEquals(HttpStatusCode.OK, graph("b1", token = "read-token").status)
    }

    @Test
    fun `an unknown build 404s`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.NotFound, graph("no-such-build").status)
    }

    @Test
    fun `a build belonging to another tenant reads as 404, never a cross-tenant peek`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-read-token"), TokenScope.READ)
        val edges = mapOf(":app:a" to listOf(":app:b"))
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "tenant-a-build", tasks = listOf(task(":app:a"), task(":app:b")), extensions = TestPayloads.internalAdaptersEdges(edges)),
        )

        val response = client.get("/v1/builds/tenant-a-build/graph") { header("Authorization", "Bearer other-read-token") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `no dependency edges 404s — adapters off, isolated projects, or capped away`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "core-only", tasks = listOf(task(":app:a"))))
        assertEquals(HttpStatusCode.NotFound, graph("core-only").status)
    }

    @Test
    fun `defaults to gexf and returns a well-formed, escaped body`() = testApplication {
        val fx = fx(); appWith(fx)
        val dangerous = ":app:<evil>&\"path"
        val edges = mapOf(dangerous to listOf(":app:b"))
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "gexf-build", tasks = listOf(task(dangerous), task(":app:b")), extensions = TestPayloads.internalAdaptersEdges(edges)),
        )

        val response = graph("gexf-build")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]?.contains("xml", ignoreCase = true) == true, response.headers["Content-Type"].toString())
        val body = response.bodyAsText()
        assertTrue(body.contains("<gexf"), body)
        assertTrue(body.contains("&lt;evil&gt;"), body)
        assertTrue(body.contains("&amp;"), body)
        assertTrue(body.contains("&quot;"), body)
    }

    @Test
    fun `format=dot returns a graphviz body`() = testApplication {
        val fx = fx(); appWith(fx)
        val edges = mapOf(":app:a" to listOf(":app:b"))
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "dot-build", tasks = listOf(task(":app:a"), task(":app:b")), extensions = TestPayloads.internalAdaptersEdges(edges)),
        )

        val response = graph("dot-build", format = "dot")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]?.contains("graphviz") == true, response.headers["Content-Type"].toString())
        assertTrue(response.bodyAsText().contains("digraph tasks"))
    }

    @Test
    fun `an unsupported format is a 400, not a silent fallback`() = testApplication {
        val fx = fx(); appWith(fx)
        val edges = mapOf(":app:a" to listOf(":app:b"))
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "b1", tasks = listOf(task(":app:a"), task(":app:b")), extensions = TestPayloads.internalAdaptersEdges(edges)),
        )
        assertEquals(HttpStatusCode.BadRequest, graph("b1", format = "svg").status)
    }
}
