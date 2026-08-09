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
     * The shared timeline renderer, loaded verbatim from the buildhound-report module
     * (plan 017) so the dashboard and the standalone artifact never drift. Served at
     * `/timeline.js` under the same `script-src 'self'` CSP as dashboard.js.
     */
    val timelineJs: ByteArray = resource("dev/buildhound/report/timeline.js")

    /**
     * uPlot, vendored verbatim (plan 105) and served under the same `script-src 'self'`
     * as the other two scripts — the CSP admits no CDN, so a charting library can only
     * arrive this way. Its stylesheet is deliberately *not* a second route: `style-src`
     * carries hashes and no `'self'`, so the CSS is retokenized into index.html's inline
     * `<style>` block instead, which [styleHashCsp] rehashes from the served bytes.
     * `VendoredAssetsTest` pins the properties that made it CSP-safe.
     */
    val uplotJs: ByteArray = resource("web/uplot.js")

    /**
     * The single inline `<style>` block is pinned by hash instead of
     * `style-src 'unsafe-inline'` (review hardening) — computed from the served
     * bytes so a style edit can never silently un-style the page.
     */
    val csp: String = styleHashCsp(indexHtml.decodeToString())

    /** Loaded once at class init — a missing resource fails startup, not a request. */
    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "embedded dashboard resource missing: $path"
        }.use { it.readBytes() }
}

/**
 * Zero-CDN API docs (plan 042): the OpenAPI spec (`api/openapi.yaml`, copied onto the classpath at
 * build time from the single source `docs/api/openapi.yaml`) plus a hand-rolled viewer under the same
 * strict CSP as the dashboard — no Swagger-UI CDN. The viewer's script is a separate `/docs.js` so
 * `script-src 'self'` holds; it `fetch`es `/openapi.yaml` (hence `connect-src 'self'`).
 */
internal object DocsAssets {
    val openApiYaml: ByteArray = resource("api/openapi.yaml")
    val docsHtml: ByteArray = resource("web/docs.html")
    val docsJs: ByteArray = resource("web/docs.js")

    /** Inline `<style>` in docs.html is hash-pinned (same posture as the dashboard) — no unsafe-inline. */
    val csp: String = styleHashCsp(docsHtml.decodeToString())

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "embedded docs resource missing: $path"
        }.use { it.readBytes() }
}

/**
 * Shared CSP for the embedded static pages, with every inline `<style>` body hash-pinned
 * so `style-src` never needs `'unsafe-inline'`.
 *
 * The tag patterns mirror the HTML tokenizer: a tag name ends only at tab/LF/FF/CR/space,
 * `/` or `>`, and end-tag attributes are ignored — so `<style media=…>`, `<STYLE>` and
 * `</style >` all pair exactly as a browser pairs them (plan 103; same fix class as plan
 * 102 in validate.mjs). The exact-terminator lookahead, not `\b`, is load-bearing: `\b`
 * would also fire on non-tags like `</style-x>` and truncate a block early, hashing the
 * wrong body. A block the extraction missed would fail closed at the browser (missing
 * hash → style blocked), but silently un-styling the page is still a shipped bug, so any
 * `<style` open left over *outside* the paired blocks fails a check. Only the text
 * outside paired blocks is scanned for leftovers: a block's raw-text body may legally
 * contain a literal `<style` (CSS string or comment), which a tokenizer never rescans.
 * The asset objects are lazily initialized, so [Route.dashboardRoutes] touches both CSPs
 * during route registration — a malformed bundled page fails the boot, not the first
 * request, matching the missing-resource posture above. Accepted limit: a quoted `>`
 * inside an *open*-tag attribute truncates the hashed body — that also fails closed at
 * the browser (missing hash → style blocked), never widening the policy.
 */
private val styleBlockPattern =
    Regex(
        """<style(?=[\t\n\f\r />])[^>]*>([\s\S]*?)</style(?=[\t\n\f\r />])[^>]*>""",
        RegexOption.IGNORE_CASE
    )
private val styleOpenPattern = Regex("""<style(?=[\t\n\f\r />])""", RegexOption.IGNORE_CASE)

internal fun styleHashCsp(html: String): String {
    val blocks = styleBlockPattern.findAll(html).toList()
    var cursor = 0
    var dangling = 0
    for (block in blocks) {
        dangling += styleOpenPattern.findAll(html.substring(cursor, block.range.first)).count()
        cursor = block.range.last + 1
    }
    dangling += styleOpenPattern.findAll(html.substring(cursor)).count()
    check(dangling == 0) {
        "unclosed or mis-paired <style> element in an embedded page "+
                "($dangling dangling open tags, ${blocks.size} paired blocks)"
    }
    val styleHashes = blocks.map { match ->
        val digest =
            MessageDigest.getInstance("SHA-256").digest(match.groupValues[1].encodeToByteArray())
        "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'"
    }
    val styleSrc = if (styleHashes.isEmpty()) "'none'" else styleHashes.joinToString(" ")
    return "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; " +
            "style-src $styleSrc; script-src 'self'; connect-src 'self'"
}

fun Route.dashboardRoutes() {
    // Route registration runs during module setup, and the asset objects are lazily
    // initialized: touching both CSPs here makes a malformed embedded page (or missing
    // resource) fail the boot instead of surfacing as an ExceptionInInitializerError on
    // the first request to these routes (plan 103 reviews).
    check(DashboardAssets.csp.isNotEmpty() && DocsAssets.csp.isNotEmpty())
    get("/") {
        call.dashboardHeaders()
        call.respondBytes(
            DashboardAssets.indexHtml,
            ContentType.Text.Html.withCharset(Charsets.UTF_8)
        )
    }
    get("/dashboard.js") {
        call.dashboardHeaders()
        call.respondBytes(
            DashboardAssets.dashboardJs,
            ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
        )
    }
    get("/timeline.js") {
        call.dashboardHeaders()
        call.respondBytes(
            DashboardAssets.timelineJs,
            ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
        )
    }
    get("/uplot.js") {
        call.dashboardHeaders()
        call.respondBytes(
            DashboardAssets.uplotJs,
            ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
        )
    }
    // Zero-CDN API docs (plan 042): the spec is public (docs, not data); the viewer + its script are
    // served under the docs CSP.
    get("/openapi.yaml") {
        call.docsHeaders()
        call.respondBytes(
            DocsAssets.openApiYaml,
            ContentType("application", "yaml").withCharset(Charsets.UTF_8)
        )
    }
    get("/docs") {
        call.docsHeaders()
        call.respondBytes(DocsAssets.docsHtml, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    get("/docs.js") {
        call.docsHeaders()
        call.respondBytes(
            DocsAssets.docsJs,
            ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
        )
    }
}

private fun ApplicationCall.docsHeaders() {
    response.header("Content-Security-Policy", DocsAssets.csp)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("X-Frame-Options", "DENY")
    response.header("Cache-Control", "no-cache")
}

private fun ApplicationCall.dashboardHeaders() {
    response.header("Content-Security-Policy", DashboardAssets.csp)
    response.header("X-Content-Type-Options", "nosniff")
    // The page hosts token entry; never allow it to be framed (clickjacking).
    response.header("X-Frame-Options", "DENY")
    // Revalidate on every load so a server upgrade can't leave stale JS against a changed API.
    response.header("Cache-Control", "no-cache")
}
