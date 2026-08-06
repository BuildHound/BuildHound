package dev.buildhound.server

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The CSP style-hash extraction must pair `<style>` blocks exactly as the HTML tokenizer
 * does (plan 103): a tag name ends only at tab/LF/FF/CR/space, `/` or `>`, end-tag
 * attributes are ignored, and anything else after the name is raw text.
 */
class StyleHashCspTest {

    private fun sha256Token(body: String): String =
        "'sha256-" + Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(body.encodeToByteArray())
        ) + "'"

    @Test
    fun `hashes the body of a bare style block`() {
        val csp = styleHashCsp("<html><style>a{color:red}</style></html>")
        assertTrue(csp.contains("style-src ${sha256Token("a{color:red}")}"), csp)
    }

    @Test
    fun `attribute, case, and close-variant style tags are all found and hashed`() {
        val csp = styleHashCsp(
            """<style media="screen">a{}</style><STYLE>b{}</STYLE><style>c{}</style >"""
        )
        for (body in listOf("a{}", "b{}", "c{}")) {
            assertTrue(csp.contains(sha256Token(body)), "missing hash for $body in $csp")
        }
    }

    @Test
    fun `a non-tag like style-x stays raw text instead of closing the block early`() {
        // A browser reads `</style-x>` as text, so the element's body runs to the real
        // close; hashing a truncated body would pin the wrong content.
        val csp = styleHashCsp("<style>a</style-x>b</style>")
        assertTrue(csp.contains(sha256Token("a</style-x>b")), csp)
        assertEquals(1, Regex("'sha256-").findAll(csp).count(), csp)
    }

    @Test
    fun `a style-prefixed custom element is not a style block`() {
        val csp = styleHashCsp("<style-custom>x</style-custom>")
        assertTrue(csp.contains("style-src 'none'"), csp)
    }

    @Test
    fun `no style blocks yields style-src none`() {
        assertTrue(styleHashCsp("<html><body>plain</body></html>").contains("style-src 'none'"))
    }

    @Test
    fun `an unclosed style block fails init instead of silently dropping its hash`() {
        assertFailsWith<IllegalStateException> { styleHashCsp("<html><style>a{color:red}") }
    }

    @Test
    fun `a literal style tag inside a block's raw text is not a dangling open`() {
        // A tokenizer never rescans raw text, so a CSS string or comment containing
        // "<style" must not trip the dangling-open check (plan 103 quality review).
        val inString = """a{content:"<style>"}b"""
        assertTrue(styleHashCsp("<style>$inString</style>").contains(sha256Token(inString)))
        val inComment = "/* see <style tag */ a{}"
        assertTrue(styleHashCsp("<style>$inComment</style>").contains(sha256Token(inComment)))
    }

    @Test
    fun `a dangling open after the last paired block still fails`() {
        // (An extra open *before* a paired block is not dangling: the browser pairs the
        // first open with the first close, swallowing the inner tag as raw text — the
        // block regex does the same, so those stay in agreement.)
        assertFailsWith<IllegalStateException> {
            styleHashCsp("<style>a{}</style><footer></footer><style media=x>")
        }
    }
}
