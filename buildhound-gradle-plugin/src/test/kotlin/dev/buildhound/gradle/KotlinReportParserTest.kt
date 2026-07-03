package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinReportParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("kotlin-reports/$name")) { "missing fixture $name" }
            .readBytes().decodeToString()

    @Test
    fun parses_the_allowlisted_fields_from_a_kotlin_record() {
        val parsed = KotlinReportParser.parse(fixture("kgp-2.4-report.json"))!!

        assertEquals("KOTLIN_2_4", parsed.reportSchema)
        // Only the isFromKotlinPlugin=true record survives the filter.
        val task = parsed.tasks.single()
        assertEquals(":compileKotlin", task.taskPath)
        assertEquals(1168, task.durationMs)
        assertEquals(false, task.incremental)
        assertEquals(listOf("UNKNOWN_CHANGES_IN_GRADLE_INPUTS"), task.nonIncrementalReasons)
        assertEquals(190, task.compilerTimesMs["RUN_COMPILATION"])
        assertEquals(22, task.compilerTimesMs["COMPILER_INITIALIZATION"])
        assertEquals(3, task.linesOfCode)
    }

    @Test
    fun never_extracts_path_bearing_fields() {
        // compilerArguments/changedFiles/icLogLines/currentDir carry absolute paths (spec §3.7).
        val text = KotlinReportParser.parse(fixture("kgp-2.4-report.json"))!!.tasks.toString()
        assertFalse(text.contains("/abs"), text)
        assertFalse(text.contains("classpath"), text)
    }

    @Test
    fun garbage_or_shapeless_json_returns_null() {
        assertNull(KotlinReportParser.parse("not json at all"))
        assertNull(KotlinReportParser.parse("[]"), "not an object")
        assertNull(KotlinReportParser.parse("{}"), "no buildOperationRecord")
    }

    @Test
    fun tolerates_missing_and_renamed_fields() {
        val json = """{"buildOperationRecord":[{"path":":compileKotlin","isFromKotlinPlugin":true}]}"""
        val task = KotlinReportParser.parse(json)!!.tasks.single()
        assertEquals(":compileKotlin", task.taskPath)
        assertNull(task.durationMs)
        assertNull(task.incremental)
        assertTrue(task.compilerTimesMs.isEmpty())
        assertNull(task.linesOfCode)
    }

    @Test
    fun non_kotlin_records_are_ignored() {
        val json = """{"buildOperationRecord":[{"path":":jar","isFromKotlinPlugin":false,"totalTimeMs":5}]}"""
        assertTrue(KotlinReportParser.parse(json)!!.tasks.isEmpty())
    }

    @Test
    fun a_hostile_over_long_task_path_is_length_capped() {
        // `path` is attacker-influenceable (from the report file), so it is bounded like every
        // other retained string — a multi-KB path can't inflate the payload.
        val huge = "x".repeat(5000)
        val json = """{"buildOperationRecord":[{"path":"$huge","isFromKotlinPlugin":true}]}"""
        val taskPath = KotlinReportParser.parse(json)!!.tasks.single().taskPath
        assertEquals(200, taskPath.length, "taskPath must be capped to MAX_PATH_CHARS")
    }
}
