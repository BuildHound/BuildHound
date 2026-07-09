package dev.buildhound.gradle

import java.io.File
import java.nio.file.Files
import org.gradle.testkit.runner.GradleRunner

/**
 * Points the runner at a per-call-unique TestKit dir that lives **outside** the JUnit
 * `@TempDir` project dir (plan 049).
 *
 * The TestKit daemon lingers after `.build()` and keeps files open under its working dir. When
 * that dir sat inside `projectDir` (the `@TempDir`), JUnit's post-test `@TempDir` deletion hit
 * the daemon's open handles and threw `TempDirDeletionStrategy$DeletionException` on macOS and
 * Windows (Linux's POSIX unlink is immune). Relocating the dir keeps `projectDir` free of
 * daemon-held files, so its deletion always succeeds.
 *
 * A **fresh** (unique-every-call) dir is deliberate: these tests inject env via
 * `withEnvironment`, and TestKit daemon selection ignores env differences — a reused daemon
 * would serve a stale environment (or, across a rebuild, a stale plugin classpath). A unique dir
 * forces a new daemon that re-reads the test's injected env.
 */
internal fun GradleRunner.freshDaemon(): GradleRunner = withTestKitDir(newTestKitDir())

/**
 * A fresh TestKit dir under `buildhound.testkit.root` (set by the `functionalTest` task to the
 * module `build/` dir so `clean` reclaims the lingering daemons), falling back to the JVM temp
 * dir when the property is absent.
 */
private fun newTestKitDir(): File {
    val root = File(System.getProperty("buildhound.testkit.root") ?: System.getProperty("java.io.tmpdir"))
    root.mkdirs()
    return Files.createTempDirectory(root.toPath(), "testkit-").toFile()
}

/**
 * The CI + AI-agent environment markers stripped by [neutralCiEnv] so an inner TestKit build's
 * BuildHound CI/agent detection is steered only by what the test injects — never by the *outer*
 * CI runner the suite happens to execute on. Mirrors the marker set proven in
 * `CiEnvironmentBreadthFunctionalTest`; kept here so any test needing a CI-neutral inner build can
 * share one authoritative list.
 */
internal val CI_ENV_MARKERS: Set<String> = setOf(
    "CI", "GITHUB_ACTIONS", "TF_BUILD", "GITLAB_CI", "JENKINS_URL", "TEAMCITY_VERSION", "CIRCLECI",
    "CIRCLE_BUILD_URL", "bamboo_resultsUrl", "TRAVIS_JOB_ID", "BITRISE_BUILD_URL", "GO_SERVER_URL",
    "BUILDKITE", "CLAUDECODE", "CODEX_THREAD_ID", "CODEX_SANDBOX_NETWORK_DISABLED", "CURSOR_AGENT",
    "OPENCODE", "GEMINI_CLI", "ANDROID_STUDIO_AGENT",
)

/**
 * A copy of the current process environment with every CI/agent marker (and `BUILDHOUND_*` override)
 * removed, for `GradleRunner.withEnvironment(...)`. Without this an inner TestKit build inherits the
 * outer runner's `GITHUB_ACTIONS`/`GITHUB_BASE_REF`, which makes BuildHound's CI detection fire (e.g.
 * `ci.targetBranch`), diverging inner-build behaviour between a local run and a CI run — a
 * portability trap, not a real signal. Combine with [freshDaemon] so a fresh daemon actually reads
 * the injected environment (TestKit daemon selection ignores env differences, so a reused daemon
 * could otherwise serve the outer CI env).
 */
internal fun neutralCiEnv(): Map<String, String> =
    System.getenv().filterKeys { it !in CI_ENV_MARKERS && !it.startsWith("BUILDHOUND_") }
