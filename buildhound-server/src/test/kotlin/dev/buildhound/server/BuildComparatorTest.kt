package dev.buildhound.server

import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.FingerprintInfo
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.ToolchainInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuildComparatorTest {

    private fun build(
        id: String,
        tasks: List<TaskExecution> = emptyList(),
        fingerprints: FingerprintInfo? = null,
        requestedTasks: List<String> = listOf("build"),
        toolchain: ToolchainInfo? = null,
    ) = BuildPayload(
        buildId = id, startedAt = 0, finishedAt = 1, outcome = BuildOutcome.SUCCESS,
        requestedTasks = requestedTasks, tasks = tasks, fingerprints = fingerprints, toolchain = toolchain,
    )

    private fun task(path: String, outcome: TaskOutcome) =
        TaskExecution(path = path, startMs = 0, durationMs = 1, outcome = outcome)

    @Test
    fun `build-level differing key ranks with full coverage and a catalog note`() {
        val a = build("a", fingerprints = FingerprintInfo(build = mapOf("jdk.home" to "aaa…", "os.name" to "same…")))
        val b = build("b", fingerprints = FingerprintInfo(build = mapOf("jdk.home" to "bbb…", "os.name" to "same…")))

        val result = BuildComparator.compare(a, b)

        val jdk = result.diffs.single { it.key == "jdk.home" }
        assertEquals("BUILD", jdk.scope)
        assertEquals(1.0, jdk.coverage)
        assertEquals("aaa…", jdk.valueA)
        assertEquals("bbb…", jdk.valueB)
        assertNotNull(jdk.note, "known-volatile key carries a catalog note")
        assertTrue(result.diffs.none { it.key == "os.name" }, "identical keys are not diffs")
    }

    @Test
    fun `task key covering half the misses ranks 0_5`() {
        val a = build(
            "a",
            tasks = (1..4).map { task(":t$it", TaskOutcome.UP_TO_DATE) },
            fingerprints = FingerprintInfo(tasks = mapOf(":t1" to mapOf("sysProps-k" to "x…"), ":t2" to mapOf("sysProps-k" to "x…"))),
        )
        val b = build(
            "b",
            tasks = (1..4).map { task(":t$it", TaskOutcome.EXECUTED) },
            fingerprints = FingerprintInfo(tasks = mapOf(":t1" to mapOf("sysProps-k" to "y…"), ":t2" to mapOf("sysProps-k" to "y…"))),
        )

        val result = BuildComparator.compare(a, b)

        assertEquals(listOf(":t1", ":t2", ":t3", ":t4"), result.missesToExplain)
        val diff = result.diffs.single { it.key == "sysProps-k" }
        assertEquals("TASK", diff.scope)
        assertEquals(2, diff.differingTaskCount)
        assertEquals(0.5, diff.coverage)
    }

    @Test
    fun `zero misses still returns build diffs unranked`() {
        val a = build("a", tasks = listOf(task(":t", TaskOutcome.FROM_CACHE)), fingerprints = FingerprintInfo(build = mapOf("jdk.home" to "a…")))
        val b = build("b", tasks = listOf(task(":t", TaskOutcome.FROM_CACHE)), fingerprints = FingerprintInfo(build = mapOf("jdk.home" to "b…")))

        val result = BuildComparator.compare(a, b)

        assertTrue(result.missesToExplain.isEmpty())
        assertEquals(1, result.diffs.count { it.key == "jdk.home" }, "build diffs are returned even with no misses")
    }

    @Test
    fun `a key present on only one side counts as differing`() {
        val a = build("a", fingerprints = FingerprintInfo(build = mapOf("env-CI" to "v…")))
        val b = build("b", fingerprints = FingerprintInfo(build = emptyMap()))

        val diff = BuildComparator.compare(a, b).diffs.single { it.key == "env-CI" }
        assertEquals("v…", diff.valueA)
        assertEquals(null, diff.valueB)
    }

    @Test
    fun `missing fingerprint maps degrade to field diffs without error`() {
        val a = build("a", toolchain = ToolchainInfo(jdk = "21.0.1"))
        val b = build("b", toolchain = ToolchainInfo(jdk = "21.0.2"))

        val result = BuildComparator.compare(a, b)

        assertTrue(result.diffs.none { it.scope == "BUILD" }, "no fingerprint data → no fingerprint diffs")
        assertEquals("FIELD", result.diffs.single { it.key == "toolchain.jdk" }.scope)
    }

    @Test
    fun `requested tasks mismatch is flagged`() {
        val a = build("a", requestedTasks = listOf("assemble"))
        val b = build("b", requestedTasks = listOf("test"))
        assertTrue(!BuildComparator.compare(a, b).requestedTasksMatch)
    }
}
