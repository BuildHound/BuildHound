package dev.buildhound.sharding

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.testing.Test

/**
 * Opt-in test-sharding settings plugin (plan 040, `dev.buildhound.test-sharding`). With
 * `BUILDHOUND_SHARD_INDEX`/`_TOTAL` set (a CI matrix), it fetches a server-balanced plan and applies
 * `Test.filter.includeTestsMatching` so each shard runs a disjoint subset. **No index ⇒ fully inert**
 * (the one case where doing nothing is correct — Tuist got this right). Every failure path (no server,
 * timeout, non-2xx, missing/invalid index, empty plan) ⇒ run **all** tests + `warn` + `appliedFilter=false`
 * — the inverse of Tuist's `GradleException` (correctness over speed). Uses only public Gradle APIs.
 */
class TestShardingSettingsPlugin : Plugin<Settings> {

    private val logger = Logging.getLogger(TestShardingSettingsPlugin::class.java)

    override fun apply(settings: Settings) {
        runCatching {
            // Composite: only the root build shards (its Test tasks reach the whole build).
            if (settings.gradle.parent != null) return@runCatching

            val providers = settings.providers
            val index = providers.environmentVariable("BUILDHOUND_SHARD_INDEX").orNull?.toIntOrNull()
            val total = providers.environmentVariable("BUILDHOUND_SHARD_TOTAL").orNull?.toIntOrNull()
            // No / invalid index ⇒ inert: no HTTP, no filter, no service — behaves as if unapplied.
            if (index == null || total == null || index < 1 || total < 1 || index > total) {
                if (index != null || total != null) {
                    logger.warn("[buildhound-test-sharding] invalid BUILDHOUND_SHARD_INDEX/_TOTAL ({}/{}); running all tests", index, total)
                }
                return@runCatching
            }
            // Reference keys the idempotent plan; default to the CI run id, else an explicit override.
            val reference = providers.environmentVariable("BUILDHOUND_SHARD_REFERENCE").orNull
                ?: providers.environmentVariable("BUILDHOUND_CI_RUN_ID").orNull
            if (reference.isNullOrBlank()) {
                logger.warn("[buildhound-test-sharding] no BUILDHOUND_SHARD_REFERENCE / BUILDHOUND_CI_RUN_ID; running all tests")
                return@runCatching
            }

            // Register the shard-plan service (params set here; the per-project filter action re-derives
            // it by name, so the IsolatedAction never captures a service reference).
            settings.gradle.sharedServices.registerIfAbsent(SHARD_SERVICE_NAME, ShardPlanService::class.java) { spec ->
                // Server + token via env only (architecture §6); providers keep them out of the CC entry.
                spec.parameters.serverUrl.set(providers.environmentVariable("BUILDHOUND_SERVER_URL"))
                spec.parameters.token.set(providers.environmentVariable("BUILDHOUND_TOKEN"))
                spec.parameters.reference.set(reference)
                spec.parameters.index.set(index)
                spec.parameters.total.set(total)
            }

            // Capture every Test task's compiled-class dirs for whole-build suite discovery. allTasks is
            // an isolated-projects violation → gated: on IP (or any failure) it degrades to no dirs, so
            // the service discovers nothing and every shard runs all tests (honest fallback).
            settings.gradle.taskGraph.whenReady { graph ->
                runCatching {
                    val dirs = graph.allTasks.filterIsInstance<Test>()
                        .flatMap { it.testClassesDirs.files.map(File::getPath) }
                        .distinct()
                    ShardingState.setTestDirs(dirs)
                }.onFailure { logger.info("[buildhound-test-sharding] test-dir walk unavailable (running all): {}", it.message) }
            }

            // Wire the per-Test-task filter via a top-level installer so the IsolatedAction captures only
            // the shard-count boolean + a service NAME — never the plugin or a service reference (the
            // exact hazard `gradle.lifecycle.beforeProject` cannot isolate, architecture §7).
            installShardFilter(settings.gradle, index == total)
        }.onFailure {
            logger.warn("[buildhound-test-sharding] setup failed (running all tests): {}", it.message)
        }
    }
}

/** The shared shard-plan service name; registered at settings time, re-derived per project by name. */
private const val SHARD_SERVICE_NAME: String = "buildhoundTestShardPlan"

/**
 * Registers the per-`Test`-task shard filter (plan 040). A **top-level** function so the
 * `gradle.lifecycle.beforeProject` `IsolatedAction` captures only [isLastShard] (a `Boolean`) — never
 * the plugin instance nor a `Provider<ShardPlanService>` (architecture §7: `beforeProject` cannot
 * isolate an action holding a service reference). The service is re-derived **inside** the action by
 * name via `registerIfAbsent` (idempotent — params were set at settings time). The `doFirst` reads its
 * task from its own argument (never a captured `Task`/`Task.project`), so it stays CC-safe.
 */
@Suppress("UnstableApiUsage")
private fun installShardFilter(gradle: org.gradle.api.invocation.Gradle, isLastShard: Boolean) {
    gradle.lifecycle.beforeProject { project ->
        runCatching {
            val service = project.gradle.sharedServices.registerIfAbsent(SHARD_SERVICE_NAME, ShardPlanService::class.java) {}
            project.tasks.withType(Test::class.java).configureEach { task ->
                task.usesService(service)
                task.doFirst { applyShard(service.get(), it as Test, isLastShard) }
            }
        }.onFailure { project.logger.info("[buildhound-test-sharding] filter wiring skipped: {}", it::class.java.simpleName) }
    }
}

/** Applies this shard's class filter to [task], or runs all tests on any failure (never throws). */
private fun applyShard(service: ShardPlanService, task: Test, isLastShard: Boolean) {
    val logger = Logging.getLogger(ShardPlanService::class.java)
    runCatching {
        val plan = service.planForBuild() ?: return // fetch failed ⇒ no filter ⇒ run all (fallback recorded)
        val filter = task.filter
        filter.isFailOnNoMatchingTests = false
        plan.classes.forEach { filter.includeTestsMatching(it) }
        // The last shard also runs anything the plan didn't assign (drift catch-all, Tuist §1.2).
        if (isLastShard) {
            val unassigned = SuiteDiscovery.discover(task.testClassesDirs.files.toList()).toSet() - plan.assigned.toSet()
            unassigned.forEach { filter.includeTestsMatching(it) }
        }
    }.onFailure {
        logger.warn("[buildhound-test-sharding] shard filter skipped (running all tests): {}", it.message)
    }
}
