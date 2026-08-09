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
    // (1900/2048 ≈ 93 %) and a second, high-GC-fraction G1 Kotlin daemon on JDK 24 (review fix:
    // previously only the pinned-Xmx card was CI-verified on this surface — GC-pressure,
    // ParallelGC-trial, and compact-object-headers were not) whose tuning cards must render, and a
    // plan-072 springBoot toolchain chip + JVM-artifacts table (bootJar/jar sizes) — all render
    // paths exercised in one fixture (minimal otherwise so no other section throws).
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
          "toolchain": { "gradle": "9.0.0", "jdk": "24.0.1", "springBoot": "3.3.2" },
          "artifacts": {
            "jvm": [
              { "module": ":app", "kind": "BOOT_JAR", "sizeBytes": 24117248 },
              { "module": ":core", "kind": "JAR", "sizeBytes": 131072 }
            ]
          },
          "processes": [
            { "role": "GRADLE_DAEMON", "heapUsedMb": 1462, "configuredXmxMb": 4096, "gcTimeMs": 3120, "uptimeS": 812, "pid": 41214, "gcCollector": "G1", "cpuTimeMs": 214000 },
            { "role": "KOTLIN_DAEMON", "heapUsedMb": 1900, "configuredXmxMb": 2048, "pid": 41377 },
            { "role": "KOTLIN_DAEMON", "heapUsedMb": 800, "configuredXmxMb": 4096, "gcTimeMs": 4000, "uptimeS": 20, "rssMb": 3072, "pid": 55832, "gcCollector": "G1", "compactObjectHeaders": false }
          ],
          "environment": {
            "os": "Linux",
            "arch": "amd64",
            "cores": 8,
            "ramMb": 32768,
            "workersMax": 6,
            "machine": { "diskTotalMb": 486400, "diskFreeMb": 204800, "diskMedia": "NVME" }
          },
          "resourceUsage": {
            "windowMs": 59000,
            "daemonCpuMs": 141600,
            "systemCpuLoadPct": 63,
            "systemLoadAverage": 4.75,
            "systemMemTotalMb": 32768,
            "systemMemFreeMb": 9216
          },
          "ci": {
            "provider": "github-actions",
            "attributes": {
              "runnerEnvironment": "github-hosted",
              "runnerOs": "Linux",
              "runnerArch": "X64",
              "runnerImageOs": "ubuntu24",
              "runnerImageVersion": "20250801.1.0",
              "runnerVersion": "17.3.0",
              "runnerClass": "ubuntu</script><script>evil()//-8-core"
            }
          }
        }
    """.trimIndent()

    /**
     * The degraded halves of the plan-104 usage block, which the rich fixture above can never
     * exercise because it supplies every input. Two distinct hazards in one payload:
     *  - `cores` is absent, so utilization is undivideable and the chip must fall back to an
     *    absolute duration under a *different* label (reusing "daemon cpu" for both units was a
     *    review finding);
     *  - `daemonCpuMs` is negative — impossible from the plugin, which guards it, but the report
     *    renders whatever payload it is handed, and an unclamped ratio printed "-0.3% of 8 cores".
     */
    private val degradedUsagePayload = """
        {
          "schemaVersion": 1,
          "buildId": "report-degraded-usage-build",
          "startedAt": 1751450000000,
          "finishedAt": 1751450005000,
          "outcome": "SUCCESS",
          "mode": "ci",
          "tasks": [],
          "environment": { "os": "Linux", "ramMb": 32768 },
          "resourceUsage": { "windowMs": 5000, "daemonCpuMs": 2400 }
        }
    """.trimIndent()

    /** An overshoot (CPU > window × cores) and a negative delta — both must clamp, never print raw. */
    private val clampedUsagePayload = """
        {
          "schemaVersion": 1,
          "buildId": "report-clamped-usage-build",
          "startedAt": 1751450000000,
          "finishedAt": 1751450005000,
          "outcome": "SUCCESS",
          "mode": "ci",
          "tasks": [],
          "environment": { "cores": 4 },
          "resourceUsage": { "windowMs": 100, "daemonCpuMs": 900 }
        }
    """.trimIndent()

    /** A negative CPU delta must be dropped entirely, not rendered as a negative percentage. */
    private val negativeUsagePayload = """
        {
          "schemaVersion": 1,
          "buildId": "report-negative-usage-build",
          "startedAt": 1751450000000,
          "finishedAt": 1751450005000,
          "outcome": "SUCCESS",
          "mode": "ci",
          "tasks": [],
          "environment": { "cores": 8 },
          "resourceUsage": { "windowMs": 5000, "daemonCpuMs": -100 }
        }
    """.trimIndent()

    /**
     * A payload carrying none of the plan-104 blocks — the case every older plugin and every
     * fully-degraded probe produces. The Machine section must stay hidden rather than render a row
     * of blanks or a fabricated 0 %.
     */
    private val bareMachinePayload = """
        {
          "schemaVersion": 1,
          "buildId": "report-bare-machine-build",
          "startedAt": 1751450000000,
          "finishedAt": 1751450005000,
          "outcome": "SUCCESS",
          "mode": "ci",
          "tasks": []
        }
    """.trimIndent()

    @Test
    fun `report render populates the failure and warnings sections`() {
        runHarness(failurePayload, mode = "full")
    }

    @Test
    fun `a payload without machine specs or resource usage keeps the Machine section hidden`() {
        runHarness(bareMachinePayload, mode = "bare")
    }

    @Test
    fun `an undivideable cpu figure falls back to a duration under a distinct label`() {
        runHarness(degradedUsagePayload, mode = "degraded-usage")
    }

    @Test
    fun `a cpu ratio over capacity clamps to 100 percent`() {
        runHarness(clampedUsagePayload, mode = "clamped-usage")
    }

    @Test
    fun `a negative cpu delta renders no utilization at all`() {
        runHarness(negativeUsagePayload, mode = "negative-usage")
    }

    private fun runHarness(payload: String, mode: String) {
        assumeTrue(nodeAvailable(), "node not on PATH — report smoke harness skipped")

        val dir = Files.createTempDirectory("buildhound-report-smoke")
        val html = dir.resolve("report.html").also { it.writeText(ReportAssets.render(payload)) }
        val harness = dir.resolve("report-smoke.js").also { it.writeBytes(resource("report-smoke.js")) }

        val process = ProcessBuilder("node", harness.toString(), html.toString(), mode)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "node did not finish in time")

        assertEquals(0, process.exitValue(), "report smoke harness ($mode) failed:\n$output")
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
