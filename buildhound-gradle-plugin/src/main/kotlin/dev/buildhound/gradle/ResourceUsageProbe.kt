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
    /** True when every field degraded — the caller ships null rather than an all-null block. */
    fun isEmpty(): Boolean =
        windowMs == null &&
            daemonCpuMs == null &&
            systemCpuLoadPct == null &&
            systemLoadAverage == null &&
            systemMemTotalMb == null &&
            systemMemFreeMb == null

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
     * Combines the [baseline] with an end sample taken now. Returns null when *nothing* could be
     * read, so an all-null block never ships as if it were data.
     *
     * The CPU delta is emitted only when both endpoints exist and the reading did not go backwards
     * (a daemon restart between the two would do that). The window is emitted independently of the
     * CPU delta: knowing the measurement window is useful even when the CPU half degraded, and
     * `DerivedMetricsCalculator.daemonCpuUtilization` already refuses to divide without both.
     */
    fun collect(baseline: DaemonState.ResourceBaseline): CollectedResourceUsage? = runCatching {
        val endNanos = System.nanoTime()
        val endCpuNanos = processCpuNanos()
        val windowMs = baseline.startNanos?.let { start ->
            ((endNanos - start) / NANOS_PER_MILLI).coerceAtLeast(0L)
        }
        val daemonCpuMs = baseline.startCpuNanos?.let { startCpu ->
            endCpuNanos?.takeIf { it >= startCpu }?.let { (it - startCpu) / NANOS_PER_MILLI }
        }
        val bean = sunBean()
        CollectedResourceUsage(
            windowMs = windowMs,
            daemonCpuMs = daemonCpuMs,
            systemCpuLoadPct = bean?.cpuLoad?.takeIf { !it.isNaN() && it >= 0 }
                ?.let { Math.round(it * PERCENT).toInt() },
            // Negative means "not available on this platform" (Windows) — an honest null, not a 0.
            systemLoadAverage = ManagementFactory.getOperatingSystemMXBean()
                .systemLoadAverage.takeIf { it >= 0 },
            systemMemTotalMb = bean?.totalMemorySize?.takeIf { it > 0 }?.let { it / BYTES_PER_MIB },
            systemMemFreeMb = bean?.freeMemorySize?.takeIf { it >= 0 }?.let { it / BYTES_PER_MIB },
        ).takeUnless { it.isEmpty() }
    }.getOrNull()

    private fun sunBean(): com.sun.management.OperatingSystemMXBean? =
        ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
}
