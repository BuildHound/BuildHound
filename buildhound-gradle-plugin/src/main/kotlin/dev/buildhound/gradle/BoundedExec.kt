package dev.buildhound.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The single bounded-wait subprocess runner for all telemetry probes (architecture §2 rule 11 —
 * now covers JDK tools, not just git). `ProcessBuilder` + `waitFor(timeout)` + `destroyForcibly`,
 * a capped/drained stdout reader thread, discarded stderr, and a closed stdin — so a hung or noisy
 * child can never stall the build or fill a pipe. Git-specific concerns (env vars, ceiling dirs)
 * live in [GitExec], which delegates here; the process probe (plan 029) calls this directly.
 *
 * Kept free of Gradle types so the unit `test` source set can pin the timeout/drain behavior.
 */
internal object BoundedExec {

    /** Probe outputs are small; anything past the cap is drained and discarded so the child can't stall. */
    const val MAX_CAPTURED_BYTES = 64 * 1024

    /** After a normal exit, how long the reader may take to flush the pipe's tail. */
    private const val READER_DRAIN_GRACE_MS = 1_000L

    sealed interface Result {
        data class Success(val stdout: String) : Result
        object NonZeroExit : Result
        object TimedOut : Result
        data class Failed(val exceptionClass: String) : Result
    }

    fun run(
        command: List<String>,
        timeoutMillis: Long,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): Result {
        val process = try {
            val builder = ProcessBuilder(command)
                // stderr can embed paths/args; it is never read, not even into a dropped buffer.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            workDir?.let { builder.directory(it) }
            env.forEach { (key, value) -> builder.environment()[key] = value }
            builder.start()
        } catch (e: Exception) {
            return Result.Failed(e::class.java.simpleName)
        }
        try {
            // A prompting child reads EOF instead of waiting on stdin forever.
            process.outputStream.close()
            val stdout = ByteArrayOutputStream()
            val reader = thread(isDaemon = true, name = "buildhound-exec-stdout") {
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
