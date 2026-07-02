package dev.buildhound.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardRoutesTest {

    @Test
    fun `dashboard page is served unauthenticated with the CSP header`() = testApplication {
        application { buildHoundModule() }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), response.contentType())
        assertEquals(DASHBOARD_CSP, response.headers["Content-Security-Policy"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertTrue(response.bodyAsText().contains("BuildHound"))
    }

    @Test
    fun `dashboard page has no inline script so script-src self holds`() = testApplication {
        application { buildHoundModule() }

        val html = client.get("/").bodyAsText()

        // Every <script> must reference an external src; an inline one would be dead
        // code under the CSP and a sign the XSS posture regressed.
        val scripts = Regex("<script[^>]*>").findAll(html).map { it.value }.toList()
        assertTrue(scripts.isNotEmpty(), "expected the dashboard.js script tag")
        scripts.forEach { tag ->
            assertTrue(tag.contains("src="), "inline <script> found: $tag")
        }
        assertFalse(html.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `dashboard script is served with a javascript content type and CSP`() = testApplication {
        application { buildHoundModule() }

        val response = client.get("/dashboard.js")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.parse("text/javascript"), response.contentType()?.withoutParameters())
        assertEquals(Charsets.UTF_8, response.contentType()?.charset())
        assertEquals(DASHBOARD_CSP, response.headers["Content-Security-Policy"])
        assertTrue(response.bodyAsText().contains("use strict"))
    }

    @Test
    fun `data endpoints stay locked while the page itself is public`() = testApplication {
        application { buildHoundModule() }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/builds").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/trends").status)
    }
}
