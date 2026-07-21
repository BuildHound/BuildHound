package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** Per-project regression settings (spec §5); defaults apply when no row exists. */
@Serializable
data class ProjectSettings(
    val baselineN: Int = 20,
    val defaultBranch: String = "main",
    val warnZ: Double = 3.5,
    val failZ: Double = 5.0,
    /** Metric name → absolute ceiling; a breach is always FAIL, independent of the baseline. */
    val budgets: Map<String, Double> = emptyMap(),
    val alertChannels: List<AlertChannel> = emptyList(),
)

@Serializable data class AlertChannel(val kind: String, val url: String)

enum class VerdictStatus {
    INSUFFICIENT_DATA,
    PASS,
    WARN,
    FAIL,
}

/** Whether a rise or a fall in a metric is the *bad* direction (semantic goodness, UX §4.2.2). */
enum class MetricDirection {
    HIGHER_BAD,
    LOWER_BAD,
}

/** One metric of the candidate build to judge. */
data class MetricInput(
    val name: String,
    val value: Double,
    val direction: MetricDirection,
    /**
     * Budgets are ceilings, so only meaningful for HIGHER_BAD metrics (duration, sizes, custom).
     */
    val budgetable: Boolean = true,
)

@Serializable
data class MetricVerdict(
    val name: String,
    val value: Double,
    val baselineMedian: Double? = null,
    val mad: Double? = null,
    val z: Double? = null,
    val budget: Double? = null,
    val status: String,
)

@Serializable
data class Verdict(
    val status: String,
    val metrics: List<MetricVerdict>,
    val baselineKey: String,
    val evaluatedAt: Long? = null,
)

/**
 * Pure regression math (spec §5, research §2.6/§5.6): rolling median + MAD baselines and a guarded
 * robust-z verdict. No I/O — the route/hook feeds it the candidate metrics and the per-metric
 * baseline value lists it already loaded, so this is plain-unit-testable.
 *
 * Guard rails against cold-start false alarms: fewer than [MIN_BASELINE] baseline points ⇒
 * INSUFFICIENT_DATA (never FAIL); a degenerate (zero) MAD ⇒ the `>2× median` fallback rule; a zero
 * baseline median ⇒ no judgement (can't form a ratio). Budgets are absolute and evaluated
 * independently of the baseline; a breach is always FAIL.
 */
object RegressionEngine {

    const val MIN_BASELINE = 3

    /** md5 of the sorted task names joined by newline — mirrored by the V3 backfill SQL exactly. */
    fun requestedTasksSignature(tasks: List<String>): String =
        MessageDigest.getInstance("MD5")
            .digest(tasks.sorted().joinToString("\n").encodeToByteArray())
            .joinToString("") { b -> "%02x".format(b) }

    fun branchClass(branch: String?, defaultBranch: String): String =
        if (branch != null && branch == defaultBranch) "main" else "pr"

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Median absolute deviation — outlier-resistant scale (unlike stddev). */
    fun mad(values: List<Double>, median: Double): Double = median(values.map { abs(it - median) })

    /** The built-in metrics every build carries: duration (higher bad), hit rate (lower bad). */
    fun builtInMetrics(payload: BuildPayload): List<MetricInput> = buildList {
        add(
            MetricInput(
                "durationMs",
                (payload.finishedAt - payload.startedAt).toDouble(),
                MetricDirection.HIGHER_BAD,
            )
        )
        payload.derived?.cacheableHitRate?.let {
            add(MetricInput("cacheableHitRate", it, MetricDirection.LOWER_BAD, budgetable = false))
        }
    }

    fun evaluate(
        inputs: List<MetricInput>,
        baselines: Map<String, List<Double>>,
        settings: ProjectSettings,
        baselineKey: String,
    ): Verdict {
        val metrics = inputs.map { evaluateMetric(it, baselines[it.name].orEmpty(), settings) }
        val overall =
            metrics.map { VerdictStatus.valueOf(it.status) }.maxByOrNull { it.ordinal }
                ?: VerdictStatus.INSUFFICIENT_DATA
        return Verdict(status = overall.name, metrics = metrics, baselineKey = baselineKey)
    }

    private fun evaluateMetric(
        input: MetricInput,
        baseline: List<Double>,
        settings: ProjectSettings,
    ): MetricVerdict {
        val budget = settings.budgets[input.name]?.takeIf { input.budgetable }
        val budgetFail = budget != null && input.value > budget

        if (baseline.size < MIN_BASELINE) {
            return MetricVerdict(
                name = input.name,
                value = input.value,
                budget = budget,
                status =
                    (if (budgetFail) VerdictStatus.FAIL else VerdictStatus.INSUFFICIENT_DATA).name,
            )
        }

        val median = median(baseline)
        val mad = mad(baseline, median)
        val zStatus: VerdictStatus
        var z: Double? = null
        if (mad == 0.0) {
            // Degenerate scale: fall back to the guarded ratio rule (research §2.6).
            zStatus =
                if (exceedsDoubleFallback(input.value, median, input.direction)) VerdictStatus.FAIL
                else VerdictStatus.PASS
        } else {
            val rawZ = 0.6745 * (input.value - median) / mad
            z = rawZ
            val badZ = if (input.direction == MetricDirection.HIGHER_BAD) rawZ else -rawZ
            zStatus =
                when {
                    badZ >= settings.failZ -> VerdictStatus.FAIL
                    badZ >= settings.warnZ -> VerdictStatus.WARN
                    else -> VerdictStatus.PASS
                }
        }

        val status = if (budgetFail) VerdictStatus.FAIL else zStatus
        return MetricVerdict(input.name, input.value, median, mad, z, budget, status.name)
    }

    /** `>2× median` (higher-bad) or `< median/2` (lower-bad); no judgement on a zero median. */
    private fun exceedsDoubleFallback(
        value: Double,
        median: Double,
        direction: MetricDirection,
    ): Boolean {
        if (median <= 0.0) return false
        return when (direction) {
            MetricDirection.HIGHER_BAD -> value > 2.0 * median
            MetricDirection.LOWER_BAD -> value < median / 2.0
        }
    }
}
