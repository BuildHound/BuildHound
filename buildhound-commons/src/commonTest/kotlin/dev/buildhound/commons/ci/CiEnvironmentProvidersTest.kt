package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val azurePushEnv = mapOf(
    "TF_BUILD" to "True",
    "BUILD_BUILDID" to "20260702",
    "SYSTEM_DEFINITIONID" to "17",
    "BUILD_DEFINITIONNAME" to "android-ci",
    "SYSTEM_JOBID" to "8f6a1c2e-job",
    "SYSTEM_STAGENAME" to "Build",
    "BUILD_SOURCEBRANCH" to "refs/heads/main",
    "BUILD_SOURCEVERSION" to "cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a",
    "SYSTEM_COLLECTIONURI" to "https://dev.azure.com/acme/",
    "SYSTEM_TEAMPROJECT" to "mobile",
    "AGENT_NAME" to "Hosted Agent 03",
)

private val gitHubPullRequestEnv = mapOf(
    "GITHUB_ACTIONS" to "true",
    "GITHUB_RUN_ID" to "28594986517",
    "GITHUB_WORKFLOW" to "CI",
    "GITHUB_WORKFLOW_REF" to "acme/app/.github/workflows/ci.yml@refs/pull/2/merge",
    "GITHUB_JOB" to "build",
    "GITHUB_REF" to "refs/pull/2/merge",
    "GITHUB_REF_NAME" to "2/merge",
    "GITHUB_HEAD_REF" to "feature/speedup",
    "GITHUB_BASE_REF" to "main",
    "GITHUB_SHA" to "e1b53e1a2b3c4d5e6f708192a3b4c5d6e7f80912",
    "GITHUB_SERVER_URL" to "https://github.com",
    "GITHUB_REPOSITORY" to "acme/app",
    "RUNNER_NAME" to "GitHub Actions 12",
)

class AzureDevOpsCiEnvironmentProviderTest {

    private val provider = AzureDevOpsCiEnvironmentProvider()

    @Test
    fun does_not_detect_outside_azure() {
        assertNull(provider.detect(emptyMap()))
        assertNull(provider.detect(gitHubPullRequestEnv))
        assertNull(provider.detect(mapOf("TF_BUILD" to "false")))
    }

    @Test
    fun maps_a_push_build() {
        val context = provider.detect(azurePushEnv)!!

        assertEquals("azure-devops", context.provider)
        assertEquals("17", context.pipelineId)
        assertEquals("android-ci", context.pipelineName)
        assertEquals("20260702", context.runId)
        assertEquals("8f6a1c2e-job", context.jobId)
        assertEquals("Build", context.stageId)
        assertEquals("main", context.branch)
        assertEquals("cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a", context.commitSha)
        assertNull(context.pullRequestId)
        assertNull(context.targetBranch)
        assertEquals("https://dev.azure.com/acme/mobile/_build/results?buildId=20260702", context.buildUrl)
        assertEquals("Hosted Agent 03", context.agentName)
    }

    @Test
    fun falls_back_to_system_definition_name() {
        val context = provider.detect(
            azurePushEnv - "BUILD_DEFINITIONNAME" + mapOf("SYSTEM_DEFINITIONNAME" to "legacy-name"),
        )!!

        assertEquals("legacy-name", context.pipelineName)
    }

    @Test
    fun maps_pull_request_fields_with_logical_branch() {
        val context = provider.detect(
            azurePushEnv + mapOf(
                "BUILD_SOURCEBRANCH" to "refs/pull/7/merge",
                "SYSTEM_PULLREQUEST_SOURCEBRANCH" to "refs/heads/feature/speedup",
                "SYSTEM_PULLREQUEST_PULLREQUESTID" to "7",
                "SYSTEM_PULLREQUEST_TARGETBRANCH" to "refs/heads/main",
            ),
        )!!

        // The synthetic refs/pull/7/merge ref must not surface as the branch.
        assertEquals("feature/speedup", context.branch)
        assertEquals("7", context.pullRequestId)
        assertEquals("main", context.targetBranch)
    }

    @Test
    fun falls_back_to_pull_request_number_for_github_hosted_repos() {
        val context = provider.detect(
            azurePushEnv + mapOf("SYSTEM_PULLREQUEST_PULLREQUESTNUMBER" to "41"),
        )!!

        assertEquals("41", context.pullRequestId)
    }

    @Test
    fun strips_tag_refs() {
        val context = provider.detect(azurePushEnv + mapOf("BUILD_SOURCEBRANCH" to "refs/tags/v1.0"))!!

        assertEquals("v1.0", context.branch)
    }

    @Test
    fun percent_encodes_project_names_in_build_url() {
        val context = provider.detect(azurePushEnv + mapOf("SYSTEM_TEAMPROJECT" to "My Project"))!!

        assertEquals("https://dev.azure.com/acme/My%20Project/_build/results?buildId=20260702", context.buildUrl)
    }

    @Test
    fun build_url_is_omitted_when_components_are_missing() {
        val context = provider.detect(azurePushEnv - "SYSTEM_TEAMPROJECT")!!

        assertNull(context.buildUrl)
    }

    @Test
    fun build_url_is_omitted_for_non_http_collection_uri() {
        val context = provider.detect(
            azurePushEnv + mapOf("SYSTEM_COLLECTIONURI" to "javascript:alert(1)//"),
        )!!

        assertNull(context.buildUrl)
    }
}

class GitHubActionsCiEnvironmentProviderTest {

    private val provider = GitHubActionsCiEnvironmentProvider()

    @Test
    fun does_not_detect_outside_github_actions() {
        assertNull(provider.detect(emptyMap()))
        assertNull(provider.detect(azurePushEnv))
        assertNull(provider.detect(mapOf("GITHUB_ACTIONS" to "")))
    }

    @Test
    fun maps_a_pull_request_build() {
        val context = provider.detect(gitHubPullRequestEnv)!!

        assertEquals("github-actions", context.provider)
        // The @<ref> suffix is unstable across runs and must be stripped.
        assertEquals("acme/app/.github/workflows/ci.yml", context.pipelineId)
        assertEquals("CI", context.pipelineName)
        assertEquals("28594986517", context.runId)
        assertEquals("build", context.jobId)
        // Logical branch is the PR head, not the synthetic merge ref.
        assertEquals("feature/speedup", context.branch)
        assertEquals("2", context.pullRequestId)
        assertEquals("main", context.targetBranch)
        assertEquals("e1b53e1a2b3c4d5e6f708192a3b4c5d6e7f80912", context.commitSha)
        assertEquals("https://github.com/acme/app/actions/runs/28594986517", context.buildUrl)
        assertEquals("GitHub Actions 12", context.agentName)
    }

    @Test
    fun maps_a_push_build_from_ref_name() {
        val context = provider.detect(
            gitHubPullRequestEnv - "GITHUB_HEAD_REF" - "GITHUB_BASE_REF" +
                mapOf("GITHUB_REF" to "refs/heads/main", "GITHUB_REF_NAME" to "main"),
        )!!

        assertEquals("main", context.branch)
        assertNull(context.pullRequestId)
        assertNull(context.targetBranch)
    }

    @Test
    fun treats_empty_head_ref_as_absent() {
        val context = provider.detect(
            gitHubPullRequestEnv +
                mapOf("GITHUB_HEAD_REF" to "", "GITHUB_REF" to "refs/heads/main", "GITHUB_REF_NAME" to "main"),
        )!!

        assertEquals("main", context.branch)
    }

    @Test
    fun build_url_is_omitted_when_components_are_missing() {
        val context = provider.detect(gitHubPullRequestEnv - "GITHUB_REPOSITORY")!!

        assertNull(context.buildUrl)
    }

    @Test
    fun build_url_is_omitted_for_non_http_server_url() {
        val context = provider.detect(
            gitHubPullRequestEnv + mapOf("GITHUB_SERVER_URL" to "javascript:alert(1)//"),
        )!!

        assertNull(context.buildUrl)
    }
}

class CiEnvironmentDetectionTest {

    @Test
    fun returns_null_when_no_provider_matches() {
        assertNull(CiEnvironment.detect(mapOf("CI" to "true")))
    }

    @Test
    fun built_in_wins_over_generic_when_both_match() {
        val context = CiEnvironment.detect(azurePushEnv + mapOf("BUILDHOUND_CI_PROVIDER" to "my-ci"))!!

        assertEquals("azure-devops", context.provider)
    }

    @Test
    fun extra_providers_run_after_built_ins_and_before_generic() {
        val extra = object : CiEnvironmentProvider {
            override val id: String = "custom"
            override fun detect(env: Map<String, String>): CiContext? =
                if (env.containsKey("CUSTOM_CI")) CiContext(provider = id) else null
        }
        val env = mapOf("CUSTOM_CI" to "1", "BUILDHOUND_CI_PROVIDER" to "my-ci")

        assertEquals("custom", CiEnvironment.detect(env, listOf(extra))?.provider)
        assertEquals("azure-devops", CiEnvironment.detect(azurePushEnv + env, listOf(extra))?.provider)
    }

    @Test
    fun falls_back_to_generic_provider() {
        val context = CiEnvironment.detect(mapOf("BUILDHOUND_CI" to "true"))!!

        assertEquals("generic", context.provider)
    }
}
