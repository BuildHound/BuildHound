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
        // Re-runs share GITHUB_RUN_ID but bump GITHUB_RUN_ATTEMPT; without it the buildUrl
        // collides across attempts (plan 027). Carry it as an attribute + a URL suffix.
        val runAttempt = env["GITHUB_RUN_ATTEMPT"]
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
            buildUrl = buildUrl(env["GITHUB_SERVER_URL"], env["GITHUB_REPOSITORY"], runId, runAttempt),
            agentName = env["RUNNER_NAME"],
            attributes = buildMap { runAttempt?.let { put("runAttempt", it) } },
        )
    }

    private fun pullRequestId(ref: String?): String? =
        ref?.let { Regex("""^refs/pull/(\d+)/""").find(it) }?.groupValues?.get(1)

    private fun buildUrl(serverUrl: String?, repository: String?, runId: String?, runAttempt: String?): String? {
        if (serverUrl == null || repository == null || runId == null) return null
        if (!serverUrl.isHttpUrl()) return null
        val base = "${serverUrl.trimEnd('/')}/$repository/actions/runs/${encodeUrlSegment(runId)}"
        val attempt = runAttempt?.toIntOrNull()
        return if (attempt != null && attempt > 1) "$base/attempts/$attempt" else base
    }
}

/**
 * Jenkins (`JENKINS_URL`). §4.4 mapping; env-only (no properties-file chain). `GIT_BRANCH` is a
 * remote-qualified `origin/main`, so strip the remote prefix. Some vars are CCUD-unverified (⚠)
 * but confirmed against Jenkins' own env-var docs.
 */
class JenkinsCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "jenkins"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["JENKINS_URL"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            pipelineName = env["JOB_NAME"],
            runId = env["BUILD_NUMBER"],
            stageId = env["STAGE_NAME"],
            branch = (env["BRANCH_NAME"] ?: env["GIT_BRANCH"])?.stripGitRef()?.stripRemotePrefix(),
            commitSha = env["GIT_COMMIT"],
            pullRequestId = env["CHANGE_ID"],
            targetBranch = env["CHANGE_TARGET"],
            buildUrl = env["BUILD_URL"]?.takeIf { it.isHttpUrl() },
            agentName = env["NODE_NAME"],
            // URL attributes are gated like buildUrl — they may be rendered as links downstream.
            attributes = buildMap { env["JENKINS_URL"]?.takeIf { it.isHttpUrl() }?.let { put("controllerUrl", it) } },
        )
    }
}

/**
 * TeamCity (`TEAMCITY_VERSION`). Deliberately partial: CCUD reads build id / agent / serverUrl
 * from `TEAMCITY_BUILD_PROPERTIES_FILE`, a properties file the env-only SPI cannot read (§4.4
 * caveat). Env fallback only; no composable build URL.
 */
class TeamCityCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "teamcity"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["TEAMCITY_VERSION"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            pipelineName = env["TEAMCITY_PROJECT_NAME"],
            jobId = env["TEAMCITY_BUILDCONF_NAME"],
            runId = env["BUILD_NUMBER"],
        )
    }
}

/** CircleCI (`CIRCLECI`, or the composable `CIRCLE_BUILD_URL`). §4.4 mapping. */
class CircleCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "circleci"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["CIRCLECI"].isNullOrEmpty() && env["CIRCLE_BUILD_URL"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            buildUrl = env["CIRCLE_BUILD_URL"]?.takeIf { it.isHttpUrl() },
            runId = env["CIRCLE_BUILD_NUM"],
            jobId = env["CIRCLE_JOB"],
            pipelineId = env["CIRCLE_WORKFLOW_ID"],
            pipelineName = env["CIRCLE_PROJECT_REPONAME"],
            branch = env["CIRCLE_BRANCH"],
            commitSha = env["CIRCLE_SHA1"],
            pullRequestId = env["CIRCLE_PR_NUMBER"],
        )
    }
}

/** Bamboo (`bamboo_resultsUrl` — lower-case env keys). §4.4 mapping. */
class BambooCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "bamboo"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["bamboo_resultsUrl"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            buildUrl = env["bamboo_resultsUrl"]?.takeIf { it.isHttpUrl() },
            runId = env["bamboo_buildNumber"],
            pipelineName = env["bamboo_planName"],
            jobId = env["bamboo_buildPlanName"],
            agentName = env["bamboo_agentId"],
            branch = env["bamboo_planRepository_branch"],
            commitSha = env["bamboo_planRepository_revision"],
        )
    }
}

/** GitLab CI (`GITLAB_CI`). §4.4 mapping; the merge-request vars only exist on MR pipelines. */
class GitLabCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "gitlab"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["GITLAB_CI"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            buildUrl = env["CI_JOB_URL"]?.takeIf { it.isHttpUrl() },
            jobId = env["CI_JOB_NAME"],
            stageId = env["CI_JOB_STAGE"],
            branch = env["CI_COMMIT_REF_NAME"],
            pipelineId = env["CI_PIPELINE_ID"],
            runId = env["CI_PIPELINE_ID"],
            commitSha = env["CI_COMMIT_SHA"],
            pullRequestId = env["CI_MERGE_REQUEST_IID"],
            targetBranch = env["CI_MERGE_REQUEST_TARGET_BRANCH_NAME"],
            pipelineName = env["CI_PROJECT_PATH"],
            attributes = buildMap { env["CI_PIPELINE_URL"]?.takeIf { it.isHttpUrl() }?.let { put("pipelineUrl", it) } },
        )
    }
}

/** Travis CI (`TRAVIS_JOB_ID`). §4.4 mapping; `TRAVIS_PULL_REQUEST` is `"false"` off a PR. */
class TravisCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "travis"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["TRAVIS_JOB_ID"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            buildUrl = env["TRAVIS_BUILD_WEB_URL"]?.takeIf { it.isHttpUrl() },
            runId = env["TRAVIS_BUILD_NUMBER"],
            jobId = env["TRAVIS_JOB_NAME"],
            branch = env["TRAVIS_BRANCH"],
            commitSha = env["TRAVIS_COMMIT"],
            pullRequestId = env["TRAVIS_PULL_REQUEST"]?.takeIf { !it.equals("false", ignoreCase = true) },
            attributes = buildMap { env["TRAVIS_EVENT_TYPE"]?.let { put("eventType", it) } },
        )
    }
}

/** Bitrise (`BITRISE_BUILD_URL`). §4.4 mapping. */
class BitriseCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "bitrise"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["BITRISE_BUILD_URL"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            buildUrl = env["BITRISE_BUILD_URL"]?.takeIf { it.isHttpUrl() },
            runId = env["BITRISE_BUILD_NUMBER"],
            branch = env["BITRISE_GIT_BRANCH"],
            commitSha = env["BITRISE_GIT_COMMIT"],
            pullRequestId = env["BITRISE_PULL_REQUEST"],
            targetBranch = env["BITRISEIO_GIT_BRANCH_DEST"],
            pipelineName = env["BITRISE_TRIGGERED_WORKFLOW_ID"],
        )
    }
}

/** GoCD (`GO_SERVER_URL`). §4.4 mapping; the build URL needs every path segment present. */
class GoCdCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "gocd"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["GO_SERVER_URL"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            pipelineName = env["GO_PIPELINE_NAME"],
            runId = env["GO_PIPELINE_COUNTER"],
            stageId = env["GO_STAGE_NAME"],
            jobId = env["GO_JOB_NAME"],
            buildUrl = goCdBuildUrl(env),
            commitSha = env["GO_REVISION"],
        )
    }

    private fun goCdBuildUrl(env: Map<String, String>): String? {
        val server = env["GO_SERVER_URL"]?.takeIf { it.isHttpUrl() } ?: return null
        val pipeline = env["GO_PIPELINE_NAME"] ?: return null
        val counter = env["GO_PIPELINE_COUNTER"] ?: return null
        val stage = env["GO_STAGE_NAME"] ?: return null
        val stageCounter = env["GO_STAGE_COUNTER"] ?: return null
        val job = env["GO_JOB_NAME"] ?: return null
        return "${server.trimEnd('/')}/tab/build/detail/${encodeUrlSegment(pipeline)}/" +
            "${encodeUrlSegment(counter)}/${encodeUrlSegment(stage)}/${encodeUrlSegment(stageCounter)}/${encodeUrlSegment(job)}"
    }
}

/** Buildkite (`BUILDKITE`). §4.4 mapping. `BUILDKITE_COMMAND` is deliberately NOT captured (a shell command line — scrub risk). */
class BuildkiteCiEnvironmentProvider : CiEnvironmentProvider {
    override val id: String = "buildkite"

    override fun detect(env: Map<String, String>): CiContext? {
        if (env["BUILDKITE"].isNullOrEmpty()) return null
        return CiContext(
            provider = id,
            buildUrl = env["BUILDKITE_BUILD_URL"]?.takeIf { it.isHttpUrl() },
            runId = env["BUILDKITE_BUILD_ID"],
            branch = env["BUILDKITE_BRANCH"],
            commitSha = env["BUILDKITE_COMMIT"],
            pullRequestId = env["BUILDKITE_PULL_REQUEST"]?.takeIf { !it.equals("false", ignoreCase = true) },
            targetBranch = env["BUILDKITE_PULL_REQUEST_BASE_BRANCH"],
            pipelineName = env["BUILDKITE_PIPELINE_SLUG"],
            jobId = env["BUILDKITE_JOB_ID"],
            agentName = env["BUILDKITE_AGENT_NAME"],
        )
    }
}

/** Strips `refs/heads/` / `refs/tags/` prefixes so branch fields carry the plain name. */
private fun String.stripGitRef(): String =
    removePrefix("refs/heads/").removePrefix("refs/tags/")

/** Jenkins `GIT_BRANCH` is remote-qualified (`origin/main`); drop the remote segment. */
private fun String.stripRemotePrefix(): String =
    removePrefix("refs/remotes/").removePrefix("origin/")

/**
 * Composed build URLs end up as hyperlinks in the HTML artifact and dashboard, so the
 * base URL must be a real http(s) origin — an env-controlled `javascript:` scheme must
 * never survive into a payload.
 */
internal fun String.isHttpUrl(): Boolean =
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
    // Most-specific markers first (§4.5); every marker below is provider-unique, so the order
    // matters only for future overlaps — none of these keys off the bare `CI` variable.
    val builtIns: List<CiEnvironmentProvider> = listOf(
        AzureDevOpsCiEnvironmentProvider(),
        GitHubActionsCiEnvironmentProvider(),
        GitLabCiEnvironmentProvider(),
        JenkinsCiEnvironmentProvider(),
        TeamCityCiEnvironmentProvider(),
        CircleCiEnvironmentProvider(),
        BambooCiEnvironmentProvider(),
        TravisCiEnvironmentProvider(),
        BitriseCiEnvironmentProvider(),
        GoCdCiEnvironmentProvider(),
        BuildkiteCiEnvironmentProvider(),
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
        return runCatching { generic.detect(env) }.getOrNull()
    }
}
