package dev.buildhound.server

import dev.buildhound.commons.payload.ArtifactSizes
import dev.buildhound.commons.payload.BenchmarkInfo
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.CiInfo
import dev.buildhound.commons.payload.DerivedMetrics
import dev.buildhound.commons.payload.EnvironmentInfo
import dev.buildhound.commons.payload.FingerprintInfo
import dev.buildhound.commons.payload.InvocationInfo
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
        /** Salted input fingerprints (plan 022/068 fixtures); null means "uncaptured", not "empty". */
        fingerprints: FingerprintInfo? = null,
        /** Opaque addon sections (plan 039/068 fixtures), e.g. `internalAdapters` (plan 038). */
        extensions: Map<String, JsonElement> = emptyMap(),
    ): BuildPayload = BuildPayload(
        buildId = buildId,
        startedAt = startedAt,
        finishedAt = startedAt + durationMs,
        outcome = outcome,
        mode = mode,
        requestedTasks = requestedTasks,
        vcs = if (branch != null || sha != null) VcsInfo(branch = branch, sha = sha) else null,
        ci = provider?.let { CiInfo(provider = it, runId = runId, pipelineName = pipelineName, buildUrl = buildUrl) },
        derived = if (hitRate != null || avoidedMs != null) DerivedMetrics(cacheableHitRate = hitRate, avoidedMs = avoidedMs) else null,
        environment = if (userId != null || invocation != null || hostnameHash != null) {
            EnvironmentInfo(userId = userId, invocation = invocation, hostnameHash = hostnameHash)
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
