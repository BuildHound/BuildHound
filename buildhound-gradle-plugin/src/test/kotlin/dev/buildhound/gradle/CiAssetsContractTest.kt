package dev.buildhound.gradle

import dev.buildhound.commons.ci.RunnerAttributes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class CiAssetsContractTest {

    private fun asset(path: String): String = File("../buildhound-ci-assets/$path").readText()

    @Test
    fun `GitHub action opts into the basic cache provider without inlining secrets`() {
        val action = asset("github/action.yml")

        assertTrue(action.contains("uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6"))
        assertTrue(action.contains("cache-provider: ${'$'}{{ inputs.cache-provider }}"))
        assertTrue(Regex("cache-provider:[\\s\\S]*?default: \\\"basic\\\"").containsMatchIn(action))
        assertFalse(Regex("(?i)(token|secret):\\s*['\\\"][A-Za-z0-9_-]{16,}").containsMatchIn(action))
    }

    /**
     * The plan-104 runner-class opt-in is a *declarative* binding, so nothing in the shell test
     * harness pattern can cover it — and until this test existed, nothing in CI could catch a
     * typo in it either. `actionlint` (`.github/workflows/ci.yml`) only lints a composite action a
     * local workflow references via `uses: ./path`, which none does, so `action.yml` gets no schema
     * check at all; the Azure and GitLab templates get none from anything.
     *
     * The failure mode without this is silent: a consumer's pipeline simply stops collecting the
     * field, with nothing red anywhere.
     */
    @Test
    fun `every CI template exports the runner class under the exact name the plugin reads`() {
        val envVar = RunnerAttributes.RUNNER_CLASS_ENV
        assertTrue(
            asset("github/action.yml").contains("$envVar: ${'$'}{{ inputs.runner-class }}"),
            "the GitHub action must export $envVar from its runner-class input",
        )
        assertTrue(
            asset("azure-pipelines/buildhound-gradle-steps.yml").contains("$envVar: ${'$'}{{ parameters.runnerClass }}"),
            "the Azure steps template must export $envVar from its runnerClass parameter",
        )
        // GitLab needs no wiring — a job `variables:` entry already reaches the shell environment —
        // but the usage docstring is the only place a consumer learns the name, so pin that it is
        // documented and spelled correctly.
        assertTrue(
            asset("gitlab/buildhound-gradle.gitlab-ci.yml").contains(envVar),
            "the GitLab template must document $envVar",
        )
    }

    /**
     * The injection invariant these templates rest on: caller-supplied inputs are bound as `env:`
     * and referenced as shell variables, never interpolated into the `run:` script text. A caller
     * who wires untrusted data (a PR title, a fork branch name) into an input must not be able to
     * reach the shell that holds `BUILDHOUND_TOKEN`. That rule lived only in a code comment.
     */
    @Test
    fun `no caller-supplied input is interpolated into a GitHub run script`() {
        val action = asset("github/action.yml")
        val interpolation = Regex("""\$\{\{\s*inputs\.[\w-]+\s*}}""")

        for (script in Regex("""(?m)^\s*run:\s*\|?[\s\S]*?(?=^\s{4}- |\Z)""").findAll(action)) {
            val body = script.value
            // `env:` blocks legitimately follow a `run:` inside the same step; the binding itself is
            // the safe form, so only the script text before the step's env map is checked.
            val scriptText = body.substringBefore("\n      env:")
            assertFalse(
                interpolation.containsMatchIn(scriptText),
                "a caller-supplied input reached a run: script — bind it as env: instead:\n$scriptText",
            )
        }
    }
}
