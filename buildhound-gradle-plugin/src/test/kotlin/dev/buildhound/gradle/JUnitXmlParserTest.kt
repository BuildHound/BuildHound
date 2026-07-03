package dev.buildhound.gradle

import dev.buildhound.commons.payload.TestCaseOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JUnitXmlParserTest {

    private fun parse(xml: String) = JUnitXmlParser.parse(xml.toByteArray())

    @Test
    fun `an all-passing suite yields a rollup and no case detail`() {
        val parsed = parse(
            """
            <testsuite name="com.example.FooTest" tests="2" failures="0" errors="0" skipped="0" time="0.030">
              <testcase name="a()" classname="com.example.FooTest" time="0.010"/>
              <testcase name="b()" classname="com.example.FooTest" time="0.020"/>
            </testsuite>
            """.trimIndent(),
        ).single()

        assertEquals("com.example.FooTest", parsed.rollup.className)
        assertEquals(2, parsed.rollup.passed)
        assertEquals(0, parsed.rollup.failed)
        assertEquals(30, parsed.rollup.durationMs, "suite time 0.030s → 30ms")
        assertTrue(parsed.detail.isEmpty(), "passing cases carry no detail (locked granularity)")
    }

    @Test
    fun `a failure produces detail with a hashed message`() {
        val parsed = parse(
            """
            <testsuite name="com.example.FooTest" tests="1" failures="1" errors="0" skipped="0" time="0.1">
              <testcase name="fails()" classname="com.example.FooTest" time="0.1">
                <failure message="expected: &lt;1&gt; but was: &lt;2&gt;" type="org.opentest4j.AssertionFailedError">STACK
                  at com.example.FooTest.fails(FooTest.java:6)
                </failure>
              </testcase>
            </testsuite>
            """.trimIndent(),
        ).single()

        assertEquals(1, parsed.rollup.failed)
        val detail = parsed.detail.single()
        assertEquals("fails()", detail.name)
        assertEquals(listOf(TestCaseOutcome.FAILED), detail.outcomes)
        assertEquals("expected: <1> but was: <2>", detail.message, "the concise message= attr, never the stack body")
        assertNotNull(detail.messageHash)
        assertTrue(detail.messageHash!!.matches(Regex("[0-9a-f]{64}")), detail.messageHash!!)
    }

    @Test
    fun `the stack-trace body is never shipped, only the message attribute`() {
        val detail = parse(
            """
            <testsuite name="com.example.FooTest" tests="1" failures="1">
              <testcase name="fails()" classname="com.example.FooTest">
                <failure message="boom" type="X">at /home/ci/secret/path/File.java:1</failure>
              </testcase>
            </testsuite>
            """.trimIndent(),
        ).single().detail.single()
        assertEquals("boom", detail.message)
        assertTrue(detail.message?.contains("/home/ci") != true)
    }

    @Test
    fun `a retried case collapses to one detail with an ordered outcome sequence`() {
        val parsed = parse(
            """
            <testsuite name="com.example.FooTest" tests="1" failures="0">
              <testcase name="flaky()" classname="com.example.FooTest" time="0.2">
                <failure message="reset" type="X">trace</failure>
              </testcase>
              <testcase name="flaky()" classname="com.example.FooTest" time="0.1"/>
            </testsuite>
            """.trimIndent(),
        ).single()

        assertEquals(1, parsed.rollup.passed, "final outcome PASSED counts as a pass")
        val detail = parsed.detail.single()
        assertEquals(listOf(TestCaseOutcome.FAILED, TestCaseOutcome.PASSED), detail.outcomes)
        assertEquals("reset", detail.message, "message comes from the failing run")
        assertEquals(300, detail.durationMs, "durations sum across retries")
    }

    @Test
    fun `errors and skips are classified`() {
        val parsed = parse(
            """
            <testsuite name="com.example.FooTest" tests="2">
              <testcase name="err()" classname="com.example.FooTest"><error message="NPE" type="java.lang.NullPointerException"/></testcase>
              <testcase name="skp()" classname="com.example.FooTest"><skipped/></testcase>
            </testsuite>
            """.trimIndent(),
        ).single()

        assertEquals(1, parsed.rollup.failed, "an ERROR counts toward failed")
        assertEquals(1, parsed.rollup.skipped)
        assertEquals(listOf(TestCaseOutcome.ERROR), parsed.detail.single { it.name == "err()" }.outcomes)
        assertTrue(parsed.detail.none { it.name == "skp()" }, "a plain skip is not failed/retried detail")
    }

    @Test
    fun `malformed xml degrades to empty, never throws`() {
        assertTrue(parse("this is not xml").isEmpty())
        assertTrue(parse("<testsuite name=\"x\"><testcase").isEmpty())
    }

    @Test
    fun `an XXE external entity is not resolved and the file is not read`() {
        // DTD processing is disabled (fail-closed): a DOCTYPE makes the reader throw → empty.
        val xxe =
            """
            <?xml version="1.0"?>
            <!DOCTYPE testsuite [ <!ENTITY xxe SYSTEM "file:///etc/hostname"> ]>
            <testsuite name="com.example.FooTest" tests="1">
              <testcase name="a()" classname="com.example.FooTest"><failure message="&xxe;"/></testcase>
            </testsuite>
            """.trimIndent()
        val result = parse(xxe)
        assertTrue(result.isEmpty(), "a DOCTYPE must be refused, not expanded")
    }

    @Test
    fun `a huge failure message is capped at ingest before it lands in the payload`() {
        val huge = "z".repeat(50_000)
        val detail = parse(
            """<testsuite name="com.example.FooTest"><testcase name="big()" classname="com.example.FooTest"><failure message="$huge"/></testcase></testsuite>""",
        ).single().detail.single()
        assertEquals(8192, detail.message!!.length, "raw message bounded at parse time, not only at scrub")
    }

    @Test
    fun `the outcome sequence is capped so a retry storm cannot balloon the payload`() {
        val cases = (1..30).joinToString("\n") {
            """<testcase name="flaky()" classname="com.example.FooTest"><failure message="x"/></testcase>"""
        }
        val detail = parse("""<testsuite name="com.example.FooTest">$cases</testsuite>""").single().detail.single()
        assertEquals(20, detail.outcomes.size, "outcomes capped at 20")
    }
}
