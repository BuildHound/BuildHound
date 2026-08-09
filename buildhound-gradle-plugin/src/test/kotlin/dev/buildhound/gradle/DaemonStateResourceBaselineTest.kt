package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan-104 baseline's read-and-reset contract. `DaemonState` is per-daemon state that outlives a
 * single build, so the deterministic hazard is a baseline from build N surviving into build N+1 —
 * which would report a window spanning both. `executionRan()` must consume it exactly once.
 *
 * Tests run in one JVM against this shared object, so each one stamps and drains explicitly rather
 * than assuming a clean slate.
 */
class DaemonStateResourceBaselineTest {

    @Test
    fun `a stamped baseline is returned once and reset, so it cannot leak into the next build`() {
        DaemonState.executionStarted()

        val first = DaemonState.executionRan().resourceBaseline
        assertNotNull(first.startNanos, "the wall baseline must be stamped by executionStarted()")

        // The next build in this daemon has not stamped yet — it must see nothing, not build N's
        // anchor. A leaked baseline is exactly the bug that would make a CC-hit build report a
        // window spanning the previous build and the gap since.
        val second = DaemonState.executionRan().resourceBaseline
        assertNull(second.startNanos, "the wall baseline must be consumed, not left behind")
        assertNull(second.startCpuNanos, "the cpu baseline must be consumed, not left behind")
    }

    @Test
    fun `re-stamping replaces the previous build's baseline rather than compounding it`() {
        DaemonState.executionStarted()
        val stale = assertNotNull(DaemonState.executionRan().resourceBaseline.startNanos)

        DaemonState.executionStarted()
        val fresh = assertNotNull(DaemonState.executionRan().resourceBaseline.startNanos)

        assertTrue(fresh >= stale, "a re-stamp must move the anchor forward, not restore an older one")
    }

    @Test
    fun `an unstamped baseline yields no usage block rather than a zero-length window`() {
        DaemonState.executionRan() // drain whatever a previous test left

        val usage = ResourceUsageProbe.collect(DaemonState.executionRan().resourceBaseline)

        assertNull(usage, "a build that never stamped must report no usage block, not a 0 ms window")
    }
}
