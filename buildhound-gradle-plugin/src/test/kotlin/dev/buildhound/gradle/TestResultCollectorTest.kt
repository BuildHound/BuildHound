package dev.buildhound.gradle

import dev.buildhound.commons.payload.TaskOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TestResultCollectorTest {

    @field:TempDir
    lateinit var dir: File

    private val passAndFail =
        """
        <testsuite name="com.example.FooTest" tests="2" failures="1" time="0.05">
          <testcase name="ok()" classname="com.example.FooTest" time="0.01"/>
          <testcase name="bad()" classname="com.example.FooTest" time="0.04"><failure message="boom"/></testcase>
        </testsuite>
        """.trimIndent()

    private fun writeXml(into: File, name: String, content: String) {
        into.mkdirs()
        File(into, name).writeText(content)
    }

    @Test
    fun `parses the xml of an executed test task`() {
        writeXml(dir, "TEST-com.example.FooTest.xml", passAndFail)
        val result = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app")),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.FAILED),
        ).single()

        assertEquals(":app:test", result.taskPath)
        assertEquals(":app", result.module)
        val cls = result.classes.single()
        assertEquals(1, cls.passed)
        assertEquals(1, cls.failed)
        assertEquals("bad()", result.failedOrRetried.single().name)
    }

    @Test
    fun `a cached or up-to-date test task is not re-ingested from stale on-disk xml`() {
        writeXml(dir, "TEST-com.example.FooTest.xml", passAndFail)
        val locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app"))
        assertTrue(
            TestResultCollector.collect(locations, mapOf(":app:test" to TaskOutcome.FROM_CACHE)).isEmpty(),
            "FROM_CACHE leaves prior-build xml on disk that must not be re-attributed",
        )
        assertTrue(TestResultCollector.collect(locations, mapOf(":app:test" to TaskOutcome.UP_TO_DATE)).isEmpty())
    }

    @Test
    fun `a missing or empty results directory yields no task entry`() {
        val missing = mapOf(":app:test" to TestResultLocations(File(dir, "nope").absolutePath, ":app"))
        assertTrue(TestResultCollector.collect(missing, mapOf(":app:test" to TaskOutcome.EXECUTED)).isEmpty())

        File(dir, "empty").mkdirs()
        val empty = mapOf(":app:test" to TestResultLocations(File(dir, "empty").absolutePath, ":app"))
        assertTrue(TestResultCollector.collect(empty, mapOf(":app:test" to TaskOutcome.EXECUTED)).isEmpty())
    }

    @Test
    fun `the failure-injection seam produces empty results and one warn, never throws`() {
        writeXml(dir, "TEST-com.example.FooTest.xml", passAndFail)
        val warnings = ArrayList<String>()
        val result = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app")),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.FAILED),
            warn = { warnings += it },
            failInjection = true,
        )
        assertTrue(result.isEmpty())
        assertEquals(1, warnings.size, warnings.toString())
    }

    @Test
    fun `class rollups beyond the per-task cap are counted in truncatedClasses`() {
        // One file, many suites (parser reacts to <testsuite> at any depth) → exercises the cap.
        val suites = (1..2001).joinToString("\n") { i ->
            """<testsuite name="com.example.C$i" tests="1" time="0.$i"><testcase name="a()" classname="com.example.C$i"/></testsuite>"""
        }
        writeXml(dir, "TEST-aggregated.xml", "<testsuites>$suites</testsuites>")
        val result = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app")),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.EXECUTED),
        ).single()

        assertEquals(2000, result.classes.size, "kept the slowest 2000")
        assertEquals(1, result.truncatedClasses)
    }

    @Test
    fun `rollups for the same class across suites are merged into one row`() {
        // Aggregated output (or a retry layout) can repeat a class across <testsuite>s; the
        // collector must not double-count it into two rows for one FQCN.
        writeXml(
            dir, "TEST-aggregated.xml",
            """
            <testsuites>
              <testsuite name="com.example.FooTest" time="1.0"><testcase name="a()" classname="com.example.FooTest"/></testsuite>
              <testsuite name="com.example.FooTest" time="2.0"><testcase name="b()" classname="com.example.FooTest"/></testsuite>
            </testsuites>
            """.trimIndent(),
        )
        val result = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app")),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.EXECUTED),
        ).single()

        val cls = result.classes.single()
        assertEquals("com.example.FooTest", cls.className)
        assertEquals(2, cls.passed, "counts sum across the two suites")
        assertEquals(3000, cls.durationMs, "durations sum across the two suites")
    }

    @Test
    fun `no locations means no work`() {
        assertTrue(TestResultCollector.collect(emptyMap(), mapOf(":app:test" to TaskOutcome.EXECUTED)).isEmpty())
    }
}
