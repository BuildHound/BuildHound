package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The plan-104 categorical runner allowlist: what it maps, what it caps, and what it refuses. */
class RunnerAttributesTest {

    @Test
    fun `github hosted runner variables map to their attribute keys`() {
        val attributes = GitHubActionsCiEnvironmentProvider().detect(
            mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_RUN_ATTEMPT" to "2",
                "RUNNER_ENVIRONMENT" to "github-hosted",
                "RUNNER_OS" to "Linux",
                "RUNNER_ARCH" to "X64",
                "ImageOS" to "ubuntu24",
                "ImageVersion" to "20250801.1.0",
            ),
        )?.attributes.orEmpty()

        assertEquals("github-hosted", attributes["runnerEnvironment"])
        assertEquals("Linux", attributes["runnerOs"])
        assertEquals("X64", attributes["runnerArch"])
        assertEquals("ubuntu24", attributes["runnerImageOs"])
        assertEquals("20250801.1.0", attributes["runnerImageVersion"])
        // The pre-existing attribute is preserved, not replaced by the merge.
        assertEquals("2", attributes["runAttempt"])
    }

    @Test
    fun `azure and gitlab map their own categorical variables`() {
        val azure = AzureDevOpsCiEnvironmentProvider().detect(
            mapOf(
                "TF_BUILD" to "True",
                "AGENT_OS" to "Linux",
                "AGENT_OSARCHITECTURE" to "X64",
                "ImageVersion" to "20250801.1.0",
            ),
        )?.attributes.orEmpty()
        assertEquals("Linux", azure["runnerOs"])
        assertEquals("X64", azure["runnerArch"])
        assertEquals("20250801.1.0", azure["runnerImageVersion"])

        val gitlab = GitLabCiEnvironmentProvider().detect(
            mapOf(
                "GITLAB_CI" to "true",
                "CI_RUNNER_EXECUTABLE_ARCH" to "linux/amd64",
                "CI_RUNNER_VERSION" to "17.3.0",
                "CI_PIPELINE_URL" to "https://gitlab.example.com/g/p/-/pipelines/9",
            ),
        )?.attributes.orEmpty()
        assertEquals("linux/amd64", gitlab["runnerArch"])
        assertEquals("17.3.0", gitlab["runnerVersion"])
        assertEquals("https://gitlab.example.com/g/p/-/pipelines/9", gitlab["pipelineUrl"])
    }

    @Test
    fun `the opt-in runner class is read on every provider and absent by default`() {
        val withClass = GitHubActionsCiEnvironmentProvider().detect(
            mapOf("GITHUB_ACTIONS" to "true", "BUILDHOUND_CI_RUNNER_CLASS" to "ubuntu-latest-8-core"),
        )?.attributes.orEmpty()
        assertEquals("ubuntu-latest-8-core", withClass["runnerClass"])

        val without = GitHubActionsCiEnvironmentProvider().detect(mapOf("GITHUB_ACTIONS" to "true"))
            ?.attributes.orEmpty()
        assertNull(without["runnerClass"])
    }

    @Test
    fun `blank values are dropped rather than shipped as empty strings`() {
        // GitHub sets several of these as present-but-empty when they do not apply.
        val attributes = RunnerAttributes.of(
            mapOf("RUNNER_ARCH" to "", "RUNNER_OS" to "   ", "RUNNER_ENVIRONMENT" to "self-hosted"),
            RunnerAttributes.GITHUB,
        )

        assertEquals(mapOf("runnerEnvironment" to "self-hosted"), attributes)
    }

    @Test
    fun `values are trimmed and capped so an operator-set label cannot smuggle a payload`() {
        val attributes = RunnerAttributes.of(
            mapOf(RunnerAttributes.RUNNER_CLASS_ENV to "  " + "x".repeat(500) + "  "),
            emptyList(),
        )

        assertEquals(RunnerAttributes.MAX_VALUE_LENGTH, attributes.getValue("runnerClass").length)
    }

    @Test
    fun `free-text runner descriptions are never collected`() {
        // CI_RUNNER_DESCRIPTION/TAGS and AGENT_MACHINENAME are operator-set text that routinely
        // carries hostnames (spec §3.7). Pinned here so a future widening is a deliberate act.
        val gitlab = GitLabCiEnvironmentProvider().detect(
            mapOf(
                "GITLAB_CI" to "true",
                "CI_RUNNER_DESCRIPTION" to "build-box-07.internal.example.com",
                "CI_RUNNER_TAGS" to "docker, jane-laptop",
            ),
        )?.attributes.orEmpty()
        assertTrue(gitlab.values.none { it.contains("build-box-07") || it.contains("jane-laptop") })

        val azure = AzureDevOpsCiEnvironmentProvider().detect(
            mapOf("TF_BUILD" to "True", "AGENT_MACHINENAME" to "fv-az1234-567"),
        )?.attributes.orEmpty()
        assertFalse(azure.values.any { it.contains("fv-az1234") })

        assertFalse(RunnerAttributes.GITHUB.any { it.first == "RUNNER_NAME" })
    }
}
