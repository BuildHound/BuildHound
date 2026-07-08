package dev.buildhound.server

import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphCentralityTest {

    private fun task(path: String, durationMs: Long, module: String? = null) =
        TaskExecution(path = path, module = module, startMs = 0, durationMs = durationMs, outcome = TaskOutcome.EXECUTED)

    @Test
    fun `empty dependency edges rank as an empty list — the caller reads this as centrality absent`() {
        // GraphCentrality itself is a pure Map-in/List-out function; the honest `centrality=null`
        // response semantics live one layer up (dependencyEdgesOf / ParallelismAnalyzer), which never
        // calls rank at all when the extension is absent — covered by ParallelismRoutesTest.
        assertEquals(emptyList(), GraphCentrality.rank(emptyMap(), listOf(task(":app:a", 100))))
    }

    @Test
    fun `a fan-in hub outranks every node of a longer single chain — the Plaid property`() {
        // A linear chain P1->P2->P3->P4->P5 (each node has at most 2 neighbors): degree caps at 2, so
        // no chain node's weightedDegree can exceed 20 (its two 10ms neighbors).
        val chainEdges = mapOf(
            ":p1" to listOf(":p2"),
            ":p2" to listOf(":p3"),
            ":p3" to listOf(":p4"),
            ":p4" to listOf(":p5"),
        )
        // A fan-in hub H depended on by 6 tasks of 50ms each: degree 6, weightedDegree 300 — the
        // single longest-chain view (summing a path) misses this hub entirely since it looks only at
        // the longest dependency path, never a node's total fan-in/out.
        val hubEdges = (1..6).associate { ":t$it" to listOf(":h") }
        val edges = chainEdges + hubEdges

        val chainTasks = listOf(":p1", ":p2", ":p3", ":p4", ":p5").map { task(it, 10) }
        val hubTasks = (1..6).map { task(":t$it", 50) } + task(":h", 5)

        val ranked = GraphCentrality.rank(edges, chainTasks + hubTasks)
        assertEquals(":h", ranked.first().taskPath, "the fan-in hub must rank #1, above every chain node")
        assertEquals(6, ranked.first().degree)
        assertEquals(300L, ranked.first().weightedDegree)

        val chainRow = ranked.single { it.taskPath == ":p3" }
        assertTrue(chainRow.weightedDegree < ranked.first().weightedDegree, "no chain node can out-rank the hub")
        assertEquals(2, chainRow.degree)
    }

    @Test
    fun `reverse adjacency — a task's dependents count toward its centrality, not only its own dependencies`() {
        // A depends on B: B never appears as a key in dependencyEdges, only as a value. B's centrality
        // must still see A as a neighbor via the reverse (dependents) adjacency.
        val edges = mapOf(":a" to listOf(":b"))
        val tasks = listOf(task(":a", 100), task(":b", 40))

        val ranked = GraphCentrality.rank(edges, tasks)
        val b = ranked.single { it.taskPath == ":b" }
        assertEquals(1, b.degree)
        assertEquals(100L, b.weightedDegree, "B's centrality is driven by A's duration via the reverse edge")
        val a = ranked.single { it.taskPath == ":a" }
        assertEquals(40L, a.weightedDegree)
    }

    @Test
    fun `a task referenced only as a dependency and absent from core tasks weighs 0`() {
        val edges = mapOf(":a" to listOf(":ghost"))
        val tasks = listOf(task(":a", 100)) // :ghost never appears in core tasks[]

        val ranked = GraphCentrality.rank(edges, tasks)
        val ghost = ranked.single { it.taskPath == ":ghost" }
        assertEquals(0L, ghost.durationMs, "an unknown task's own duration is unknown, not fabricated")
        assertEquals(1, ghost.degree)
        assertEquals(100L, ghost.weightedDegree, "the ghost's centrality still comes from its known neighbor A")
    }

    @Test
    fun `ranking is deterministic — descending weightedDegree with a taskPath tie-break`() {
        // Two isolated pairs, each node with exactly one 10ms neighbor: all four rows tie on
        // weightedDegree(10)/degree(1), so the order must fall back to alphabetical taskPath.
        val edges = mapOf(":p" to listOf(":q"), ":r" to listOf(":s"))
        val tasks = listOf(task(":p", 10), task(":q", 10), task(":r", 10), task(":s", 10))
        val ranked = GraphCentrality.rank(edges, tasks)
        assertEquals(listOf(":p", ":q", ":r", ":s"), ranked.map { it.taskPath })
    }
}
