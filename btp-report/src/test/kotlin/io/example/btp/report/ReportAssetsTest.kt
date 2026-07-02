package io.example.btp.report

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
        assertFalse(template.contains("http://"), "template must not reference external resources")
        assertFalse(template.contains("https://"), "template must not reference external resources")
    }

    @Test
    fun `render embeds the payload json`() {
        val rendered = ReportAssets.render("""{"buildId":"abc"}""")

        assertTrue(rendered.contains("""{"buildId":"abc"}"""))
        assertFalse(rendered.contains(ReportAssets.DATA_PLACEHOLDER))
    }
}
