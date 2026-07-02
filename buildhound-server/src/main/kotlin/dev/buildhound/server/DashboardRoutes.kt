package dev.buildhound.server

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Dashboard v0 (plan 012): two embedded static resources, no data. All data flows
 * through the Bearer-authenticated query API from the browser, so these pages are
 * public. The script lives in a separate resource so the CSP can require
 * `script-src 'self'` — no inline script can ever execute, even if payload-derived
 * text escaped a `textContent` sink.
 */
internal const val DASHBOARD_CSP =
    "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'"

fun Route.dashboardRoutes() {
    val indexHtml = dashboardResource("web/index.html")
    val dashboardJs = dashboardResource("web/dashboard.js")

    get("/") {
        call.response.header("Content-Security-Policy", DASHBOARD_CSP)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(indexHtml, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    get("/dashboard.js") {
        call.response.header("Content-Security-Policy", DASHBOARD_CSP)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(dashboardJs, ContentType.Text.JavaScript.withCharset(Charsets.UTF_8))
    }
}

/** Loaded once at route registration — a missing resource fails startup, not a request. */
private fun dashboardResource(path: String): ByteArray =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(path)) {
        "embedded dashboard resource missing: $path"
    }.use { it.readBytes() }
