package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ProcessRole
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** End-of-build JVM process probe (plan 029): CC-safe, never-fail, degrades to `processes: []`. */
class ProcessProbeFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--configuration-cache")

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun setUpProject(extraDsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "probe-fixture"
            buildhound { $extraDsl }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
    }

    @Test
    fun `the probe writes a well-formed processes list, with a heap-bearing daemon when JDK tools are present`() {
        setUpProject()
        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)

        val payload = readPayload()
        val processes = payload.processes
        // JDK-tool availability varies across agents: the field is always a valid list and every row
        // is well-formed (role + jps pid present) — never a specific count.
        processes.forEach { assertNotNull(it.role, "each process row carries a role") }
        processes.forEach { assertNotNull(it.pid, "each observed row carries its jps pid (plan 065): $it") }
        // workersMax is a config-time StartParameter scalar (plan 065) — populated on every real
        // build regardless of JDK-tool availability.
        assertNotNull(payload.environment?.workersMax, "workersMax must be captured")
        // The build runs in a daemon, so on an agent with jps its GRADLE_DAEMON row is present with the
        // exit-criterion "configured vs used" pair. When JDK tools are absent the list is empty — then
        // this is a no-op, never a flaky skip.
        processes.firstOrNull { it.role == ProcessRole.GRADLE_DAEMON }?.let { daemon ->
            assertNotNull(daemon.configuredXmxMb, "configured -Xmx must be captured: $daemon")
            assertNotNull(daemon.heapUsedMb, "heap used must be captured: $daemon")
            // Same jinfo line the -Xmx parse read (plan 065): every supported JDK prints its
            // ergonomically-selected collector flag, so a parsed jinfo implies a collector. Field
            // *presence*, never a specific value — the ergonomic pick varies by agent size.
            assertNotNull(daemon.gcCollector, "gcCollector must be extracted when jinfo was parsed: $daemon")
        }
    }

    @Test
    fun `the probe still runs on a configuration-cache hit`() {
        setUpProject()
        runner("hello").build()
        val reuse = runner("hello").build()

        // A CC violation in the ValueSource would surface as a problem, not a clean reuse.
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertEquals(TaskOutcome.SUCCESS, reuse.task(":hello")?.outcome)
        // The probe is a ValueSource re-obtained on reuse; the fields are still present — including
        // the plan-065 workersMax scalar, which replays from the CC entry on a hit.
        val payload = readPayload()
        assertNotNull(payload.processes)
        assertNotNull(payload.environment?.workersMax, "workersMax must survive a CC hit")
    }

    @Test
    fun `processProbe disabled yields an empty process list`() {
        setUpProject("processProbe { enabled = false }")
        runner("hello").build()
        assertTrue(readPayload().processes.isEmpty())
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `a hung jps degrades to an empty list without failing the build`() {
        setUpProject()
        val fakeJps = File(projectDir, "fake-jps").apply {
            writeText("#!/bin/sh\nexec sleep 300\n")
            setExecutable(true)
        }
        val result = runner(
            "hello",
            "-Pbuildhound.internal.processprobe.jps=${fakeJps.absolutePath}",
            "-Pbuildhound.processprobe.timeout.ms=200",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "a hung probe must never fail the build")
        assertTrue(readPayload().processes.isEmpty(), "a jps timeout degrades to an empty list")
    }
}
