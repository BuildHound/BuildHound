package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.CiInfo
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.DerivedMetricsCalculator
import dev.buildhound.commons.payload.EnvironmentInfo
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.ToolchainInfo
import dev.buildhound.commons.payload.VcsInfo

/**
 * Merges the collectors' outputs into a schema-v1 [BuildPayload] (spec §4). Pure and
 * Gradle-free so it unit-tests without the Gradle API on the classpath (plan 004 retro).
 */
internal object PayloadAssembler {

    /** Mode resolution (spec §3.4): AUTO becomes ci exactly when a CI context exists. */
    fun resolveMode(configured: TelemetryMode, ci: CollectedCi?): BuildMode? = when (configured) {
        TelemetryMode.DISABLED -> null
        TelemetryMode.CI -> BuildMode.CI
        TelemetryMode.LOCAL -> BuildMode.LOCAL
        TelemetryMode.AUTO -> if (ci != null) BuildMode.CI else BuildMode.LOCAL
    }

    fun assemble(
        buildId: String,
        projectKey: String?,
        mode: BuildMode,
        buildFailed: Boolean,
        requestedTasks: List<String>,
        tasks: List<TaskExecution>,
        environment: CollectedEnvironment?,
        vcs: CollectedVcs?,
        ci: CollectedCi?,
        configurationCache: ConfigurationCacheState,
        daemonReused: Boolean,
        tags: Map<String, String>,
        nowMs: Long,
    ): BuildPayload {
        val startedAt = tasks.minOfOrNull { it.startMs } ?: nowMs
        val finishedAt = (tasks.maxOfOrNull { it.startMs + it.durationMs } ?: nowMs).coerceAtLeast(startedAt)
        return BuildPayload(
            buildId = buildId,
            projectKey = projectKey,
            startedAt = startedAt,
            finishedAt = finishedAt,
            outcome = if (buildFailed) BuildOutcome.FAILED else BuildOutcome.SUCCESS,
            requestedTasks = requestedTasks,
            mode = mode,
            environment = environment?.let {
                EnvironmentInfo(
                    os = it.os,
                    arch = it.arch,
                    cores = it.cores,
                    ramMb = it.ramMb,
                    hostnameHash = it.hostnameHash,
                    userId = it.userId,
                    daemonReused = daemonReused,
                    configurationCache = configurationCache,
                )
            },
            toolchain = environment?.let { ToolchainInfo(gradle = it.gradleVersion, jdk = it.jdkVersion) },
            vcs = vcsInfo(vcs, ci),
            ci = ciInfo(ci),
            tags = tags,
            tasks = tasks,
            derived = DerivedMetricsCalculator.compute(tasks, environment?.cores),
        )
    }

    /** Git wins; CI context fills the gaps (detached HEAD on CI has no branch name). */
    fun vcsInfo(vcs: CollectedVcs?, ci: CollectedCi?): VcsInfo? {
        val branch = vcs?.branch ?: ci?.branch
        val sha = vcs?.sha ?: ci?.commitSha
        val dirty = vcs?.dirty
        if (branch == null && sha == null && dirty == null) return null
        return VcsInfo(branch = branch, sha = sha, dirty = dirty)
    }

    /**
     * Only §4-declared fields ride at the top level; PR correlation goes into the
     * declared `attributes` map. `agentName` is dropped entirely (plan 005).
     */
    fun ciInfo(ci: CollectedCi?): CiInfo? {
        if (ci == null) return null
        val attributes = buildMap {
            ci.pipelineId?.let { put("pipelineId", it) }
            ci.stageId?.let { put("stageId", it) }
            ci.pullRequestId?.let { put("pullRequestId", it) }
            ci.targetBranch?.let { put("targetBranch", it) }
        }
        return CiInfo(
            provider = ci.provider,
            runId = ci.runId,
            pipelineName = ci.pipelineName,
            jobId = ci.jobId,
            buildUrl = ci.buildUrl,
            attributes = attributes,
        )
    }
}
