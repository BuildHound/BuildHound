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
    private val executionStartedMs = AtomicLong(UNSET)

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

    /**
     * Anchor for the CC entry-load proxy (plan 064): stamped from the [TaskEventCollector] service's
     * own instantiation — the first plugin-controlled instant of execution, which on a CC **hit** is
     * the moment right after the CC entry is deserialized (configuration is skipped, so no earlier hook
     * runs). Recorded as `System.currentTimeMillis()` (not `nanoTime`) deliberately: the interval is
     * measured against a task's `TaskFinishEvent` start time, which Gradle reports in epoch
     * milliseconds — the two clocks must match to subtract, so the monotonic reading is unusable here
     * (plan-064 divergence from its own `nanoTime` §Design note; same wall-clock tradeoff the plan-066
     * `jvmStartMs` anchor accepts). The build service is fresh per build (like [TaskEventCollector.buildId]),
     * so this is stamped once per build; [executionRan] reads and resets it.
     */
    fun executionStarted() {
        executionStartedMs.set(System.currentTimeMillis())
    }

    /** Called once per build from the finalizer; returns and resets the heuristic state. */
    fun executionRan(): Execution {
        val start = configStartNanos.getAndSet(UNSET)
        val end = configEndNanos.getAndSet(UNSET)
        val configurationMs = if (start != UNSET && end != UNSET && end >= start) (end - start) / 1_000_000 else null
        val executionStarted = executionStartedMs.getAndSet(UNSET).takeUnless { it == UNSET }
        return Execution(
            daemonReused = executions.incrementAndGet() > 1,
            configuredThisBuild = configuredSinceLastExecution.getAndSet(false),
            configurationMs = configurationMs,
            executionStartedMs = executionStarted,
        )
    }

    data class Execution(
        val daemonReused: Boolean,
        val configuredThisBuild: Boolean,
        val configurationMs: Long? = null,
        /** Epoch-ms anchor for the CC entry-load proxy (plan 064); null when never stamped this build. */
        val executionStartedMs: Long? = null,
    )
}
