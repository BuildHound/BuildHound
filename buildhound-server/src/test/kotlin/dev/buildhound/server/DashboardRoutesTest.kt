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
        assertEquals(DashboardAssets.csp, response.headers["Content-Security-Policy"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-cache", response.headers["Cache-Control"])
        assertTrue(response.bodyAsText().contains("BuildHound"))
    }

    @Test
    fun `CSP locks scripts to self, pins the inline style by hash, and forbids framing`() {
        val csp = DashboardAssets.csp

        assertTrue(csp.contains("default-src 'none'"))
        assertTrue(csp.contains("script-src 'self'"))
        assertTrue(csp.contains("connect-src 'self'"))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
        // The style block is hash-pinned; no 'unsafe-*' source anywhere in the policy.
        assertTrue(Regex("style-src 'sha256-[A-Za-z0-9+/=]+'").containsMatchIn(csp), csp)
        assertFalse(csp.contains("unsafe"), csp)
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
        assertEquals(DashboardAssets.csp, response.headers["Content-Security-Policy"])
        assertTrue(response.bodyAsText().contains("use strict"))
    }

    @Test
    fun `timeline script is served with a javascript content type and CSP and matches the report module`() = testApplication {
        application { buildHoundModule() }

        val response = client.get("/timeline.js")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.parse("text/javascript"), response.contentType()?.withoutParameters())
        assertEquals(Charsets.UTF_8, response.contentType()?.charset())
        assertEquals(DashboardAssets.csp, response.headers["Content-Security-Policy"])
        assertEquals("no-cache", response.headers["Cache-Control"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        val served = response.bodyAsText()
        assertTrue(served.contains("buildhoundTimeline"))
        // Byte-identical (over UTF-8) to the buildhound-report resource — one renderer, no drift.
        val reportResource = checkNotNull(javaClass.classLoader.getResourceAsStream("dev/buildhound/report/timeline.js"))
            .use { it.readBytes() }.decodeToString()
        assertEquals(reportResource, served, "served /timeline.js must match the report module resource")
    }

    @Test
    fun `data endpoints stay locked while the page itself is public`() = testApplication {
        application { buildHoundModule() }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/builds").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/trends").status)
    }
}
