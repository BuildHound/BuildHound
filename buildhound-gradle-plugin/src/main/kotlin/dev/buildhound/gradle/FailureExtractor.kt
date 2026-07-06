package dev.buildhound.gradle

import java.io.Serializable
import java.security.MessageDigest

/**
 * The build-failure detail captured from the Flow API's `buildWorkResult.failure` (plan 044),
 * carried CC-safely to the finalizer as a Flow parameter. Plain `String`s only, so Gradle can
 * isolate/serialize it and the CC entry replays it. `message`/`stackTrace` are raw here —
 * [PayloadScrubber] scrubs and truncates them at assembly; `exceptionClass` is a declared type
 * name; `messageHash` is a SHA-256 of the **raw** message (computed pre-scrub, so it is a stable
 * cross-build correlation key — the `TestCaseDetail.messageHash` rule).
 */
data class CollectedFailure(
    val exceptionClass: String? = null,
    val message: String? = null,
    val messageHash: String? = null,
    val stackTrace: String? = null,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Turns the failing [Throwable] into a serializable [CollectedFailure]. Gradle-free, so it unit-tests
 * without the Gradle API on the classpath (the `PayloadAssembler` precedent).
 *
 * Gradle wraps a failed build as `MultipleBuildFailures → TaskExecutionException → real cause`; the
 * diagnostic frames live in the cause chain, not the top frame. `Throwable.stackTraceToString()`
 * virtual-dispatches into `MultipleBuildFailures.printStackTrace`, which renders every cause's full
 * chain — so the raw trace already flattens the aggregate. We additionally join the child messages
 * (reflected via `getCauses()`, never a hard dependency on an internal type) so the one-line message
 * is useful rather than the generic "Build completed with N failures".
 *
 * Everything is failure-tolerant: a hostile `getMessage()`/`printStackTrace` override degrades a
 * field to null and never throws — the finalizer must never fail the build.
 */
internal object FailureExtractor {

    /**
     * Ceiling on the raw stacktrace before it enters the payload pipeline — a runaway trace must not
     * bloat the Flow parameter / CC entry. The scrubber truncates further (~8 KiB) for the wire
     * payload; the local HTML artifact may render up to this ceiling (still scrubbed).
     */
    const val RAW_STACKTRACE_CEILING: Int = 64 * 1024

    fun extract(failure: Throwable): CollectedFailure {
        val exceptionClass = runCatching { failure::class.java.name }.getOrNull()
        val causes = multiCauses(failure)
        val message = runCatching {
            if (causes.isNotEmpty()) {
                causes.mapNotNull { it.message?.takeIf(String::isNotBlank) }
                    .joinToString("; ")
                    .ifBlank { failure.message }
            } else {
                failure.message
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val stackTrace = runCatching { failure.stackTraceToString() }
            .getOrNull()
            ?.take(RAW_STACKTRACE_CEILING)
            ?.takeIf { it.isNotBlank() }
        return CollectedFailure(
            exceptionClass = exceptionClass,
            message = message,
            // Hash the raw message BEFORE the scrubber truncates/redacts it, so the key is stable.
            messageHash = message?.let(::sha256),
            stackTrace = stackTrace,
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { byte -> ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    /**
     * A `MultipleBuildFailures` / `DefaultMultiCauseException` exposes `getCauses(): List<Throwable>`.
     * Reflected so this stays Gradle-free and version-robust — a missing method yields no child
     * messages (the flattened stacktrace still carries the detail), never a throw.
     */
    private fun multiCauses(failure: Throwable): List<Throwable> = runCatching {
        val result = failure.javaClass.getMethod("getCauses").invoke(failure)
        @Suppress("UNCHECKED_CAST")
        (result as? List<Throwable>).orEmpty()
    }.getOrDefault(emptyList())
}
