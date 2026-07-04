package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.StartMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure marker-selection logic (plan 033): bound, TTL, oldest-first, no IO. */
class MarkerReconcilerTest {

    private val now = 2_000_000_000_000L

    private fun marker(id: String, ageMs: Long) =
        StartMarker(buildId = id, startedAtMs = now - ageMs, mode = BuildMode.LOCAL)

    @Test
    fun `live markers reconcile oldest-first and expired ones prune`() {
        val fresh = marker("fresh", ageMs = 60_000)
        val old = marker("old", ageMs = 3L * 24 * 60 * 60 * 1000) // 3 days — still live
        val expired = marker("expired", ageMs = MarkerReconciler.TTL_MS + 1)

        val plan = MarkerReconciler.plan(listOf(fresh, old, expired), nowMs = now)

        assertEquals(listOf("old", "fresh"), plan.reconcile.map { it.buildId }, "oldest-first, expired excluded")
        assertEquals(listOf("expired"), plan.prune, "past-TTL marker is pruned, not synthesized")
    }

    @Test
    fun `reconciliation is bounded and the overflow is neither reconciled nor pruned`() {
        // 25 live markers, increasing age; the cap is 20.
        val markers = (1..25).map { marker("b$it", ageMs = it.toLong() * 1000) }
        val plan = MarkerReconciler.plan(markers, nowMs = now, max = MarkerReconciler.MAX_RECONCILE)

        assertEquals(MarkerReconciler.MAX_RECONCILE, plan.reconcile.size)
        // Oldest (largest age) reconcile first; the 5 youngest overflow and wait for the next build.
        assertTrue(plan.reconcile.first().buildId == "b25", "oldest reconciles first")
        assertTrue(plan.prune.isEmpty(), "live overflow is left for the next build, never deleted unread")
        val handled = (plan.reconcile.map { it.buildId } + plan.prune).toSet()
        assertEquals(5, markers.count { it.buildId !in handled }, "exactly the 5 youngest are deferred")
    }

    @Test
    fun `an empty directory yields an empty plan`() {
        val plan = MarkerReconciler.plan(emptyList(), nowMs = now)
        assertTrue(plan.reconcile.isEmpty() && plan.prune.isEmpty())
    }
}
