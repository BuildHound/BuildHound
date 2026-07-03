package dev.buildhound.server

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs dashboard.js in a real JS engine against a DOM/fetch stub (plan 012, closing
 * the plan-006 lesson: string assertions don't catch SyntaxErrors — a broken script
 * would ship green and brick the whole dashboard). Skips when node is not on the
 * PATH; the CI runners have it, so regressions can't reach main unchecked.
 */
class DashboardScriptTest {

    @Test
    fun `dashboard script passes the node smoke harness`() {
        assumeTrue(nodeAvailable(), "node not on PATH — smoke harness skipped")

        val dir = Files.createTempDirectory("buildhound-dashboard-smoke")
        val script = dir.resolve("dashboard.js").also { it.writeBytes(resource("web/dashboard.js")) }
        val harness = dir.resolve("dashboard-smoke.js").also { it.writeBytes(resource("web/dashboard-smoke.js")) }
        // The shared timeline renderer rides in on the buildhound-report dependency; the
        // harness loads it as a global before dashboard.js, like the browser does (plan 017).
        val timeline = dir.resolve("timeline.js").also { it.writeBytes(resource("dev/buildhound/report/timeline.js")) }

        val process = ProcessBuilder("node", harness.toString(), script.toString(), timeline.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "node did not finish in time")

        assertEquals(0, process.exitValue(), "dashboard smoke harness failed:\n$output")
    }

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().let {
            it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0
        }
    }.getOrDefault(false)

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing resource $path" }
            .use { it.readBytes() }
}
