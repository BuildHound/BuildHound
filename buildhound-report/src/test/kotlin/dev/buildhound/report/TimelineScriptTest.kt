package dev.buildhound.report

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs timeline.js in a real JS engine against a DOM stub (plan 017), driving the greedy
 * lane algorithm and SVG output — string assertions alone can't catch a SyntaxError or a
 * lane-math regression (the plan-006 lesson). Skips when node is absent; CI runners have it.
 */
class TimelineScriptTest {

    @Test
    fun `timeline renderer passes the node smoke harness`() {
        assumeTrue(nodeAvailable(), "node not on PATH — timeline smoke harness skipped")

        val dir = Files.createTempDirectory("buildhound-timeline-smoke")
        val script = dir.resolve("timeline.js").also { it.writeBytes(mainResource("dev/buildhound/report/timeline.js")) }
        val harness = dir.resolve("timeline-smoke.js").also { it.writeBytes(testResource("timeline-smoke.js")) }

        val process = ProcessBuilder("node", harness.toString(), script.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "node did not finish in time")

        assertEquals(0, process.exitValue(), "timeline smoke harness failed:\n$output")
    }

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().let {
            it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0
        }
    }.getOrDefault(false)

    private fun mainResource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing main resource $path" }
            .use { it.readBytes() }

    private fun testResource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test resource $path" }
            .use { it.readBytes() }
}
