package dev.buildhound.gradle

import java.io.File
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class BuildHoundSettingsPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun setUpProject(extraDsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("dev.buildhound")
            }

            rootProject.name = "buildhound-fixture"

            buildhound {
                tags.put("team", "mobile")
                $extraDsl
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

    private fun environmentLine(output: String): String =
        output.lineSequence().single { it.startsWith("[buildhound] environment:") }

    @Test
    fun `plugin applies cleanly and captures task events with configuration cache`() {
        setUpProject()

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(result.output.contains("[buildhound] captured"), "expected telemetry summary in:\n${result.output}")
    }

    @Test
    fun `plugin keeps collecting on configuration cache reuse`() {
        setUpProject()

        runner("hello", "--configuration-cache").build()
        val secondRun = runner("hello", "--configuration-cache").build()

        assertTrue(
            secondRun.output.contains("[buildhound] captured"),
            "telemetry must survive configuration-cache reuse:\n${secondRun.output}",
        )
    }

    @Test
    fun `environment is collected and cc state tracks store then hit`() {
        setUpProject()

        val firstRun = runner("hello", "--configuration-cache").build()
        val secondRun = runner("hello", "--configuration-cache").build()

        val firstLine = environmentLine(firstRun.output)
        assertTrue(firstLine.contains("cc=MISS_STORED"), firstLine)
        assertTrue(firstLine.contains("gradle="), firstLine)
        assertTrue(firstLine.contains("cores="), firstLine)

        // ValueSources re-execute on reuse, so the summary must still be present and fresh.
        val secondLine = environmentLine(secondRun.output)
        assertTrue(secondLine.contains("cc=HIT"), secondLine)
    }

    @Test
    fun `cc state is disabled without configuration cache`() {
        setUpProject()

        val result = runner("hello", "--no-configuration-cache").build()

        assertTrue(environmentLine(result.output).contains("cc=DISABLED"))
    }

    @Test
    fun `pseudonymized environment leaks neither username nor hostname`() {
        setUpProject()

        val result = runner("hello", "--configuration-cache").build()

        val line = environmentLine(result.output)
        val username = System.getProperty("user.name")
        val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        assertFalse(line.contains(username), "plaintext username in: $line")
        if (!hostname.isNullOrEmpty()) {
            assertFalse(line.contains(hostname), "plaintext hostname in: $line")
        }
        assertTrue(
            File(projectDir, ".gradle/buildhound/identity.salt").isFile,
            "expected the interim identity salt to be created",
        )
    }

    @Test
    fun `identity pseudonymize can be disabled via dsl`() {
        setUpProject(extraDsl = "identity { pseudonymize = false }")

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(
            File(projectDir, ".gradle/buildhound/identity.salt").exists(),
            "plaintext mode must not create a salt",
        )
    }

    @Test
    fun `enabled false disables collection and salt creation`() {
        setUpProject(extraDsl = "enabled = false")

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(result.output.contains("[buildhound] captured"), result.output)
        assertFalse(result.output.contains("[buildhound] environment:"), result.output)
        assertFalse(
            File(projectDir, ".gradle/buildhound").exists(),
            "enabled=false must not touch the identity salt",
        )
    }
}
