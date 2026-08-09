package dev.buildhound.commons.ci

/**
 * Runner-class attributes for the built-in CI providers (plan 104).
 *
 * **Why these live in CI detection and not the environment probe.** The runner's *measured* specs —
 * cores, RAM, disk — are already correct from inside the job, because `Runtime.availableProcessors`
 * and the OS memory bean are cgroup-aware. What the JVM cannot see is which runner *class* the job
 * asked for: GitHub's `runs-on` labels and Azure's pool/`vmImage` are pipeline-definition values, and
 * the hosted image identity lives in provider-specific variables. That is the part only a CI
 * integration can supply, so it is collected here and exported by `buildhound-ci-assets`.
 *
 * **Categorical allowlist only (spec §3.7).** [GITHUB]/[AZURE]/[GITLAB] are compile-time constants,
 * so widening them is a plan's decision rather than a build's — the plan-051/065 allowlist
 * discipline. Every candidate is a bounded categorical value (`X64`, `github-hosted`, `ubuntu24`).
 *
 * Deliberately **excluded**, and not to be added without a privacy review: GitLab's
 * `CI_RUNNER_DESCRIPTION` and `CI_RUNNER_TAGS`, and Azure's `AGENT_MACHINENAME` — operator-set free
 * text that routinely carries hostnames. The already-collected `CiContext.agentName` is the one
 * place a runner's own name belongs.
 */
object RunnerAttributes {

    /**
     * Values are provider- or operator-set strings rather than enums, so each is length-capped
     * before it can reach the payload. Comfortably above every real value (`Windows_NT`, `ubuntu24`,
     * `20250801.1.0`) and far below anything that could smuggle a payload in.
     */
    const val MAX_VALUE_LENGTH: Int = 64

    /**
     * Provider-neutral opt-in: the runner class the job requested (e.g. `ubuntu-latest-8-core`),
     * which reaches no environment variable on any provider. Exported by the `buildhound-ci-assets`
     * templates from the pipeline definition; unset — and therefore uncollected — by default.
     */
    const val RUNNER_CLASS_ENV: String = "BUILDHOUND_CI_RUNNER_CLASS"

    /** GitHub Actions: `RUNNER_ENVIRONMENT` distinguishes `github-hosted` from `self-hosted`. */
    val GITHUB: List<Pair<String, String>> = listOf(
        "RUNNER_ENVIRONMENT" to "runnerEnvironment",
        "RUNNER_OS" to "runnerOs",
        "RUNNER_ARCH" to "runnerArch",
        "ImageOS" to "runnerImageOs",
        "ImageVersion" to "runnerImageVersion",
    )

    /** Azure Pipelines: `ImageVersion` is set on Microsoft-hosted agents only. */
    val AZURE: List<Pair<String, String>> = listOf(
        "AGENT_OS" to "runnerOs",
        "AGENT_OSARCHITECTURE" to "runnerArch",
        "ImageVersion" to "runnerImageVersion",
    )

    /** GitLab CI: architecture + runner version; the free-text description/tags stay excluded. */
    val GITLAB: List<Pair<String, String>> = listOf(
        "CI_RUNNER_EXECUTABLE_ARCH" to "runnerArch",
        "CI_RUNNER_VERSION" to "runnerVersion",
    )

    /**
     * Maps [allowlist] over [env], plus the provider-neutral [RUNNER_CLASS_ENV]. Blank values are
     * dropped (an unset GitHub variable is often present-but-empty rather than absent), and every
     * value is trimmed and capped at [MAX_VALUE_LENGTH]. Empty when nothing matched — providers
     * merge the result into their own `attributes`, so an empty map costs nothing.
     */
    fun of(env: Map<String, String>, allowlist: List<Pair<String, String>>): Map<String, String> =
        buildMap {
            for ((envKey, attributeKey) in allowlist) {
                sanitize(env[envKey])?.let { put(attributeKey, it) }
            }
            sanitize(env[RUNNER_CLASS_ENV])?.let { put("runnerClass", it) }
        }

    private fun sanitize(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_VALUE_LENGTH)
}
