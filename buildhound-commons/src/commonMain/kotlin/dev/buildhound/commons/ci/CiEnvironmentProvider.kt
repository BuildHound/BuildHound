package dev.buildhound.commons.ci

/**
 * Public extension point for CI detection (spec §3.3). Implementations map provider-specific
 * environment variables to a normalized [CiContext]. Discovery order in the plugin:
 * built-ins first, then `ServiceLoader` on the settings classpath, then [GenericCiEnvironmentProvider].
 * First non-null [detect] wins. A new CI should be supportable in ~30 lines.
 */
interface CiEnvironmentProvider {
    /** Stable identifier, e.g. `"azure-devops"`, `"github-actions"`. */
    val id: String

    /** Returns the normalized context, or null when [env] does not belong to this provider. */
    fun detect(env: Map<String, String>): CiContext?
}

data class CiContext(
    val provider: String,
    val pipelineId: String? = null,
    val pipelineName: String? = null,
    /** Correlation key for backend connectors (e.g. Azure `BUILD_BUILDID`). */
    val runId: String? = null,
    val jobId: String? = null,
    val stageId: String? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val pullRequestId: String? = null,
    val targetBranch: String? = null,
    val buildUrl: String? = null,
    val agentName: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Fallback provider so unsupported CIs work with zero code. Two activation tiers,
 * checked in order:
 *
 * 1. `BUILDHOUND_CI=true` or `BUILDHOUND_CI_PROVIDER` set → full `BUILDHOUND_CI_*`
 *    field mapping.
 * 2. The conventional `CI` variable (set by CircleCI, GitLab, Jenkins, Travis and most
 *    others) → minimal context with provider `"generic"` and no fields. Unlike CCUD's
 *    presence-only `isGenericCI`, an explicit `CI=false` / `CI=0` (case-insensitive)
 *    counts as *not* CI — the ci-info ecosystem opt-out convention (plan 014); an empty
 *    value still counts as present. Setting `BUILDHOUND_CI` to any non-`true` value
 *    suppresses this fallback entirely.
 */
class GenericCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "generic"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["BUILDHOUND_CI"] != "true" && env["BUILDHOUND_CI_PROVIDER"] == null) {
            // Explicit BUILDHOUND_CI (e.g. "false") overrides the bare-CI heuristic.
            if (env["BUILDHOUND_CI"] != null) return null
            return if (isBareCi(env["CI"])) CiContext(provider = id) else null
        }
        return CiContext(
            provider = env["BUILDHOUND_CI_PROVIDER"] ?: id,
            pipelineId = env["BUILDHOUND_CI_PIPELINE_ID"],
            pipelineName = env["BUILDHOUND_CI_PIPELINE_NAME"],
            runId = env["BUILDHOUND_CI_RUN_ID"],
            jobId = env["BUILDHOUND_CI_JOB_ID"],
            stageId = env["BUILDHOUND_CI_STAGE_ID"],
            branch = env["BUILDHOUND_CI_BRANCH"],
            commitSha = env["BUILDHOUND_CI_COMMIT_SHA"],
            pullRequestId = env["BUILDHOUND_CI_PULL_REQUEST_ID"],
            targetBranch = env["BUILDHOUND_CI_TARGET_BRANCH"],
            buildUrl = env["BUILDHOUND_CI_BUILD_URL"],
            agentName = env["BUILDHOUND_CI_AGENT_NAME"],
        )
    }

    private fun isBareCi(value: String?): Boolean =
        value != null && !value.equals("false", ignoreCase = true) && value != "0"
}
