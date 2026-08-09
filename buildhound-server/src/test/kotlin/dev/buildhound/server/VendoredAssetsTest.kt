package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the properties that made a third-party charting library admissible here at all
 * (plan 105). The dashboard's CSP is `default-src 'none'` with `style-src` carrying
 * sha256 hashes and no `'unsafe-inline'`, `script-src 'self'`, and no `'unsafe-eval'`,
 * so a library that sets an inline `style` attribute, injects a `<style>` element, or
 * reaches for dynamic code, network or workers is silently broken in a browser — and
 * neither the node smoke harness nor a Ktor route test would notice.
 *
 * uPlot was selected because it does none of those things, on the actual downloaded
 * bytes. That was a selection-time measurement; this test is what keeps it true. A
 * version bump that reintroduces one of these fails here rather than in production.
 *
 * The scan starts after the BuildHound provenance header so the header's own prose can
 * name the hazards it is documenting.
 */
class VendoredAssetsTest {

    private val marker = "// THE SOFTWARE."

    private fun vendoredBody(): String {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("web/uplot.js"))
            .use { it.readBytes() }.decodeToString()
        val headerEnd = text.indexOf(marker)
        assertTrue(headerEnd >= 0, "the vendored file must keep its provenance/license header")
        return text.substring(headerEnd + marker.length)
    }

    @Test
    fun `vendored chart library sets no inline style attribute`() {
        // The decisive check. `style-src` pins hashes and never allows `'unsafe-inline'`, and
        // hashes do not cover style *attributes*, so setAttribute("style", …) is blocked. This
        // is not hypothetical: the runner-up library emits 14 such attributes per chart through
        // a generic attribute helper that a search for the literal call site does not find.
        val body = vendoredBody()
        assertFalse(body.contains("setAttribute"), "a vendored chart library must not set attributes")
        assertFalse(body.contains("cssText"), "cssText would bypass the hash-pinned style-src")
        assertFalse(body.contains("insertRule"), "insertRule would bypass the hash-pinned style-src")
        assertFalse(body.contains("adoptedStyleSheets"), "adoptedStyleSheets would bypass the hash-pinned style-src")
    }

    @Test
    fun `vendored chart library injects no style element`() {
        // A runtime-injected <style> is unhashable, so it is blocked and the chart silently
        // renders unstyled. The served page's style-block/hash parity test cannot see this:
        // the element never appears in the served HTML.
        val body = vendoredBody()
        for (spelling in listOf("createElement(\"style\"", "createElement('style'", "\"style\")", "'style')")) {
            assertFalse(body.contains(spelling), "a vendored chart library must not create a <style> element: $spelling")
        }
    }

    @Test
    fun `vendored chart library uses no dynamic code evaluation`() {
        val body = vendoredBody()
        // The CSP carries no 'unsafe-eval'; either of these would throw at runtime.
        assertFalse(body.contains("eval("), "eval() is blocked by the CSP")
        assertFalse(body.contains("new Function("), "new Function() is blocked by the CSP")
        assertFalse(body.contains("document.write"), "document.write has no place in a charting library")
    }

    @Test
    fun `vendored chart library makes no network or worker calls`() {
        val body = vendoredBody()
        // `connect-src 'self'` and the absence of img-src/worker-src/child-src mean anything
        // here would be blocked; more to the point, a chart library has no business calling out.
        for (hazard in listOf("fetch(", "XMLHttpRequest", "new Worker", "createObjectURL", "import(", "toDataURL")) {
            assertFalse(body.contains(hazard), "a vendored chart library must not use $hazard")
        }
    }

    @Test
    fun `vendored chart library is not reachable from the standalone report`() {
        // The report artifact is network-free and dependency-free by locked decision #4, and
        // ReportAssetsTest enforces that on the template. The dependency direction is
        // server -> report, so nothing stops a future edit from splicing this file into the
        // report; this asserts the library stays on the server side of that line.
        assertEquals(
            null,
            javaClass.classLoader.getResource("dev/buildhound/report/uplot.js"),
            "the vendored chart library must never be packaged into the report module",
        )
    }
}
