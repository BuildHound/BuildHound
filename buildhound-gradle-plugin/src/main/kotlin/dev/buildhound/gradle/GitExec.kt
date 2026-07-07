package dev.buildhound.gradle

import java.io.File

/**
 * Bounded-wait runner for the git probes. `ExecOperations.exec` has no timeout, so a
 * hung git — fsmonitor daemon, network worktree, stuck credential helper — would stall
 * the build indefinitely; CCUD bounds every git exec at 10 s with `destroyForcibly` for
 * the same reason (plan 015, architecture §2 rule 11).
 *
 * The bounded-exec mechanics live in [BoundedExec] (shared with the process probe, plan 029);
 * this adds only the git-specific environment. Kept free of Gradle types so the unit `test`
 * source set can pin the timeout behavior directly (same rationale as [VcsParsing]).
 */
internal object GitExec {

    /** Per-probe bound, CCUD parity; `buildhound.vcs.timeout.ms` overrides per build. */
    const val DEFAULT_TIMEOUT_MS = 10_000L

    /** @see BoundedExec.MAX_CAPTURED_BYTES */
    const val MAX_CAPTURED_BYTES = BoundedExec.MAX_CAPTURED_BYTES

    /**
     * [searchParents] (plan 050) controls repository discovery scope. Default `true` lets git walk
     * up from [workDir] to the enclosing `.git` (stopping at a repo boundary or the filesystem
     * root — git's own behavior), so a Gradle root nested inside its repository (included/composite
     * build, monorepo subroot) resolves the repository it actually lives in. `false` restores the
     * pre-050 fail-closed ceiling — `GIT_CEILING_DIRECTORIES=<workDir parent>` confines discovery to
     * [workDir] so an enclosing repository is never attributed to the build.
     *
     * [executable] is a seam for the fake-binary tests; production always runs `git`.
     */
    fun run(
        workDir: File,
        timeoutMillis: Long,
        args: List<String>,
        searchParents: Boolean = true,
        executable: String = "git",
    ): BoundedExec.Result {
        val env = buildMap {
            put("GIT_TERMINAL_PROMPT", "0")
            // Never take optional .git/index locks from a telemetry read.
            put("GIT_OPTIONAL_LOCKS", "0")
            if (!searchParents) {
                workDir.parentFile?.let { put("GIT_CEILING_DIRECTORIES", it.absolutePath) }
            }
        }
        return BoundedExec.run(listOf(executable) + args, timeoutMillis, workDir, env)
    }
}
