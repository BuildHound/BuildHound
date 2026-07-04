package dev.buildhound.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The zero-CDN API docs (plan 042): public, CSP-hardened, no unsafe-inline, no external requests. */
class DocsRoutesTest {

    private fun ApplicationTestBuilder.app() =
        application { buildHoundModule(ServerStores(InMemoryBuildStore(), InMemoryTokenStore()), RateLimits(0, 0, 0)) }

    @Test
    fun `openapi yaml is served publicly with a hardened CSP`() = testApplication {
        app()
        val res = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("openapi: 3.1.0"))
        val csp = res.headers["Content-Security-Policy"] ?: ""
        assertTrue(csp.contains("script-src 'self'"), csp)
        assertTrue(csp.contains("connect-src 'self'"), csp)
        assertFalse(csp.contains("unsafe-inline"), "the docs CSP must never allow unsafe-inline")
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
    }

    @Test
    fun `the docs viewer page and its script are served, script kept external for script-src self`() = testApplication {
        app()
        val page = client.get("/docs")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("BuildHound API"), html)
        // The script is a separate resource (not inline) so script-src 'self' holds.
        assertTrue(html.contains("<script src=\"/docs.js\">"), html)
        assertFalse(Regex("<script>[^<]").containsMatchIn(html), "no inline script in the docs page")

        assertEquals(HttpStatusCode.OK, client.get("/docs.js").status)
    }
}
