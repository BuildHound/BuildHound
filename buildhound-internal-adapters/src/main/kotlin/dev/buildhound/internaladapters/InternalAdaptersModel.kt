package dev.buildhound.internaladapters

import kotlinx.serialization.Serializable

/**
 * How a task's outputs were obtained (plan 038), derived from the `BuildCache*Load/Store` ops +
 * `ExecuteWork` origin fields — mirrors Tuist's local/remote split and the avoidance-outcome taxonomy.
 */
@Serializable
enum class CacheOrigin { LOCAL_HIT, REMOTE_HIT, STORED, MISS, UNKNOWN }

/**
 * One task's internal-op capture (plan 038). `cacheKey`/`propertyHashes` are **salted** digests of
 * already-opaque Gradle hashes (never raw), so they diff within a project but can't be reversed.
 */
@Serializable
data class InternalTaskDetail(
    val path: String,
    val cacheKey: String? = null,
    val origin: CacheOrigin = CacheOrigin.UNKNOWN,
    val originBuildInvocationId: String? = null,
    val originCacheKey: String? = null,
    val cachingDisabledReason: String? = null,
    val cachingDisabledCategory: String? = null,
    val propertyHashes: Map<String, String> = emptyMap(),
    /** Truncation counts (plan 019 caps): properties dropped past [Caps.MAX_PROPERTY_HASHES]. */
    val droppedProperties: Int = 0,
    /**
     * Cache-transfer timings (plan 067, research F17 — the specced-then-dropped plan-038 tail): bytes
     * moved to/from the build cache for this task, and the wall time its load / store build-operations
     * took. Read reflectively off each op's own type — the load ops' `Result` (`getArchiveSize`) and the
     * store ops' `Details` (the store `Result` types expose only a bare `isStored` boolean on every
     * Gradle version checked — 8.14.5/9.4.0/9.4.1/9.6.1, verified via javap). A getter a Gradle version
     * doesn't expose degrades to null, never throws (spec §3.1); a load-miss byte-count sentinel (Gradle's
     * own, not this code's — `-1` local, `0` remote) is dropped rather than corrupted into the total.
     * Byte counts + durations only; no path, no URL. Null when this task moved no bytes (a genuine miss
     * with no store) or the getter was unavailable — an honest null (plan 005), never a fabricated zero.
     */
    val transferBytes: Long? = null,
    val loadMs: Long? = null,
    val storeMs: Long? = null,
)

/**
 * The `extensions["internalAdapters"]` payload (plan 038), versioned independently of the core schema
 * so the internal-op shape can evolve without a `buildhound-commons` bump. `avoidedMs` and
 * `dependencyEdges` are the core-`derived` inputs the finalizer threads into `DerivedMetricsCalculator`.
 */
@Serializable
data class InternalAdaptersPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val gradleVersion: String,
    val tasks: List<InternalTaskDetail> = emptyList(),
    val avoidedMs: Long? = null,
    val dependencyEdges: Map<String, List<String>> = emptyMap(),
    /** Task rows dropped past [Caps.MAX_TASKS]. */
    val droppedTasks: Int = 0,
    /**
     * Captured build warnings (plan 044), opt-in per catcher (off by default). `deprecations` are Gradle
     * deprecation-usage summaries; `logWarnings` are `WARN`-level log lines (`logger.warn`, and some
     * compiler output). Each is a deduped, scrubbed, capped list; `droppedWarnings` counts distinct
     * warnings shed past the cap. Empty when the matching toggle is off.
     */
    val deprecations: List<String> = emptyList(),
    val logWarnings: List<String> = emptyList(),
    val droppedWarnings: Int = 0,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** The addon id / `extensions` key — the join point with core (plan 039). */
        const val EXTENSION_KEY: String = "internalAdapters"
    }
}

/**
 * Pure origin classification (plan 038): no Gradle types, so it unit-tests without a build. Precedence
 * mirrors "how did we get the outputs": a local hit beats a remote hit beats a store; an executed task
 * with no hit/store is a genuine MISS; anything else (not cacheable, skipped) is UNKNOWN.
 */
object OriginClassifier {
    fun classify(localLoadHit: Boolean, remoteLoadHit: Boolean, stored: Boolean, executed: Boolean): CacheOrigin =
        when {
            localLoadHit -> CacheOrigin.LOCAL_HIT
            remoteLoadHit -> CacheOrigin.REMOTE_HIT
            stored -> CacheOrigin.STORED
            executed -> CacheOrigin.MISS
            else -> CacheOrigin.UNKNOWN
        }
}

/** Which per-version adapter to select (plan 038); `>9.x`/unparseable degrades to UNKNOWN, never crashes. */
enum class GradleBucket { V8, V9, UNKNOWN }

object VersionGate {
    fun bucket(version: String): GradleBucket =
        when (version.substringBefore('.').toIntOrNull()) {
            8 -> GradleBucket.V8
            9 -> GradleBucket.V9
            else -> GradleBucket.UNKNOWN
        }
}

/**
 * Cardinality guardrails (plan 038, plan 019 spirit): per-property-hash, per-task, and per-file caps
 * so a pathological build can't explode the payload. Pure — returns the capped collection + a dropped
 * count for the "truncate + count" contract (spec §3.9).
 */
object Caps {
    const val MAX_PROPERTY_HASHES: Int = 200
    const val MAX_TASKS: Int = 2000
    const val MAX_FILES_PER_TASK: Int = 500

    /** Per-stream distinct-warning cap and per-message char cap (plan 044, plan 019 spirit). */
    const val MAX_WARNINGS: Int = 200
    const val MAX_WARNING_CHARS: Int = 1000

    fun <V> capMap(map: Map<String, V>, max: Int): Pair<Map<String, V>, Int> =
        if (map.size <= max) map to 0
        else map.entries.take(max).associate { it.key to it.value } to (map.size - max)

    fun <T> capList(list: List<T>, max: Int): Pair<List<T>, Int> =
        if (list.size <= max) list to 0 else list.take(max) to (list.size - max)
}
