package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import java.net.URI
import java.util.Locale

/** Provider-native CI job summaries rendered only from the already-scrubbed payload (plan 055). */
internal object CiJobSummary {
    private const val MAX_SUMMARY_CHARS = 16_384

    fun render(payload: BuildPayload, dashboardBaseUrl: String?): String {
        val durationMs = (payload.finishedAt - payload.startedAt).coerceAtLeast(0)
        val hitRate = payload.derived?.cacheableHitRate
            ?.let { "%.1f%%".format(Locale.ROOT, it * 100.0) }
            ?: "n/a"
        val tasks = payload.requestedTasks
            .joinToString(" ") { markdownText(it) }
            .ifEmpty { "(default tasks)" }
        val link = dashboardBaseUrl?.let(::safeDashboardBaseUrl)
            ?.let { "$it/#/build/${urlSegment(payload.buildId)}" }

        return buildString {
            appendLine("## BuildHound")
            appendLine()
            appendLine("| Build | Value |")
            appendLine("|---|---:|")
            appendLine("| Outcome | ${markdownText(payload.outcome.name)} |")
            appendLine("| Duration | ${formatDuration(durationMs)} |")
            appendLine("| Cacheable hit rate | $hitRate |")
            appendLine("| Requested tasks | $tasks |")
            if (link != null) appendLine("| Dashboard | [Open build](<$link>) |")
        }.take(MAX_SUMMARY_CHARS)
    }

    fun write(
        payload: BuildPayload,
        provider: String?,
        env: Map<String, String>,
        dashboardBaseUrl: String?,
        outputDir: File,
        warn: (String) -> Unit,
    ) {
        val markdown = render(payload, dashboardBaseUrl)
        when (provider) {
            "github-actions" -> {
                val path = env["GITHUB_STEP_SUMMARY"]?.takeIf { it.isNotBlank() } ?: return
                runCatching { File(path).appendText("\n$markdown") }
                    .onFailure { warn(it.message ?: "GitHub summary is not writable") }
            }
            "azure-devops" -> {
                val file = File(outputDir, "job-summary.md")
                runCatching {
                    file.parentFile.mkdirs()
                    file.writeText(markdown)
                    println("##vso[task.uploadsummary]${file.absolutePath}")
                }.onFailure { warn(it.message ?: "Azure summary is not writable") }
            }
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(Locale.ROOT, ms / 1_000.0)
        else -> "%dm %02ds".format(Locale.ROOT, ms / 60_000, ms / 1_000 % 60)
    }

    private fun markdownText(value: String): String =
        value.replace("\\", "\\\\").replace("|", "\\|").replace(Regex("[\\r\\n]+"), " ").take(1_024)

    private fun urlSegment(value: String): String =
        value.toByteArray().joinToString("") { byte ->
            val unsigned = byte.toInt() and 0xff
            if ((unsigned in 'a'.code..'z'.code) || (unsigned in 'A'.code..'Z'.code) ||
                (unsigned in '0'.code..'9'.code) || unsigned == '-'.code || unsigned == '_'.code
            ) byte.toInt().toChar().toString() else "%%%02X".format(Locale.ROOT, unsigned)
        }

    /** Reject credential/query-bearing or structurally ambiguous bases before persisting them in CI. */
    private fun safeDashboardBaseUrl(value: String): String? = runCatching {
        if (value.any { it.isISOControl() }) return null
        val parsed = URI(value.trim())
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") return null
        if (parsed.host.isNullOrBlank() || parsed.userInfo != null || parsed.query != null || parsed.fragment != null) return null
        URI(scheme, null, parsed.host, parsed.port, parsed.path?.trimEnd('/').orEmpty(), null, null).toASCIIString()
    }.getOrNull()
}
