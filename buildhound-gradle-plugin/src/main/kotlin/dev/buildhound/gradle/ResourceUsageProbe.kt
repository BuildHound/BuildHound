package dev.buildhound.gradle

import java.io.Serializable
import java.lang.management.ManagementFactory

/**
 * The plan-104 resource-usage DTO: the daemon's CPU consumption over the execution window plus
 * end-of-build system point samples. Plain `Serializable`, mapped onto `ResourceUsageInfo` by
 * [PayloadAssembler] — same shape as [CollectedEnvironment]/[CollectedProcess].
 */
data class CollectedResourceUsage(
    val windowMs: Long? = null,
    val daemonCpuMs: Long? = null,
    val systemCpuLoadPct: Int? = null,
    val systemLoadAverage: Double? = null,
    val systemMemTotalMb: Long? = null,
    val systemMemFreeMb: Long? = null,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Machine resource usage from JVM management beans (plan 104).
 *
 * **Why there is no sampler here.** The feature request's binding constraint was that this must not
 * cost build performance, and a periodic sampler is the one design that inherently does — it adds a
 * thread to a long-lived shared daemon and a syscall every interval. So the whole probe is *two*
 * readings: a baseline stamped by [DaemonState.executionStarted] (the build service's own
 * instantiation, a hook the plugin already owned) and one end sample taken in the finalizer, where
 * the plugin already runs. Nothing here is called a "peak" — a two-point measurement cannot see one.
 *
 * `MemoryPoolMXBean.resetPeakUsage()` would give a real peak and is deliberately **not** used: it
 * mutates JVM-global state inside a daemon that other tooling also observes.
 *
 * Every read is individually guarded and degrades to null. A JVM without the `com.sun.management`
 * extension (a non-HotSpot runtime) simply reports fewer fields; nothing here can fail a build.
 *
 * **Known scope limit (composite builds).** The baseline is stamped from the collector build
 * service's instantiation, which in a composite topology an *included* build's task completion can
 * trigger while the **root** build is still configuring. On such a build the window would include
 * some root configuration time under a label that says it does not. The bias direction is
 * indeterminate — numerator and denominator are padded from the same early anchor — and this is the
 * pre-existing plan-064 anchor's behaviour, not a new mechanism; plan 064's own consumer is gated on
 * a configuration-cache HIT (where configuration is skipped) and so never met it. Recorded rather
 * than silently inherited; untested for composites.
 */
internal object ResourceUsageProbe {

    private const val NANOS_PER_MILLI = 1_000_000L
    private const val BYTES_PER_MIB = 1_048_576L
    private const val PERCENT = 100.0

    /**
     * Cumulative CPU time of *this* JVM (the Gradle daemon) in nanoseconds; null when unavailable.
     * The bean returns a negative when the platform cannot measure it — mapped to null, never kept
     * as a negative that would later subtract into a nonsense delta.
     */
    fun processCpuNanos(): Long? = runCatching {
        sunBean()?.processCpuTime?.takeIf { it >= 0 }
    }.getOrNull()

    /**
     * Combines the [baseline] with an end sample taken now.
     *
     * **Returns null when the window could not be measured** — i.e. the execution anchor never
     * fired, on a zero-task or `--dry-run` build. The system point samples below are readable on
     * essentially any JVM, so gating on "every field degraded" would have made this block *always*
     * non-null and quietly broken the documented contract that `resourceUsage == null` means "no
     * execution-window data" (caught in review). The window is what the block is *for*; four system
     * readings taken at the finalizer of a build that executed nothing are not a substitute.
     *
     * The CPU delta is emitted only when both endpoints exist and the reading did not go backwards
     * (a daemon restart between the two would do that) — it can be null while the window is not, and
     * `DerivedMetricsCalculator.daemonCpuUtilization` already refuses to divide without both.
     *
     * Each read is individually guarded, so one unreadable bean drops one field rather than
     * discarding the window that was already measured.
     */
    fun collect(baseline: DaemonState.ResourceBaseline): CollectedResourceUsage? {
        val endNanos = System.nanoTime()
        val start = baseline.startNanos ?: return null
        val windowMs = ((endNanos - start) / NANOS_PER_MILLI).coerceAtLeast(0L)
        val daemonCpuMs = baseline.startCpuNanos?.let { startCpu ->
            processCpuNanos()?.takeIf { it >= startCpu }?.let { (it - startCpu) / NANOS_PER_MILLI }
        }
        return CollectedResourceUsage(
            windowMs = windowMs,
            daemonCpuMs = daemonCpuMs,
            systemCpuLoadPct = read { sunBean()?.cpuLoad?.takeIf { !it.isNaN() && it >= 0 } }
                ?.let { Math.round(it * PERCENT).toInt() },
            // Negative means "not available on this platform" (Windows) — an honest null, not a 0.
            systemLoadAverage = read {
                ManagementFactory.getOperatingSystemMXBean().systemLoadAverage.takeIf { it >= 0 }
            },
            systemMemTotalMb = read { sunBean()?.totalMemorySize?.takeIf { it > 0 } }?.div(BYTES_PER_MIB),
            systemMemFreeMb = read { sunBean()?.freeMemorySize?.takeIf { it >= 0 } }?.div(BYTES_PER_MIB),
        )
    }

    private fun <T> read(block: () -> T?): T? = runCatching(block).getOrNull()

    private fun sunBean(): com.sun.management.OperatingSystemMXBean? =
        ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
}
