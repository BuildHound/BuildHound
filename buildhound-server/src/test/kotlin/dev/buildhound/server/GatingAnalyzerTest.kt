package dev.buildhound.server

import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class GatingAnalyzerTest {

    private fun task(
        path: String,
        startMs: Long,
        durationMs: Long,
        outcome: TaskOutcome = TaskOutcome.EXECUTED,
        module: String? = null,
    ) = TaskExecution(path = path, module = module, startMs = startMs, durationMs = durationMs, outcome = outcome)

    @Test
    fun `empty tasks produce empty blockers`() {
        assertEquals(emptyList(), GatingAnalyzer.analyze(emptyList()))
    }

    @Test
    fun `a single task produces no blockers — no work remained`() {
        val tasks = listOf(task(":app:a", startMs = 0, durationMs = 100))
        assertEquals(emptyList(), GatingAnalyzer.analyze(tasks))
    }

    @Test
    fun `a fully parallel build has zero gating`() {
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 100),
            task(":app:b", startMs = 0, durationMs = 100),
        )
        assertEquals(emptyList(), GatingAnalyzer.analyze(tasks))
    }

    @Test
    fun `a sole-runner tail task is not penalized`() {
        // B (0-5) finishes and A (0-10) runs on alone to the end — nothing else was ever queued, so
        // A's [5,10) tail is just the build finishing, not a gating blocker.
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 10),
            task(":app:b", startMs = 0, durationMs = 5),
        )
        assertEquals(emptyList(), GatingAnalyzer.analyze(tasks))
    }

    @Test
    fun `a sole runner blocking a later start is attributed the interval`() {
        // A(0-15) runs alone after B(0-5) ends, and C doesn't start until 15 — A's [5,15) interval
        // gated C from starting sooner.
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 15),
            task(":app:b", startMs = 0, durationMs = 5),
            task(":app:c", startMs = 15, durationMs = 5),
        )
        val blockers = GatingAnalyzer.analyze(tasks)
        assertEquals(listOf(BlockerRow(taskPath = ":app:a", module = null, serializedMs = 10, durationMs = 15)), blockers)
    }

    @Test
    fun `a sole-runner interval before an overlap join is still attributed — work remained upstream`() {
        // A(0-10) runs alone until C joins at 5; B doesn't start until 10. A's [0,5) sole-runner
        // interval still counts as gating — some task (B, and C itself) starts at/after 5 ("work
        // remained"), even though A and C later overlap for the rest of A's duration. A's tail [5,10)
        // is not double-counted (C is active there too).
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 10),
            task(":app:b", startMs = 10, durationMs = 10),
            task(":app:c", startMs = 5, durationMs = 20),
        )
        val blockers = GatingAnalyzer.analyze(tasks)
        assertEquals(listOf(BlockerRow(taskPath = ":app:a", module = null, serializedMs = 5, durationMs = 10)), blockers)
    }

    @Test
    fun `simultaneous start and end boundaries are handled deterministically`() {
        // A and B share [0,10); C starts exactly at 10 when both end, runs alone to 20 with nothing
        // queued after — a tail, not a blocker. Repeated calls must agree (no order-dependence).
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 10),
            task(":app:b", startMs = 0, durationMs = 10),
            task(":app:c", startMs = 10, durationMs = 10),
        )
        val first = GatingAnalyzer.analyze(tasks)
        val second = GatingAnalyzer.analyze(tasks.reversed())
        assertEquals(emptyList(), first)
        assertEquals(first, second)
    }

    @Test
    fun `all-zero durations produce empty blockers`() {
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 0),
            task(":app:b", startMs = 0, durationMs = 0),
        )
        assertEquals(emptyList(), GatingAnalyzer.analyze(tasks))
    }

    @Test
    fun `a zero-duration NO_SOURCE task never occupies a slot nor extends maxStart`() {
        // Without the NO_SOURCE row this is exactly the tail-task shape (A alone from 5-10, nothing
        // else ever starts): empty. If the 0ms row were wrongly treated as "work starting at 10",
        // A's [5,10) tail would be misread as a gating interval.
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 10),
            task(":app:b", startMs = 0, durationMs = 5),
            task(":app:noop", startMs = 10, durationMs = 0, outcome = TaskOutcome.NO_SOURCE),
        )
        assertEquals(emptyList(), GatingAnalyzer.analyze(tasks))
    }

    @Test
    fun `blockers rank by serializedMs descending with a taskPath tie-break`() {
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 20),
            task(":app:b", startMs = 0, durationMs = 5),
            task(":app:z", startMs = 20, durationMs = 30),
            task(":app:m", startMs = 20, durationMs = 30),
        )
        val blockers = GatingAnalyzer.analyze(tasks)
        // :app:a alone from [5,20) = 15ms, gated by :app:z/:app:m both starting at 20.
        assertEquals(1, blockers.size)
        assertEquals(":app:a", blockers[0].taskPath)
        assertEquals(15L, blockers[0].serializedMs)
    }

    @Test
    fun `module rides through onto the blocker row`() {
        val tasks = listOf(
            task(":app:a", startMs = 0, durationMs = 15, module = ":app"),
            task(":app:b", startMs = 0, durationMs = 5, module = ":app"),
            task(":app:c", startMs = 15, durationMs = 5, module = ":app"),
        )
        val blocker = GatingAnalyzer.analyze(tasks).single()
        assertEquals(":app", blocker.module)
    }
}
