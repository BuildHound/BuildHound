package io.example.btp.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class BuildTelemetrySettingsPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun setUpProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("io.example.buildtelemetry")
            }

            rootProject.name = "btp-fixture"

            buildTelemetry {
                tags.put("team", "mobile")
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            tasks.register("hello") {
                doLast { println("hello from fixture") }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)

    @Test
    fun `plugin applies cleanly and captures task events with configuration cache`() {
        setUpProject()

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(result.output.contains("[btp] captured"), "expected telemetry summary in:\n${result.output}")
    }

    @Test
    fun `plugin keeps collecting on configuration cache reuse`() {
        setUpProject()

        runner("hello", "--configuration-cache").build()
        val secondRun = runner("hello", "--configuration-cache").build()

        assertTrue(
            secondRun.output.contains("[btp] captured"),
            "telemetry must survive configuration-cache reuse:\n${secondRun.output}",
        )
    }
}
