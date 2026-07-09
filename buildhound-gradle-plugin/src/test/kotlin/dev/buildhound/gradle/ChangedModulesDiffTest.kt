package dev.buildhound.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * The exact `git diff --name-only --relative <revspec>` command [ChangedModulesValueSource] runs
 * (plan 063), pinned against real git so a fake cannot fake diff semantics — the [GitExecTest]
 * repository-discovery pattern. Only the diff *command* is under test here (the ValueSource itself is a
 * Gradle `ValueSource`, which the unit `test` source set cannot load); the file→module mapping is
 * covered by [ChangedModuleMapperTest].
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

        val result = GitExec.run(dir, 10_000, listOf("diff", "--name-only", "--relative", base))

        val success = assertIs<BoundedExec.Result.Success>(result)
        val files = success.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        assertEquals(listOf("app/src/A.txt"), files, "--relative yields the path relative to the git root")
    }

    @Test
    fun `a missing base ref yields NonZeroExit (degrades to null in the ValueSource)`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        initRepoWithCommit(dir)

        // origin/does-not-exist is never fetched — the dominant CI-sparsity case.
        val result = GitExec.run(dir, 10_000, listOf("diff", "--name-only", "--relative", "origin/nope...HEAD"))

        assertIs<BoundedExec.Result.NonZeroExit>(result)
    }

    @Test
    fun `an unchanged working tree yields empty output (a resolvable base with no changes)`() {
        assumeTrue(gitAvailable(), "git not on PATH")
        val base = initRepoWithCommit(dir)

        val result = GitExec.run(dir, 10_000, listOf("diff", "--name-only", "--relative", base))

        val success = assertIs<BoundedExec.Result.Success>(result)
        assertEquals("", success.stdout.trim())
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
