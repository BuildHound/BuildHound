package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.PayloadScrubber

/**
 * Wall-clock-bounded ingest scrub (whole-branch review, HIGH — DoS backstop).
 *
 * [PayloadScrubber]'s regexes are super-linear per string, and each string is clamped to 8 KiB
 * before they run (plan 076). But since 076 the scrub runs at ingest *before* [dev.buildhound.commons.payload.PayloadCapper]'s
 * count caps, so the **number** of strings scrubbed is unbounded: a single authenticated POST
 * (32 MB gzip → 64 MB inflated) can carry thousands of pathological ~8 KiB strings in per-task
 * `executionReasons`/test messages and pin one Netty worker for many minutes — a multi-tenant CPU
 * DoS that rate limiting cannot stop (one request suffices).
 *
 * This guard caps the *total* scrub CPU any one request may spend. It drives the real
 * [PayloadScrubber] field-by-field via a [PayloadScrubber.FieldScrubber], checking a monotonic
 * deadline ([System.nanoTime]) between fields; once the budget is spent it wholesale-redacts every
 * remaining field to [SENTINEL] instead of running the regex. It **never** char-truncates a value
 * mid-secret (076's invariant — dropping a whole value is safe, splitting one is not) and it fails
 * **closed**: budget-exceeded text is redacted, not stored raw.
 *
 * A legitimate payload never trips this — even `PayloadCaps.DEFAULT.maxTasks` (20 000) tasks of
 * short honest reasons scrub in well under a second, far below [BUDGET_MS]. Only an all-pathological
 * payload reaches the deadline, and it stops within one 8 KiB-clamped string (~2 s worst) of it, so
 * total scrub CPU is bounded to roughly [BUDGET_MS] + one clamped-string worst-case (~5 s).
 *
 * KMP-purity is preserved: `buildhound-commons` gains no clock dependency; all timing and
 * non-determinism live in [TimedFieldScrubber] here, on the JVM server only.
 */
object IngestScrub {
    /** Redaction placed on every free-text field scrubbed after the budget is spent (a visible signal). */
    const val SENTINEL: String = "<redacted:scrub-timeout>"

    /** Total wall-clock scrub CPU one ingest request may spend before remaining fields are redacted. */
    const val BUDGET_MS: Long = 3_000L

    data class Result(val payload: BuildPayload, val budgetExceeded: Boolean)

    /**
     * Runs the defensive ingest scrub under a wall-clock budget. [projectRoots] is `emptyList()` in
     * production (the server has no notion of the client's project root — see `Routes.kt`).
     */
    fun scrub(payload: BuildPayload, projectRoots: List<String>, budgetMs: Long = BUDGET_MS): Result {
        val scrubber = TimedFieldScrubber(budgetMs)
        val scrubbed = PayloadScrubber.scrub(payload, projectRoots, scrubber)
        return Result(scrubbed, scrubber.exceeded)
    }

    /**
     * Runs the pure scrubber per field until the deadline, then redacts wholesale. Not thread-safe —
     * one instance drives one single-threaded [PayloadScrubber.scrub] pass, which is exactly how it is
     * used (a fresh instance per ingest request).
     */
    private class TimedFieldScrubber(budgetMs: Long) : PayloadScrubber.FieldScrubber {
        private val deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L
        var exceeded = false
            private set

        override fun scrub(text: String, projectRoots: List<String>): String {
            if (exceeded || System.nanoTime() >= deadlineNanos) {
                exceeded = true
                return SENTINEL
            }
            return PayloadScrubber.scrubText(text, projectRoots)
        }
    }
}
