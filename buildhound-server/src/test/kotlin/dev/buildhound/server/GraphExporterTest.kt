package dev.buildhound.server

import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphExporterTest {

    private fun task(path: String, durationMs: Long = 100) =
        TaskExecution(path = path, startMs = 0, durationMs = durationMs, outcome = TaskOutcome.EXECUTED)

    /**
     * Decodes the first double-quoted DOT string's `\\`/`\"` escaping (test-only, no graphviz parser dep).
     * Self-referential by construction: this decoder implements the exact same escaping convention as
     * [GraphExporter.dotEscape] rather than an independent DOT grammar/parser, so the DOT tests below prove
     * internal round-trip consistency, not real Graphviz-grammar validity — unlike the `gexf` tests, which
     * parse through a real `javax.xml` [org.w3c.dom.Document] parser. Don't read DOT coverage here as
     * "validated against Graphviz."
     */
    private fun firstQuotedDotString(dot: String): String {
        val start = dot.indexOf('"')
        check(start >= 0) { "no quoted string found in: $dot" }
        val sb = StringBuilder()
        var i = start + 1
        while (i < dot.length) {
            val c = dot[i]
            when {
                c == '\\' && i + 1 < dot.length -> {
                    sb.append(dot[i + 1])
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        error("unterminated quoted string in: $dot")
    }

    @Test
    fun `gexf is well-formed XML and round-trips an escaped task-path label`() {
        val dangerous = ":app:<evil>&\"weird\\path"
        val edges = mapOf(dangerous to listOf(":app:b"))
        val tasks = listOf(task(dangerous, 500), task(":app:b", 200))

        val xml = GraphExporter.gexf(edges, tasks)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val nodes = doc.getElementsByTagName("node")
        val labels = (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("label").nodeValue }
        assertTrue(labels.contains(dangerous), "the XML parser must decode the label back to the exact raw path: $labels")
    }

    @Test
    fun `gexf escapes every XML-significant character in a label`() {
        val xml = GraphExporter.gexf(mapOf(":a<>&\"'b" to emptyList()), emptyList())
        assertTrue(xml.contains("&lt;"), xml)
        assertTrue(xml.contains("&gt;"), xml)
        assertTrue(xml.contains("&amp;"), xml)
        assertTrue(xml.contains("&quot;"), xml)
        assertTrue(xml.contains("&apos;"), xml)
    }

    @Test
    fun `gexf renders directed edges between the synthetic node ids, not raw paths`() {
        val xml = GraphExporter.gexf(mapOf(":a" to listOf(":b")), listOf(task(":a", 10), task(":b", 20)))
        assertTrue(xml.contains("<edge id=\"e0\" source=\"n0\" target=\"n1\"/>"), xml)
    }

    @Test
    fun `dot output is well-formed and round-trips an escaped task-path label`() {
        val dangerous = ":app:<evil>&\"weird\\path"
        val edges = mapOf(dangerous to listOf(":app:b"))
        val tasks = listOf(task(dangerous, 500), task(":app:b", 200))

        val dot = GraphExporter.dot(edges, tasks)
        assertTrue(dot.startsWith("digraph tasks {\n"), dot)
        assertTrue(dot.trimEnd().endsWith("}"), dot)
        assertEquals(dangerous, firstQuotedDotString(dot))
    }

    @Test
    fun `dot escapes backslash before quote so an injected quote can never close the string early`() {
        val injected = "a\"; rm -rf /; \"b"
        val dot = GraphExporter.dot(mapOf(injected to emptyList()), emptyList())
        assertTrue(dot.contains("\\\""), "the embedded quote must be backslash-escaped: $dot")
        assertEquals(injected, firstQuotedDotString(dot))
    }

    @Test
    fun `gexf and dot both cover the same edges independently`() {
        val edges = mapOf(":a" to listOf(":b"))
        val tasks = listOf(task(":a", 10), task(":b", 20))
        assertTrue(GraphExporter.gexf(edges, tasks).contains("<gexf"))
        assertTrue(GraphExporter.dot(edges, tasks).contains("digraph tasks"))
    }

    @Test
    fun `an unknown dependency target absent from core tasks still gets a node with duration 0`() {
        val edges = mapOf(":a" to listOf(":ghost"))
        val gexf = GraphExporter.gexf(edges, listOf(task(":a", 100)))
        assertTrue(gexf.contains("label=\":ghost\""), gexf)
        assertTrue(gexf.contains("value=\"0\""), gexf)
    }
}
