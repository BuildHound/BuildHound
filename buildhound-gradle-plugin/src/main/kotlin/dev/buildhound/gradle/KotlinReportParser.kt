package dev.buildhound.gradle

import dev.buildhound.commons.payload.KotlinTaskReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient, name-keyed parser over the KGP json build report (plan 023). The report is an
 * unstable INTERNAL KGP format, so: every field is optional, extraction is by name (never
 * position), unknown fields are ignored, and only an allowlist is retained — never compiler
 * arguments, changed files, or IC log lines (they carry absolute paths / classpaths, §3.7).
 * Pure over strings (no Gradle/file types) so it unit-tests directly (plan 004 retro). Report
 * shape verified by the plan's ordering spike (§4a).
 */
internal object KotlinReportParser {

    data class Parsed(val reportSchema: String?, val tasks: List<KotlinTaskReport>)

    private const val MAX_REASONS = 10
    private const val MAX_REASON_CHARS = 200
    private const val MAX_PHASE_KEYS = 32
    private const val MAX_KEY_CHARS = 64
    // `path` comes from an untrusted report file (unlike Gradle's own event stream), so it is
    // bounded like every other retained string — an adversarial multi-MB path can't inflate the
    // payload. Generous enough for real `:a:b:compileReleaseUnitTestKotlin`-style task paths.
    private const val MAX_PATH_CHARS = 200

    /** Parses one report file's json; null when it is not a usable KGP report. Never throws. */
    fun parse(json: String): Parsed? = runCatching {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return@runCatching null
        val records = root["buildOperationRecord"] as? JsonArray ?: return@runCatching null
        var schema: String? = null
        val tasks = records.mapNotNull { element ->
            val record = element as? JsonObject ?: return@mapNotNull null
            if (record.bool("isFromKotlinPlugin") != true) return@mapNotNull null
            val path = record.str("path") ?: return@mapNotNull null
            schema = schema ?: record.str("kotlinLanguageVersion")
            val metrics = record["buildMetrics"] as? JsonObject
            val attributes = (metrics?.get("buildAttributes") as? JsonObject)?.get("myAttributes") as? JsonObject
            KotlinTaskReport(
                taskPath = path.take(MAX_PATH_CHARS),
                durationMs = record.long("totalTimeMs"),
                incremental = attributes?.isEmpty(),
                nonIncrementalReasons = attributes?.keys?.take(MAX_REASONS)?.map { it.take(MAX_REASON_CHARS) }.orEmpty(),
                compilerTimesMs = phaseTimes(metrics),
                linesOfCode = linesOfCode(metrics),
            )
        }
        Parsed(reportSchema = schema ?: "unknown", tasks = tasks)
    }.getOrNull()

    /** buildMetrics.buildTimes.buildTimesNs = [[{name,…}, nanos], …] → { name: ms }. */
    private fun phaseTimes(metrics: JsonObject?): Map<String, Long> {
        val pairs = (metrics?.get("buildTimes") as? JsonObject)?.get("buildTimesNs") as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (pair in pairs) {
            if (out.size >= MAX_PHASE_KEYS) break
            val entry = pair as? JsonArray ?: continue
            val name = (entry.getOrNull(0) as? JsonObject)?.str("name") ?: continue
            val nanos = (entry.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: continue
            out[name.take(MAX_KEY_CHARS)] = nanos / 1_000_000
        }
        return out
    }

    /** buildMetrics.buildPerformanceMetrics.myBuildMetrics = [[{name,…}, value], …]; SOURCE_LINES_NUMBER. */
    private fun linesOfCode(metrics: JsonObject?): Long? {
        val pairs = (metrics?.get("buildPerformanceMetrics") as? JsonObject)?.get("myBuildMetrics") as? JsonArray ?: return null
        for (pair in pairs) {
            val entry = pair as? JsonArray ?: continue
            if ((entry.getOrNull(0) as? JsonObject)?.str("name") == "SOURCE_LINES_NUMBER") {
                return (entry.getOrNull(1) as? JsonPrimitive)?.longOrNull
            }
        }
        return null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
