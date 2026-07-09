package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ChangeDiffBase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Change blast-radius attribution end to end (plan 063, research F13): a multi-module build over a real
 * git repo, driven by the `.gradle/buildhound/last-built-sha` diff base the finalizer writes. Real git
 * so a fake cannot fake diff semantics ([GitExecTest] posture); `@DisabledOnOs(WINDOWS)` like the other
 * real-git suites.
 */
@DisabledOnOs(OS.WINDOWS)
class ChangedModulesFunctionalTest {

    @field:org.junit.jupiter.api.io.TempDir
    lateinit var projectDir: File

    /**
     * A stable Gradle User Home for the inner build (the [BuildCacheConfigFunctionalTest] pattern):
     * [freshDaemon] rotates the TestKit dir per call, and without an explicit `-g` that rotating dir
     * IS the GUH — so the store→hit pair in the CC test would run under two different GUHs and the
     * hit build would find no configuration-cache entry. A stable `-g` keeps the CC entry findable.
     */
    @field:org.junit.jupiter.api.io.TempDir
    lateinit var guhDir: File

    /**
     * Neutralize the CI environment for the inner build ([neutralCiEnv] + [freshDaemon]): on a CI
     * runner the inner TestKit build would otherwise inherit `GITHUB_ACTIONS`/`GITHUB_BASE_REF`, so
     * BuildHound's CI detection sets `ci.targetBranch`, and [ChangedModulesValueSource] takes its
     * `origin/<target>...HEAD` CI-PR-base branch — which cannot resolve in these isolated single-repo
     * fixtures (no `origin` remote), degrading the whole block to null. These tests deliberately
     * exercise the `LAST_BUILT_SHA` base, so the inner build must look like a non-CI local build.
     */
    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .freshDaemon()
            .withEnvironment(neutralCiEnv())
            .withArguments(*arguments, "-g", guhDir.absolutePath)

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun lastShaFile(): File = File(projectDir, ".gradle/buildhound/last-built-sha")

    private fun summaryLine(output: String): String =
        output.lineSequence().single { it.startsWith("[buildhound] build ") }

    /** A two-module fixture (`:app`, `:core:common`) with tracked source files, committed as the base. */
    private fun setUpFixtureAndCommit(): String {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "changed-modules-fixture"
            include(":app")
            include(":core:common")
            """.trimIndent(),
        )
        File(projectDir, "app").mkdirs()
        File(projectDir, "app/build.gradle.kts").writeText(
            """tasks.register("work") { doLast { println("app work") } }""",
        )
        File(projectDir, "core/common").mkdirs()
        File(projectDir, "core/common/build.gradle.kts").writeText(
            """tasks.register("work") { doLast { println("common work") } }""",
        )
        // Tracked non-build-script files — modifying these dirties the working tree without
        // invalidating the configuration cache (a build-script edit would).
        File(projectDir, "app/notes.txt").writeText("base")
        File(projectDir, "core/common/notes.txt").writeText("base")
        return initGitRepo()
    }

    @Test
    fun `a seeded last-sha base attributes the touched modules and rewrites the sha`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val base = setUpFixtureAndCommit()
        // Seed the previous-build HEAD, then dirty a file in each module.
        lastShaFile().parentFile.mkdirs()
        lastShaFile().writeText(base)
        File(projectDir, "app/notes.txt").writeText("changed")
        File(projectDir, "core/common/notes.txt").writeText("changed")

        val result = runner(":app:work").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:work")?.outcome)
        val changed = readPayload().changedModules ?: error("expected a changedModules block")
        assertEquals(ChangeDiffBase.LAST_BUILT_SHA, changed.base)
        assertEquals(listOf(":app", ":core:common"), changed.modules, "the two touched modules, distinct + sorted")
        assertEquals(false, changed.unattributedChanges)
        // The finalizer records this build's HEAD as the next build's base (HEAD == base — no new commit).
        assertTrue(lastShaFile().isFile, "the finalizer must write the last-built-sha file")
        assertEquals(base, lastShaFile().readText().trim())
    }

    @Test
    fun `a root-level change attributes to the whole build`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val base = setUpFixtureAndCommit()
        // A root-owned, non-build-script tracked file must be present in the base commit to diff.
        File(projectDir, "docs.txt").writeText("base")
        val rebase = commitAll()
        lastShaFile().parentFile.mkdirs()
        lastShaFile().writeText(rebase)
        File(projectDir, "docs.txt").writeText("changed")

        val result = runner(":app:work").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:work")?.outcome)
        val changed = readPayload().changedModules ?: error("expected a changedModules block")
        // A root-level file has no deeper subproject prefix → the whole-build radius ":".
        assertEquals(listOf(":"), changed.modules)
        assertEquals(false, changed.unattributedChanges, "root legitimately owns non-subproject paths")
        // base is referenced only to force git init; silence the unused warning without a real assert.
        assertTrue(base.isNotEmpty())
    }

    @Test
    fun `no base resolvable leaves the block absent but still records the sha for next time`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        setUpFixtureAndCommit()
        // No last-sha file, no CI target branch → no resolvable base.
        assertTrue(!lastShaFile().exists())

        val result = runner(":app:work").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:work")?.outcome)
        assertNull(readPayload().changedModules, "no resolvable base → the block is absent")
        // The finalizer still records HEAD, so the NEXT build has a LAST_BUILT_SHA base.
        assertTrue(lastShaFile().isFile, "the finalizer records the HEAD sha for the next build")
        assertEquals(40, lastShaFile().readText().trim().length, "a full 40-hex HEAD sha")
    }

    @Test
    fun `the block survives a configuration-cache store then hit`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val base = setUpFixtureAndCommit()
        lastShaFile().parentFile.mkdirs()
        lastShaFile().writeText(base)
        // Dirty an :app file BEFORE the store build and never touch it again — so nothing changes
        // between the two builds and the CC entry genuinely hits (the git diff re-runs at execution).
        File(projectDir, "app/notes.txt").writeText("changed")

        val store = runner(":app:work", "--configuration-cache").build()
        assertTrue(summaryLine(store.output).contains("cc=MISS_STORED"), summaryLine(store.output))
        val stored = readPayload().changedModules ?: error("expected a changedModules block on the store build")
        assertEquals(listOf(":app"), stored.modules)
        assertEquals(ChangeDiffBase.LAST_BUILT_SHA, stored.base)

        // A genuine cc=HIT: obtain() re-executes via the Flow-action param (the VcsValueSource replay
        // caveat), so the git diff is re-run against the baked module-dir index and stays populated.
        val hit = runner(":app:work", "--configuration-cache").build()
        assertTrue(hit.output.contains("Reusing configuration cache"), hit.output)
        assertTrue(summaryLine(hit.output).contains("cc=HIT"), summaryLine(hit.output))
        val replayed = readPayload().changedModules ?: error("expected changedModules to survive a CC hit")
        assertEquals(stored.modules, replayed.modules)
        assertEquals(stored.base, replayed.base)
    }

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun git(vararg a: String): String {
        val p = ProcessBuilder(listOf("git") + a).directory(projectDir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed: $out" }
        return out.trim()
    }

    /** git init + config + one commit of the whole fixture; returns the commit sha. */
    private fun initGitRepo(): String {
        git("init", "--initial-branch=main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "T")
        return commitAll()
    }

    private fun commitAll(): String {
        git("add", ".")
        git("commit", "-m", "snapshot")
        return git("rev-parse", "HEAD")
    }
}
