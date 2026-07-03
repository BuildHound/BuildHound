package dev.buildhound.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bounded-wait runner for the git probes. `ExecOperations.exec` has no timeout, so a
 * hung git — fsmonitor daemon, network worktree, stuck credential helper — would stall
 * the build indefinitely; CCUD bounds every git exec at 10 s with `destroyForcibly` for
 * the same reason (plan 015, architecture §2 rule 11).
 *
 * Kept free of Gradle types so the unit `test` source set can pin the timeout behavior
 * directly (same rationale as [VcsParsing]).
 */
internal object GitExec {

    /** Per-probe bound, CCUD parity; `buildhound.vcs.timeout.ms` overrides per build. */
    const val DEFAULT_TIMEOUT_MS = 10_000L

    /**
     * Probe outputs are tiny (a ref name, a sha, "is status empty"); anything past the
     * cap is drained and discarded so the child can never stall on a full pipe.
     */
    const val MAX_CAPTURED_BYTES = 64 * 1024

    /** After a normal exit, how long the reader may take to flush the pipe's tail. */
    private const val READER_DRAIN_GRACE_MS = 1_000L

    sealed interface Result {
        data class Success(val stdout: String) : Result
        object NonZeroExit : Result
        object TimedOut : Result
        data class Failed(val exceptionClass: String) : Result
    }

    /** [executable] is a seam for the fake-binary tests; production always runs `git`. */
    fun run(workDir: File, timeoutMillis: Long, args: List<String>, executable: String = "git"): Result {
        val process = try {
            val builder = ProcessBuilder(listOf(executable) + args)
                .directory(workDir)
                // stderr can embed paths; it is never read, not even into a dropped buffer.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
            // Never take optional .git/index locks from a telemetry read.
            builder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
            // Never discover an enclosing, unrelated repository above the project.
            workDir.parentFile?.let { builder.environment()["GIT_CEILING_DIRECTORIES"] = it.absolutePath }
            builder.start()
        } catch (e: Exception) {
            return Result.Failed(e::class.java.simpleName)
        }
        try {
            // A prompting git reads EOF instead of waiting on stdin forever.
            process.outputStream.close()
            val stdout = ByteArrayOutputStream()
            val reader = thread(isDaemon = true, name = "buildhound-git-stdout") {
                runCatching {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            val room = MAX_CAPTURED_BYTES - stdout.size()
                            if (room > 0) stdout.write(buffer, 0, minOf(read, room))
                        }
                    }
                }
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return Result.TimedOut
            // join() is the happens-before edge for reading the buffer on this thread.
            reader.join(READER_DRAIN_GRACE_MS)
            return if (process.exitValue() == 0) Result.Success(stdout.toString(Charsets.UTF_8)) else Result.NonZeroExit
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Failed(e::class.java.simpleName)
        } catch (e: Exception) {
            return Result.Failed(e::class.java.simpleName)
        } finally {
            // No-op after a normal exit; SIGKILL for the hung case.
            process.destroyForcibly()
        }
    }
}
