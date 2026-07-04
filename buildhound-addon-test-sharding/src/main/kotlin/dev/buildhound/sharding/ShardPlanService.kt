package dev.buildhound.sharding

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Fetches this build's shard plan exactly once (plan 040), at execution time, and caches it for every
 * `Test` task's `doFirst`. The HTTP call lives here — not at apply time — so no shard slice is baked
 * into a configuration-cache entry and a fetch failure degrades per run. Suites are discovered from the
 * whole build's `Test` tasks (captured into [ShardingState] by the config-time `whenReady` walk), so a
 * single request covers the full suite set. Records the [TestShardingExtension] outcome (incl. the
 * run-all fallback) for the collector.
 */
abstract class ShardPlanService : BuildService<ShardPlanService.Params> {

    interface Params : BuildServiceParameters {
        val serverUrl: Property<String>
        val token: Property<String>
        val reference: Property<String>
        val index: Property<Int>
        val total: Property<Int>
    }

    private val logger = Logging.getLogger(ShardPlanService::class.java)
    private val fetched = AtomicBoolean(false)
    private val plan = AtomicReference<ShardPlanResponse?>(null)

    /** The fetched plan for this build, or null when sharding fell back to run-all. Fetch-once. */
    fun planForBuild(): ShardPlanResponse? {
        if (fetched.compareAndSet(false, true)) {
            val response = fetchPlan()
            plan.set(response)
            if (response == null) {
                // The run-all fallback: log once (fetch-once), never fail the build.
                logger.warn("[buildhound-test-sharding] no shard plan (server unreachable / non-2xx / no suites) — running all tests")
            }
            ShardingState.recordOutcome(
                TestShardingExtension(
                    shardPlanId = response?.shardPlanId,
                    shardIndex = parameters.index.get(),
                    shardTotal = parameters.total.get(),
                    appliedFilter = response != null,
                ),
            )
        }
        return plan.get()
    }

    private fun fetchPlan(): ShardPlanResponse? {
        val url = parameters.serverUrl.orNull?.takeIf { it.isNotBlank() } ?: return null
        val suites = SuiteDiscovery.discover(ShardingState.testDirs().map { File(it) })
        if (suites.isEmpty()) return null
        return ShardPlanClient(url, parameters.token.orNull).fetch(
            ShardPlanRequest(
                reference = parameters.reference.get(),
                index = parameters.index.get(),
                total = parameters.total.get(),
                suites = suites,
            ),
        )
    }
}
