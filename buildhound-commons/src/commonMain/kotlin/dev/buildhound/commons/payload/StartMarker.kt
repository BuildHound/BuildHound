package dev.buildhound.commons.payload

import kotlinx.serialization.Serializable

/**
 * A tiny build-started marker (plan 033), written at execution start under
 * `build/buildhound/started/<buildId>.json` and reconciled by the *next* build's finalizer: an
 * unreconciled marker means that build never finalized (the daemon died), so it is synthesized into
 * an `INTERRUPTED` [BuildPayload]. Defined in commons so the plugin's writer and reader agree on the
 * shape.
 *
 * It deliberately carries only cheaply-available, CC-safe fields — no tasks, no environment probe, no
 * identity, no token, no absolute paths. `ci`/`vcs` are intentionally absent: a build-service
 * parameter value source bakes into the config-cache entry and replays stale on a hit, so capturing
 * them here would be unreliable exactly on the CC-enabled CI builds that matter; the connector
 * expected-build check (plan 028) carries authoritative CI correlation for that case instead, and a
 * local build has no CI context anyway. `mode` is resolved with no CI context, so an `AUTO` build
 * reconciled by marker is labeled `LOCAL` (the connector upgrades genuine CI cases).
 */
@Serializable
data class StartMarker(
    val buildId: String,
    val startedAtMs: Long,
    val mode: BuildMode,
    val projectKey: String? = null,
    val requestedTasks: List<String> = emptyList(),
)
