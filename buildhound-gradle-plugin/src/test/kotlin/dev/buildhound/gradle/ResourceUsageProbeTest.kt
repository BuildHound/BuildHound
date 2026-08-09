package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan-104 two-point probe against the real management beans. The system point samples are
 * whatever this host reports, so the assertions pin the *contract* — which fields exist, and that
 * every degradation path yields null rather than a fabricated zero — not specific values.
 */
class ResourceUsageProbeTest {

    @Test
    fun `a stamped baseline yields a window and a non-negative cpu delta`() {
        val baseline = DaemonState.ResourceBaseline(
            startNanos = System.nanoTime(),
            startCpuNanos = ResourceUsageProbe.processCpuNanos(),
        )
        // Burn a little CPU so the delta has something to measure on a fast machine.
        var sink = 0L
        repeat(2_000_000) { sink += it.toLong() }
        assertTrue(sink > 0)

        val usage = assertNotNull(ResourceUsageProbe.collect(baseline))
        val windowMs = assertNotNull(usage.windowMs)
        assertTrue(windowMs >= 0, "window must never be negative, was $windowMs")
        // The CPU half is absent only on a JVM without com.sun.management; where present it must be
        // a non-negative delta, never the bean's cumulative lifetime total.
        usage.daemonCpuMs?.let { assertTrue(it >= 0, "cpu delta must never be negative, was $it") }
    }

    @Test
    fun `a never-stamped baseline reports no window and no cpu delta, not zeroes`() {
        val usage = ResourceUsageProbe.collect(DaemonState.ResourceBaseline())

        // A build that never instantiated the collector (zero tasks, --dry-run) has no window. The
        // block may still exist for the system point samples — but the unmeasured halves stay null.
        assertNull(usage?.windowMs)
        assertNull(usage?.daemonCpuMs)
    }

    @Test
    fun `a cpu reading that went backwards is dropped rather than wrapped into a huge delta`() {
        // A daemon restart between the two samples would make the end reading lower than the
        // baseline; the guard must drop the field instead of emitting a negative or a wrapped value.
        val usage = ResourceUsageProbe.collect(
            DaemonState.ResourceBaseline(startNanos = System.nanoTime(), startCpuNanos = Long.MAX_VALUE),
        )

        assertNull(usage?.daemonCpuMs)
        assertNotNull(usage?.windowMs, "the window survives a degraded cpu half")
    }

    @Test
    fun `point samples are within their declared ranges when present`() {
        val usage = assertNotNull(ResourceUsageProbe.collect(DaemonState.ResourceBaseline(startNanos = System.nanoTime())))

        usage.systemCpuLoadPct?.let { assertTrue(it in 0..100, "cpu load percent out of range: $it") }
        usage.systemLoadAverage?.let { assertTrue(it >= 0, "a negative load average must be null, was $it") }
        usage.systemMemFreeMb?.let { free ->
            usage.systemMemTotalMb?.let { total -> assertTrue(free <= total, "free ($free) exceeded total ($total)") }
        }
    }
}
