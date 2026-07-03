package dev.buildhound.gradle

import dev.buildhound.commons.payload.TestCaseDetail
import dev.buildhound.commons.payload.TestCaseOutcome
import dev.buildhound.commons.payload.TestClassResult
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Parses one JUnit XML result file into per-class rollups + failed/retried case detail (plan 024).
 * Pure over bytes (no Gradle/file types) so it unit-tests directly. The XML is a build output but
 * still untrusted input, so the StAX reader is **fail-closed on XXE**: DTDs and external entities
 * are disabled (a `<!DOCTYPE …>` makes the reader throw, which degrades this file to `emptyList`).
 * Never throws — any malformed file yields `emptyList` (absent over wrong, architecture §2 rule 3).
 *
 * Allowlist (discovery spike §4a): `<testsuite name= time=>`, `<testcase name= classname= time=>`,
 * `<failure message=>` / `<error message=>` / `<skipped/>`. The suite's `hostname=`/`timestamp=`
 * (machine identity) and the `<failure>` element **body** (full stack trace, may carry absolute
 * paths) are deliberately never read; only the concise `message=` attribute is retained.
 */
internal object JUnitXmlParser {

    private const val MAX_OUTCOMES = 20
    // Bound the raw failure message at ingest — before it lands in the in-memory payload and
    // before the scrubber runs its regex battery over it — so a hostile test writing a multi-MB
    // `<failure message="…">` (bounded only by the 10 MiB file cap) can't bloat memory or stall
    // the finalizer. The scrubber applies its final 512-char cut after scrubbing; the hash is
    // over this capped text (stability is all it needs).
    private const val MAX_RAW_MESSAGE_CHARS = 8192

    /** One test class's rollup plus the detail for its failed/retried cases (may be empty). */
    data class ParsedClass(val rollup: TestClassResult, val detail: List<TestCaseDetail>)

    private val factory: XMLInputFactory = XMLInputFactory.newInstance().apply {
        // XXE guard — fail closed. No DTD processing, no external entity resolution.
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        runCatching { setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false) }
    }

    fun parse(bytes: ByteArray): List<ParsedClass> = runCatching {
        val reader = synchronized(factory) { factory.createXMLStreamReader(ByteArrayInputStream(bytes)) }
        val suites = ArrayList<Suite>()
        var suite: Suite? = null
        var case: Case? = null
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "testsuite" -> suite = Suite(
                            className = reader.attr("name") ?: "",
                            durationMs = reader.attr("time").toSecondsMs(),
                        )
                        "testcase" -> case = Case(
                            className = reader.attr("classname") ?: suite?.className ?: "",
                            name = reader.attr("name") ?: "",
                            durationMs = reader.attr("time").toSecondsMs(),
                        )
                        "failure" -> case?.mark(TestCaseOutcome.FAILED, reader.attr("message"))
                        "error" -> case?.mark(TestCaseOutcome.ERROR, reader.attr("message"))
                        "skipped" -> case?.mark(TestCaseOutcome.SKIPPED, null)
                    }
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "testcase" -> { case?.let { suite?.cases?.add(it) }; case = null }
                        "testsuite" -> { suite?.let { suites.add(it) }; suite = null }
                    }
                }
            }
        } finally {
            runCatching { reader.close() }
        }
        suites.map { it.toParsedClass() }
    }.getOrElse { emptyList() }

    /** A `<testcase>`'s occurrences within one suite; more than one = a retry. */
    private class Case(val className: String, val name: String, val durationMs: Long) {
        val outcomes = ArrayList<TestCaseOutcome>()
        var failureMessage: String? = null
        private var current: TestCaseOutcome? = null

        /** A case with no failure/error/skipped child passed. */
        fun mark(outcome: TestCaseOutcome, message: String?) {
            current = outcome
            if (message != null && failureMessage == null) failureMessage = message.take(MAX_RAW_MESSAGE_CHARS)
        }

        fun finalOutcome(): TestCaseOutcome = current ?: TestCaseOutcome.PASSED
    }

    private class Suite(val className: String, val durationMs: Long) {
        val cases = ArrayList<Case>()

        fun toParsedClass(): ParsedClass {
            // Collapse retries: same method name → one entry with an ordered outcome sequence.
            val byName = LinkedHashMap<String, MutableList<Case>>()
            for (c in cases) byName.getOrPut(c.name) { ArrayList() }.add(c)

            var passed = 0
            var failed = 0
            var skipped = 0
            val detail = ArrayList<TestCaseDetail>()
            for ((name, runs) in byName) {
                val outcomes = runs.map { it.finalOutcome() }
                when (outcomes.last()) {
                    TestCaseOutcome.PASSED -> passed++
                    TestCaseOutcome.FAILED, TestCaseOutcome.ERROR -> failed++
                    TestCaseOutcome.SKIPPED -> skipped++
                }
                val retried = runs.size > 1
                val everFailed = outcomes.any { it == TestCaseOutcome.FAILED || it == TestCaseOutcome.ERROR }
                if (retried || everFailed) {
                    val rawMessage = runs.firstNotNullOfOrNull { it.failureMessage }
                    detail += TestCaseDetail(
                        className = runs.first().className,
                        name = name,
                        outcomes = outcomes.take(MAX_OUTCOMES),
                        durationMs = runs.sumOf { it.durationMs },
                        messageHash = rawMessage?.let(::sha256),
                        message = rawMessage,
                    )
                }
            }
            return ParsedClass(
                rollup = TestClassResult(
                    className = className,
                    passed = passed,
                    failed = failed,
                    skipped = skipped,
                    durationMs = durationMs,
                ),
                detail = detail,
            )
        }
    }

    private fun javax.xml.stream.XMLStreamReader.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeLocalName(i) == name) return getAttributeValue(i)
        return null
    }

    /** JUnit `time` is fractional seconds; ×1000 to ms. Absent/garbage → 0. */
    private fun String?.toSecondsMs(): Long =
        this?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}
