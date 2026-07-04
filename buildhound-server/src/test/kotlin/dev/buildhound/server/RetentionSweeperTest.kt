package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RetentionSweeper purges only past-window rows, is tenant-scoped, and never throws (plan 042). */
class RetentionSweeperTest {

    private val nowMs = 2_000_000_000_000L // fixed "now"
    private val dayMs = 24L * 60 * 60 * 1000

    private fun seed(store: InMemoryBuildStore, projectId: String, buildId: String, ageDays: Long) {
        store.save(projectId, TestPayloads.build(buildId = buildId, startedAt = nowMs - ageDays * dayMs))
    }

    @Test
    fun `sweep purges builds past the build window and keeps within-window builds`() {
        val builds = InMemoryBuildStore()
        val settings = InMemorySettingsStore()
        settings.setRetention("p", RetentionConfig(rawDays = 30, buildDays = 100))
        seed(builds, "p", "old", ageDays = 200)   // past 100d → purged
        seed(builds, "p", "fresh", ageDays = 10)   // within → kept

        val summary = RetentionSweeper(builds, settings).sweep(nowMs)

        assertEquals(1, summary.projects)
        assertEquals(1, summary.builds)
        assertNull(builds.findById("p", "old"), "a build past the window is deleted")
        assertNotNull(builds.findById("p", "fresh"), "a build within the window is kept")
    }

    @Test
    fun `sweep uses each project's own window and never crosses tenants`() {
        val builds = InMemoryBuildStore()
        val settings = InMemorySettingsStore()
        // p keeps 100d; q keeps the default 395d.
        settings.setRetention("p", RetentionConfig(30, 100))
        seed(builds, "p", "p-old", ageDays = 200)  // purged (p window 100)
        seed(builds, "q", "q-old", ageDays = 200)  // kept   (q default 395)

        RetentionSweeper(builds, settings).sweep(nowMs)

        assertNull(builds.findById("p", "p-old"), "p's own 100d window purges its 200d build")
        assertNotNull(builds.findById("q", "q-old"), "q's default 395d window keeps its 200d build")
    }

    @Test
    fun `a project with no retention row uses the spec defaults`() {
        val builds = InMemoryBuildStore()
        val settings = InMemorySettingsStore() // no rows → defaults 90/395
        seed(builds, "p", "kept", ageDays = 300)   // within 395d default → kept
        seed(builds, "p", "gone", ageDays = 400)   // past 395d → purged

        RetentionSweeper(builds, settings).sweep(nowMs)

        assertNotNull(builds.findById("p", "kept"))
        assertNull(builds.findById("p", "gone"))
    }

    @Test
    fun `a store that throws on purge is caught and the sweep still reports`() {
        val throwing = object : BuildStore by InMemoryBuildStore() {
            override fun allProjectIds() = listOf("p")
            override fun purgeOlderThan(projectId: String, buildCutoffMs: Long, rawCutoffMs: Long): RetentionPurge =
                throw RuntimeException("boom")
        }
        // sweep must not propagate the exception (never-fail); the failed project is just skipped.
        val summary = RetentionSweeper(throwing, InMemorySettingsStore()).sweep(nowMs)
        assertEquals(0, summary.projects, "the throwing project is skipped, not counted")
    }

    @Test
    fun `startRetentionSweeper with 0 hours starts no thread`() {
        val before = Thread.getAllStackTraces().keys.count { it.name == "buildhound-retention" }
        startRetentionSweeper(RetentionSweeper(InMemoryBuildStore(), InMemorySettingsStore()), sweepHours = 0)
        val after = Thread.getAllStackTraces().keys.count { it.name == "buildhound-retention" }
        assertEquals(before, after, "0 hours disables the sweep — no daemon thread")
    }
}
