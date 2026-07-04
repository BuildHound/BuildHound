package dev.buildhound.internaladapters

import dev.buildhound.commons.payload.BuildHoundExtensionContributor
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.ExtensionContributionContext
import dev.buildhound.commons.payload.PayloadScrubber
import kotlinx.serialization.json.JsonElement

/**
 * Contributes `extensions["internalAdapters"]` (plan 038) — the ServiceLoader-discovered
 * [BuildHoundExtensionContributor] core's finalizer evaluates (plan 039). Reads-and-clears the
 * daemon-static [InternalAdaptersState] accumulator (so the next build starts clean even on a CC hit,
 * where core's Flow finalizer still runs) and assembles the versioned [InternalAdaptersPayload]:
 * salted cache keys, classified origin, caching-disabled reason, and the config-time dependency edges
 * + cache-avoided time core threads into `DerivedMetricsCalculator`. Never throws (the registry guards
 * anyway); returns null when nothing was captured (no tasks, no edges).
 */
class InternalAdaptersCollector : BuildHoundExtensionContributor {

    override val addonId: String = InternalAdaptersPayload.EXTENSION_KEY

    override fun contribute(context: ExtensionContributionContext): JsonElement? {
        // Read-and-clear both the per-task capture AND this build's edges in one take (edges live on
        // the accumulator, so a CC-hit build — where `whenReady` didn't run — sees empty edges and a
        // null criticalPath rather than another invocation's stale graph).
        val acc = InternalAdaptersState.takeAccumulator()
        val edges = acc.edges
        if (acc.byPath.isEmpty() && edges.isEmpty()) return null

        val salt = InternalAdaptersState.salt()
        val root = InternalAdaptersState.projectRoot()
        val (kept, droppedTasks) = Caps.capList(acc.byPath.entries.sortedBy { it.key }, Caps.MAX_TASKS)

        val details = kept.map { (path, t) ->
            InternalTaskDetail(
                path = path,
                cacheKey = SaltHasher.hash(salt, t.cacheKeyRaw),
                origin = OriginClassifier.classify(t.localLoadHit, t.remoteLoadHit, t.stored, t.executed),
                // The origin build-invocation UUID is a cross-build/cross-user identifier → salted like
                // the keys, so it still diffs within a project but can't be reversed (§3.7, review finding).
                originBuildInvocationId = SaltHasher.hash(salt, t.originBuildInvocationId?.encodeToByteArray()),
                originCacheKey = SaltHasher.hash(salt, t.originCacheKeyRaw),
                // Caching-disabled reasons are arbitrary third-party free text (a plugin's
                // `@DisableCachingByDefault(because=…)`); core does not deep-scrub opaque extensions, so
                // this module scrubs them itself — the same PayloadScrubber core applies to its own
                // `nonCacheableReason` (review finding).
                cachingDisabledReason = t.cachingDisabledReason?.let { PayloadScrubber.scrubText(it, root) },
                cachingDisabledCategory = t.cachingDisabledCategory,
            )
        }

        // avoidedMs = origin execution time of the tasks that were served from cache (the work skipped).
        val avoidedMs = kept
            .filter { (_, t) -> t.localLoadHit || t.remoteLoadHit }
            .mapNotNull { (_, t) -> t.originExecutionTimeMs }
            .takeIf { it.isNotEmpty() }
            ?.sum()

        // Symmetry with the task-row cap: trim the edge map instead of letting the whole extension get
        // dropped wholesale by the plan-039 byte budget on a huge monorepo (review nit).
        val (cappedEdges, _) = Caps.capMap(edges, Caps.MAX_TASKS)

        val payload = InternalAdaptersPayload(
            gradleVersion = InternalAdaptersState.gradleVersion(),
            tasks = details,
            avoidedMs = avoidedMs,
            dependencyEdges = cappedEdges,
            droppedTasks = droppedTasks,
        )
        return BuildHoundJson.payload.encodeToJsonElement(InternalAdaptersPayload.serializer(), payload)
    }
}
