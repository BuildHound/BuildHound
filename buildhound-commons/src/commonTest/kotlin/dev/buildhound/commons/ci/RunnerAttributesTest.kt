package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The plan-104 categorical runner allowlist: what it maps, what it caps, and what it refuses. */
class RunnerAttributesTest {

    /** Runner attributes are merged by [CiEnvironment.detect], so every test here goes through it. */
    private fun attributesFor(env: Map<String, String>): Map<String, String> =
        CiEnvironment.detect(env)?.attributes.orEmpty()

    @Test
    fun `github hosted runner variables map to their attribute keys`() {
        val attributes = attributesFor(
            mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_RUN_ATTEMPT" to "2",
                "RUNNER_ENVIRONMENT" to "github-hosted",
                "RUNNER_OS" to "Linux",
                "RUNNER_ARCH" to "X64",
                "ImageOS" to "ubuntu24",
                "ImageVersion" to "20250801.1.0",
            ),
        )

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
        val azure = attributesFor(
            mapOf(
                "TF_BUILD" to "True",
                "AGENT_OS" to "Linux",
                "AGENT_OSARCHITECTURE" to "X64",
                "ImageVersion" to "20250801.1.0",
            ),
        )
        assertEquals("Linux", azure["runnerOs"])
        assertEquals("X64", azure["runnerArch"])
        assertEquals("20250801.1.0", azure["runnerImageVersion"])

        val gitlab = attributesFor(
            mapOf(
                "GITLAB_CI" to "true",
                "CI_RUNNER_EXECUTABLE_ARCH" to "linux/amd64",
                "CI_RUNNER_VERSION" to "17.3.0",
                "CI_PIPELINE_URL" to "https://gitlab.example.com/g/p/-/pipelines/9",
            ),
        )
        assertEquals("linux/amd64", gitlab["runnerArch"])
        assertEquals("17.3.0", gitlab["runnerVersion"])
        assertEquals("https://gitlab.example.com/g/p/-/pipelines/9", gitlab["pipelineUrl"])
    }

    /**
     * The opt-in is applied at the dispatcher, so it must reach **every** provider — including the
     * eight built-ins with no runner allowlist of their own, a third-party `ServiceLoader` provider,
     * and both tiers of the generic fallback. Wiring it per-provider (the first cut) made it a
     * silent no-op everywhere except the three providers that got the call, which is exactly the
     * audience an operator-set label exists for.
     */
    @Test
    fun `the opt-in runner class reaches every provider, not just the three with an allowlist`() {
        val runnerClass = mapOf(RunnerAttributes.RUNNER_CLASS_ENV to "ubuntu-latest-8-core")
        val markers = mapOf(
            "github-actions" to mapOf("GITHUB_ACTIONS" to "true"),
            "azure-devops" to mapOf("TF_BUILD" to "True"),
            "gitlab" to mapOf("GITLAB_CI" to "true"),
            "jenkins" to mapOf("JENKINS_URL" to "https://ci.example.com/", "BUILD_NUMBER" to "7"),
            "circleci" to mapOf("CIRCLECI" to "true"),
            "buildkite" to mapOf("BUILDKITE" to "true"),
            "travis-ci" to mapOf("TRAVIS" to "true", "TRAVIS_JOB_ID" to "9"),
            // Generic tier 2: the bare conventional `CI` variable, no BUILDHOUND_CI_* mapping at all
            // — the unsupported-CI audience the generic provider exists for.
            "generic" to mapOf("CI" to "true"),
        )
        for ((label, marker) in markers) {
            val context = CiEnvironment.detect(marker + runnerClass)
            assertEquals("ubuntu-latest-8-core", context?.attributes?.get("runnerClass"), label)
        }

        // A third-party SPI provider gets it too, without implementing anything.
        val thirdParty = object : CiEnvironmentProvider {
            override val id: String = "acme-ci"
            override fun detect(env: Map<String, String>): CiContext? = CiContext(provider = id)
        }
        val extra = CiEnvironment.detect(runnerClass, extraProviders = listOf(thirdParty))
        assertEquals("ubuntu-latest-8-core", extra?.attributes?.get("runnerClass"))
    }

    @Test
    fun `no runner attributes are added when the operator opted out`() {
        val without = CiEnvironment.detect(mapOf("GITHUB_ACTIONS" to "true"))?.attributes.orEmpty()
        assertNull(without["runnerClass"])
    }

    @Test
    fun `a provider's own attribute wins over a generic env read of the same key`() {
        val context = CiEnvironment.detect(
            mapOf("GITHUB_ACTIONS" to "true", "GITHUB_RUN_ATTEMPT" to "3", "RUNNER_ARCH" to "ARM64"),
        )
        assertEquals("3", context?.attributes?.get("runAttempt"), "the provider's own key survives the merge")
        assertEquals("ARM64", context?.attributes?.get("runnerArch"))
    }

    @Test
    fun `a runner class outside the categorical shape is dropped whole, not truncated`() {
        // The one operator-supplied value in the map, so "categorical" is enforced rather than
        // merely documented: a value carrying shell metacharacters, quotes, newlines or control
        // bytes is not a runner label, and half of it is not either.
        for (hostile in listOf("ubuntu\$(id)", "ubuntu`id`", "a\nb", "it's", "<script>", "x;y", "a|b")) {
            val attributes = RunnerAttributes.of(mapOf(RunnerAttributes.RUNNER_CLASS_ENV to hostile), emptyList())
            assertNull(attributes["runnerClass"], hostile)
        }
        // Real-world labels keep working — separators, underscores and spaces included,
        // since a self-hosted pool name legitimately has them.
        val real = listOf(
            "ubuntu-latest-8-core", "saas-linux-medium-amd64", "windows_2022",
            "linux/arm64", "pool:build", "Self Hosted Pool",
        )
        for (ok in real) {
            val attributes = RunnerAttributes.of(mapOf(RunnerAttributes.RUNNER_CLASS_ENV to ok), emptyList())
            assertEquals(ok, attributes["runnerClass"], ok)
        }
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
        val gitlab = attributesFor(
            mapOf(
                "GITLAB_CI" to "true",
                "CI_RUNNER_DESCRIPTION" to "build-box-07.internal.example.com",
                "CI_RUNNER_TAGS" to "docker, jane-laptop",
            ),
        )
        assertTrue(gitlab.values.none { it.contains("build-box-07") || it.contains("jane-laptop") })

        val azure = attributesFor(mapOf("TF_BUILD" to "True", "AGENT_MACHINENAME" to "fv-az1234-567"))
        assertFalse(azure.values.any { it.contains("fv-az1234") })

        assertFalse(RunnerAttributes.GITHUB.any { it.first == "RUNNER_NAME" })
    }
}
