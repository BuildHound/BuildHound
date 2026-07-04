package dev.buildhound.gradle

import dev.buildhound.commons.ci.SourceLinks
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.CiInfo
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.DerivedMetricsCalculator
import dev.buildhound.commons.payload.EnvironmentInfo
import dev.buildhound.commons.payload.FingerprintInfo
import dev.buildhound.commons.payload.KotlinInfo
import dev.buildhound.commons.payload.PayloadCapper
import dev.buildhound.commons.payload.PayloadCaps
import dev.buildhound.commons.payload.PayloadScrubber
import dev.buildhound.commons.payload.ProcessInfo
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TestTaskResult
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
        projectRoots: List<String>,
        configurationMs: Long? = null,
        caps: PayloadCaps = PayloadCaps.DEFAULT,
        fingerprints: FingerprintInfo? = null,
        kotlin: KotlinInfo? = null,
        tests: List<TestTaskResult> = emptyList(),
        processes: List<CollectedProcess> = emptyList(),
    ): BuildPayload {
        val startedAt = tasks.minOfOrNull { it.startMs } ?: nowMs
        val finishedAt = (tasks.maxOfOrNull { it.startMs + it.durationMs } ?: nowMs).coerceAtLeast(startedAt)
        val payload = BuildPayload(
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
                    // IDE + AI-agent detection (plan 027).
                    ide = it.ide,
                    ideVersion = it.ideVersion,
                    ideSync = it.ideSync,
                    aiAgent = it.aiAgent,
                )
            },
            toolchain = environment?.let { ToolchainInfo(gradle = it.gradleVersion, jdk = it.jdkVersion) },
            vcs = vcsInfo(vcs, ci),
            ci = ciInfo(ci),
            // Source/commit/PR links from the redacted remote + CI PR number (plan 027); github/gitlab only.
            links = SourceLinks.compose(vcs?.remoteUrl, vcs?.sha ?: ci?.commitSha, ci?.pullRequestId),
            tags = tags,
            tasks = tasks,
            // Derived metrics are computed over the FULL task list, before any cap drops
            // rows — hit rate/utilization must not shift when the payload is truncated.
            derived = DerivedMetricsCalculator.compute(tasks, environment?.cores, configurationMs),
            // Salted input fingerprints (plan 022); null when uncaptured or unsaltable.
            fingerprints = fingerprints?.takeIf { it.build.isNotEmpty() || it.tasks.isNotEmpty() },
            // Bundled Kotlin build report (plan 023); null when unwired/unobservable.
            kotlin = kotlin,
            // Per-test-task results parsed from JUnit XML (plan 024); empty when no test ran.
            tests = tests,
            // End-of-build JVM process snapshot (plan 029); empty when disabled/unobservable. Numeric
            // + enum only — nothing for the scrubber to touch (no PID, path, or command line).
            processes = processes.map {
                ProcessInfo(
                    role = it.role,
                    heapUsedMb = it.heapUsedMb,
                    heapCommittedMb = it.heapCommittedMb,
                    heapMaxMb = it.heapMaxMb,
                    configuredXmxMb = it.configuredXmxMb,
                    gcTimeMs = it.gcTimeMs,
                    rssMb = it.rssMb,
                    uptimeS = it.uptimeS,
                )
            },
        )
        // Spec §3.7 then §3.9: scrub whole free-text values first (so secret patterns see
        // the complete string, never a truncated slice), then enforce the payload budgets.
        // One capped, scrubbed payload everywhere — local file, artifact, upload.
        val scrubbed = PayloadScrubber.scrub(payload, projectRoots)
        return PayloadCapper.cap(scrubbed, caps)
    }

    /** Git wins; CI context fills the gaps (detached HEAD on CI has no branch name). */
    fun vcsInfo(vcs: CollectedVcs?, ci: CollectedCi?): VcsInfo? {
        val branch = vcs?.branch ?: ci?.branch
        val sha = vcs?.sha ?: ci?.commitSha
        val dirty = vcs?.dirty
        val remoteUrl = vcs?.remoteUrl
        if (branch == null && sha == null && dirty == null && remoteUrl == null) return null
        return VcsInfo(branch = branch, sha = sha, dirty = dirty, remoteUrl = remoteUrl)
    }

    /**
     * Only §4-declared fields ride at the top level; PR correlation and provider
     * extras go into the declared `attributes` map (our derived keys win on clash).
     * `agentName` is dropped entirely (plan 005).
     */
    fun ciInfo(ci: CollectedCi?): CiInfo? {
        if (ci == null) return null
        val attributes = buildMap {
            putAll(ci.attributes)
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
            // Central gate: the generic provider and SPI extras are not scheme-checked,
            // and this URL will be rendered as a hyperlink downstream.
            buildUrl = ci.buildUrl?.takeIf(::isHttpUrl),
            attributes = attributes,
        )
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
}
