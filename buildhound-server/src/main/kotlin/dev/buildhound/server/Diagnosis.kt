package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.DerivedMetricsCalculator
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlinx.serialization.Serializable

/**
 * Config-vs-execution classification for one build (plan 071, research F21). A **two-way** split,
 * deliberately not a full phase decomposition — there is no separate dependency-resolution phase to
 * invent. [dominant] is `"EXECUTION"` whenever [configurationMs] does not strictly exceed
 * [executionMs], so a config-cache hit (`configurationMs == 0`) reads as honestly execution-dominant.
 */
@Serializable
data class PhaseBreakdown(
    val configurationMs: Long,
    val executionMs: Long,
    val dominant: String, // CONFIGURATION | EXECUTION
)

/** This build's cache-hit rate against the (v1-constant) target (plan 071). */
@Serializable
data class HitRateAssessment(
    val hitRate: Double,
    val target: Double,
    val belowTarget: Boolean,
)

/** One of this build's slowest executed tasks (plan 071) — never fabricated when `tasks` is empty (IP). */
@Serializable
data class Hotspot(
    val path: String,
    val module: String? = null,
    val type: String? = null,
    val durationMs: Long,
)

/**
 * Headline-metric deltas vs the comparable baseline (plan 071): reuse of the already-stored [Verdict]'s
 * per-metric detail (`value`/`baselineMedian`/`z`/`status`) — the verdict's baseline window *is* the
 * comparable set (plan 025), so this is reuse, not a new query. Either field may be null when the
 * corresponding metric was not evaluated (e.g. no `cacheableHitRate` on this build).
 */
@Serializable
data class MetricDeltas(
    val durationMs: MetricVerdict? = null,
    val cacheableHitRate: MetricVerdict? = null,
)

/**
 * Agent-facing synthesis of one build's already-collected signals (plan 071, research F21): dominant
 * phase, hit-rate-vs-target, top hotspots, and deltas vs the comparable baseline, in a single
 * agent-consumable object — the sanctioned counter to an agent defaulting to `./gradlew --scan` (which
 * uploads to `scans.gradle.com`). Every analytic field is independently nullable so an absent signal
 * degrades honestly rather than fabricating a phase, rate, or hotspot list (isolated-projects, legacy
 * payloads, or a build with no evaluated verdict).
 */
@Serializable
data class Diagnosis(
    val buildId: String,
    val dominantPhase: PhaseBreakdown? = null,
    val cacheHitRate: HitRateAssessment? = null,
    val topHotspots: List<Hotspot> = emptyList(),
    val deltas: MetricDeltas? = null,
)

/**
 * Pure synthesiser — plain-unit-testable, no storage or Ktor types (server §5), the same discipline as
 * [BuildComparator]/`BottleneckCalculator`. `diagnose` never fabricates: each field maps to a real,
 * already-collected source and degrades to null/empty on an absent one.
 */
object BuildDiagnoser {

    /** v1 ships a constant target (F21's default-constant option); a per-project override is deferred. */
    const val DEFAULT_CACHE_HIT_TARGET: Double = 0.8

    private const val TOP_HOTSPOTS_LIMIT: Int = 10

    fun diagnose(payload: BuildPayload, verdict: Verdict?): Diagnosis = Diagnosis(
        buildId = payload.buildId,
        dominantPhase = dominantPhase(payload),
        cacheHitRate = cacheHitRate(payload),
        topHotspots = topHotspots(payload),
        deltas = deltas(verdict),
    )

    /**
     * `derived.configurationMs` (config) vs [DerivedMetricsCalculator.wallClockMs] over `tasks`
     * (execution wall-clock — **not** summed task durations, which parallelism would overcount).
     * Null when `configurationMs` is null (unmeasurable, honest-null-degrade); `0` (a CC hit) is a
     * real measurement, not absence, so it correctly reads as execution-dominant below.
     */
    private fun dominantPhase(payload: BuildPayload): PhaseBreakdown? {
        val configurationMs = payload.derived?.configurationMs ?: return null
        val executionMs = DerivedMetricsCalculator.wallClockMs(payload.tasks)
        val dominant = if (configurationMs > executionMs) "CONFIGURATION" else "EXECUTION"
        return PhaseBreakdown(configurationMs = configurationMs, executionMs = executionMs, dominant = dominant)
    }

    /** Null when `derived.cacheableHitRate` is null (isolated-projects / pre-016 payloads — inherited honest-null). */
    private fun cacheHitRate(payload: BuildPayload): HitRateAssessment? {
        val hitRate = payload.derived?.cacheableHitRate ?: return null
        return HitRateAssessment(
            hitRate = hitRate,
            target = DEFAULT_CACHE_HIT_TARGET,
            belowTarget = hitRate < DEFAULT_CACHE_HIT_TARGET,
        )
    }

    /**
     * This build's `EXECUTED` tasks ranked by duration, top-N; empty (never fabricated) under IP.
     * Tie-break on [TaskExecution.path] for a deterministic order at the top-N boundary — the same
     * `.thenBy { key }` discipline as every other ranker in this module (see [BuildComparator] and
     * `BottleneckCalculator`).
     */
    private fun topHotspots(payload: BuildPayload): List<Hotspot> =
        payload.tasks
            .filter { it.outcome == TaskOutcome.EXECUTED }
            .sortedWith(compareByDescending<TaskExecution> { it.durationMs }.thenBy { it.path })
            .take(TOP_HOTSPOTS_LIMIT)
            .map { Hotspot(path = it.path, module = it.module, type = it.type, durationMs = it.durationMs) }

    /** Null when no verdict was evaluated for this build; per-metric field is null when that metric wasn't judged. */
    private fun deltas(verdict: Verdict?): MetricDeltas? {
        if (verdict == null) return null
        return MetricDeltas(
            durationMs = verdict.metrics.find { it.name == "durationMs" },
            cacheableHitRate = verdict.metrics.find { it.name == "cacheableHitRate" },
        )
    }
}
