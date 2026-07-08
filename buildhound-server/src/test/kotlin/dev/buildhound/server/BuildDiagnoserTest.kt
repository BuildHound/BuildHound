package dev.buildhound.server

import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.DerivedMetrics
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildDiagnoserTest {

    private fun build(
        derived: DerivedMetrics? = null,
        tasks: List<TaskExecution> = emptyList(),
    ) = BuildPayload(
        buildId = "b1", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.SUCCESS,
        derived = derived, tasks = tasks,
    )

    private fun task(path: String, outcome: TaskOutcome, durationMs: Long, startMs: Long = 0, module: String? = null, type: String? = null) =
        TaskExecution(path = path, module = module, type = type, startMs = startMs, durationMs = durationMs, outcome = outcome)

    // ---- dominant phase ----

    @Test
    fun `configuration dominant when configurationMs exceeds the execution wall-clock`() {
        val payload = build(
            derived = DerivedMetrics(configurationMs = 5000),
            tasks = listOf(task(":a", TaskOutcome.EXECUTED, durationMs = 1000, startMs = 0)),
        )
        val diagnosis = BuildDiagnoser.diagnose(payload, verdict = null)
        assertEquals("CONFIGURATION", diagnosis.dominantPhase?.dominant)
        assertEquals(5000, diagnosis.dominantPhase?.configurationMs)
        assertEquals(1000, diagnosis.dominantPhase?.executionMs)
    }

    @Test
    fun `execution dominant when the execution wall-clock exceeds configurationMs`() {
        val payload = build(
            derived = DerivedMetrics(configurationMs = 200),
            tasks = listOf(task(":a", TaskOutcome.EXECUTED, durationMs = 5000, startMs = 0)),
        )
        val diagnosis = BuildDiagnoser.diagnose(payload, verdict = null)
        assertEquals("EXECUTION", diagnosis.dominantPhase?.dominant)
    }

    @Test
    fun `null configurationMs degrades dominantPhase to null (unmeasurable)`() {
        val payload = build(derived = DerivedMetrics(configurationMs = null), tasks = listOf(task(":a", TaskOutcome.EXECUTED, 1000)))
        assertNull(BuildDiagnoser.diagnose(payload, verdict = null).dominantPhase)

        val noDerived = build(derived = null, tasks = listOf(task(":a", TaskOutcome.EXECUTED, 1000)))
        assertNull(BuildDiagnoser.diagnose(noDerived, verdict = null).dominantPhase)
    }

    @Test
    fun `a config-cache hit (configurationMs 0) is honestly execution-dominant, not null`() {
        val payload = build(
            derived = DerivedMetrics(configurationMs = 0),
            tasks = listOf(task(":a", TaskOutcome.EXECUTED, durationMs = 3000, startMs = 0)),
        )
        val phase = BuildDiagnoser.diagnose(payload, verdict = null).dominantPhase
        assertEquals(0, phase?.configurationMs)
        assertEquals("EXECUTION", phase?.dominant)
    }

    // ---- cache hit rate vs target ----

    @Test
    fun `null cacheableHitRate degrades cacheHitRate to null`() {
        val payload = build(derived = DerivedMetrics(cacheableHitRate = null))
        assertNull(BuildDiagnoser.diagnose(payload, verdict = null).cacheHitRate)
        assertNull(BuildDiagnoser.diagnose(build(derived = null), verdict = null).cacheHitRate)
    }

    @Test
    fun `hit rate below the default target is flagged belowTarget`() {
        val payload = build(derived = DerivedMetrics(cacheableHitRate = 0.5))
        val assessment = BuildDiagnoser.diagnose(payload, verdict = null).cacheHitRate
        assertEquals(0.5, assessment?.hitRate)
        assertEquals(BuildDiagnoser.DEFAULT_CACHE_HIT_TARGET, assessment?.target)
        assertTrue(assessment!!.belowTarget)
    }

    @Test
    fun `hit rate at or above the default target is not belowTarget`() {
        val atTarget = BuildDiagnoser.diagnose(build(derived = DerivedMetrics(cacheableHitRate = 0.8)), verdict = null).cacheHitRate
        assertTrue(atTarget?.belowTarget == false)
        val above = BuildDiagnoser.diagnose(build(derived = DerivedMetrics(cacheableHitRate = 0.95)), verdict = null).cacheHitRate
        assertTrue(above?.belowTarget == false)
    }

    // ---- hotspots ----

    @Test
    fun `hotspots rank EXECUTED-only tasks by duration descending, top-N`() {
        val payload = build(tasks = listOf(
            task(":slow", TaskOutcome.EXECUTED, durationMs = 9000, module = ":app", type = "Compile"),
            task(":fast", TaskOutcome.EXECUTED, durationMs = 100),
            task(":cached", TaskOutcome.FROM_CACHE, durationMs = 50_000), // never a hotspot
            task(":uptodate", TaskOutcome.UP_TO_DATE, durationMs = 50_000),
        ))
        val hotspots = BuildDiagnoser.diagnose(payload, verdict = null).topHotspots
        assertEquals(listOf(":slow", ":fast"), hotspots.map { it.path })
        assertEquals(":app", hotspots.first().module)
        assertEquals("Compile", hotspots.first().type)
    }

    @Test
    fun `no tasks yields an empty hotspot list, never fabricated`() {
        assertEquals(emptyList(), BuildDiagnoser.diagnose(build(), verdict = null).topHotspots)
    }

    @Test
    fun `hotspots are capped at the top-10 limit`() {
        val tasks = (1..15).map { i -> task(":t$i", TaskOutcome.EXECUTED, durationMs = i.toLong()) }
        val hotspots = BuildDiagnoser.diagnose(build(tasks = tasks), verdict = null).topHotspots
        assertEquals(10, hotspots.size)
        assertEquals(":t15", hotspots.first().path) // largest duration first
    }

    @Test
    fun `a duration tie at the top-10 boundary is broken deterministically by path`() {
        // Six tasks share the max duration (3000) — only some of them fit under the top-10 cap once
        // combined with the four distinct-duration tasks below, so the tie-break at the boundary must
        // be a stable, path-ordered pick rather than whatever order sortedByDescending happened to
        // preserve.
        val tied = listOf(":z", ":a", ":m", ":q", ":b", ":k").map { task(it, TaskOutcome.EXECUTED, durationMs = 3000) }
        val distinct = (1..4).map { i -> task(":d$i", TaskOutcome.EXECUTED, durationMs = i.toLong()) }
        val hotspots = BuildDiagnoser.diagnose(build(tasks = tied + distinct), verdict = null).topHotspots

        assertEquals(10, hotspots.size)
        // All six duration-3000 ties come first, alphabetically by path (thenBy { it.path }).
        assertEquals(listOf(":a", ":b", ":k", ":m", ":q", ":z"), hotspots.take(6).map { it.path })
        // Then the distinct-duration tasks, largest first.
        assertEquals(listOf(":d4", ":d3", ":d2", ":d1"), hotspots.drop(6).map { it.path })

        // Re-running with the same inputs must reproduce the exact same order (determinism, not luck).
        val again = BuildDiagnoser.diagnose(build(tasks = tied + distinct), verdict = null).topHotspots
        assertEquals(hotspots.map { it.path }, again.map { it.path })
    }

    // ---- deltas vs the comparable baseline ----

    @Test
    fun `deltas are null when no verdict was evaluated`() {
        assertNull(BuildDiagnoser.diagnose(build(), verdict = null).deltas)
    }

    @Test
    fun `deltas are populated from the supplied verdict's durationMs and cacheableHitRate metrics`() {
        val verdict = Verdict(
            status = "FAIL",
            baselineKey = "k",
            metrics = listOf(
                MetricVerdict(name = "durationMs", value = 5000.0, baselineMedian = 1000.0, z = 6.0, status = "FAIL"),
                MetricVerdict(name = "cacheableHitRate", value = 0.5, baselineMedian = 0.9, z = -4.0, status = "WARN"),
            ),
        )
        val deltas = BuildDiagnoser.diagnose(build(), verdict).deltas
        assertEquals(5000.0, deltas?.durationMs?.value)
        assertEquals(1000.0, deltas?.durationMs?.baselineMedian)
        assertEquals(0.5, deltas?.cacheableHitRate?.value)
        assertEquals("WARN", deltas?.cacheableHitRate?.status)
    }

    @Test
    fun `a verdict with only durationMs judged leaves cacheableHitRate delta null`() {
        val verdict = Verdict(
            status = "PASS",
            baselineKey = "k",
            metrics = listOf(MetricVerdict(name = "durationMs", value = 1000.0, status = "PASS")),
        )
        val deltas = BuildDiagnoser.diagnose(build(), verdict).deltas
        assertEquals(1000.0, deltas?.durationMs?.value)
        assertNull(deltas?.cacheableHitRate)
    }

    /**
     * Pins the wiring between [RegressionEngine.builtInMetrics] (which names its metrics
     * "durationMs"/"cacheableHitRate") and [BuildDiagnoser.deltas] (which independently hardcodes the
     * same two literals to look them back up, Diagnosis.kt:122-123 / RegressionEngine.kt:91,93). A
     * rename on either side that isn't mirrored on the other would silently null out the corresponding
     * delta — honest-null-degrade means that drift produces no failure anywhere else, so this test runs
     * a build through the *real* engine (not a hand-built [Verdict]) and asserts both deltas survive.
     */
    @Test
    fun `deltas stay wired to RegressionEngine's actual built-in metric names`() {
        val payload = TestPayloads.build(durationMs = 4000, hitRate = 0.5)
        val baselines = mapOf(
            "durationMs" to List(5) { 1000.0 },
            "cacheableHitRate" to List(5) { 0.9 },
        )
        val verdict = RegressionEngine.evaluate(
            inputs = RegressionEngine.builtInMetrics(payload),
            baselines = baselines,
            settings = ProjectSettings(),
            baselineKey = "k",
        )
        val deltas = BuildDiagnoser.diagnose(payload, verdict).deltas
        assertTrue(deltas?.durationMs != null, "durationMs delta went null — check the metric name in RegressionEngine.builtInMetrics still matches BuildDiagnoser.deltas' lookup")
        assertTrue(deltas?.cacheableHitRate != null, "cacheableHitRate delta went null — check the metric name in RegressionEngine.builtInMetrics still matches BuildDiagnoser.deltas' lookup")
    }
}
