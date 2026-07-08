package dev.buildhound.internaladapters

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Daemon-static bridge (plan 038) between the execution-time [BuildOperationAdapter] and the
 * ServiceLoader-discovered [InternalAdaptersCollector] (which core instantiates fresh, with no way to
 * reach a build service). Same classloader (both on the settings classpath), written and read only at
 * **execution time** — no config-phase capture, so it is CC-safe (the `DaemonState` precedent).
 *
 * The `BuildOperationListenerManager` is daemon-scoped: a registered listener persists across builds
 * in a warm daemon (this is why capture survives a CC hit — spike result). Two guards keep that from
 * leaking across builds: registration happens **once per daemon** ([claimRegistration]); the
 * accumulated per-task data is **read-and-cleared** by the collector each build ([takeAccumulator]) —
 * core's Flow finalizer runs every build (including CC hits), so the next build always starts clean.
 * The stable-per-daemon facts (salt, gradle version, per-file opt-in, dependency edges) persist across
 * CC hits deliberately — they don't change on a CC hit.
 */
object InternalAdaptersState {

    private val registered = AtomicBoolean(false)
    private val logListenerRegistered = AtomicBoolean(false)
    private val accumulator = AtomicReference(Accumulator())
    private val perFileHashes = AtomicBoolean(false)
    private val collectCacheOrigins = AtomicBoolean(false)
    private val collectDeprecations = AtomicBoolean(false)
    private val collectLogWarnings = AtomicBoolean(false)
    private val gradleVersion = AtomicReference("unknown")
    private val salt = AtomicReference<ByteArray?>(null)
    private val projectRoot = AtomicReference<String?>(null)

    /** True exactly once per daemon — the caller then registers the build-operation listener. */
    fun claimRegistration(): Boolean = registered.compareAndSet(false, true)

    /** Undo a claim whose `addListener` failed, so a later build in the daemon can retry (review finding). */
    fun releaseRegistration() = registered.set(false)

    /** True exactly once per daemon — the caller then registers the WARN-level logging listener (plan 044). */
    fun claimLogListenerRegistration(): Boolean = logListenerRegistered.compareAndSet(false, true)

    fun releaseLogListenerRegistration() = logListenerRegistered.set(false)

    /**
     * Cache-origin/key + critical-path capture toggle (plan 038/051), set via [configure] at config
     * time. Gates the [BuildOperationAdapter] cache data paths, so enabling *only* a warning catcher
     * never accumulates cache telemetry.
     */
    fun collectCacheOrigins(): Boolean = collectCacheOrigins.get()

    /** Warning-catcher toggles (plan 044), set via [configure] at config time — see its KDoc for why. */
    fun collectDeprecations(): Boolean = collectDeprecations.get()

    fun collectLogWarnings(): Boolean = collectLogWarnings.get()

    /**
     * Config-time facts. `perFile`/`gradle`/`projectRoot` are stable per daemon, so they persist
     * across CC hits (they don't change). The **dependency edges are build-specific**, so they are
     * written into the *current build's accumulator*, not a daemon-static field: on a CC hit the
     * `whenReady` walk does not run, so the accumulator's edges stay empty and `criticalPathMs`
     * degrades to null — never a stale graph from a different task invocation (review finding).
     */
    fun configure(
        perFile: Boolean,
        gradle: String,
        root: String?,
        edges: Map<String, List<String>>,
        collectCacheOrigins: Boolean = false,
        collectDeprecations: Boolean = false,
        collectLogWarnings: Boolean = false,
    ) {
        perFileHashes.set(perFile)
        // Toggles are read here (config time) not at apply(): the `buildhound { internalAdapters {} }`
        // DSL block runs after apply() returns, so the user's value isn't set at apply. Daemon-static,
        // so they persist across CC hits (the DSL value doesn't change between builds) — like perFileHashes.
        this.collectCacheOrigins.set(collectCacheOrigins)
        this.collectDeprecations.set(collectDeprecations)
        this.collectLogWarnings.set(collectLogWarnings)
        gradleVersion.set(gradle)
        if (root != null) projectRoot.set(root)
        accumulator.get().edges = edges
    }

    fun setProjectRoot(root: String?) = projectRoot.set(root)

    fun projectRoot(): String? = projectRoot.get()

    fun setSalt(bytes: ByteArray?) = salt.set(bytes)

    fun salt(): ByteArray? = salt.get()

    fun perFileHashes(): Boolean = perFileHashes.get()

    fun gradleVersion(): String = gradleVersion.get()

    /** The live accumulator the adapter writes into during this build. */
    fun accumulator(): Accumulator = accumulator.get()

    /** Atomically returns this build's accumulator and installs a fresh one for the next build. */
    fun takeAccumulator(): Accumulator = accumulator.getAndSet(Accumulator())

    /** Test-only: full reset of the daemon-static state. */
    fun resetForTest() {
        registered.set(false)
        logListenerRegistered.set(false)
        accumulator.set(Accumulator())
        perFileHashes.set(false)
        collectCacheOrigins.set(false)
        collectDeprecations.set(false)
        collectLogWarnings.set(false)
        gradleVersion.set("unknown")
        salt.set(null)
        projectRoot.set(null)
    }
}

/**
 * One build's capture, populated concurrently from the build-operation listener (build ops run in
 * parallel). `parentOf`/`taskPathOf` correlate a cache/snapshot op back to its enclosing task; the
 * per-task partials are merged as the ops arrive in any order.
 */
class Accumulator {
    val parentOf: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()
    val taskPathOf: ConcurrentHashMap<Long, String> = ConcurrentHashMap()
    val byPath: ConcurrentHashMap<String, TaskAccum> = ConcurrentHashMap()

    /** This build's dependency graph (set by the config-time `whenReady` walk); empty on a CC hit. */
    @Volatile var edges: Map<String, List<String>> = emptyMap()

    /**
     * Captured warnings (plan 044), each a **deduped set** — the same deprecation fires once per call
     * site, and a repeated `logger.warn` is one signal, so uniqueness collapses the flood and bounds
     * memory on its own. Separate namespaces so a deprecation surfacing in both streams stays visible.
     * Both are populated only when the matching toggle is on. Scrubbed downstream by the collector.
     */
    val deprecations: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val logWarnings: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Distinct warnings seen past the per-stream cap (plan 019 "truncate + count"). */
    val droppedWarnings: AtomicInteger = AtomicInteger(0)

    fun addDeprecation(message: String) = addBounded(deprecations, message)

    fun addLogWarning(message: String) = addBounded(logWarnings, message)

    // A **soft** cap: contains/size/add are not one atomic step, so concurrent build-op threads may
    // overshoot MAX_WARNINGS by a few or double-count a drop near the boundary. Deliberate — the set is
    // idempotent, the overshoot is small and bounded, and a warning count is diagnostic, not a hard
    // invariant; a lock on every WARN event would cost more than it is worth.
    private fun addBounded(target: MutableSet<String>, message: String) {
        if (target.contains(message)) return // dedup: a repeat is not a new distinct warning
        if (target.size >= Caps.MAX_WARNINGS) { droppedWarnings.incrementAndGet(); return }
        target.add(message)
    }

    fun forPath(path: String): TaskAccum = byPath.computeIfAbsent(path) { TaskAccum() }

    /** Walk the op-parent chain from [opId] to the nearest enclosing task path, or null. */
    fun taskPathFor(opId: Long?): String? {
        var current = opId
        var hops = 0
        while (current != null && hops < 256) {
            taskPathOf[current]?.let { return it }
            current = parentOf[current]
            hops++
        }
        return null
    }
}

/** Per-task partial capture; volatile fields since ops for one task can land on different threads. */
class TaskAccum {
    @Volatile var cacheKeyRaw: ByteArray? = null
    @Volatile var localLoadHit: Boolean = false
    @Volatile var remoteLoadHit: Boolean = false
    @Volatile var stored: Boolean = false
    @Volatile var executed: Boolean = false
    @Volatile var originBuildInvocationId: String? = null
    @Volatile var originCacheKeyRaw: ByteArray? = null
    @Volatile var originExecutionTimeMs: Long? = null
    @Volatile var cachingDisabledReason: String? = null
    @Volatile var cachingDisabledCategory: String? = null
}
