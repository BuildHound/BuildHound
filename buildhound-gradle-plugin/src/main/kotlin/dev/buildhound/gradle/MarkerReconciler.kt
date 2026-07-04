package dev.buildhound.gradle

import dev.buildhound.commons.payload.StartMarker

/**
 * Pure selection logic for start-marker reconciliation (plan 033), split out of the finalizer so it
 * unit-tests without any filesystem or Gradle API. Given the stale markers found in `started/` (the
 * current build's own already excluded by the caller), it decides which to synthesize into
 * `INTERRUPTED` builds now and which to prune, bounding the work so a dead server or a crash loop
 * cannot make reconciliation unbounded — mirrors the spool caps (plan 009).
 */
internal object MarkerReconciler {

    /** At most this many stale markers reconciled per build; the rest wait for the next build. */
    const val MAX_RECONCILE: Int = 20

    /** A marker older than this is presumed genuinely orphaned and pruned without synthesizing. */
    const val TTL_MS: Long = 14L * 24 * 60 * 60 * 1000

    /** [reconcile] → synthesize+route an INTERRUPTED build; [prune] (buildIds) → delete, no synthesis. */
    data class Plan(val reconcile: List<StartMarker>, val prune: List<String>)

    fun plan(markers: List<StartMarker>, nowMs: Long, max: Int = MAX_RECONCILE, ttlMs: Long = TTL_MS): Plan {
        // Oldest first, so the longest-lost build surfaces first and the cap is deterministic.
        val sorted = markers.sortedBy { it.startedAtMs }
        val (expired, live) = sorted.partition { nowMs - it.startedAtMs > ttlMs }
        // Live overflow beyond `max` is neither reconciled nor pruned this build — the next build
        // drains it (each build clears up to `max` and adds none of its own), so the dir converges.
        return Plan(reconcile = live.take(max), prune = expired.map { it.buildId })
    }
}
