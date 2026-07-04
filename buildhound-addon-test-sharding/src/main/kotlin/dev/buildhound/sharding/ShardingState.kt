package dev.buildhound.sharding

import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge (plan 040) between the plugin/service (which apply the shard filter) and the
 * ServiceLoader-discovered [TestShardingCollector] (which core instantiates fresh, with no way to
 * reach the build service). [testDirs] is captured at config time by the `whenReady` walk and read at
 * execution by the service for suite discovery; [outcome] is recorded once the plan is fetched (or the
 * run-all fallback fires) and read-and-cleared by the collector. All access is execution/config-time
 * only — no CC fingerprint input (the `DaemonState` / internal-adapters precedent). On a CC hit the
 * `whenReady` walk doesn't run, so [testDirs] holds whatever the last configuration set — for a
 * same-project rebuild those dirs are identical, so the filter still applies correctly; on a cold
 * daemon (no config ever ran) [testDirs] is empty and the service discovers nothing → runs all tests.
 * CI shard jobs use fresh daemons, so the warm-daemon-different-invocation case is a local-dev edge.
 */
object ShardingState {

    private val testDirs = AtomicReference<List<String>>(emptyList())
    private val outcome = AtomicReference<TestShardingExtension?>(null)

    fun setTestDirs(paths: List<String>) = testDirs.set(paths)

    fun testDirs(): List<String> = testDirs.get()

    fun recordOutcome(ext: TestShardingExtension) = outcome.set(ext)

    /** The build's sharding outcome, cleared for the next build (core's finalizer runs every build). */
    fun takeOutcome(): TestShardingExtension? = outcome.getAndSet(null)

    fun resetForTest() {
        testDirs.set(emptyList())
        outcome.set(null)
    }
}
