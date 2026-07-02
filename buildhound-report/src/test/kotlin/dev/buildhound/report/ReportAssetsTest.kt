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
