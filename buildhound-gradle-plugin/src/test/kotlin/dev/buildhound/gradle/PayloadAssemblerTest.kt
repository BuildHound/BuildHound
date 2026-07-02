package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PayloadAssemblerTest {

    private val ci = CollectedCi(
        provider = "azure-devops",
        pipelineId = "17",
        pipelineName = "android-ci",
        runId = "20260702",
        jobId = "job-1",
        stageId = "Build",
        branch = "feature/x",
        commitSha = "a".repeat(40),
        pullRequestId = "7",
        targetBranch = "main",
        buildUrl = "https://dev.azure.com/acme/mobile/_build/results?buildId=20260702",
    )

    @Test
    fun `mode resolution follows the spec matrix`() {
        assertEquals(BuildMode.CI, PayloadAssembler.resolveMode(TelemetryMode.AUTO, ci))
        assertEquals(BuildMode.LOCAL, PayloadAssembler.resolveMode(TelemetryMode.AUTO, null))
        assertEquals(BuildMode.CI, PayloadAssembler.resolveMode(TelemetryMode.CI, null))
        assertEquals(BuildMode.LOCAL, PayloadAssembler.resolveMode(TelemetryMode.LOCAL, ci))
        assertNull(PayloadAssembler.resolveMode(TelemetryMode.DISABLED, ci))
    }

    @Test
    fun `ci context fills vcs gaps but never dirty`() {
        assertNull(PayloadAssembler.vcsInfo(null, null))

        val fromCi = PayloadAssembler.vcsInfo(CollectedVcs(), ci)
        assertEquals("feature/x", fromCi?.branch)
        assertEquals("a".repeat(40), fromCi?.sha)
        assertNull(fromCi?.dirty)

        val git = PayloadAssembler.vcsInfo(CollectedVcs(branch = "main", sha = "b".repeat(40), dirty = true), ci)
        assertEquals("main", git?.branch)
        assertEquals("b".repeat(40), git?.sha)
        assertEquals(true, git?.dirty)
    }

    @Test
    fun `ci info carries declared fields and puts pr correlation into attributes`() {
        val info = PayloadAssembler.ciInfo(ci)!!

        assertEquals("azure-devops", info.provider)
        assertEquals("20260702", info.runId)
        assertEquals("android-ci", info.pipelineName)
        assertEquals("job-1", info.jobId)
        assertEquals(
            mapOf("pipelineId" to "17", "stageId" to "Build", "pullRequestId" to "7", "targetBranch" to "main"),
            info.attributes,
        )
    }

    @Test
    fun `agent name never reaches the payload`() {
        // CollectedCi has no agentName field by design (plan 005); pin the payload shape too.
        val json = dev.buildhound.commons.payload.BuildHoundJson.payload.encodeToString(
            dev.buildhound.commons.payload.CiInfo.serializer(),
            PayloadAssembler.ciInfo(ci)!!,
        )
        assertFalse(json.contains("agent", ignoreCase = true), json)
    }

    @Test
    fun `assembles timestamps from tasks with sane fallbacks`() {
        val tasks = listOf(
            task(":a", startMs = 1_000, durationMs = 500, outcome = TaskOutcome.EXECUTED),
            task(":b", startMs = 1_200, durationMs = 2_000, outcome = TaskOutcome.FROM_CACHE),
        )

        val payload = assemble(tasks = tasks, nowMs = 99_999)
        assertEquals(1_000, payload.startedAt)
        assertEquals(3_200, payload.finishedAt)

        val empty = assemble(tasks = emptyList(), nowMs = 99_999)
        assertEquals(99_999, empty.startedAt)
        assertEquals(99_999, empty.finishedAt)
    }

    @Test
    fun `assembles environment toolchain outcome and derived metrics`() {
        val payload = assemble(
            tasks = listOf(
                task(":a", startMs = 0, durationMs = 1_000, outcome = TaskOutcome.EXECUTED),
                task(":b", startMs = 0, durationMs = 1_000, outcome = TaskOutcome.FROM_CACHE),
            ),
            buildFailed = true,
        )

        assertEquals(BuildOutcome.FAILED, payload.outcome)
        assertEquals("Linux", payload.environment?.os)
        assertEquals(true, payload.environment?.daemonReused)
        assertEquals(ConfigurationCacheState.HIT, payload.environment?.configurationCache)
        assertEquals("8.14.3", payload.toolchain?.gradle)
        assertEquals("21.0.10", payload.toolchain?.jdk)
        assertEquals(0.5, payload.derived?.cacheableHitRate)
        assertEquals(mapOf("team" to "mobile"), payload.tags)
        assertEquals("fixture", payload.projectKey)
        assertEquals(listOf("build"), payload.requestedTasks)
    }

    private fun assemble(
        tasks: List<TaskExecution>,
        buildFailed: Boolean = false,
        nowMs: Long = 0,
    ) = PayloadAssembler.assemble(
        buildId = "test-build",
        projectKey = "fixture",
        mode = BuildMode.CI,
        buildFailed = buildFailed,
        requestedTasks = listOf("build"),
        tasks = tasks,
        environment = CollectedEnvironment(
            os = "Linux", arch = "amd64", cores = 8, ramMb = 16_000,
            hostnameHash = "h_0123456789ab", userId = "u_0123456789ab",
            gradleVersion = "8.14.3", jdkVersion = "21.0.10",
        ),
        vcs = CollectedVcs(branch = "main", sha = "c".repeat(40), dirty = false),
        ci = ci,
        configurationCache = ConfigurationCacheState.HIT,
        daemonReused = true,
        tags = mapOf("team" to "mobile"),
        nowMs = nowMs,
    )

    private fun task(path: String, startMs: Long, durationMs: Long, outcome: TaskOutcome) =
        TaskExecution(path = path, startMs = startMs, durationMs = durationMs, outcome = outcome)
}
