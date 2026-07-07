package dev.buildhound.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** Fake-executable fixtures are POSIX shell scripts; CI runs ubuntu (plan 015). */
@DisabledOnOs(OS.WINDOWS)
class GitExecTest {

    @field:TempDir
    lateinit var dir: File

    private fun fakeGit(body: String): String {
        val script = File(dir, "fake-git")
        script.writeText("#!/bin/sh\n$body\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    @Test
    fun `captures stdout on exit zero`() {
        val exe = fakeGit("printf 'main\\n'")
        assertEquals(
            BoundedExec.Result.Success("main\n"),
            GitExec.run(dir, 10_000, listOf("rev-parse"), executable = exe),
        )
    }

    @Test
    fun `nonzero exit yields NonZeroExit`() {
        val exe = fakeGit("exit 3")
        assertIs<BoundedExec.Result.NonZeroExit>(GitExec.run(dir, 10_000, listOf("status"), executable = exe))
    }

    @Test
    fun `missing executable yields Failed not an exception`() {
        val exe = File(dir, "no-such-git").absolutePath
        assertIs<BoundedExec.Result.Failed>(GitExec.run(dir, 10_000, listOf("status"), executable = exe))
    }

    @Test
    fun `hung process is killed at the timeout instead of blocking`() {
        val exe = fakeGit("exec sleep 300")
        val startedNs = System.nanoTime()
        val result = GitExec.run(dir, 250, listOf("status"), executable = exe)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertIs<BoundedExec.Result.TimedOut>(result)
        // Far below the fake's 300 s sleep; generous vs. the 250 ms bound to stay unflaky.
        assertTrue(elapsedMs < 30_000, "timed-out probe took $elapsedMs ms")
    }

    @Test
    fun `output past the capture cap is drained without deadlock`() {
        // 2 MiB — beyond the OS pipe buffer AND the capture cap: the child can only
        // exit (and this test only pass) if the reader keeps draining past the cap.
        val exe = fakeGit("dd if=/dev/zero bs=1024 count=2048 2>/dev/null | tr '\\0' 'x'")
        val result = GitExec.run(dir, 30_000, listOf("status"), executable = exe)
        val success = assertIs<BoundedExec.Result.Success>(result)
        assertEquals(GitExec.MAX_CAPTURED_BYTES, success.stdout.length)
    }

    // --- Repository discovery scope (plan 050): real git, so a fake cannot fake `.git` discovery. ---

    @Test
    fun `discovers the enclosing repo from a subdirectory by default`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        initRepo(dir)
        val nested = File(dir, "a/b").apply { mkdirs() }

        val result = GitExec.run(nested, 10_000, listOf("rev-parse", "--abbrev-ref", "HEAD"))

        val success = assertIs<BoundedExec.Result.Success>(result)
        assertEquals("main", success.stdout.trim(), "a nested dir must resolve the enclosing repo's branch")
    }

    @Test
    fun `searchParents false confines discovery to the working directory`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        initRepo(dir)
        val nested = File(dir, "a/b").apply { mkdirs() }

        // Ceiling at nested's parent stops git before it can reach the repo at `dir`.
        val result = GitExec.run(nested, 10_000, listOf("rev-parse", "--abbrev-ref", "HEAD"), searchParents = false)

        assertIs<BoundedExec.Result.NonZeroExit>(result)
    }

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    /** git init + one commit at [root], initial branch `main` (git >= 2.28, as the functional tests assume). */
    private fun initRepo(root: File) {
        fun git(vararg a: String) {
            val p = ProcessBuilder(listOf("git") + a).directory(root).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed" }
        }
        git("init", "--initial-branch=main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "T")
        File(root, "f.txt").writeText("x")
        git("add", ".")
        git("commit", "-m", "init")
    }
}
