package dev.buildhound.commons.ci

/**
 * Built-in provider for Azure DevOps Pipelines (spec §3.3 pins this mapping).
 * Detection marker: `TF_BUILD` (set to `True` on all Azure agents).
 * On PR builds `BUILD_SOURCEBRANCH` is the synthetic `refs/pull/N/merge` ref, so the
 * logical branch is `SYSTEM_PULLREQUEST_SOURCEBRANCH` when present.
 */
class AzureDevOpsCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "azure-devops"

    override fun detect(env: Map<String, String>): CiContext? {
        if (!env["TF_BUILD"].equals("true", ignoreCase = true)) return null
        val buildId = env["BUILD_BUILDID"]
        return CiContext(
            provider = id,
            pipelineId = env["SYSTEM_DEFINITIONID"],
            pipelineName = env["BUILD_DEFINITIONNAME"] ?: env["SYSTEM_DEFINITIONNAME"],
            runId = buildId,
            jobId = env["SYSTEM_JOBID"],
            stageId = env["SYSTEM_STAGENAME"],
            branch = (env["SYSTEM_PULLREQUEST_SOURCEBRANCH"] ?: env["BUILD_SOURCEBRANCH"])?.stripGitRef(),
            commitSha = env["BUILD_SOURCEVERSION"],
            // PULLREQUESTID is set for Azure Repos PRs; PRs from GitHub repos built on
            // Azure Pipelines carry PULLREQUESTNUMBER instead.
            pullRequestId = env["SYSTEM_PULLREQUEST_PULLREQUESTID"]
                ?: env["SYSTEM_PULLREQUEST_PULLREQUESTNUMBER"],
            targetBranch = env["SYSTEM_PULLREQUEST_TARGETBRANCH"]?.stripGitRef(),
            buildUrl = buildUrl(env["SYSTEM_COLLECTIONURI"], env["SYSTEM_TEAMPROJECT"], buildId),
            agentName = env["AGENT_NAME"],
        )
    }

    private fun buildUrl(collectionUri: String?, project: String?, buildId: String?): String? {
        if (collectionUri == null || project == null || buildId == null) return null
        if (!collectionUri.isHttpUrl()) return null
        return "${collectionUri.trimEnd('/')}/${encodeUrlSegment(project)}/_build/results" +
            "?buildId=${encodeUrlSegment(buildId)}"
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
            // WORKFLOW_REF carries an @<ref> suffix that changes per branch/PR; strip it
            // so the id is stable across runs of the same workflow.
            pipelineId = env["GITHUB_WORKFLOW_REF"]?.substringBefore('@'),
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
        if (!serverUrl.isHttpUrl()) return null
        return "${serverUrl.trimEnd('/')}/$repository/actions/runs/${encodeUrlSegment(runId)}"
    }
}

/** Strips `refs/heads/` / `refs/tags/` prefixes so branch fields carry the plain name. */
private fun String.stripGitRef(): String =
    removePrefix("refs/heads/").removePrefix("refs/tags/")

/**
 * Composed build URLs end up as hyperlinks in the HTML artifact and dashboard, so the
 * base URL must be a real http(s) origin — an env-controlled `javascript:` scheme must
 * never survive into a payload.
 */
private fun String.isHttpUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

/** Minimal RFC 3986 percent-encoding (KMP-pure): keeps unreserved characters only. */
private fun encodeUrlSegment(raw: String): String = buildString {
    for (byte in raw.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~" -> append(c)
            else -> append('%').append(byte.toUByte().toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/**
 * Detection entry point (spec §3.3): built-ins first, then caller-supplied extras
 * (the plugin passes `ServiceLoader` discoveries — JVM-only, so loading stays out of
 * this KMP-common module), then [GenericCiEnvironmentProvider] as the zero-code
 * fallback (`BUILDHOUND_CI_*` mapping first, bare `CI` variable last). First non-null
 * [CiEnvironmentProvider.detect] wins.
 */
object CiEnvironment {
    val builtIns: List<CiEnvironmentProvider> = listOf(
        AzureDevOpsCiEnvironmentProvider(),
        GitHubActionsCiEnvironmentProvider(),
    )

    private val generic = GenericCiEnvironmentProvider()

    fun detect(
        env: Map<String, String>,
        extraProviders: List<CiEnvironmentProvider> = emptyList(),
    ): CiContext? {
        // A throwing provider (third-party SPI) must not abort detection for the rest.
        for (provider in builtIns + extraProviders) {
            runCatching { provider.detect(env) }.getOrNull()?.let { return it }
        }
        return generic.detect(env)
    }
}
