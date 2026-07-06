package dev.buildhound.gradle

import dev.buildhound.commons.payload.KotlinInfo
import dev.buildhound.commons.payload.KotlinTaskReport
import java.io.File

/**
 * Reads and bundles the KGP json build report into a [KotlinInfo] at build finish (plan 023).
 * All file IO is here, at execution time — no configuration-phase reads (architecture §2.9).
 * Never throws: any failure degrades to `null` (absent over wrong), honoring the never-fail rule.
 *
 * Logging is injected as a `warn` sink rather than referencing Gradle's `Logging` directly, so
 * this object carries no Gradle API dependency and its pure logic is unit-testable off the
 * Gradle classpath. The caller (the finalizer) supplies its own logger.
 *
 * Window matching: KGP writes its report from its own build-finish machinery whose ordering
 * against our FlowAction is unspecified, and it appends timestamped files across builds — so
 * only files modified since `startedAt − 60 s` are candidates, and "no in-window file" degrades
 * to `null` rather than bundling stale data from a previous build.
 */
internal object KotlinReportBundler {

    private const val MAX_FILES = 20
    private const val MAX_FILE_BYTES = 10L * 1024 * 1024
    private const val WINDOW_MARGIN_MS = 60_000L
    private const val MAX_TASKS = 200

    fun bundle(jsonDirectory: String?, startedAtMs: Long, rootDir: String? = null, warn: (String) -> Unit = {}): KotlinInfo? {
        if (jsonDirectory.isNullOrBlank()) return null
        return runCatching {
            val dir = resolveReportDir(jsonDirectory, rootDir)
            // A missing directory is the normal "no Kotlin report this build" case (e.g. a `help` run,
            // or an up-to-date build where nothing recompiled) — absent, not misconfigured, so stay
            // silent. Only a path that exists but is a *file* is a real misconfiguration worth a warn.
            if (!dir.exists()) return null
            if (!dir.isDirectory) {
                warn("[buildhound] kotlin report path is not a directory (a file exists there); skipping: ${dir.name}")
                return null
            }
            val candidates = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.filter { it.lastModified() >= startedAtMs - WINDOW_MARGIN_MS }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            if (candidates.isEmpty()) return null // no in-window report — absent, not stale
            if (candidates.size > MAX_FILES) {
                warn("[buildhound] $MAX_FILES+ kotlin report files in window; bundling the $MAX_FILES newest")
            }

            var reportSchema: String? = null
            val allTasks = ArrayList<KotlinTaskReport>()
            for (file in candidates.take(MAX_FILES)) {
                if (file.length() > MAX_FILE_BYTES) {
                    warn("[buildhound] kotlin report file over ${MAX_FILE_BYTES / (1024 * 1024)} MiB; skipped")
                    continue
                }
                val parsed = KotlinReportParser.parse(file.readText()) ?: continue
                reportSchema = reportSchema ?: parsed.reportSchema
                allTasks += parsed.tasks
            }
            if (allTasks.isEmpty()) return null

            val ranked = allTasks.sortedByDescending { it.durationMs ?: 0L }
            val kept = ranked.take(MAX_TASKS)
            KotlinInfo(
                reportSchema = reportSchema ?: "unknown",
                perTask = kept,
                truncatedTasks = ranked.size - kept.size,
            )
        }.getOrElse {
            warn("[buildhound] kotlin report bundling failed (build unaffected): ${it::class.java.simpleName}")
            null
        }
    }

    /**
     * KGP resolves a *relative* `kotlin.build.report.json.directory` against the root project directory
     * and writes the report there. We must resolve it the same way: `File(relativePath)` alone would be
     * relative to the daemon's working directory, which can differ from the root (a reused daemon, or a
     * build launched from elsewhere), making us miss a report KGP actually wrote. Absolute paths are
     * used verbatim; a null [rootDir] falls back to the old working-dir behaviour.
     */
    private fun resolveReportDir(jsonDirectory: String, rootDir: String?): File {
        val configured = File(jsonDirectory)
        return if (configured.isAbsolute || rootDir == null) configured else File(rootDir, jsonDirectory)
    }

    /**
     * Non-noisy wiring hint (spec §3.4). Silent when disabled; when the report output is `json`
     * but no directory is set, or when nothing is wired and the build ran Kotlin compilations,
     * prints one copy-paste `gradle.properties` block. Non-Kotlin projects stay silent.
     */
    fun warnIfMisconfigured(
        enabled: Boolean,
        reportOutput: String?,
        jsonDirectory: String?,
        hasKotlinCompilations: Boolean,
        warn: (String) -> Unit,
    ) {
        misconfigurationWarning(enabled, reportOutput, jsonDirectory, hasKotlinCompilations)
            ?.let { warn("[buildhound] $it") }
    }

    /** Pure decision behind [warnIfMisconfigured]; null when nothing should be logged. */
    fun misconfigurationWarning(
        enabled: Boolean,
        reportOutput: String?,
        jsonDirectory: String?,
        hasKotlinCompilations: Boolean,
    ): String? {
        if (!enabled) return null
        val jsonRequested = reportOutput?.contains("json", ignoreCase = true) == true
        return when {
            jsonRequested && !jsonDirectory.isNullOrBlank() -> null // correctly wired
            jsonRequested ->
                "kotlin.build.report.output=JSON but kotlin.build.report.json.directory is unset — Kotlin metrics can't be bundled.\n$COPY_PASTE"
            hasKotlinCompilations ->
                "Kotlin compilations ran but no build report is wired — add to gradle.properties to bundle Kotlin metrics:\n$COPY_PASTE"
            else -> null
        }
    }

    private val COPY_PASTE =
        "    kotlin.build.report.output=JSON\n" +
            "    kotlin.build.report.json.directory=build/kotlin-build-reports"
}
