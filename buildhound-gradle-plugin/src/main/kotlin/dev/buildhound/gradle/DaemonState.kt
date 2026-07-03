package dev.buildhound.gradle

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * v0 heuristics for daemon reuse and configuration-cache outcome (spec §3.2 allows
 * "start parameters + heuristics; refined later"). State is per plugin-classloader per
 * daemon JVM: `apply()` marks that configuration ran; the finalizer consumes the mark.
 *
 * - finalizer execution #2+ in the same daemon → the daemon was reused;
 * - CC requested and configuration ran since the previous finalizer execution → the
 *   entry was (re)stored this build (`MISS_STORED`); CC requested and configuration was
 *   skipped → `HIT`.
 * - configuration duration (plan 016): `apply()` marks a start nanotime, the task-graph
 *   `whenReady` callback marks the end; the finalizer reads the delta. Null unless both
 *   marks are present and ordered — a CC hit skips configuration so neither fires (the
 *   finalizer maps that to 0). Measurement starts at settings-plugin apply, so
 *   init-script and settings-compile time are excluded (stated approximation).
 *
 * Known limit: a daemon alternating between different builds can misattribute a mark;
 * accepted for v0 and documented in plan 003.
 */
internal object DaemonState {

    private const val UNSET = Long.MIN_VALUE

    private val executions = AtomicInteger()
    private val configuredSinceLastExecution = AtomicBoolean(false)
    private val configStartNanos = AtomicLong(UNSET)
    private val configEndNanos = AtomicLong(UNSET)

    fun configurationRan() {
        configuredSinceLastExecution.set(true)
        // Fresh config phase: stamp the start and clear any stale end from a prior build.
        configStartNanos.set(System.nanoTime())
        configEndNanos.set(UNSET)
    }

    /** Called from the task-graph `whenReady` callback — the end of configuration. */
    fun configurationCompleted() {
        configEndNanos.set(System.nanoTime())
    }

    /** Called once per build from the finalizer; returns and resets the heuristic state. */
    fun executionRan(): Execution {
        val start = configStartNanos.getAndSet(UNSET)
        val end = configEndNanos.getAndSet(UNSET)
        val configurationMs = if (start != UNSET && end != UNSET && end >= start) (end - start) / 1_000_000 else null
        return Execution(
            daemonReused = executions.incrementAndGet() > 1,
            configuredThisBuild = configuredSinceLastExecution.getAndSet(false),
            configurationMs = configurationMs,
        )
    }

    data class Execution(
        val daemonReused: Boolean,
        val configuredThisBuild: Boolean,
        val configurationMs: Long? = null,
    )
}
