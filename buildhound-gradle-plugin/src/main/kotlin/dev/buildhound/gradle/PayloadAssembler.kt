package dev.buildhound.gradle

import dev.buildhound.commons.ci.SourceLinks
import dev.buildhound.commons.payload.ArtifactSize
import dev.buildhound.commons.payload.ArtifactSizes
import dev.buildhound.commons.payload.BenchmarkInfo
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.BuildStructureInfo
import dev.buildhound.commons.payload.CapsSummary
import dev.buildhound.commons.payload.CiInfo
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.DerivedMetricsCalculator
import dev.buildhound.commons.payload.EnvironmentInfo
import dev.buildhound.commons.payload.FailureInfo
import dev.buildhound.commons.payload.FingerprintInfo
import dev.buildhound.commons.payload.GradlePropertyPosture
import dev.buildhound.commons.payload.GuhWarmth
import dev.buildhound.commons.payload.InvocationInfo
import dev.buildhound.commons.payload.JvmArtifactSize
import dev.buildhound.commons.payload.KotlinInfo
import dev.buildhound.commons.payload.PayloadCapper
import dev.buildhound.commons.payload.PayloadCaps
import dev.buildhound.commons.payload.PayloadScrubber
import dev.buildhound.commons.payload.ProcessInfo
import dev.buildhound.commons.payload.ProjectEvaluation
import dev.buildhound.commons.payload.StartMarker
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TestTaskResult
import dev.buildhound.commons.payload.TestTelemetryInfo
import dev.buildhound.commons.payload.ToolchainInfo
import dev.buildhound.commons.payload.VcsInfo
import dev.buildhound.commons.payload.WrapperInfo
import kotlinx.serialization.json.JsonElement

/**
 * Merges the collectors' outputs into a schema-v1 [BuildPayload] (spec §4). Pure and
 * Gradle-free so it unit-tests without the Gradle API on the classpath (plan 004 retro).
 */
internal object PayloadAssembler {

    /**
     * Mode resolution (spec §3.4/§7): DISABLED short-circuits; an active benchmark forces BENCHMARK
     * over AUTO/CI/LOCAL (the profiler pipeline sets env, not the DSL); else AUTO becomes ci exactly
     * when a CI context exists.
     */
    fun resolveMode(configured: TelemetryMode, ci: CollectedCi?, benchmark: CollectedBenchmark?): BuildMode? {
        if (configured == TelemetryMode.DISABLED) return null
        if (benchmark != null) return BuildMode.BENCHMARK
        return when (configured) {
            TelemetryMode.CI -> BuildMode.CI
            TelemetryMode.LOCAL -> BuildMode.LOCAL
            TelemetryMode.AUTO -> if (ci != null) BuildMode.CI else BuildMode.LOCAL
            TelemetryMode.DISABLED -> null
        }
    }

    /**
     * Synthesize an `INTERRUPTED` payload (plan 033) from a dead build's [StartMarker]: a build that
     * started but never finalized. `finishedAt == startedAt`, no tasks, no derived metrics, no
     * environment/ci/vcs (the marker carries none). Scrubbed for defense-in-depth even though every
     * field is already sanitized — `projectKey`/`requestedTasks` cannot carry a path, but the scrub is
     * cheap and keeps one code path. Never capped (an empty payload is below every budget).
     */
    fun assembleInterrupted(marker: StartMarker, projectRoots: List<String>): BuildPayload {
        val payload = BuildPayload(
            buildId = marker.buildId,
            projectKey = marker.projectKey,
            startedAt = marker.startedAtMs,
            finishedAt = marker.startedAtMs,
            outcome = BuildOutcome.INTERRUPTED,
            requestedTasks = marker.requestedTasks,
            mode = marker.mode,
            tasks = emptyList(),
            derived = null,
        )
        return PayloadScrubber.scrub(payload, projectRoots)
    }

    fun assemble(
        buildId: String,
        projectKey: String?,
        mode: BuildMode,
        buildFailed: Boolean,
        failure: CollectedFailure? = null,
        requestedTasks: List<String>,
        tasks: List<TaskExecution>,
        environment: CollectedEnvironment?,
        invocation: CollectedInvocation? = null,
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
        // Honest degraded state for JUnit-XML-disabled Test tasks (plan 053, research F3); null when
        // no executed task ran with the flag off this build.
        testTelemetry: TestTelemetryInfo? = null,
        processes: List<CollectedProcess> = emptyList(),
        benchmark: CollectedBenchmark? = null,
        artifacts: List<ArtifactSize> = emptyList(),
        // JVM archive sizes (plan 072, research F22); empty on a non-JVM-archive build. Rides beside
        // [artifacts] in the same ArtifactSizes block, emitted whenever *either* list is non-empty.
        jvmArtifacts: List<JvmArtifactSize> = emptyList(),
        // Per-project configuration-time attribution (plan 052); empty on a CC hit or when nothing was
        // captured, in which case the payload's block below collapses to null (`takeIf { isNotEmpty() }`).
        projectEvaluations: List<ProjectEvaluation> = emptyList(),
        extensions: Map<String, JsonElement> = emptyMap(),
        avoidedMs: Long? = null,
        dependencyEdges: Map<String, List<String>>? = null,
        // Detected build-tool versions (plan 046, plan 072); independent of [environment], each null
        // when the plugin was absent or its version was unresolvable.
        agp: String? = null,
        kgp: String? = null,
        ksp: String? = null,
        springBoot: String? = null,
        // Declared build-structure inventory (plan 069, research F19); null when the projectsLoaded
        // walk never ran (master switch off) or a guarded failure degraded it.
        buildStructure: CollectedBuildStructure? = null,
        // Isolated-projects activation (plan 069); null only when [environment] itself is absent.
        isolatedProjects: Boolean? = null,
        // Wrapper & startup-phase telemetry (plan 066, research F16); null when uncaptured (master
        // switch off, or every probe degraded).
        wrapper: CollectedWrapper? = null,
    ): BuildPayload {
        // Mirror the benchmark keys into tags (spec's tag contract), but user tags win on clash.
        val mergedTags = if (benchmark == null) {
            tags
        } else {
            buildMap {
                put("scenario", benchmark.scenario)
                benchmark.iteration?.let { put("iteration", it.toString()) }
                benchmark.isolationMode?.let { put("isolationMode", it) }
            } + tags
        }
        val startedAt = tasks.minOfOrNull { it.startMs } ?: nowMs
        val finishedAt = (tasks.maxOfOrNull { it.startMs + it.durationMs } ?: nowMs).coerceAtLeast(startedAt)
        val payload = BuildPayload(
            buildId = buildId,
            projectKey = projectKey,
            startedAt = startedAt,
            finishedAt = finishedAt,
            outcome = if (buildFailed) BuildOutcome.FAILED else BuildOutcome.SUCCESS,
            // Failure detail (plan 044): the raw message/stacktrace ride here; the scrubber (below)
            // relativizes/redacts paths + secrets and truncates before this ships. Present only on a
            // failed build; `messageHash` is over the raw message (computed at extraction).
            failure = failure?.let {
                FailureInfo(
                    exceptionClass = it.exceptionClass,
                    messageHash = it.messageHash,
                    message = it.message,
                    stackTrace = it.stackTrace,
                )
            },
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
                    // Invocation-switch & performance-flag posture (plan 051); null when uncaptured.
                    invocation = invocationInfo(invocation),
                    // Isolated-projects activation (plan 069); rides alongside configurationCache.
                    isolatedProjects = isolatedProjects,
                    // Plaintext workers.max (plan 065): the benchmark-slicing dimension; the salted
                    // gradle.maxWorkers fingerprint (plan 022) stays a separate channel.
                    workersMax = it.workersMax,
                )
            },
            // AGP/KGP/KSP (plan 046) + Spring Boot (plan 072) join Gradle/JDK here; emitted whenever any
            // dimension is known, so a build with detected tool versions but no environment snapshot
            // still reports them.
            toolchain = toolchainInfo(environment, agp, kgp, ksp, springBoot),
            vcs = vcsInfo(vcs, ci),
            ci = ciInfo(ci),
            // Source/commit/PR links from the redacted remote + CI PR number (plan 027); github/gitlab only.
            links = SourceLinks.compose(vcs?.remoteUrl, vcs?.sha ?: ci?.commitSha, ci?.pullRequestId),
            tags = mergedTags,
            // gradle-profiler benchmark context (plan 030); null on non-benchmark builds.
            benchmark = benchmark?.let {
                BenchmarkInfo(
                    scenario = it.scenario,
                    iteration = it.iteration,
                    isolationMode = it.isolationMode,
                    seedRef = it.seedRef,
                )
            },
            tasks = tasks,
            // Derived metrics are computed over the FULL task list, before any cap drops
            // rows — hit rate/utilization must not shift when the payload is truncated. avoidedMs +
            // dependencyEdges are supplied by the opt-in internal-adapters module (plan 038), else null.
            derived = DerivedMetricsCalculator.compute(tasks, environment?.cores, configurationMs, avoidedMs, dependencyEdges),
            // Salted input fingerprints (plan 022); null when uncaptured or unsaltable.
            fingerprints = fingerprints?.takeIf { it.build.isNotEmpty() || it.tasks.isNotEmpty() },
            // Bundled Kotlin build report (plan 023); null when unwired/unobservable.
            kotlin = kotlin,
            // Per-test-task results parsed from JUnit XML (plan 024); empty when no test ran.
            tests = tests,
            // Honest degraded state for JUnit-XML-disabled Test tasks (plan 053); null when uncaptured.
            testTelemetry = testTelemetry,
            // Artifact sizes: Android (plan 031) + JVM archive (plan 072); null when neither an Android
            // nor a JVM-archive build produced anything. Each list capped largest-first so a pathological
            // flavor matrix (or module count) can't blow the payload budget.
            artifacts = artifactSizes(artifacts, jvmArtifacts),
            // Per-project configuration-time attribution (plan 052); ranked slowest-first so the
            // top-N cardinality cap (PayloadCapper) keeps the projects that carry the signal. Null when
            // nothing was captured (a CC hit, or a build too fast/degenerate to leave any timing).
            projectEvaluations = projectEvaluations.takeIf { it.isNotEmpty() }?.sortedByDescending { it.evaluationMs },
            // Declared build-structure inventory (plan 069, research F19); null when uncaptured.
            buildStructure = buildStructureInfo(buildStructure),
            // Wrapper & startup-phase telemetry (plan 066, research F16); null when uncaptured.
            // guhWarmth is computed entirely from fields already inside CollectedWrapper (the dist
            // mtime vs. this daemon's own JVM start time) — no build-timing input needed here.
            wrapper = wrapperInfo(wrapper),
            // End-of-build JVM process snapshot (plan 029); empty when disabled/unobservable. Numeric
            // + typed-allowlist enum/bool only — nothing for the scrubber to touch (no path or
            // command line; the pid — carried since plan 065 — is an ephemeral host-local integer).
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
                    // Wire pid is Int (plan 065); a pid outside Int range (never observed on any
                    // supported OS) degrades to an honest null rather than a wrapped value.
                    pid = it.pid?.takeIf { p -> p in 1..Int.MAX_VALUE.toLong() }?.toInt(),
                    gcCollector = it.gcCollector,
                    compactObjectHeaders = it.compactObjectHeaders,
                )
            },
            // Addon-contributed sections (plan 039), keyed by addon id. Opaque JSON — the scrubber
            // leaves it untouched (core can't know an addon's shape; addons carry the §3.7 bar
            // themselves), the capper bounds it to its byte budget.
            extensions = extensions,
        )
        // Spec §3.7 then §3.9: scrub whole free-text values first (so secret patterns see
        // the complete string, never a truncated slice), then enforce the payload budgets.
        // One capped, scrubbed payload everywhere — local file, artifact, upload.
        val scrubbed = PayloadScrubber.scrub(payload, projectRoots)
        val capped = PayloadCapper.cap(scrubbed, caps)
        // Build-structure candidates are already capped upstream, inside BuildStructureValueSource
        // itself (plan 069 review) — PayloadCapper never sees the overflow, so its drop count is
        // folded into the CapsSummary here rather than inside PayloadCapper.cap().
        val droppedCandidates = buildStructure?.droppedEmptyIntermediateCandidates ?: 0
        return if (droppedCandidates > 0) {
            capped.copy(caps = (capped.caps ?: CapsSummary()).copy(droppedEmptyIntermediateCandidates = droppedCandidates))
        } else {
            capped
        }
    }

    /** Cardinality guardrail (plan 031): a pathological flavor matrix keeps only the largest N. */
    private const val MAX_ARTIFACTS = 200

    private fun capArtifacts(list: List<ArtifactSize>): List<ArtifactSize> =
        if (list.size <= MAX_ARTIFACTS) list else list.sortedByDescending { it.sizeBytes }.take(MAX_ARTIFACTS)

    /** JVM analogue of [capArtifacts] (plan 072): keep the largest N archives. */
    private fun capJvmArtifacts(list: List<JvmArtifactSize>): List<JvmArtifactSize> =
        if (list.size <= MAX_ARTIFACTS) list else list.sortedByDescending { it.sizeBytes }.take(MAX_ARTIFACTS)

    /**
     * Emits the [ArtifactSizes] block whenever *either* the Android (plan 031) or the JVM (plan 072)
     * list is non-empty; null when both are empty ("neither an Android nor a JVM-archive build").
     */
    private fun artifactSizes(android: List<ArtifactSize>, jvm: List<JvmArtifactSize>): ArtifactSizes? {
        if (android.isEmpty() && jvm.isEmpty()) return null
        return ArtifactSizes(android = capArtifacts(android), jvm = capJvmArtifacts(jvm))
    }

    /**
     * Toolchain snapshot (spec §3.2, plan 046, plan 072): Gradle/JDK come from the environment probe,
     * AGP/KGP/KSP/Spring-Boot from plugin detection. Null only when every dimension is unknown, so the
     * block is absent on a build with neither an environment snapshot nor a detected tool version.
     */
    private fun toolchainInfo(
        environment: CollectedEnvironment?,
        agp: String?,
        kgp: String?,
        ksp: String?,
        springBoot: String?,
    ): ToolchainInfo? {
        val gradle = environment?.gradleVersion
        val jdk = environment?.jdkVersion
        if (gradle == null && jdk == null && agp == null && kgp == null && ksp == null && springBoot == null) return null
        return ToolchainInfo(gradle = gradle, jdk = jdk, agp = agp, kgp = kgp, ksp = ksp, springBoot = springBoot)
    }

    /**
     * Maps the plugin-side [CollectedInvocation] DTO onto the wire [InvocationInfo] (plan 051).
     * Null when uncaptured (telemetry disabled, or the environment snapshot itself is absent).
     */
    private fun invocationInfo(invocation: CollectedInvocation?): InvocationInfo? {
        if (invocation == null) return null
        return InvocationInfo(
            buildCacheEnabled = invocation.buildCacheEnabled,
            offline = invocation.offline,
            rerunTasks = invocation.rerunTasks,
            refreshDependencies = invocation.refreshDependencies,
            configureOnDemand = invocation.configureOnDemand,
            maxWorkerCount = invocation.maxWorkerCount,
            parallel = invocation.parallel,
            fileEncoding = invocation.fileEncoding,
            locale = invocation.locale,
            properties = invocation.properties.map { GradlePropertyPosture(it.key, it.value, it.origin) },
        )
    }

    /**
     * Maps the plugin-side [CollectedBuildStructure] DTO onto the wire [BuildStructureInfo] (plan
     * 069). Null when [structure] itself is null (the walk/probes never ran) or when every field is
     * null/empty (a guarded failure degraded every dimension) — an all-unknown capture reports the
     * same as "uncaptured", never a half-populated block.
     */
    private fun buildStructureInfo(structure: CollectedBuildStructure?): BuildStructureInfo? {
        if (structure == null) return null
        if (structure.projectCount == null &&
            structure.maxDepth == null &&
            structure.includedBuildCount == null &&
            structure.buildSrcPresent == null &&
            structure.sourcesInRoot == null &&
            structure.emptyIntermediateCandidates.isEmpty()
        ) {
            return null
        }
        return BuildStructureInfo(
            projectCount = structure.projectCount,
            maxDepth = structure.maxDepth,
            includedBuildCount = structure.includedBuildCount,
            buildSrcPresent = structure.buildSrcPresent,
            sourcesInRoot = structure.sourcesInRoot,
            emptyIntermediateCandidates = structure.emptyIntermediateCandidates,
        )
    }

    /**
     * Maps the plugin-side [CollectedWrapper] DTO onto the wire [WrapperInfo] (plan 066, research
     * F16), folding in [GuhWarmth.classify] (the dist mtime vs. this daemon's own JVM start time —
     * both already inside [wrapper], so no build-timing input is needed here). Null when [wrapper]
     * itself is null (disabled) or every dimension is unknown (variant/pinned/jar hash all null AND
     * the dist probe never resolved presence either way) — an all-unknown capture reports the same
     * as "uncaptured", never a half-populated block. Otherwise `guhWarmth` is always a concrete
     * `COLD`/`WARM`/`UNKNOWN` — never left null — since [distributionVariant]/
     * [distributionSha256Pinned] describe the *committed* wrapper config independent of whether this
     * particular invocation could confirm its own GUH dist warmth.
     */
    private fun wrapperInfo(wrapper: CollectedWrapper?): WrapperInfo? {
        if (wrapper == null) return null
        if (wrapper.variant == null &&
            wrapper.distributionSha256Pinned == null &&
            wrapper.wrapperJarSha256 == null &&
            wrapper.distPresent == null
        ) {
            return null
        }
        return WrapperInfo(
            distributionVariant = wrapper.variant,
            distributionSha256Pinned = wrapper.distributionSha256Pinned,
            wrapperJarSha256 = wrapper.wrapperJarSha256,
            guhWarmth = GuhWarmth.classify(
                distMtimeMs = wrapper.distMtimeMs,
                distPresent = wrapper.distPresent,
                jvmStartMs = wrapper.jvmStartMs,
            ),
        )
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
