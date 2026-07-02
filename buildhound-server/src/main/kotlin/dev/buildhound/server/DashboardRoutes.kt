package dev.buildhound.server

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest
import java.util.Base64

/**
 * Dashboard v0 (plan 012): two embedded static resources, no data. All data flows
 * through the Bearer-authenticated query API from the browser, so these pages are
 * public. The script lives in a separate resource so the CSP can require
 * `script-src 'self'` — no inline script can ever execute, even if payload-derived
 * text escaped a `textContent` sink.
 */
internal object DashboardAssets {
    val indexHtml: ByteArray = resource("web/index.html")
    val dashboardJs: ByteArray = resource("web/dashboard.js")

    /**
     * The single inline `<style>` block is pinned by hash instead of
     * `style-src 'unsafe-inline'` (review hardening) — computed from the served
     * bytes so a style edit can never silently un-style the page.
     */
    val csp: String = run {
        val styleHashes = Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .findAll(indexHtml.decodeToString())
            .map { match ->
                val digest = MessageDigest.getInstance("SHA-256").digest(match.groupValues[1].encodeToByteArray())
                "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'"
            }
            .toList()
        val styleSrc = if (styleHashes.isEmpty()) "'none'" else styleHashes.joinToString(" ")
        "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; " +
            "style-src $styleSrc; script-src 'self'; connect-src 'self'"
    }

    /** Loaded once at class init — a missing resource fails startup, not a request. */
    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "embedded dashboard resource missing: $path"
        }.use { it.readBytes() }
}

fun Route.dashboardRoutes() {
    get("/") {
        call.dashboardHeaders()
        call.respondBytes(DashboardAssets.indexHtml, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    get("/dashboard.js") {
        call.dashboardHeaders()
        call.respondBytes(DashboardAssets.dashboardJs, ContentType.Text.JavaScript.withCharset(Charsets.UTF_8))
    }
}

private fun ApplicationCall.dashboardHeaders() {
    response.header("Content-Security-Policy", DashboardAssets.csp)
    response.header("X-Content-Type-Options", "nosniff")
    // The page hosts token entry; never allow it to be framed (clickjacking).
    response.header("X-Frame-Options", "DENY")
    // Revalidate on every load so a server upgrade can't leave stale JS against a changed API.
    response.header("Cache-Control", "no-cache")
}
