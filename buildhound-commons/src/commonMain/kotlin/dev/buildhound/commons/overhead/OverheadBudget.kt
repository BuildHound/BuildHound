package dev.buildhound.commons.overhead

/**
 * Plugin-overhead budget model (plan 034). The single place plugin/CI/docs agree on what a "breach"
 * means: gradle-profiler drives a fixed fixture twice — plugin applied vs not — and this budget turns
 * the two `benchmark.csv` outputs into a per-axis pass/fail verdict. Pure data; the math is in
 * [OverheadCalculator]. Not a payload type — no schema/golden involvement.
 */

/** Which gradle-profiler run a scenario measurement comes from. */
enum class Run { PLUGIN_ON, PLUGIN_OFF }

/** One measurement cell: a scenario read from a specific run. */
data class AxisSample(val run: Run, val scenario: String)

/**
 * One budgeted overhead axis. Overhead = `mean(treatment) − mean(baseline)`. The cap is the **looser**
 * of [maxMs] and [maxPct] (percent of the baseline mean), so a fast fixture on a fast runner never
 * trips a percentage cap on sub-millisecond noise and a slow runner never hides a large absolute
 * regression inside a percentage. When [requireSeparation] is set, a breach counts only if the delta
 * also exceeds the combined spread (treatment stddev + baseline stddev) — same-machine noise must not
 * mint false breaches.
 */
data class OverheadAxis(
    val name: String,
    val treatment: AxisSample,
    val baseline: AxisSample,
    val maxMs: Long? = null,
    val maxPct: Double? = null,
    val requireSeparation: Boolean = true,
) {
    init {
        require(maxMs != null || maxPct != null) { "axis '$name' must set at least one of maxMs/maxPct" }
    }
}

/** The set of axes enforced together. */
data class OverheadBudget(val axes: List<OverheadAxis>) {
    companion object {
        // Scenario names match buildhound-ci-assets/overhead/overhead.scenarios.
        private const val NO_OP = "no_op"
        private const val INCREMENTAL = "incremental"
        private const val CC_HIT = "cc_hit"
        private const val NO_OP_UPLOAD = "no_op_upload"

        // The upload axis baseline: a no-op ALSO in CI mode but with no server URL, so the delta vs
        // no_op_upload isolates only the upload path (not the local→ci mode switch's context cost).
        private const val NO_OP_CI = "no_op_ci"

        /**
         * Provisional caps (plan 034 §3). These are **calibrated on the CI reference runner during
         * the first green harness run** and the committed values updated from the observed
         * plugin-on/off deltas with headroom (decision-log row); the shapes below are the starting
         * point, deliberately generous so the guardrail catches regressions, not steady-state noise.
         */
        val DEFAULT: OverheadBudget = OverheadBudget(
            listOf(
                // Configuration steady-state: plugin-on vs plugin-off on a config-cache hit.
                OverheadAxis(
                    name = "configuration",
                    treatment = AxisSample(Run.PLUGIN_ON, CC_HIT),
                    baseline = AxisSample(Run.PLUGIN_OFF, CC_HIT),
                    maxMs = 40, maxPct = 3.0,
                ),
                // Per-task: the collector listener's marginal cost on a task-dense incremental build.
                OverheadAxis(
                    name = "per-task",
                    treatment = AxisSample(Run.PLUGIN_ON, INCREMENTAL),
                    baseline = AxisSample(Run.PLUGIN_OFF, INCREMENTAL),
                    maxPct = 5.0,
                ),
                // Finalizer: the plugin's fixed build-end share on an up-to-date build.
                OverheadAxis(
                    name = "finalizer",
                    treatment = AxisSample(Run.PLUGIN_ON, NO_OP),
                    baseline = AxisSample(Run.PLUGIN_OFF, NO_OP),
                    maxMs = 150, maxPct = 8.0,
                ),
                // Upload: the synchronous-with-spool send cost, isolated by a no_op with a loopback
                // server URL vs a no_op in the SAME (CI) mode without one — both plugin-on and both CI,
                // so only the upload path differs (not the mode-switch's context-collection cost).
                OverheadAxis(
                    name = "upload",
                    treatment = AxisSample(Run.PLUGIN_ON, NO_OP_UPLOAD),
                    baseline = AxisSample(Run.PLUGIN_ON, NO_OP_CI),
                    maxMs = 250,
                ),
            ),
        )
    }
}

/** One scenario's summary stats parsed from a gradle-profiler `benchmark.csv`. */
data class ScenarioStats(val name: String, val meanMs: Double, val stddevMs: Double)

/** The evaluated result for one axis. */
data class OverheadVerdict(
    val axis: String,
    val treatmentMeanMs: Double,
    val baselineMeanMs: Double,
    val deltaMs: Double,
    /** Overhead as a fraction of the baseline mean (`deltaMs / baselineMeanMs`); 0 when baseline is 0. */
    val deltaFraction: Double,
    /** The effective allowance in ms — the looser of the absolute and percentage caps. */
    val allowanceMs: Double,
    /** True when the delta exceeds the combined stddev (statistically separated from noise). */
    val separated: Boolean,
    /** True when a required scenario was absent from the CSVs — a loud, breach-counted failure. */
    val dataMissing: Boolean,
    val breached: Boolean,
)

/** The whole-budget outcome. [anyBreached] is the process exit signal for the CI verdict tool. */
data class OverheadReport(val verdicts: List<OverheadVerdict>, val anyBreached: Boolean)
