package dev.buildhound.report

/**
 * Access to the standalone HTML report template (spec §3.8). The artifact is fully self-contained —
 * inlined CSS/JS, zero CDN/network requests (locked decision #4) — so it renders inside CI artifact
 * viewers and email attachments. The build payload is injected as one JSON blob replacing
 * [DATA_PLACEHOLDER].
 */
object ReportAssets {

    /** Includes the `null` fallback so the template is valid JS before AND after render. */
    const val DATA_PLACEHOLDER: String = "/*__BUILDHOUND_DATA__*/null"

    /** Replaced at [template] time by the shared timeline renderer (plan 017). */
    const val TIMELINE_PLACEHOLDER: String = "/*__BUILDHOUND_TIMELINE_JS__*/"

    /**
     * The template with the shared [timelineJs] renderer spliced into its script element, so
     * `template()` always returns the complete self-contained document — the existing zero-network
     * test then covers the timeline code for free (plan 017). The [DATA_PLACEHOLDER] is left intact
     * for [render].
     */
    fun template(): String {
        val spliced = rawTemplate().replace(TIMELINE_PLACEHOLDER, timelineJs())
        check(!spliced.contains(TIMELINE_PLACEHOLDER)) {
            "timeline placeholder was not spliced into the report template"
        }
        return spliced
    }

    /** The shared timeline renderer source (also served verbatim to the dashboard). */
    fun timelineJs(): String =
        resource("/dev/buildhound/report/timeline.js").also {
            // It is spliced into a <script> element; a </script sequence would end it early.
            require(!it.contains("</script", ignoreCase = true)) {
                "timeline.js must not contain a </script sequence"
            }
        }

    private fun rawTemplate(): String = resource("/dev/buildhound/report/report-template.html")

    private fun resource(path: String): String =
        checkNotNull(ReportAssets::class.java.getResourceAsStream(path)) {
                "missing from buildhound-report resources: $path"
            }
            .readBytes()
            .decodeToString()

    /**
     * Embeds the payload into the template's script element. `<` is escaped to `\\u003c` (valid
     * inside JSON strings) so payload content like `</script>` can never terminate the script
     * element — payload strings are untrusted in an HTML context.
     */
    fun render(payloadJson: String): String =
        template().replace(DATA_PLACEHOLDER, payloadJson.replace("<", "\\u003c"))
}
