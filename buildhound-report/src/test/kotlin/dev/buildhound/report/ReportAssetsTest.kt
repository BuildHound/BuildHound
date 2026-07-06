package dev.buildhound.report

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportAssetsTest {

    @Test
    fun `template is present and contains the data placeholder`() {
        val template = ReportAssets.template()

        assertTrue(template.contains(ReportAssets.DATA_PLACEHOLDER))
        assertTrue(template.contains("<!DOCTYPE html>"))
    }

    @Test
    fun `summary wires a chip for every toolchain dimension`() {
        val template = ReportAssets.template()

        // gradle/jdk plus the AGP/KGP/KSP dimensions (plan 046). Each renders via the chip()/el()
        // textContent path, so an attacker-controlled plugin version string cannot inject markup.
        for (dim in listOf("gradle", "jdk", "agp", "kgp", "ksp")) {
            assertTrue(template.contains("d.toolchain.$dim"), "toolchain chip for '$dim' must be wired")
        }
    }

    @Test
    fun `template splices the shared timeline renderer and leaves no placeholder`() {
        val template = ReportAssets.template()

        assertTrue(template.contains("function buildhoundTimeline"), "timeline renderer must be spliced in")
        assertFalse(template.contains(ReportAssets.TIMELINE_PLACEHOLDER), "timeline placeholder must be consumed")
        // The data placeholder survives template() — render() replaces it later.
        assertTrue(template.contains(ReportAssets.DATA_PLACEHOLDER))
    }

    @Test
    fun `timeline js is safe to splice into a script element`() {
        // A </script sequence would terminate the host <script> early; timelineJs() also
        // asserts this itself, so this pins the contract from the test side too.
        assertFalse(ReportAssets.timelineJs().contains("</script", ignoreCase = true))
    }

    @Test
    fun `template makes no external requests`() {
        val template = ReportAssets.template()

        // Locked decision #4: fully standalone artifact, zero CDN/network access.
        for (marker in listOf("http://", "https://", "url(", "@import", "<link", "<img", "fetch(", "XMLHttpRequest", "import(")) {
            assertFalse(template.contains(marker), "template must not reference external resources: $marker")
        }
    }

    @Test
    fun `render embeds the payload json`() {
        val rendered = ReportAssets.render("""{"buildId":"abc"}""")

        // The whole assignment must be syntactically valid JS: no trailing null sentinel.
        assertTrue(rendered.contains("""const buildhoundData = {"buildId":"abc"};"""))
        assertFalse(rendered.contains(ReportAssets.DATA_PLACEHOLDER))
        assertTrue(rendered.startsWith("<!DOCTYPE html>"))
    }

    @Test
    fun `render escapes script breakouts in payload strings`() {
        val rendered = ReportAssets.render("""{"branch":"</script><script>alert(1)//"}""")

        assertFalse(rendered.contains("</script><script>"), "payload must not escape the script element")
        assertTrue(rendered.contains("\\u003c/script>\\u003cscript>alert(1)//"))
    }
}
