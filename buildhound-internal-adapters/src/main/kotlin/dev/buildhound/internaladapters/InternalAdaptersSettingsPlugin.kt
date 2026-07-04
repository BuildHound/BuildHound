package dev.buildhound.internaladapters

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.GradleInternal
import org.gradle.api.logging.Logging
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.util.GradleVersion

/**
 * The opt-in internal-adapters settings plugin (plan 038, `dev.buildhound.internal-adapters`).
 * Applying it *is* the consent to use internal Gradle APIs — the single sanctioned exception to
 * architecture §2 rule 4. It cooperates with core: it no-ops (with a warn) when the core plugin is
 * not applied, and contributes through the plan-039 `BuildHoundCollectorRegistry`, never touching
 * the core plugin's classpath.
 *
 * Everything is `runCatching`-guarded — apply can only degrade to no capture, never fail the build.
 */
class InternalAdaptersSettingsPlugin : Plugin<Settings> {

    private val logger = Logging.getLogger(InternalAdaptersSettingsPlugin::class.java)

    override fun apply(settings: Settings) {
        runCatching {
            val ext = settings.extensions.create("internalAdapters", InternalAdaptersExtension::class.java)
            ext.perFileHashes.convention(false)

            // Composite: only the root build observes (same guard as core — build ops from included
            // builds already reach the root daemon's listener).
            if (settings.gradle.parent != null) return@runCatching

            // Cooperate with core: no-op when core (dev.buildhound) is not applied — its shared service
            // is then absent. Applying this addon alone must never capture nor fail.
            val corePresent = runCatching {
                settings.gradle.sharedServices.registrations.findByName(CORE_SERVICE_NAME) != null
            }.getOrDefault(false)
            if (!corePresent) {
                logger.warn("[buildhound-internal-adapters] core plugin 'dev.buildhound' not applied — no-op")
                return@runCatching
            }

            // Register the daemon-scoped internal build-operation listener **once per daemon** — it
            // persists across builds (that is how capture survives a CC hit); per-build data is
            // read-and-cleared by the collector, so the counter is the only registration guard needed.
            if (InternalAdaptersState.claimRegistration()) {
                val gradle = settings.gradle as GradleInternal
                val manager = gradle.services.get(BuildOperationListenerManager::class.java)
                manager.addListener(BuildOperationAdapter(settings.rootProject.projectDir))
            }

            // Config-time facts (stable across CC hits): the per-file opt-in, the Gradle version, and
            // the dependency edge list for criticalPath. The graph walk is an isolated-projects
            // violation by design → gated: on IP (or any failure) it degrades to no edges (null
            // criticalPath), never an error, exactly as plan 016's allTasks walk.
            settings.gradle.taskGraph.whenReady { graph ->
                val edges = runCatching {
                    graph.allTasks.associate { task -> task.path to graph.getDependencies(task).map { it.path } }
                }.getOrElse {
                    logger.info("[buildhound-internal-adapters] dependency-edge walk unavailable (criticalPath omitted): {}", it.message)
                    emptyMap()
                }
                InternalAdaptersState.configure(ext.perFileHashes.get(), GradleVersion.current().version, edges)
            }
        }.onFailure {
            logger.warn("[buildhound-internal-adapters] setup failed (build unaffected): {}", it.message)
        }
    }

    private companion object {
        // Core's shared build-service name (TaskEventCollector.SERVICE_NAME) — hardcoded because the
        // addon must not depend on the core plugin; documented in the architecture decision log.
        const val CORE_SERVICE_NAME = "buildhoundTaskEventCollector"
    }
}
