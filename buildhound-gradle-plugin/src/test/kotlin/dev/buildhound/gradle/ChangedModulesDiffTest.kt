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

/**
 * The exact `git diff --name-only --relative --end-of-options <revspec>` command
 * [ChangedModulesValueSource] runs (plan 063; `--end-of-options` added by the plan-063 review's
 * option-injection hardening), pinned against real git so a fake cannot fake diff semantics — the
 * [GitExecTest] repository-discovery pattern. Only the diff *command* is under test here (the
 * ValueSource itself is a Gradle `ValueSource`, which the unit `test` source set cannot load); the
 * file→module mapping is covered by [ChangedModuleMapperTest].
 */
@DisabledOnOs(OS.WINDOWS)
class ChangedModulesDiffTest {

    @field:TempDir
    lateinit var dir: File

    @Test
    fun `diff against a recorded sha yields the changed path relative to the root`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val base = initRepoWithCommit(dir)
        // Mutate a tracked file under a subdirectory (the module-dir shape).
        File(dir, "app/src/A.txt").writeText("changed")

        val result = GitExec.run(dir, 10_000, listOf("diff", "--name-only", "--relative", "--end-of-options", base))

        val success = assertIs<BoundedExec.Result.Success>(result)
        val files = success.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        assertEquals(listOf("app/src/A.txt"), files, "--relative yields the path relative to the git root")
    }

    @Test
    fun `a missing base ref yields NonZeroExit (degrades to null in the ValueSource)`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        initRepoWithCommit(dir)

        // origin/does-not-exist is never fetched — the dominant CI-sparsity case.
        val result = GitExec.run(
            dir, 10_000,
            listOf("diff", "--name-only", "--relative", "--end-of-options", "origin/nope...HEAD"),
        )

        assertIs<BoundedExec.Result.NonZeroExit>(result)
    }

    @Test
    fun `an unchanged working tree yields empty output (a resolvable base with no changes)`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val base = initRepoWithCommit(dir)

        val result = GitExec.run(dir, 10_000, listOf("diff", "--name-only", "--relative", "--end-of-options", base))

        val success = assertIs<BoundedExec.Result.Success>(result)
        assertEquals("", success.stdout.trim())
    }

    @Test
    fun `a flag-injection-shaped revspec is rejected rather than executed as a git option`() {
        // A corrupted last-built-sha file (or a targetBranch ever losing its origin/ prefix upstream)
        // could hand runGit a revspec that looks like a git option. Without --end-of-options this is a
        // real arbitrary-file-write: `--output=<path>` makes git write its diff output to that path
        // instead of failing. --end-of-options must force it to be parsed as a (nonexistent) revision.
        assumeTrue(gitAvailable(), "git not on PATH")
        initRepoWithCommit(dir)
        val target = File(dir, "pwned.txt")

        val result = GitExec.run(
            dir, 10_000,
            listOf("diff", "--name-only", "--relative", "--end-of-options", "--output=${target.absolutePath}"),
        )

        assertIs<BoundedExec.Result.NonZeroExit>(result)
        assertTrue(!target.exists(), "the injected --output flag must never be honored")
    }

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    /** git init + one commit (a tracked `app/src/A.txt`); returns the commit sha. */
    private fun initRepoWithCommit(root: File): String {
        fun git(vararg a: String): String {
            val p = ProcessBuilder(listOf("git") + a).directory(root).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed: $out" }
            return out.trim()
        }
        git("init", "--initial-branch=main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "T")
        File(root, "app/src").mkdirs()
        File(root, "app/src/A.txt").writeText("base")
        git("add", ".")
        git("commit", "-m", "init")
        return git("rev-parse", "HEAD")
    }
}
