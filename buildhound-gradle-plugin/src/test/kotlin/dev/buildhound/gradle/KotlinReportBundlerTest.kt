package dev.buildhound.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class KotlinReportBundlerTest {

    @field:TempDir
    lateinit var dir: File

    private val validReport =
        """{"buildOperationRecord":[{"path":":compileKotlin","isFromKotlinPlugin":true,"totalTimeMs":100,
           "buildMetrics":{"buildTimes":{"buildTimesNs":[[{"name":"RUN_COMPILATION"},50000000]]}}}]}"""

    private fun report(name: String, lastModified: Long, content: String = validReport) =
        File(dir, name).apply { writeText(content); setLastModified(lastModified) }

    @Test
    fun bundles_an_in_window_report() {
        val now = System.currentTimeMillis()
        report("r.json", now)

        val info = KotlinReportBundler.bundle(dir.absolutePath, now - 1000)!!
        assertEquals(":compileKotlin", info.perTask.single().taskPath)
        assertEquals(50, info.perTask.single().compilerTimesMs["RUN_COMPILATION"])
        assertEquals(0, info.truncatedTasks)
    }

    @Test
    fun ignores_a_stale_report_from_a_previous_build() {
        report("old.json", 1_000L) // 1970 — well before any window
        assertNull(KotlinReportBundler.bundle(dir.absolutePath, System.currentTimeMillis()))
    }

    @Test
    fun degrades_to_null_when_the_path_is_a_file_not_a_directory() {
        val file = File(dir, "notadir.json").apply { writeText("x") }
        assertNull(KotlinReportBundler.bundle(file.absolutePath, System.currentTimeMillis() - 1000))
    }

    @Test
    fun degrades_to_null_and_never_throws_on_garbage_json() {
        report("garbage.json", System.currentTimeMillis(), "not json")
        assertNull(KotlinReportBundler.bundle(dir.absolutePath, System.currentTimeMillis() - 1000))
    }

    @Test
    fun null_directory_or_missing_directory_returns_null() {
        assertNull(KotlinReportBundler.bundle(null, System.currentTimeMillis()))
        assertNull(KotlinReportBundler.bundle(File(dir, "does-not-exist").absolutePath, System.currentTimeMillis()))
    }

    @Test
    fun ranks_by_duration_and_truncates_beyond_the_task_cap() {
        val now = System.currentTimeMillis()
        val records = (1..250).joinToString(",") {
            """{"path":":m$it:compileKotlin","isFromKotlinPlugin":true,"totalTimeMs":$it}"""
        }
        report("many.json", now, """{"buildOperationRecord":[$records]}""")

        val info = KotlinReportBundler.bundle(dir.absolutePath, now - 1000)!!
        assertEquals(200, info.perTask.size, "capped to MAX_TASKS")
        assertEquals(50, info.truncatedTasks)
        // Ranked by duration descending, so the slowest (250) is kept and first.
        assertEquals(250, info.perTask.first().durationMs)
        assertEquals(51, info.perTask.last().durationMs, "the 200 slowest survive; 1..50 are dropped")
    }

    @Test
    fun merges_tasks_across_files_taking_the_schema_from_the_newest() {
        val now = System.currentTimeMillis()
        report("older.json", now - 2000, """{"buildOperationRecord":[{"path":":a:compileKotlin","isFromKotlinPlugin":true,"totalTimeMs":10,"kotlinLanguageVersion":"KOTLIN_2_3"}]}""")
        report("newer.json", now, """{"buildOperationRecord":[{"path":":b:compileKotlin","isFromKotlinPlugin":true,"totalTimeMs":20,"kotlinLanguageVersion":"KOTLIN_2_4"}]}""")

        val info = KotlinReportBundler.bundle(dir.absolutePath, now - 5000)!!
        assertEquals(setOf(":a:compileKotlin", ":b:compileKotlin"), info.perTask.map { it.taskPath }.toSet())
        assertEquals("KOTLIN_2_4", info.reportSchema, "schema taken from the newest report first")
    }

    @Test
    fun warning_matrix_decisions() {
        // Disabled → silent.
        assertNull(KotlinReportBundler.misconfigurationWarning(false, "JSON", null, true))
        // Correctly wired → silent.
        assertNull(KotlinReportBundler.misconfigurationWarning(true, "JSON", "build/reports", true))
        // JSON requested but no directory → warn.
        assertTrue(KotlinReportBundler.misconfigurationWarning(true, "json", null, false)!!.contains("json.directory"))
        // Nothing wired + Kotlin compilations ran → copy-paste hint.
        assertTrue(KotlinReportBundler.misconfigurationWarning(true, null, null, true)!!.contains("kotlin.build.report.output=JSON"))
        // Nothing wired + non-Kotlin build → silent (never nag).
        assertNull(KotlinReportBundler.misconfigurationWarning(true, null, null, false))
    }
}
