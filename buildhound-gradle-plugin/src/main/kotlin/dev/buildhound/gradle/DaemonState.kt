package dev.buildhound.gradle

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v0 heuristics for daemon reuse and configuration-cache outcome (spec §3.2 allows
 * "start parameters + heuristics; refined later"). State is per plugin-classloader per
 * daemon JVM: `apply()` marks that configuration ran; the finalizer consumes the mark.
 *
 * - finalizer execution #2+ in the same daemon → the daemon was reused;
 * - CC requested and configuration ran since the previous finalizer execution → the
 *   entry was (re)stored this build (`MISS_STORED`); CC requested and configuration was
 *   skipped → `HIT`.
 *
 * Known limit: a daemon alternating between different builds can misattribute a mark;
 * accepted for v0 and documented in plan 003.
 */
internal object DaemonState {

    private val executions = AtomicInteger()
    private val configuredSinceLastExecution = AtomicBoolean(false)

    fun configurationRan() {
        configuredSinceLastExecution.set(true)
    }

    /** Called once per build from the finalizer; returns and resets the heuristic state. */
    fun executionRan(): Execution = Execution(
        daemonReused = executions.incrementAndGet() > 1,
        configuredThisBuild = configuredSinceLastExecution.getAndSet(false),
    )

    data class Execution(val daemonReused: Boolean, val configuredThisBuild: Boolean)
}
