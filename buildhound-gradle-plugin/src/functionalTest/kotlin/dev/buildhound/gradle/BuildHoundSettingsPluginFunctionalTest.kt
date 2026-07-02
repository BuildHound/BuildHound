package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun summaryLine(output: String): String =
        output.lineSequence().single { it.startsWith("[buildhound] build ") }

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    @Test
    fun `plugin applies cleanly and writes a schema v1 payload`() {
        setUpProject()

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        val payload = readPayload()
        assertEquals(BuildPayload.SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(BuildOutcome.SUCCESS, payload.outcome)
        assertEquals("buildhound-fixture", payload.projectKey)
        assertEquals(listOf("hello"), payload.requestedTasks)
        assertEquals(mapOf("team" to "mobile"), payload.tags)
        assertTrue(payload.tasks.any { it.path == ":hello" }, "expected :hello in ${payload.tasks}")
        assertTrue(payload.finishedAt >= payload.startedAt)
        assertNotNull(payload.environment, "environment block missing")
        assertNotNull(payload.toolchain?.gradle, "toolchain.gradle missing")
        assertNotNull(payload.derived, "derived metrics missing")
    }

    @Test
    fun `payload keeps flowing on configuration cache reuse`() {
        setUpProject()

        runner("hello", "--configuration-cache").build()
        val secondRun = runner("hello", "--configuration-cache").build()

        assertTrue(summaryLine(secondRun.output).contains("cc=HIT"), summaryLine(secondRun.output))
        assertTrue(readPayload().tasks.isNotEmpty(), "telemetry must survive configuration-cache reuse")
    }

    @Test
    fun `cc state tracks store then hit and disabled`() {
        setUpProject()

        val firstRun = runner("hello", "--configuration-cache").build()
        val secondRun = runner("hello", "--configuration-cache").build()
        val noCcRun = runner("hello", "--no-configuration-cache").build()

        assertTrue(summaryLine(firstRun.output).contains("cc=MISS_STORED"), summaryLine(firstRun.output))
        assertTrue(summaryLine(secondRun.output).contains("cc=HIT"), summaryLine(secondRun.output))
        assertTrue(summaryLine(noCcRun.output).contains("cc=DISABLED"), summaryLine(noCcRun.output))
    }

    @Test
    fun `pseudonymized identity never leaks plaintext`() {
        setUpProject()

        val result = runner("hello", "--configuration-cache").build()

        val username = System.getProperty("user.name")
        val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        val environment = readPayload().environment
        assertNotNull(environment)
        environment.userId?.let { userId ->
            assertTrue(userId.matches(Regex("u_[0-9a-f]{12}")), "unexpected userId shape: $userId")
        }
        environment.hostnameHash?.let { hash ->
            assertTrue(hash.matches(Regex("h_[0-9a-f]{12}")), "unexpected hostnameHash shape: $hash")
        }
        // Neither the log nor the payload may carry the plaintext identity.
        val payloadText = File(projectDir, "build/buildhound/build-payload.json").readText()
        assertFalse(payloadText.contains("\"$username\""), "plaintext username in payload")
        if (!hostname.isNullOrEmpty()) {
            assertFalse(payloadText.contains("\"$hostname\""), "plaintext hostname in payload")
            assertFalse(result.output.lineSequence().any { it.startsWith("[buildhound]") && it.contains(hostname) })
        }
        assertTrue(
            File(projectDir, ".gradle/buildhound/identity.salt").isFile,
            "expected the interim identity salt to be created",
        )
    }

    @Test
    fun `identity pseudonymize can be disabled via dsl`() {
        setUpProject(extraDsl = "identity { pseudonymize = false }")

        runner("hello", "--configuration-cache").build()

        assertEquals(System.getProperty("user.name"), readPayload().environment?.userId)
        assertFalse(
            File(projectDir, ".gradle/buildhound/identity.salt").exists(),
            "plaintext mode must not create a salt",
        )
    }

    @Test
    fun `vcs is collected from a git working copy`() {
        setUpProject()
        // The build itself creates .gradle/ and build/ — ignore them or the clean run is dirty.
        File(projectDir, ".gitignore").writeText(".gradle/\nbuild/\n")
        exec("git", "init", "--initial-branch=main")
        exec("git", "config", "user.email", "test@example.com")
        exec("git", "config", "user.name", "Test")
        exec("git", "add", ".")
        exec("git", "commit", "-m", "init")

        runner("hello", "--configuration-cache").build()
        val clean = readPayload().vcs
        assertEquals("main", clean?.branch)
        assertTrue(clean?.sha.orEmpty().matches(Regex("[0-9a-f]{40}")), "sha: ${clean?.sha}")
        assertEquals(false, clean?.dirty)

        File(projectDir, "untracked.txt").writeText("x")
        val dirtyRun = runner("hello", "--configuration-cache").build()
        assertEquals(true, readPayload().vcs?.dirty)
        // Freshness must come from re-executing the source on reuse, not from a CC miss.
        assertTrue(summaryLine(dirtyRun.output).contains("cc=HIT"))
    }

    @Test
    fun `non git projects degrade to a null vcs block`() {
        setUpProject()

        runner("hello", "--configuration-cache").build()

        val payload = readPayload()
        if (payload.ci == null) {
            assertNull(payload.vcs, "expected no vcs block without git or CI: ${payload.vcs}")
        } else {
            assertNull(payload.vcs?.dirty, "dirty must never come from CI context")
        }
    }

    @Test
    fun `generic ci detection resolves mode and fills the ci block`() {
        setUpProject()

        val cleanedEnv = System.getenv().filterKeys { it != "GITHUB_ACTIONS" && it != "TF_BUILD" }
        runner("hello", "--configuration-cache")
            // Fresh daemon: an inherited-env daemon from earlier tests would not see
            // the injected variables (daemon selection ignores env differences).
            .withTestKitDir(File(projectDir, "testkit"))
            .withEnvironment(
                cleanedEnv + mapOf(
                    "BUILDHOUND_CI" to "true",
                    "BUILDHOUND_CI_PROVIDER" to "my-inhouse-ci",
                    "BUILDHOUND_CI_RUN_ID" to "42",
                    "BUILDHOUND_CI_BRANCH" to "main",
                ),
            )
            .build()

        val payload = readPayload()
        assertEquals(BuildMode.CI, payload.mode)
        assertEquals("my-inhouse-ci", payload.ci?.provider)
        assertEquals("42", payload.ci?.runId)
        assertEquals("main", payload.vcs?.branch, "CI context must fill vcs gaps")
    }

    @Test
    fun `html artifact is written next to the payload`() {
        setUpProject()

        runner("hello", "--configuration-cache").build()

        val html = File(projectDir, "build/buildhound/buildhound-report.html")
        assertTrue(html.isFile, "expected the standalone report artifact")
        val content = html.readText()
        assertTrue(content.startsWith("<!DOCTYPE html>"), "artifact must stay a full HTML document")
        assertTrue(content.contains(readPayload().buildId), "artifact must embed the payload data")
    }

    @Test
    fun `html artifact can be disabled via dsl`() {
        setUpProject(extraDsl = "htmlReport { enabled = false }")

        runner("hello", "--configuration-cache").build()

        assertTrue(File(projectDir, "build/buildhound/build-payload.json").isFile, "payload still written")
        assertFalse(
            File(projectDir, "build/buildhound/buildhound-report.html").exists(),
            "report must be skippable",
        )
    }

    @Test
    fun `mode disabled writes no payload`() {
        setUpProject(extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.DISABLED")

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(result.output.contains("[buildhound] build "), result.output)
        assertFalse(File(projectDir, "build/buildhound").exists(), "disabled mode must not write payloads")
        assertFalse(File(projectDir, ".gradle/buildhound").exists(), "disabled mode must not create a salt")
    }

    @Test
    fun `finalization failure never fails the build and leaves a marker`() {
        setUpProject()
        // Occupy the output dir path with a file so the payload write must fail.
        File(projectDir, "build").mkdirs()
        File(projectDir, "build/buildhound").writeText("in the way")

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(
            result.output.contains("[buildhound] telemetry finalization failed (build unaffected)"),
            result.output,
        )
        assertTrue(
            File(projectDir, "build/buildhound-failure.marker").isFile,
            "expected a failure marker next to the output dir",
        )
    }

    @Test
    fun `enabled false disables collection and salt creation`() {
        setUpProject(extraDsl = "enabled = false")

        val result = runner("hello", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(result.output.contains("[buildhound] build "), result.output)
        assertFalse(
            File(projectDir, ".gradle/buildhound").exists(),
            "enabled=false must not touch the identity salt",
        )
        assertFalse(File(projectDir, "build/buildhound").exists())
    }

    private fun exec(vararg command: String) {
        val process = ProcessBuilder(*command).directory(projectDir).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "command ${command.joinToString(" ")} failed:\n$output" }
    }
}
