package dev.buildhound.commons.overhead

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverheadCalculatorTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/overhead/$name")) { "missing $name" }.readBytes().decodeToString()

    private fun stats(name: String, mean: Double, stddev: Double) = mapOf(name to ScenarioStats(name, mean, stddev))

    @Test
    fun `the default budget passes on the sample fixtures`() {
        val on = ProfilerCsv.parse(resource("benchmark-on.csv"))
        val off = ProfilerCsv.parse(resource("benchmark-off.csv"))
        val report = OverheadCalculator.evaluate(on, off, OverheadBudget.DEFAULT)
        assertEquals(4, report.verdicts.size)
        assertFalse(report.anyBreached, OverheadCalculator.markdownTable(report))
        assertTrue(report.verdicts.none { it.dataMissing })
    }

    // A single-axis budget with an absolute floor of 150 ms OR 8 %, separation off for clarity.
    private fun finalizerBudget() = OverheadBudget(
        listOf(
            OverheadAxis(
                name = "finalizer",
                treatment = AxisSample(Run.PLUGIN_ON, "no_op"),
                baseline = AxisSample(Run.PLUGIN_OFF, "no_op"),
                maxMs = 150, maxPct = 8.0, requireSeparation = false,
            ),
        ),
    )

    @Test
    fun `a delta over the percentage but under the absolute floor does not breach (looser cap wins)`() {
        // baseline 600 → 8% = 48 ms; delta 100 ms exceeds 48 but is under the 150 ms floor.
        val on = stats("no_op", 700.0, 0.0)
        val off = stats("no_op", 600.0, 0.0)
        val report = OverheadCalculator.evaluate(on, off, finalizerBudget())
        assertFalse(report.anyBreached, "100ms > 8% but < 150ms floor → within budget")
        assertEquals(150.0, report.verdicts.single().allowanceMs)
    }

    @Test
    fun `a delta over both the floor and the percentage breaches`() {
        val on = stats("no_op", 800.0, 0.0)
        val off = stats("no_op", 600.0, 0.0) // delta 200 > 150 floor and > 48 pct
        val report = OverheadCalculator.evaluate(on, off, finalizerBudget())
        assertTrue(report.anyBreached)
    }

    @Test
    fun `the separation guard suppresses a breach lost in same-machine noise`() {
        // Tiny 10 ms allowance so the delta clears the cap; the point is the stddev guard.
        val axis = OverheadAxis(
            name = "noisy", treatment = AxisSample(Run.PLUGIN_ON, "no_op"), baseline = AxisSample(Run.PLUGIN_OFF, "no_op"),
            maxMs = 10, requireSeparation = true,
        )
        val budget = OverheadBudget(listOf(axis))
        // delta 100 ms, combined stddev 120 ms → NOT separated → no breach despite clearing 10 ms.
        val noisy = OverheadCalculator.evaluate(stats("no_op", 700.0, 60.0), stats("no_op", 600.0, 60.0), budget)
        assertFalse(noisy.anyBreached, "delta within combined stddev must not breach")
        assertFalse(noisy.verdicts.single().separated)
        // Tighten the spread → delta now clears the noise floor → breach.
        val clean = OverheadCalculator.evaluate(stats("no_op", 700.0, 20.0), stats("no_op", 600.0, 20.0), budget)
        assertTrue(clean.anyBreached, "delta beyond combined stddev breaches")
    }

    @Test
    fun `a missing required scenario is a loud, breach-counted failure`() {
        val report = OverheadCalculator.evaluate(emptyMap(), emptyMap(), finalizerBudget())
        val verdict = report.verdicts.single()
        assertTrue(verdict.dataMissing)
        assertTrue(verdict.breached)
        assertTrue(report.anyBreached, "missing data must fail the job, never report a false pass")
    }

    @Test
    fun `the table preserves the sign of a negative (plugin-faster) delta`() {
        // Plugin-on faster than baseline by 0.4 ms — the near-zero case the harness exists to show.
        val report = OverheadCalculator.evaluate(stats("no_op", 600.0, 0.0), stats("no_op", 600.4, 0.0), finalizerBudget())
        assertFalse(report.anyBreached)
        val table = OverheadCalculator.markdownTable(report)
        assertTrue(table.contains("-0.4"), "a negative delta must render with its sign, not as +0.4: $table")
    }

    @Test
    fun `markdown table renders a verdict per axis`() {
        val report = OverheadCalculator.evaluate(stats("no_op", 800.0, 0.0), stats("no_op", 600.0, 0.0), finalizerBudget())
        val table = OverheadCalculator.markdownTable(report)
        assertTrue(table.contains("| Axis |"))
        assertTrue(table.contains("finalizer"))
        assertTrue(table.contains("BREACH"))
    }
}
