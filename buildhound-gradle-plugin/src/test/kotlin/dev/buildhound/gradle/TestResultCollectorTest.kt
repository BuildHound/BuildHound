package dev.buildhound.gradle

import dev.buildhound.commons.payload.TaskOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val collection = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app")),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.FAILED),
        )
        assertTrue(collection.xmlDisabledTasks.isEmpty())
        val result = collection.results.single()

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
            TestResultCollector.collect(locations, mapOf(":app:test" to TaskOutcome.FROM_CACHE)).results.isEmpty(),
            "FROM_CACHE leaves prior-build xml on disk that must not be re-attributed",
        )
        assertTrue(TestResultCollector.collect(locations, mapOf(":app:test" to TaskOutcome.UP_TO_DATE)).results.isEmpty())
    }

    @Test
    fun `a missing or empty results directory yields no task entry`() {
        val missing = mapOf(":app:test" to TestResultLocations(File(dir, "nope").absolutePath, ":app"))
        assertTrue(TestResultCollector.collect(missing, mapOf(":app:test" to TaskOutcome.EXECUTED)).results.isEmpty())

        File(dir, "empty").mkdirs()
        val empty = mapOf(":app:test" to TestResultLocations(File(dir, "empty").absolutePath, ":app"))
        assertTrue(TestResultCollector.collect(empty, mapOf(":app:test" to TaskOutcome.EXECUTED)).results.isEmpty())
    }

    @Test
    fun `the failure-injection seam produces empty results and one warn, never throws`() {
        writeXml(dir, "TEST-com.example.FooTest.xml", passAndFail)
        val warnings = ArrayList<String>()
        val collection = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app")),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.FAILED),
            warn = { warnings += it },
            failInjection = true,
        )
        assertTrue(collection.results.isEmpty())
        assertTrue(collection.xmlDisabledTasks.isEmpty())
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
        ).results.single()

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
        ).results.single()

        val cls = result.classes.single()
        assertEquals("com.example.FooTest", cls.className)
        assertEquals(2, cls.passed, "counts sum across the two suites")
        assertEquals(3000, cls.durationMs, "durations sum across the two suites")
    }

    @Test
    fun `no locations means no work`() {
        val collection = TestResultCollector.collect(emptyMap(), mapOf(":app:test" to TaskOutcome.EXECUTED))
        assertTrue(collection.results.isEmpty())
        assertTrue(collection.xmlDisabledTasks.isEmpty())
    }

    // --- plan 053: flag-authoritative degraded state ---

    @Test
    fun `an executed task with junitXmlRequired false is recorded as disabled and never parsed, even with stale xml on disk`() {
        // Stale XML from a prior required=true run still sits on disk; the flag must override it —
        // no phantom result may appear alongside the disabled note for the same task.
        writeXml(dir, "TEST-com.example.FooTest.xml", passAndFail)
        val collection = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app", junitXmlRequired = false)),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.EXECUTED),
        )

        assertTrue(collection.results.isEmpty(), "the flag short-circuits before any listFiles/parse")
        assertEquals(listOf(":app:test"), collection.xmlDisabledTasks)
    }

    @Test
    fun `junitXmlRequired true still parses as before`() {
        writeXml(dir, "TEST-com.example.FooTest.xml", passAndFail)
        val collection = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app", junitXmlRequired = true)),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.EXECUTED),
        )

        assertTrue(collection.xmlDisabledTasks.isEmpty())
        assertEquals(1, collection.results.single().classes.single().passed)
    }

    @Test
    fun `an up-to-date or from-cache task with junitXmlRequired false produces neither a result nor a note`() {
        val locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app", junitXmlRequired = false))
        val upToDate = TestResultCollector.collect(locations, mapOf(":app:test" to TaskOutcome.UP_TO_DATE))
        assertTrue(upToDate.results.isEmpty())
        assertTrue(upToDate.xmlDisabledTasks.isEmpty(), "only a this-build EXECUTED/FAILED task can produce the note")

        val fromCache = TestResultCollector.collect(locations, mapOf(":app:test" to TaskOutcome.FROM_CACHE))
        assertTrue(fromCache.results.isEmpty())
        assertTrue(fromCache.xmlDisabledTasks.isEmpty())
    }

    @Test
    fun `xmlDisabledTasks is sorted for determinism regardless of location map order`() {
        val locations = linkedMapOf(
            ":z:test" to TestResultLocations(File(dir, "z").absolutePath, ":z", junitXmlRequired = false),
            ":a:test" to TestResultLocations(File(dir, "a").absolutePath, ":a", junitXmlRequired = false),
        )
        val collection = TestResultCollector.collect(
            locations,
            mapOf(":z:test" to TaskOutcome.EXECUTED, ":a:test" to TaskOutcome.EXECUTED),
        )
        assertEquals(listOf(":a:test", ":z:test"), collection.xmlDisabledTasks)
    }

    @Test
    fun `the failure-injection seam leaves xmlDisabledTasks empty too`() {
        val warnings = ArrayList<String>()
        val collection = TestResultCollector.collect(
            locations = mapOf(":app:test" to TestResultLocations(dir.absolutePath, ":app", junitXmlRequired = false)),
            taskOutcomes = mapOf(":app:test" to TaskOutcome.EXECUTED),
            warn = { warnings += it },
            failInjection = true,
        )
        assertTrue(collection.results.isEmpty())
        assertTrue(collection.xmlDisabledTasks.isEmpty())
        assertEquals(1, warnings.size, warnings.toString())
    }
}
