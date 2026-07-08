package dev.buildhound.server

import dev.buildhound.commons.payload.TaskExecution

/**
 * Pure GEXF/DOT serialization of the internal-adapters dependency graph (plan 062, research F12) —
 * `TaskDependencyGraphPublisher` GEXF prior art. Every task-path label is escaped for its target format
 * before it is written: task paths are project-internal identifiers (module/task names a project author
 * chose), but they still ride into a downstream graph tool (Gephi, `dot`) unsanitized text, so a path
 * containing `<`/`&`/`"`/`\` must never break out of a label or a quoted DOT id (architecture §6,
 * injection-surface note). Node identity uses a synthetic `n<index>` id (GEXF) so escaping is confined to
 * the human-readable `label`/`durationMs` attributes, never the id itself. Callers are expected to have
 * already checked `dependencyEdges.isNotEmpty()` (the route 404s before calling in); an empty map here
 * would just render an empty (still well-formed) graph.
 */
object GraphExporter {

    /** Every task path referenced anywhere in the edge map, as a source or as a dependency — insertion order. */
    private fun nodesOf(dependencyEdges: Map<String, List<String>>): List<String> {
        val nodes = LinkedHashSet<String>()
        for ((path, deps) in dependencyEdges) {
            nodes += path
            nodes += deps
        }
        return nodes.toList()
    }

    fun gexf(dependencyEdges: Map<String, List<String>>, tasks: List<TaskExecution>): String {
        val durationByPath = tasks.associate { it.path to it.durationMs }
        val nodes = nodesOf(dependencyEdges)
        val idByPath = nodes.withIndex().associate { (index, path) -> path to "n$index" }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gexf xmlns=\"http://www.gexf.net/1.3\" version=\"1.3\">\n")
        sb.append("  <graph mode=\"static\" defaultedgetype=\"directed\">\n")
        sb.append("    <attributes class=\"node\">\n")
        sb.append("      <attribute id=\"0\" title=\"durationMs\" type=\"long\"/>\n")
        sb.append("    </attributes>\n")
        sb.append("    <nodes>\n")
        for (path in nodes) {
            val duration = durationByPath[path] ?: 0L
            sb.append("      <node id=\"${idByPath.getValue(path)}\" label=\"${xmlEscape(path)}\">\n")
            sb.append("        <attvalues><attvalue for=\"0\" value=\"$duration\"/></attvalues>\n")
            sb.append("      </node>\n")
        }
        sb.append("    </nodes>\n")
        sb.append("    <edges>\n")
        var edgeId = 0
        for ((path, deps) in dependencyEdges) {
            val source = idByPath.getValue(path)
            for (dep in deps) {
                val target = idByPath[dep] ?: continue
                sb.append("      <edge id=\"e$edgeId\" source=\"$source\" target=\"$target\"/>\n")
                edgeId++
            }
        }
        sb.append("    </edges>\n")
        sb.append("  </graph>\n")
        sb.append("</gexf>\n")
        return sb.toString()
    }

    fun dot(dependencyEdges: Map<String, List<String>>, tasks: List<TaskExecution>): String {
        val durationByPath = tasks.associate { it.path to it.durationMs }
        val nodes = nodesOf(dependencyEdges)

        val sb = StringBuilder()
        sb.append("digraph tasks {\n")
        for (path in nodes) {
            val label = dotEscape(path)
            val duration = durationByPath[path] ?: 0L
            sb.append("  \"$label\" [label=\"$label\", durationMs=$duration];\n")
        }
        for ((path, deps) in dependencyEdges) {
            val from = dotEscape(path)
            for (dep in deps) {
                sb.append("  \"$from\" -> \"${dotEscape(dep)}\";\n")
            }
        }
        sb.append("}\n")
        return sb.toString()
    }

    /** XML attribute-value escaping (order matters: `&` first, or later replacements would double-escape). */
    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** DOT quoted-string escaping: backslash first, then the quote that would otherwise close the string. */
    private fun dotEscape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
