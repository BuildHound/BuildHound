package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/** CI-provider breadth, IDE/agent detection, config overrides, and uploadInBackground (plan 027). */
class CiEnvironmentBreadthFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*arguments, "--configuration-cache")

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun setUpProject(dsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "ci-env-fixture"
            buildhound { $dsl }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
    }

    // Strip every CI + agent marker so the injected env alone steers detection, whatever CI runs this.
    private val markers = setOf(
        "CI", "GITHUB_ACTIONS", "TF_BUILD", "GITLAB_CI", "JENKINS_URL", "TEAMCITY_VERSION", "CIRCLECI",
        "CIRCLE_BUILD_URL", "bamboo_resultsUrl", "TRAVIS_JOB_ID", "BITRISE_BUILD_URL", "GO_SERVER_URL",
        "BUILDKITE", "CLAUDECODE", "CODEX_THREAD_ID", "CODEX_SANDBOX_NETWORK_DISABLED", "CURSOR_AGENT",
        "OPENCODE", "GEMINI_CLI", "ANDROID_STUDIO_AGENT",
    )

    private fun neutralEnv(): Map<String, String> =
        System.getenv().filterKeys { it !in markers && !it.startsWith("BUILDHOUND_") }

    @Test
    fun `a GitLab environment is detected as the gitlab provider in ci mode`() {
        setUpProject()
        runner("hello").freshDaemon()
            .withEnvironment(neutralEnv() + mapOf("GITLAB_CI" to "true", "CI_JOB_NAME" to "test", "CI_COMMIT_REF_NAME" to "feature/x"))
            .build()

        val payload = readPayload()
        assertEquals(BuildMode.CI, payload.mode)
        assertEquals("gitlab", payload.ci?.provider)
        assertEquals("feature/x", payload.vcs?.branch, "CI context fills the branch gap")
    }

    @Test
    fun `a Claude Code environment records the ai agent`() {
        setUpProject()
        runner("hello").freshDaemon()
            .withEnvironment(neutralEnv() + mapOf("CLAUDECODE" to "1"))
            .build()

        assertEquals("Claude Code", readPayload().environment?.aiAgent)
    }

    @Test
    fun `a BUILDHOUND_MODE override disables telemetry even when the DSL says auto`() {
        setUpProject() // mode defaults to auto
        runner("hello").freshDaemon()
            .withEnvironment(neutralEnv() + mapOf("BUILDHOUND_MODE" to "disabled"))
            .build()

        assertFalse(File(projectDir, "build/buildhound/build-payload.json").exists(), "disabled → nothing written")
    }

    @Test
    fun `a hostile override value is ignored and never fails the build`() {
        setUpProject()
        // `enabled` is a boolean; a non-boolean override is ignored (convention stays true).
        val result = runner("hello").freshDaemon()
            .withEnvironment(neutralEnv() + mapOf("BUILDHOUND_ENABLED" to "maybe"))
            .build()

        assertTrue(readPayload().tasks.isNotEmpty(), "unparseable override ignored, telemetry still runs")
        assertTrue(result.output.contains("hello"))
    }

    @Test
    fun `uploadInBackground spools a local build instead of attempting the inline upload`() {
        // A local build with a server url + opt-in waived; uploadInBackground defers the send.
        setUpProject(
            dsl = """
            server { url = "http://127.0.0.1:1" }
            localBuilds { requireOptInFile = false }
            upload { uploadInBackground = true }
            """.trimIndent(),
        )
        runner("hello").freshDaemon().withEnvironment(neutralEnv()).build()

        val spool = File(projectDir, "build/buildhound/spool").listFiles { f -> f.name.endsWith(".json.gz") }.orEmpty()
        assertEquals(1, spool.size, "the payload was spooled, not uploaded inline")
    }

    @Test
    fun `GitHub Actions appends a summary on configuration-cache store and reuse`() {
        setUpProject()
        val summary = File(projectDir, "github-step-summary.md")
        val environment = neutralEnv() + mapOf(
            "GITHUB_ACTIONS" to "true",
            "GITHUB_STEP_SUMMARY" to summary.invariantSeparatorsPath,
        )

        val gradle = runner("hello").freshDaemon().withEnvironment(environment)
        gradle.build()
        val first = summary.readText()
        val second = gradle.build()

        assertTrue(first.contains("## BuildHound"))
        assertTrue(summary.readText().length > first.length, "CC replay appends another summary")
        assertTrue(second.output.contains("Configuration cache entry reused"))
    }

    @Test
    fun `job summary property override suppresses GitHub output`() {
        setUpProject()
        val summary = File(projectDir, "github-step-summary-disabled.md")
        runner("hello", "-Pbuildhound.ci.jobSummary=false").freshDaemon()
            .withEnvironment(
                neutralEnv() + mapOf(
                    "GITHUB_ACTIONS" to "true",
                    "GITHUB_STEP_SUMMARY" to summary.invariantSeparatorsPath,
                ),
            ).build()

        assertFalse(summary.exists())
    }

    @Test
    fun `Azure DevOps emits an uploadsummary command`() {
        setUpProject()
        val result = runner("hello").freshDaemon()
            .withEnvironment(neutralEnv() + mapOf("TF_BUILD" to "true"))
            .build()

        assertTrue(result.output.contains("##vso[task.uploadsummary]"))
        assertTrue(File(projectDir, "build/buildhound/job-summary.md").isFile)
    }

    @Test
    fun `dashboard URL overrides server URL and server URL remains the fallback`() {
        setUpProject(
            dsl = """
            mode = dev.buildhound.gradle.TelemetryMode.LOCAL
            server { url = "https://ingest.example.com" }
            ci { dashboardUrl = "https://dashboard.example.com" }
            """.trimIndent(),
        )
        val summary = File(projectDir, "github-step-summary-url.md")
        val environment = neutralEnv() + mapOf(
            "GITHUB_ACTIONS" to "true",
            "GITHUB_STEP_SUMMARY" to summary.invariantSeparatorsPath,
        )
        val gradle = runner("hello").freshDaemon().withEnvironment(environment)
        gradle.build()
        assertTrue(summary.readText().contains("https://dashboard.example.com/#/build/"))
        assertFalse(summary.readText().contains("https://ingest.example.com/#/build/"))

        setUpProject(
            dsl = """
            mode = dev.buildhound.gradle.TelemetryMode.LOCAL
            server { url = "https://ingest.example.com" }
            """.trimIndent(),
        )
        gradle.build()
        assertTrue(summary.readText().contains("https://ingest.example.com/#/build/"))
    }
}
