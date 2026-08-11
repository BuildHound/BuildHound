package dev.buildhound.server

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the properties that made a third-party charting library admissible here at all
 * (plan 108). The dashboard's CSP is `default-src 'none'` with `style-src` carrying
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

    /**
     * sha256 of `dist/uPlot.iife.min.js` from the recorded upstream tarball. This is the check
     * that makes the greps below meaningful rather than decorative: without it, a re-vendoring
     * that placed the provenance block *after* the code would leave [marker] at the end of the
     * file, hand every grep a one-character body, and pass all of them while scanning nothing.
     * Bump this and the header together, never one alone.
     */
    private val upstreamSha256 = "19c8d4c6ad88929a79f4ae49d6f7161566dfd0ba3d15cc495e974f787eb78f1f"

    private fun vendoredBody(): String {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("web/uplot.js"))
            .use { it.readBytes() }.decodeToString()
        val headerEnd = text.indexOf(marker)
        assertTrue(headerEnd >= 0, "the vendored file must keep its provenance/license header")
        return text.substring(headerEnd + marker.length)
    }

    @Test
    fun `vendored chart library is byte-identical to the recorded upstream release`() {
        // Everything else in this class is a substring search, and a substring search over the
        // wrong bytes proves nothing. Pinning the digest is what turns "we checked the library"
        // into a claim a reviewer can trust after a version bump — and it subsumes every grep
        // below for the bytes as shipped today.
        val body = vendoredBody().trimStart('\n')
        val digest = MessageDigest.getInstance("SHA-256").digest(body.encodeToByteArray())
        // Name the likeliest cause in the failure itself: a checkout that rewrote LF to CRLF
        // fails this with no other symptom, and the digest alone does not say so. `.gitattributes`
        // marks this path `-text` to prevent it; if that is ever dropped, this message is the
        // only breadcrumb.
        val eolHint = if (body.contains('\r')) {
            " — the file contains CR bytes, so the checkout rewrote its line endings; check that" +
                " .gitattributes still marks this path `-text`"
        } else {
            ""
        }
        assertEquals(
            upstreamSha256,
            digest.joinToString("") { "%02x".format(it) },
            "the bytes below the provenance header must be the recorded upstream release, unmodified$eolHint",
        )
    }

    @Test
    fun `vendored chart library sets no style attribute`() {
        // The decisive check. `style-src` pins hashes and never allows `'unsafe-inline'`, and
        // hashes do not cover style *attributes*, so setAttribute("style", …) is blocked. This
        // is not hypothetical: the runner-up library emits 14 such attributes per chart through
        // a generic attribute helper that a search for the literal call site does not find —
        // hence the search for `setAttribute` at all, not for `setAttribute("style"`.
        //
        // Note what this does NOT say: uPlot does set inline styles, through the CSSOM
        // (`el.style.width = …`). That is deliberate and safe — `style-src` governs style
        // attributes and `<style>` elements, not CSSOM assignment, which is precisely why this
        // library works under a hash-only policy at all.
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
        // `connect-src 'self'` and the absence of img-src/worker-src/child-src mean most of
        // these would be blocked anyway; a chart library still has no business calling out.
        //
        // `location` earns its place for the opposite reason: the CSP does NOT constrain
        // top-level navigation (no form-action, no navigate-to), so a `top.location = …` is
        // the one exfiltration path the policy would not stop — and the dashboard's read token
        // lives in localStorage. Storage and postMessage are listed for the same reason.
        val hazards = listOf(
            "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "EventSource",
            "new Worker", "createObjectURL", "import(", "toDataURL", "new Image",
            "location", "postMessage", "localStorage", "sessionStorage", "document.cookie",
            "sourceMappingURL",
        )
        for (hazard in hazards) {
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
