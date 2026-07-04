package dev.buildhound.internaladapters

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val accumulator = AtomicReference(Accumulator())
    private val perFileHashes = AtomicBoolean(false)
    private val gradleVersion = AtomicReference("unknown")
    private val salt = AtomicReference<ByteArray?>(null)
    private val dependencyEdges = AtomicReference<Map<String, List<String>>>(emptyMap())

    /** True exactly once per daemon — the caller then registers the listener. */
    fun claimRegistration(): Boolean = registered.compareAndSet(false, true)

    /** Config-time facts, stable across CC hits (set by the plugin when `apply` runs). */
    fun configure(perFile: Boolean, gradle: String, edges: Map<String, List<String>>) {
        perFileHashes.set(perFile)
        gradleVersion.set(gradle)
        dependencyEdges.set(edges)
    }

    fun setSalt(bytes: ByteArray?) = salt.set(bytes)

    fun salt(): ByteArray? = salt.get()

    fun perFileHashes(): Boolean = perFileHashes.get()

    fun gradleVersion(): String = gradleVersion.get()

    fun dependencyEdges(): Map<String, List<String>> = dependencyEdges.get()

    /** The live accumulator the adapter writes into during this build. */
    fun accumulator(): Accumulator = accumulator.get()

    /** Atomically returns this build's accumulator and installs a fresh one for the next build. */
    fun takeAccumulator(): Accumulator = accumulator.getAndSet(Accumulator())

    /** Test-only: full reset of the daemon-static state. */
    fun resetForTest() {
        registered.set(false)
        accumulator.set(Accumulator())
        perFileHashes.set(false)
        gradleVersion.set("unknown")
        salt.set(null)
        dependencyEdges.set(emptyMap())
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
