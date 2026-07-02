package dev.buildhound.report

/**
 * Access to the standalone HTML report template (spec §3.8). The artifact is fully
 * self-contained — inlined CSS/JS, zero CDN/network requests (locked decision #4) — so it
 * renders inside CI artifact viewers and email attachments. The build payload is injected
 * as one JSON blob replacing [DATA_PLACEHOLDER].
 */
object ReportAssets {

    const val DATA_PLACEHOLDER: String = "/*__BUILDHOUND_DATA__*/"

    fun template(): String =
        checkNotNull(ReportAssets::class.java.getResourceAsStream("/dev/buildhound/report/report-template.html")) {
            "report-template.html missing from buildhound-report resources"
        }.readBytes().decodeToString()

    fun render(payloadJson: String): String = template().replace(DATA_PLACEHOLDER, payloadJson)
}
