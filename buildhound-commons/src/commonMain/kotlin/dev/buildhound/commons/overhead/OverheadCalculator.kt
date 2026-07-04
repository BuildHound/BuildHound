package dev.buildhound.commons.overhead

import kotlin.math.roundToLong

/**
 * Pure overhead verdict math (plan 034): given the plugin-on and plugin-off scenario stats, evaluate
 * each [OverheadAxis] against its budget. The one place plugin/CI/docs agree on "breach". Unit-tested
 * like the other commons calculators; the CI verdict tool is a thin launcher over [evaluate].
 */
object OverheadCalculator {

    fun evaluate(
        pluginOn: Map<String, ScenarioStats>,
        pluginOff: Map<String, ScenarioStats>,
        budget: OverheadBudget = OverheadBudget.DEFAULT,
    ): OverheadReport {
        val verdicts = budget.axes.map { axis -> evaluateAxis(axis, pluginOn, pluginOff) }
        return OverheadReport(verdicts = verdicts, anyBreached = verdicts.any { it.breached })
    }

    private fun statsFor(sample: AxisSample, on: Map<String, ScenarioStats>, off: Map<String, ScenarioStats>): ScenarioStats? =
        when (sample.run) {
            Run.PLUGIN_ON -> on[sample.scenario]
            Run.PLUGIN_OFF -> off[sample.scenario]
        }

    private fun evaluateAxis(
        axis: OverheadAxis,
        on: Map<String, ScenarioStats>,
        off: Map<String, ScenarioStats>,
    ): OverheadVerdict {
        val treatment = statsFor(axis.treatment, on, off)
        val baseline = statsFor(axis.baseline, on, off)
        if (treatment == null || baseline == null) {
            // A required scenario is missing → cannot verify overhead → loud, breach-counted failure.
            return OverheadVerdict(
                axis = axis.name, treatmentMeanMs = 0.0, baselineMeanMs = 0.0, deltaMs = 0.0,
                deltaFraction = 0.0, allowanceMs = 0.0, separated = false, dataMissing = true, breached = true,
            )
        }
        val deltaMs = treatment.meanMs - baseline.meanMs
        val deltaFraction = if (baseline.meanMs != 0.0) deltaMs / baseline.meanMs else 0.0
        // The cap is the LOOSER (larger allowance) of the absolute floor and the percentage.
        val pctAllowance = axis.maxPct?.let { it / 100.0 * baseline.meanMs }
        val allowanceMs = listOfNotNull(axis.maxMs?.toDouble(), pctAllowance).maxOrNull() ?: 0.0
        // Separated = the delta clears the combined same-machine spread (noise floor).
        val separated = deltaMs > (treatment.stddevMs + baseline.stddevMs)
        // Breach = over the (looser) allowance AND, when required, statistically separated from noise.
        val breached = deltaMs > allowanceMs && (!axis.requireSeparation || separated)
        return OverheadVerdict(
            axis = axis.name,
            treatmentMeanMs = treatment.meanMs,
            baselineMeanMs = baseline.meanMs,
            deltaMs = deltaMs,
            deltaFraction = deltaFraction,
            allowanceMs = allowanceMs,
            separated = separated,
            dataMissing = false,
            breached = breached,
        )
    }

    /** Render the report as a Markdown table (the CI artifact + the launcher's stdout). Pure. */
    fun markdownTable(report: OverheadReport): String {
        val header = "| Axis | Baseline (ms) | Plugin (ms) | Δ (ms) | Δ (%) | Allowance (ms) | Separated | Verdict |"
        val divider = "|---|---:|---:|---:|---:|---:|:---:|:---:|"
        val rows = report.verdicts.map { v ->
            val verdict = when {
                v.dataMissing -> "⚠ MISSING"
                v.breached -> "❌ BREACH"
                else -> "✅ ok"
            }
            "| ${v.axis} | ${v.baselineMeanMs.round1()} | ${v.treatmentMeanMs.round1()} | ${v.deltaMs.round1()} | " +
                "${(v.deltaFraction * 100).round1()} | ${v.allowanceMs.round1()} | ${if (v.separated) "yes" else "no"} | $verdict |"
        }
        return (listOf(header, divider) + rows).joinToString("\n")
    }

    private fun Double.round1(): String {
        // Preserve the sign: a plugin-faster-than-baseline delta in (-1, 0) rounds to an integer part
        // of 0, which carries no sign, so format the magnitude and prepend "-" explicitly. Without
        // this, a genuinely-negative overhead renders as positive in the published table.
        val scaled = kotlin.math.abs((this * 10).roundToLong())
        val sign = if ((this * 10).roundToLong() < 0) "-" else ""
        return "$sign${scaled / 10}.${scaled % 10}"
    }
}
