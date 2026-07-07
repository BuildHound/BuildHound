package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Kotlin build-report bundling (plan 023). Rather than drive a real KGP compilation (a heavy,
 * version-coupled dependency in TestKit), these tests pre-seed a fixture json report in the
 * configured directory and assert the finalizer's parser + bundler + property wiring end-to-end.
 * The window filter accepts files modified within 60 s of build start, so a just-written fixture
 * is in-window; a fixture back-dated past that margin exercises the stale-report guard.
 */
class KotlinReportFunctionalTest {

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

    private fun setUpProject(dsl: String = "", gradleProperties: String = "", buildScript: String? = null) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "kotlin-fixture"
            buildhound { $dsl }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            buildScript ?: """tasks.register("hello") { doLast { println("hello") } }""",
        )
        if (gradleProperties.isNotBlank()) {
            File(projectDir, "gradle.properties").writeText(gradleProperties)
        }
    }

    /**
     * Absolute path with forward separators, safe to interpolate into gradle.properties. Gradle
     * parses that file with java.util.Properties, which treats '\' as an escape char — a raw Windows
     * absolutePath like `D:\a\...\reports` is mangled (`\r` even becomes a CR), so the plugin looks
     * in the wrong place and finds no report. Forward slashes round-trip and File(...) accepts them
     * on every OS. Do not revert this to absolutePath.
     */
    private fun File.propertiesPath(): String = absoluteFile.invariantSeparatorsPath

    /** A minimal but shape-accurate KGP json report with a single Kotlin compilation record. */
    private fun seedReport(dir: File, name: String = "report.json", ageMs: Long = 0): File {
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText(
            """
            {
              "buildOperationRecord": [
                {
                  "path": ":app:compileKotlin",
                  "isFromKotlinPlugin": true,
                  "totalTimeMs": 1168,
                  "kotlinLanguageVersion": "KOTLIN_2_4",
                  "buildMetrics": {
                    "buildTimes": { "buildTimesNs": [ [ { "name": "RUN_COMPILATION" }, 190000000 ] ] },
                    "buildPerformanceMetrics": { "myBuildMetrics": [ [ { "name": "SOURCE_LINES_NUMBER" }, 42 ] ] },
                    "buildAttributes": { "myAttributes": { "UNKNOWN_CHANGES_IN_GRADLE_INPUTS": 1 } }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        if (ageMs != 0L) file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }

    @Test
    fun `kotlin metrics are bundled from the configured json report directory and survive cc reuse`() {
        val reportDir = File(projectDir, "reports")
        setUpProject(
            gradleProperties = "kotlin.build.report.output=JSON\n" +
                "kotlin.build.report.json.directory=${reportDir.propertiesPath()}\n",
        )
        seedReport(reportDir)

        val first = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":hello")?.outcome)
        val kotlin = readPayload().kotlin ?: error("expected a kotlin section")
        assertEquals("KOTLIN_2_4", kotlin.reportSchema)
        val task = kotlin.perTask.single()
        assertEquals(":app:compileKotlin", task.taskPath)
        assertEquals(1168, task.durationMs)
        assertEquals(false, task.incremental)
        assertEquals(190, task.compilerTimesMs["RUN_COMPILATION"])
        assertEquals(42, task.linesOfCode)

        // Config-cache reuse still reads the report fresh at execution time.
        val second = runner("hello").build()
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertEquals(":app:compileKotlin", readPayload().kotlin?.perTask?.single()?.taskPath)
    }

    @Test
    fun `a non-kotlin build has no kotlin section and prints no wiring hint`() {
        setUpProject()

        val result = runner("hello").build()

        assertNull(readPayload().kotlin, "no report wired and no kotlin tasks → no kotlin section")
        assertFalse(result.output.contains("kotlin.build.report"), result.output)
    }

    @Test
    fun `a kotlin build with no report wired prints one copy-paste hint and still succeeds`() {
        setUpProject(buildScript = """tasks.register("compileKotlin") { doLast { println("compiled") } }""")

        val result = runner("compileKotlin").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertNull(readPayload().kotlin)
        assertTrue(result.output.contains("kotlin.build.report.output=JSON"), result.output)
    }

    @Test
    fun `a report directory that is actually a file degrades to null with a single warn`() {
        val notADir = File(projectDir, "reports-as-file")
        notADir.parentFile.mkdirs()
        notADir.writeText("i am a file")
        setUpProject(
            gradleProperties = "kotlin.build.report.json.directory=${notADir.propertiesPath()}\n",
        )

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "must never fail the build")
        assertNull(readPayload().kotlin)
        assertEquals(
            1,
            result.output.lineSequence().count { it.contains("is not a directory") },
            "exactly one warn: ${result.output}",
        )
    }

    @Test
    fun `a stale report from a previous build is not bundled`() {
        val reportDir = File(projectDir, "reports")
        setUpProject(
            gradleProperties = "kotlin.build.report.json.directory=${reportDir.propertiesPath()}\n",
        )
        seedReport(reportDir, ageMs = 5 * 60_000) // 5 min old — outside the 60 s window

        runner("hello").build()

        assertNull(readPayload().kotlin, "a report older than the window must be treated as absent")
    }

    @Test
    fun `bundling is skipped when disabled via the dsl`() {
        val reportDir = File(projectDir, "reports")
        setUpProject(
            dsl = "kotlinReports { bundle = false }",
            gradleProperties = "kotlin.build.report.json.directory=${reportDir.propertiesPath()}\n",
        )
        seedReport(reportDir)

        val result = runner("hello").build()

        assertNull(readPayload().kotlin, "an in-window report must be ignored when bundling is off")
        assertFalse(result.output.contains("kotlin.build.report"), result.output)
    }
}
