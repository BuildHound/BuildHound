package dev.buildhound.commons.ci

/**
 * Built-in provider for Azure DevOps Pipelines (spec §3.3 pins this mapping).
 * Detection marker: `TF_BUILD` (set to `True` on all Azure agents).
 */
class AzureDevOpsCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "azure-devops"

    override fun detect(env: Map<String, String>): CiContext? {
        if (!env["TF_BUILD"].equals("true", ignoreCase = true)) return null
        val buildId = env["BUILD_BUILDID"]
        return CiContext(
            provider = id,
            pipelineId = env["SYSTEM_DEFINITIONID"],
            pipelineName = env["SYSTEM_DEFINITIONNAME"],
            runId = buildId,
            jobId = env["SYSTEM_JOBID"],
            stageId = env["SYSTEM_STAGENAME"],
            branch = env["BUILD_SOURCEBRANCH"]?.stripGitRef(),
            commitSha = env["BUILD_SOURCEVERSION"],
            pullRequestId = env["SYSTEM_PULLREQUEST_PULLREQUESTID"],
            targetBranch = env["SYSTEM_PULLREQUEST_TARGETBRANCH"]?.stripGitRef(),
            buildUrl = buildUrl(env["SYSTEM_COLLECTIONURI"], env["SYSTEM_TEAMPROJECT"], buildId),
            agentName = env["AGENT_NAME"],
        )
    }

    private fun buildUrl(collectionUri: String?, project: String?, buildId: String?): String? {
        if (collectionUri == null || project == null || buildId == null) return null
        return "${collectionUri.trimEnd('/')}/$project/_build/results?buildId=$buildId"
    }
}

/**
 * Built-in provider for GitHub Actions. Detection marker: `GITHUB_ACTIONS=true`.
 * On pull_request events the checked-out ref is the synthetic merge ref, so the
 * logical branch is `GITHUB_HEAD_REF` when present, else `GITHUB_REF_NAME`.
 */
class GitHubActionsCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "github-actions"

    override fun detect(env: Map<String, String>): CiContext? {
        if (!env["GITHUB_ACTIONS"].equals("true", ignoreCase = true)) return null
        val runId = env["GITHUB_RUN_ID"]
        return CiContext(
            provider = id,
            pipelineId = env["GITHUB_WORKFLOW_REF"],
            pipelineName = env["GITHUB_WORKFLOW"],
            runId = runId,
            jobId = env["GITHUB_JOB"],
            branch = env["GITHUB_HEAD_REF"]?.takeIf { it.isNotEmpty() } ?: env["GITHUB_REF_NAME"],
            commitSha = env["GITHUB_SHA"],
            pullRequestId = pullRequestId(env["GITHUB_REF"]),
            targetBranch = env["GITHUB_BASE_REF"]?.takeIf { it.isNotEmpty() },
            buildUrl = buildUrl(env["GITHUB_SERVER_URL"], env["GITHUB_REPOSITORY"], runId),
            agentName = env["RUNNER_NAME"],
        )
    }

    private fun pullRequestId(ref: String?): String? =
        ref?.let { Regex("""^refs/pull/(\d+)/""").find(it) }?.groupValues?.get(1)

    private fun buildUrl(serverUrl: String?, repository: String?, runId: String?): String? {
        if (serverUrl == null || repository == null || runId == null) return null
        return "${serverUrl.trimEnd('/')}/$repository/actions/runs/$runId"
    }
}

/** Strips `refs/heads/` / `refs/tags/` prefixes so branch fields carry the plain name. */
private fun String.stripGitRef(): String =
    removePrefix("refs/heads/").removePrefix("refs/tags/")

/**
 * Detection entry point (spec §3.3): built-ins first, then caller-supplied extras
 * (the plugin passes `ServiceLoader` discoveries — JVM-only, so loading stays out of
 * this KMP-common module), then [GenericCiEnvironmentProvider] as the zero-code
 * fallback. First non-null [CiEnvironmentProvider.detect] wins.
 */
object CiEnvironment {
    val builtIns: List<CiEnvironmentProvider> = listOf(
        AzureDevOpsCiEnvironmentProvider(),
        GitHubActionsCiEnvironmentProvider(),
    )

    fun detect(
        env: Map<String, String>,
        extraProviders: List<CiEnvironmentProvider> = emptyList(),
    ): CiContext? =
        (builtIns + extraProviders + GenericCiEnvironmentProvider())
            .firstNotNullOfOrNull { it.detect(env) }
}
