package dev.buildhound.server.connector

import kotlinx.serialization.Serializable

/** The `GET /v1/builds/{id}/ci-run` response (plan 028): status + normalized tree + derived views. */
@Serializable
data class CiRunView(
    val status: CiRunStatus,
    val queuedMs: Long? = null,
    val spans: List<CiSpan> = emptyList(),
    val gradleSharePct: Double? = null,
)

/**
 * "Gradle share of pipeline" (plan 028): the ingested Gradle build's wall-clock as a fraction of the
 * whole CI pipeline's wall-clock — how much of the run was the build vs checkout/publish/sign steps.
 * Clamped to `[0,1]`; null when either duration is unknown or the pipeline span is non-positive.
 */
object GradleShare {
    fun percent(buildDurationMs: Long?, run: CiRun?): Double? {
        val pipelineMs = run?.let { r ->
            if (r.startedAt != null && r.finishedAt != null) r.finishedAt - r.startedAt else null
        }
        if (buildDurationMs == null || buildDurationMs < 0 || pipelineMs == null || pipelineMs <= 0) return null
        return (buildDurationMs.toDouble() / pipelineMs).coerceIn(0.0, 1.0)
    }
}
