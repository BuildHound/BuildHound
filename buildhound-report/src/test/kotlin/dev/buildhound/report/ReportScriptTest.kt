package dev.buildhound.report

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs the report template's render() IIFE in a real JS engine against a DOM stub (plan 045),
 * closing the gap that ReportAssetsTest only checks string-splice invariants and never executes
 * render(). The report's Failure/Warnings/Tests blocks are a hand-copy of the dashboard's (covered by
 * DashboardScriptTest); this pins the copy so the two surfaces can't silently drift. Skips when
 * node is not on the PATH; CI runners have it, so a render regression can't reach main unchecked.
 */
class ReportScriptTest {

    // A failed build with plan-044 failure detail + the opt-in internal-adapters warning block, an
    // empty `tests` block with a plan-053 `testTelemetry` note — one entry is a hostile task path
    // shaped like a script breakout, proving the JSON-escape (ReportAssets.render) + textContent
    // (report-template.html) chain holds end-to-end — plus a plan-065 pinned-Xmx Kotlin daemon
    // (1900/2048 ≈ 93 %) whose tuning card must render — all render paths exercised in one fixture
    // (minimal otherwise so no other section throws).
    private val failurePayload = """
        {
          "schemaVersion": 1,
          "buildId": "report-failure-build",
          "startedAt": 1751450000000,
          "finishedAt": 1751450005000,
          "outcome": "FAILED",
          "mode": "ci",
          "tasks": [],
          "failure": {
            "exceptionClass": "org.gradle.api.GradleException",
            "message": "Execution failed for task ':app:compileKotlin'",
            "stackTrace": "org.gradle.api.GradleException: boom\n\tat org.example.Widget.build(Widget.java:42)"
          },
          "extensions": {
            "internalAdapters": {
              "schemaVersion": 1,
              "gradleVersion": "9.6.1",
              "deprecations": ["The Foo API has been deprecated. This will fail with an error in Gradle 10."],
              "logWarnings": ["warning: [deprecation] bar() in Baz has been deprecated"],
              "droppedWarnings": 3
            }
          },
          "tests": [],
          "testTelemetry": { "xmlDisabledTasks": [":app:test", ":app</script><script>evil()//:test"] },
          "processes": [
            { "role": "GRADLE_DAEMON", "heapUsedMb": 1462, "configuredXmxMb": 4096, "gcTimeMs": 3120, "uptimeS": 812, "pid": 41214, "gcCollector": "G1" },
            { "role": "KOTLIN_DAEMON", "heapUsedMb": 1900, "configuredXmxMb": 2048, "pid": 41377 }
          ]
        }
    """.trimIndent()

    @Test
    fun `report render populates the failure and warnings sections`() {
        assumeTrue(nodeAvailable(), "node not on PATH — report smoke harness skipped")

        val dir = Files.createTempDirectory("buildhound-report-smoke")
        val html = dir.resolve("report.html").also { it.writeText(ReportAssets.render(failurePayload)) }
        val harness = dir.resolve("report-smoke.js").also { it.writeBytes(resource("report-smoke.js")) }

        val process = ProcessBuilder("node", harness.toString(), html.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "node did not finish in time")

        assertEquals(0, process.exitValue(), "report smoke harness failed:\n$output")
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
