package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The edge-triggered FLAKY alert hook (plan 036): one alert per class per process, never-fail. */
class FlakyAlerterTest {

    private val recent = System.currentTimeMillis() - 3_600_000

    private fun harness(builds: BuildStore = InMemoryBuildStore()): Triple<BuildStore, RecordingAlertDispatcher, FlakyAlerter> {
        val settings = InMemorySettingsStore()
        settings.put("p", ProjectSettings(alertChannels = listOf(AlertChannel("webhook", "https://hook.example.com/x"))))
        val alerts = RecordingAlertDispatcher()
        return Triple(builds, alerts, FlakyAlerter(builds, settings, alerts, dashboardBaseUrl = "https://bh.example.com"))
    }

    private fun divergentBuild(id: String, at: Long, failed: Int) =
        TestPayloads.build(buildId = id, startedAt = at, sha = "sha-a", tests = listOf(TestPayloads.testTask(failed = failed)))

    @Test
    fun `a newly-flaky class fires exactly one alert, edge-triggered`() {
        val (builds, alerts, alerter) = harness()
        // Ingest three same-sha builds; FooTest diverges on the second.
        builds.save("p", divergentBuild("b1", recent, failed = 0)); alerter.evaluate("p", "pilot", divergentBuild("b1", recent, 0))
        builds.save("p", divergentBuild("b2", recent + 1, failed = 1)); alerter.evaluate("p", "pilot", divergentBuild("b2", recent + 1, 1))
        builds.save("p", divergentBuild("b3", recent + 2, failed = 0)); alerter.evaluate("p", "pilot", divergentBuild("b3", recent + 2, 0))
        assertEquals(1, alerts.sent.size, "the class crossed the threshold once → one alert")
        assertTrue(alerts.sent.single().second is FlakyAlert)

        // A fourth still-divergent build must NOT re-alert (edge-triggered, not per-build).
        builds.save("p", divergentBuild("b4", recent + 3, failed = 1)); alerter.evaluate("p", "pilot", divergentBuild("b4", recent + 3, 1))
        assertEquals(1, alerts.sent.size, "still-flaky class does not re-alert")
    }

    @Test
    fun `no channels configured means no alert`() {
        val builds = InMemoryBuildStore()
        val settings = InMemorySettingsStore() // no settings row → default ProjectSettings, empty channels
        val alerts = RecordingAlertDispatcher()
        val alerter = FlakyAlerter(builds, settings, alerts, dashboardBaseUrl = null)
        repeat(3) { i -> val b = divergentBuild("b$i", recent + i, failed = if (i == 1) 1 else 0); builds.save("p", b); alerter.evaluate("p", "pilot", b) }
        assertTrue(alerts.sent.isEmpty())
    }

    @Test
    fun `a thrown detector error never propagates (ingest unaffected)`() {
        // A store whose flaky() throws — the hook must swallow it.
        val throwing = object : BuildStore by InMemoryBuildStore() {
            override fun flaky(projectId: String, days: Int, nowMs: Long, projectKey: String?): List<FlakyRecord> = throw RuntimeException("boom")
        }
        val (_, alerts, alerter) = harness(throwing)
        // Must not throw.
        alerter.evaluate("p", "pilot", divergentBuild("b1", recent, 1))
        assertTrue(alerts.sent.isEmpty())
    }
}
