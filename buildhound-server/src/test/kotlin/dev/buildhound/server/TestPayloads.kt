package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.CiInfo
import dev.buildhound.commons.payload.DerivedMetrics
import dev.buildhound.commons.payload.VcsInfo

/** Builds schema-v1 [BuildPayload]s for server tests without hand-writing JSON each time. */
object TestPayloads {
    fun build(
        buildId: String = "11111111-2222-3333-4444-555555555555",
        durationMs: Long = 1000,
        startedAt: Long = 1_751_450_000_000,
        hitRate: Double? = null,
        outcome: BuildOutcome = BuildOutcome.SUCCESS,
        mode: BuildMode = BuildMode.CI,
        branch: String? = "main",
        requestedTasks: List<String> = listOf("build"),
        pipelineName: String? = "android-ci",
        provider: String? = "azure-devops",
        runId: String? = null,
    ): BuildPayload = BuildPayload(
        buildId = buildId,
        startedAt = startedAt,
        finishedAt = startedAt + durationMs,
        outcome = outcome,
        mode = mode,
        requestedTasks = requestedTasks,
        vcs = branch?.let { VcsInfo(branch = it) },
        ci = provider?.let { CiInfo(provider = it, runId = runId, pipelineName = pipelineName) },
        derived = hitRate?.let { DerivedMetrics(cacheableHitRate = it) },
    )
}
