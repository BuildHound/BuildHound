package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The nine providers added in plan 027 (§4.4), plus first-match order and GHA run-attempt. */
class CiProviderMatrixTest {

    private fun detect(env: Map<String, String>) = CiEnvironment.detect(env)

    @Test
    fun jenkins_maps_and_strips_the_remote_branch_prefix() {
        val ctx = detect(
            mapOf(
                "JENKINS_URL" to "https://ci.example.com/", "JOB_NAME" to "app/main", "BUILD_NUMBER" to "42",
                "GIT_BRANCH" to "origin/feature/x", "GIT_COMMIT" to "a".repeat(40), "BUILD_URL" to "https://ci.example.com/job/42/",
                "CHANGE_ID" to "7", "CHANGE_TARGET" to "main",
            ),
        )!!
        assertEquals("jenkins", ctx.provider)
        assertEquals("app/main", ctx.pipelineName)
        assertEquals("42", ctx.runId)
        assertEquals("feature/x", ctx.branch, "origin/ prefix stripped")
        assertEquals("7", ctx.pullRequestId)
        assertEquals("https://ci.example.com/", ctx.attributes["controllerUrl"])
    }

    @Test
    fun teamcity_is_the_documented_env_only_partial() {
        val ctx = detect(mapOf("TEAMCITY_VERSION" to "2024.1", "TEAMCITY_PROJECT_NAME" to "App", "BUILD_NUMBER" to "9"))!!
        assertEquals("teamcity", ctx.provider)
        assertEquals("App", ctx.pipelineName)
        assertEquals("9", ctx.runId)
        assertNull(ctx.buildUrl, "no composable URL without the properties-file chain")
    }

    @Test
    fun circleci_gitlab_bamboo_travis_bitrise_gocd_buildkite_map() {
        assertEquals("circleci", detect(mapOf("CIRCLECI" to "true", "CIRCLE_BUILD_NUM" to "5"))!!.provider)
        assertEquals("gitlab", detect(mapOf("GITLAB_CI" to "true", "CI_JOB_NAME" to "test"))!!.provider)
        assertEquals("bamboo", detect(mapOf("bamboo_resultsUrl" to "https://b/x", "bamboo_buildNumber" to "3"))!!.provider)
        assertEquals("travis", detect(mapOf("TRAVIS_JOB_ID" to "1", "TRAVIS_BUILD_NUMBER" to "2"))!!.provider)
        assertEquals("bitrise", detect(mapOf("BITRISE_BUILD_URL" to "https://b/x", "BITRISE_BUILD_NUMBER" to "4"))!!.provider)
        assertEquals("gocd", detect(mapOf("GO_SERVER_URL" to "https://go/", "GO_PIPELINE_NAME" to "p"))!!.provider)
        assertEquals("buildkite", detect(mapOf("BUILDKITE" to "true", "BUILDKITE_BUILD_ID" to "id"))!!.provider)
    }

    @Test
    fun gitlab_carries_merge_request_and_pipeline_url_attribute() {
        val ctx = detect(
            mapOf(
                "GITLAB_CI" to "true", "CI_JOB_URL" to "https://gl/job/1", "CI_COMMIT_REF_NAME" to "feature",
                "CI_MERGE_REQUEST_IID" to "12", "CI_MERGE_REQUEST_TARGET_BRANCH_NAME" to "main",
                "CI_PIPELINE_URL" to "https://gl/pipeline/9", "CI_PIPELINE_ID" to "9",
            ),
        )!!
        assertEquals("12", ctx.pullRequestId)
        assertEquals("main", ctx.targetBranch)
        assertEquals("9", ctx.runId)
        assertEquals("https://gl/pipeline/9", ctx.attributes["pipelineUrl"])
    }

    @Test
    fun travis_and_buildkite_drop_the_false_pull_request_sentinel() {
        assertNull(detect(mapOf("TRAVIS_JOB_ID" to "1", "TRAVIS_PULL_REQUEST" to "false"))!!.pullRequestId)
        assertEquals("7", detect(mapOf("TRAVIS_JOB_ID" to "1", "TRAVIS_PULL_REQUEST" to "7"))!!.pullRequestId)
        assertNull(detect(mapOf("BUILDKITE" to "true", "BUILDKITE_PULL_REQUEST" to "false"))!!.pullRequestId)
    }

    @Test
    fun gocd_build_url_needs_every_segment_present() {
        val complete = detect(
            mapOf(
                "GO_SERVER_URL" to "https://go.example.com/", "GO_PIPELINE_NAME" to "app", "GO_PIPELINE_COUNTER" to "10",
                "GO_STAGE_NAME" to "build", "GO_STAGE_COUNTER" to "1", "GO_JOB_NAME" to "compile",
            ),
        )!!
        assertEquals("https://go.example.com/tab/build/detail/app/10/build/1/compile", complete.buildUrl)
        // Missing a segment → no URL, but still detected.
        assertNull(detect(mapOf("GO_SERVER_URL" to "https://go/", "GO_PIPELINE_NAME" to "app"))!!.buildUrl)
    }

    @Test
    fun a_javascript_base_url_never_reaches_a_build_url() {
        assertNull(detect(mapOf("CIRCLECI" to "true", "CIRCLE_BUILD_URL" to "javascript:alert(1)"))!!.buildUrl)
        assertNull(detect(mapOf("BUILDKITE" to "true", "BUILDKITE_BUILD_URL" to "javascript:alert(1)"))!!.buildUrl)
    }

    @Test
    fun a_specific_marker_beats_the_bare_ci_generic_fallback() {
        // GitLab sets both GITLAB_CI and CI=true; the specific provider must win over generic.
        val ctx = detect(mapOf("GITLAB_CI" to "true", "CI" to "true", "CI_JOB_NAME" to "test"))!!
        assertEquals("gitlab", ctx.provider)
        // Nothing specific matches → generic on bare CI.
        assertEquals("generic", detect(mapOf("CI" to "true"))!!.provider)
        // No CI at all → not CI.
        assertNull(detect(emptyMap()))
    }

    @Test
    fun github_run_attempt_appends_the_url_suffix_only_beyond_the_first() {
        val base = mapOf(
            "GITHUB_ACTIONS" to "true", "GITHUB_RUN_ID" to "555", "GITHUB_SERVER_URL" to "https://github.com",
            "GITHUB_REPOSITORY" to "acme/app",
        )
        val first = detect(base + ("GITHUB_RUN_ATTEMPT" to "1"))!!
        assertEquals("https://github.com/acme/app/actions/runs/555", first.buildUrl)
        assertEquals("1", first.attributes["runAttempt"])
        val rerun = detect(base + ("GITHUB_RUN_ATTEMPT" to "2"))!!
        assertEquals("https://github.com/acme/app/actions/runs/555/attempts/2", rerun.buildUrl)
    }
}
