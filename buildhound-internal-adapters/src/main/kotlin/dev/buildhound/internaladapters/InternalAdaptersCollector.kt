package dev.buildhound.internaladapters

import dev.buildhound.commons.payload.BuildHoundExtensionContributor
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.ExtensionContributionContext
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
        val acc = InternalAdaptersState.takeAccumulator()
        val edges = InternalAdaptersState.dependencyEdges()
        if (acc.byPath.isEmpty() && edges.isEmpty()) return null

        val salt = InternalAdaptersState.salt()
        val (kept, droppedTasks) = Caps.capList(acc.byPath.entries.sortedBy { it.key }, Caps.MAX_TASKS)

        val details = kept.map { (path, t) ->
            InternalTaskDetail(
                path = path,
                cacheKey = SaltHasher.hash(salt, t.cacheKeyRaw),
                origin = OriginClassifier.classify(t.localLoadHit, t.remoteLoadHit, t.stored, t.executed),
                originBuildInvocationId = t.originBuildInvocationId,
                originCacheKey = SaltHasher.hash(salt, t.originCacheKeyRaw),
                cachingDisabledReason = t.cachingDisabledReason,
                cachingDisabledCategory = t.cachingDisabledCategory,
            )
        }

        // avoidedMs = origin execution time of the tasks that were served from cache (the work skipped).
        val avoidedMs = kept
            .filter { (_, t) -> t.localLoadHit || t.remoteLoadHit }
            .mapNotNull { (_, t) -> t.originExecutionTimeMs }
            .takeIf { it.isNotEmpty() }
            ?.sum()

        val payload = InternalAdaptersPayload(
            gradleVersion = InternalAdaptersState.gradleVersion(),
            tasks = details,
            avoidedMs = avoidedMs,
            dependencyEdges = edges,
            droppedTasks = droppedTasks,
        )
        return BuildHoundJson.payload.encodeToJsonElement(InternalAdaptersPayload.serializer(), payload)
    }

    /** Static extractor core uses to pull the derived-metric inputs back out of the merged extension. */
    companion object {
        /** The `(avoidedMs, dependencyEdges)` core threads into `DerivedMetricsCalculator.compute`. */
        fun derivedInputs(element: JsonElement?): Pair<Long?, Map<String, List<String>>> {
            if (element == null) return null to emptyMap()
            return runCatching {
                val p = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), element)
                p.avoidedMs to p.dependencyEdges
            }.getOrElse { null to emptyMap() }
        }
    }
}
