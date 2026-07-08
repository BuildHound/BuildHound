package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for [GuhWarmth.classify] (plan 066 review fix): the window around this
 * daemon's own [dev.buildhound.commons.payload.GuhWarmth]'s anchor (`jvmStartMs`) is asymmetric —
 * see the KDoc on [GuhWarmth.Companion.classify] for the "meaningfully after" rationale that these
 * boundary cases pin down.
 */
class GuhWarmthTest {

    @Test
    fun `unknown when the dist is not present`() {
        assertEquals(
            GuhWarmth.UNKNOWN,
            GuhWarmth.classify(distMtimeMs = 1_000, distPresent = false, jvmStartMs = 1_000),
        )
        assertEquals(
            GuhWarmth.UNKNOWN,
            GuhWarmth.classify(distMtimeMs = 1_000, distPresent = null, jvmStartMs = 1_000),
        )
    }

    @Test
    fun `unknown when a timestamp is unavailable`() {
        assertEquals(
            GuhWarmth.UNKNOWN,
            GuhWarmth.classify(distMtimeMs = null, distPresent = true, jvmStartMs = 1_000),
        )
        assertEquals(
            GuhWarmth.UNKNOWN,
            GuhWarmth.classify(distMtimeMs = 1_000, distPresent = true, jvmStartMs = null),
        )
    }

    @Test
    fun `cold when the mtime sits at this daemon's own JVM start`() {
        assertEquals(
            GuhWarmth.COLD,
            GuhWarmth.classify(distMtimeMs = 1_000, distPresent = true, jvmStartMs = 1_000),
        )
    }

    @Test
    fun `cold when the mtime is slightly before jvmStart, within the window`() {
        val jvmStart = 10_000_000L
        assertEquals(
            GuhWarmth.COLD,
            GuhWarmth.classify(
                distMtimeMs = jvmStart - GuhWarmth.FRESH_WINDOW_MS,
                distPresent = true,
                jvmStartMs = jvmStart,
            ),
        )
    }

    @Test
    fun `warm when the mtime predates jvmStart by more than the fresh window`() {
        val jvmStart = 10_000_000L
        assertEquals(
            GuhWarmth.WARM,
            GuhWarmth.classify(
                distMtimeMs = jvmStart - GuhWarmth.FRESH_WINDOW_MS - 1,
                distPresent = true,
                jvmStartMs = jvmStart,
            ),
        )
    }

    // --- Positive-boundary coverage (plan 066 review fix): a dist mtime AFTER jvmStart used to
    // collapse into the same symmetric `abs(...) <= FRESH_WINDOW_MS` test as the "before" side,
    // so a mtime meaningfully after this daemon's own start read WARM even though WARM is
    // documented as "meaningfully older" — it is not older, it's newer. The fix makes the window
    // asymmetric: still COLD within the window on the after side (write-completion lag racing the
    // JVM's observable start), but UNKNOWN — not WARM — once meaningfully after, since that shape
    // doesn't fit either narrative (not this daemon's own bootstrap, not a stale reused dist).

    @Test
    fun `cold when the mtime is after jvmStart by exactly the fresh window`() {
        val jvmStart = 10_000_000L
        assertEquals(
            GuhWarmth.COLD,
            GuhWarmth.classify(
                distMtimeMs = jvmStart + GuhWarmth.FRESH_WINDOW_MS,
                distPresent = true,
                jvmStartMs = jvmStart,
            ),
        )
    }

    @Test
    fun `unknown, not warm, when the mtime is after jvmStart by more than the fresh window`() {
        val jvmStart = 10_000_000L
        assertEquals(
            GuhWarmth.UNKNOWN,
            GuhWarmth.classify(
                distMtimeMs = jvmStart + GuhWarmth.FRESH_WINDOW_MS + 1,
                distPresent = true,
                jvmStartMs = jvmStart,
            ),
        )
    }
}
