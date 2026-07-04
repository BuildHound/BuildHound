package dev.buildhound.server

import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.server.connector.CiRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The connector expected-build synthesis rule (plan 033): deterministic, idempotent, guarded. */
class InterruptedBuildTest {

    private val run = CiRun(startedAt = 100, finishedAt = 500)

    @Test
    fun `records a deterministic INTERRUPTED build when no build exists for the run`() {
        val builds = InMemoryBuildStore()
        InterruptedBuild.recordIfMissing(builds, "proj", "azure-devops", "77", run)

        val saved = builds.findById("proj", InterruptedBuild.deterministicId("azure-devops", "77"))
            ?: error("expected a synthesized build")
        assertEquals(BuildOutcome.INTERRUPTED, saved.outcome)
        assertEquals(100, saved.startedAt)
        assertEquals(500, saved.finishedAt)
        assertEquals("azure-devops", saved.ci?.provider)
        assertEquals("77", saved.ci?.runId)
        assertTrue(saved.tasks.isEmpty())
    }

    @Test
    fun `is idempotent — a re-fired hook adds nothing`() {
        val builds = InMemoryBuildStore()
        InterruptedBuild.recordIfMissing(builds, "proj", "azure-devops", "77", run)
        InterruptedBuild.recordIfMissing(builds, "proj", "azure-devops", "77", run)
        assertEquals(1, builds.count("proj"))
    }

    @Test
    fun `does nothing when a real build already ingested for the run`() {
        val builds = InMemoryBuildStore()
        builds.save("proj", TestPayloads.build(buildId = "real", provider = "azure-devops", runId = "88"))
        InterruptedBuild.recordIfMissing(builds, "proj", "azure-devops", "88", run)
        assertNull(
            builds.findById("proj", InterruptedBuild.deterministicId("azure-devops", "88")),
            "an ingested run must not also be recorded interrupted",
        )
        assertEquals(1, builds.count("proj"))
    }

    @Test
    fun `does nothing for a run that never finished`() {
        val builds = InMemoryBuildStore()
        InterruptedBuild.recordIfMissing(builds, "proj", "azure-devops", "99", CiRun(startedAt = 100, finishedAt = null))
        assertEquals(0, builds.count("proj"))
    }
}
