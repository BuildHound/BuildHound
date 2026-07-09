package dev.buildhound.server

import dev.buildhound.commons.payload.ArtifactSizes
import dev.buildhound.commons.payload.BenchmarkInfo
import dev.buildhound.commons.payload.BuildCacheConfigInfo
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ChangedModulesInfo
import dev.buildhound.commons.payload.CiInfo
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.DerivedMetrics
import dev.buildhound.commons.payload.EnvironmentInfo
import dev.buildhound.commons.payload.FingerprintInfo
import dev.buildhound.commons.payload.InvocationInfo
import dev.buildhound.commons.payload.ProcessInfo
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.TestCaseDetail
import dev.buildhound.commons.payload.TestCaseOutcome
import dev.buildhound.commons.payload.TestClassResult
import dev.buildhound.commons.payload.TestTaskResult
import dev.buildhound.commons.payload.ToolchainInfo
import dev.buildhound.commons.payload.VcsInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Builds schema-v1 [BuildPayload]s for server tests without hand-writing JSON each time. */
object TestPayloads {
    fun build(
        buildId: String = "11111111-2222-3333-4444-555555555555",
        durationMs: Long = 1000,
        startedAt: Long = 1_751_450_000_000,
        hitRate: Double? = null,
        avoidedMs: Long? = null,
        outcome: BuildOutcome = BuildOutcome.SUCCESS,
        mode: BuildMode = BuildMode.CI,
        branch: String? = "main",
        requestedTasks: List<String> = listOf("build"),
        pipelineName: String? = "android-ci",
        provider: String? = "azure-devops",
        runId: String? = null,
        buildUrl: String? = null,
        /** CI attributes (plan 027/041 fixtures), e.g. GHA's `runAttempt` — the plan-059 rerun signal. */
        ciAttributes: Map<String, String> = emptyMap(),
        projectKey: String? = null,
        userId: String? = null,
        invocation: InvocationInfo? = null,
        benchmark: BenchmarkInfo? = null,
        artifacts: ArtifactSizes? = null,
        toolchain: ToolchainInfo? = null,
        tasks: List<TaskExecution> = emptyList(),
        sha: String? = null,
        tests: List<TestTaskResult> = emptyList(),
        tags: Map<String, String> = emptyMap(),
        /** Build-level identity hash (plan 022/068 fixtures); the single-salt-stream grouping key. */
        hostnameHash: String? = null,
        /** Plaintext workers.max (plan 065 fixtures); the benchmarkSeries slicing dimension. */
        workersMax: Int? = null,
        /** Configuration-cache state (plan 064 fixtures); drives the /trends CC counters + cc-economics reuse class. */
        configurationCache: ConfigurationCacheState? = null,
        /** Configuration (store) cost (plan 064 fixtures); the cc-economics store-cost p50 on MISS_STORED. */
        configurationMs: Long? = null,
        /** CC entry-load proxy (plan 064 fixtures); the cc-economics load p50 on HIT. */
        ccLoadMs: Long? = null,
        /** CC entry byte size (plan 064 fixtures); the cc-economics entry-size p50 on a CC-requesting build. */
        ccEntrySizeBytes: Long? = null,
        /** Salted input fingerprints (plan 022/068 fixtures); null means "uncaptured", not "empty". */
        fingerprints: FingerprintInfo? = null,
        /** Committed build-cache config snapshot (plan 067 fixtures); null means the pre-067 "uncaptured". */
        buildCache: BuildCacheConfigInfo? = null,
        /** Opaque addon sections (plan 039/068 fixtures), e.g. `internalAdapters` (plan 038). */
        extensions: Map<String, JsonElement> = emptyMap(),
        /** Change blast-radius attribution (plan 063 fixtures); null means "no resolvable diff base". */
        changedModules: ChangedModulesInfo? = null,
        /** Excluded task names (plan 054 fixtures); the `-x test` CI smell for the wasted-work rule. */
        excludedTaskNames: List<String> = emptyList(),
        /** End-of-build JVM process snapshot (plan 029/065 fixtures); the GC-pressure recommendation input. */
        processes: List<ProcessInfo> = emptyList(),
    ): BuildPayload = BuildPayload(
        buildId = buildId,
        projectKey = projectKey,
        startedAt = startedAt,
        finishedAt = startedAt + durationMs,
        outcome = outcome,
        mode = mode,
        requestedTasks = requestedTasks,
        excludedTaskNames = excludedTaskNames,
        vcs = if (branch != null || sha != null) VcsInfo(branch = branch, sha = sha) else null,
        ci = provider?.let { CiInfo(provider = it, runId = runId, pipelineName = pipelineName, buildUrl = buildUrl, attributes = ciAttributes) },
        derived = if (hitRate != null || avoidedMs != null || configurationMs != null || ccLoadMs != null || ccEntrySizeBytes != null) {
            DerivedMetrics(
                cacheableHitRate = hitRate,
                avoidedMs = avoidedMs,
                configurationMs = configurationMs,
                ccLoadMs = ccLoadMs,
                ccEntrySizeBytes = ccEntrySizeBytes,
            )
        } else {
            null
        },
        environment = if (userId != null || invocation != null || hostnameHash != null || workersMax != null || buildCache != null || configurationCache != null) {
            EnvironmentInfo(
                userId = userId,
                invocation = invocation,
                hostnameHash = hostnameHash,
                workersMax = workersMax,
                buildCache = buildCache,
                configurationCache = configurationCache,
            )
        } else {
            null
        },
        benchmark = benchmark,
        artifacts = artifacts,
        toolchain = toolchain,
        tasks = tasks,
        tests = tests,
        tags = tags,
        fingerprints = fingerprints,
        extensions = extensions,
        changedModules = changedModules,
        processes = processes,
    )

    /** A test-task result for flaky fixtures (plan 036): one class + optional fail-then-pass retries. */
    fun testTask(
        module: String? = ":app",
        className: String = "com.example.FooTest",
        passed: Int = 5,
        failed: Int = 0,
        retriedCases: List<String> = emptyList(),
    ): TestTaskResult = TestTaskResult(
        taskPath = "${module ?: ""}:test",
        module = module,
        classes = listOf(TestClassResult(className = className, passed = passed, failed = failed)),
        failedOrRetried = retriedCases.map {
            TestCaseDetail(className = className, name = it, outcomes = listOf(TestCaseOutcome.FAILED, TestCaseOutcome.PASSED))
        },
    )

    /** A task row for rollup fixtures; startMs is derived so timestamps are consistent. */
    fun task(
        path: String,
        outcome: TaskOutcome,
        durationMs: Long,
        type: String? = null,
        startMs: Long = 0,
        cacheable: Boolean? = null,
        executionReasons: List<String> = emptyList(),
        incremental: Boolean = false,
    ): TaskExecution = TaskExecution(
        path = path,
        module = path.substringBeforeLast(':').ifEmpty { ":" },
        type = type,
        startMs = startMs,
        durationMs = durationMs,
        outcome = outcome,
        cacheable = cacheable,
        executionReasons = executionReasons,
        incremental = incremental,
    )

    /**
     * A minimal `extensions["internalAdapters"]` block (plan 038/068 fixtures) carrying only what
     * [RelocatabilityDetector] reads (`tasks[].path`/`.origin`) — a shape-subset of the real
     * `InternalAdaptersPayload`, deliberately hand-built here rather than depending on
     * `buildhound-internal-adapters` (server keeps no dependency on that module, plan 039).
     */
    fun internalAdapters(taskOrigins: List<Pair<String, String>>): Map<String, JsonElement> = mapOf(
        "internalAdapters" to buildJsonObject {
            put("schemaVersion", 1)
            put("gradleVersion", "9.6.1")
            putJsonArray("tasks") {
                taskOrigins.forEach { (path, origin) ->
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("origin", origin)
                        },
                    )
                }
            }
        },
    )

    /** A `fingerprints.build` map (plan 022/068 fixtures) from raw key/hash pairs, no plugin dependency. */
    fun fingerprints(build: Map<String, String>): FingerprintInfo = FingerprintInfo(build = build)

    /**
     * A minimal `extensions["internalAdapters"]` block carrying only [dependencyEdges] (plan 062
     * fixtures) — again a shape-subset of the real `InternalAdaptersPayload`, hand-built so tests don't
     * depend on `buildhound-internal-adapters` (server keeps no dependency on that module, plan 039).
     */
    fun internalAdaptersEdges(dependencyEdges: Map<String, List<String>>): Map<String, JsonElement> = mapOf(
        "internalAdapters" to buildJsonObject {
            put("schemaVersion", 1)
            put("gradleVersion", "9.6.1")
            putJsonObject("dependencyEdges") {
                dependencyEdges.forEach { (path, deps) ->
                    putJsonArray(path) { deps.forEach { add(it) } }
                }
            }
        },
    )
}
