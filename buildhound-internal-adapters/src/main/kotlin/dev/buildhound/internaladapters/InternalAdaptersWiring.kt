package dev.buildhound.internaladapters

import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.GradleInternal
import org.gradle.api.logging.Logging
import org.gradle.internal.logging.LoggingOutputInternal
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.util.GradleVersion

/**
 * Internal-adapters wiring, bundled with the core plugin and driven from it (plan 074). This replaced
 * the standalone `dev.buildhound.internal-adapters` settings plugin: there is now **one** plugin
 * (`dev.buildhound`) and **one** config block (`buildhound { internalAdapters { } }`).
 *
 * Consent is by toggle, made structural. [install] takes only public Gradle types ([Settings],
 * [TaskExecutionGraph]) plus plain booleans; the internal Gradle APIs (build-operation listener
 * manager, `LoggingOutputInternal`) are touched **only** inside the private `register…` helpers, which
 * run **only** when the matching toggle is on. With every toggle off those helpers are never invoked,
 * so their internal-API classes are never linked — **on a daemon where no build has enabled a toggle,
 * applying the core plugin touches no internal API.**
 *
 * Called from core's `taskGraph.whenReady` (execution-time facts, CC-safe); on a CC hit `whenReady`
 * does not run, so — exactly as before — capture rides the daemon-static listener registered on the
 * first miss. **Known limitation (plan 075):** because the toggle-resetting [InternalAdaptersState.configure]
 * also runs only in `whenReady`, a CC-hit build does not re-establish its own toggle intent — so once
 * some build in a warm daemon has opted in, a later all-off build that reuses a *pre-toggle* CC entry can
 * still capture (via the lingering daemon-static listener) until the next CC miss. The captured data is
 * still scrubbed; the gap is a consent-model edge, tracked for an execution-time-rehydration fix.
 * Everything is `runCatching`-guarded: setup can only degrade to no capture, never fail the build
 * (spec §3.1). The core plugin already made the composite-build root-only decision before calling this,
 * so no parent-build guard is repeated here.
 */
object InternalAdaptersWiring {

    private val logger = Logging.getLogger(InternalAdaptersWiring::class.java)

    /**
     * @param settings the root build's settings (core has already excluded included builds).
     * @param graph the finalized task graph, for the config-time dependency-edge walk (critical path).
     * All booleans default off; each independently gates one internal-API data path.
     */
    fun install(
        settings: Settings,
        graph: TaskExecutionGraph,
        collectCacheOrigins: Boolean,
        collectDeprecations: Boolean,
        collectLogWarnings: Boolean,
        perFileHashes: Boolean,
    ) {
        runCatching {
            val anyEnabled = collectCacheOrigins || collectDeprecations || collectLogWarnings

            // Reset the daemon-static toggles for THIS build FIRST, unconditionally. This is
            // internal-API-free (pure module state + public `GradleVersion`/`projectDir`), so it upholds
            // "applied ≠ consent" while fixing the warm-daemon hazard: a build that enabled a toggle
            // leaves the listener registered for the daemon's life, so a *later* all-off build in the
            // same daemon must set the toggles back to false or that lingering listener would keep
            // capturing. The build-specific dependency-edge list (for criticalPath) is only walked when
            // cache origins are on; otherwise empty ⇒ criticalPath degrades to null, the same honest
            // degradation as on a CC hit (where whenReady did not run). The graph walk is an
            // isolated-projects violation by design → gated: on IP (or any failure) it degrades to no
            // edges, never an error (plan 016's allTasks-walk precedent).
            val edges =
                if (collectCacheOrigins) {
                    runCatching {
                        graph.allTasks.associate { task ->
                            task.path to graph.getDependencies(task).map { it.path }
                        }
                    }
                        .getOrElse {
                            logger.info(
                                "[buildhound-internal-adapters] dependency-edge walk unavailable " +
                                    "(criticalPath omitted): {}",
                                it.message,
                            )
                            emptyMap()
                        }
                } else {
                    emptyMap()
                }
            InternalAdaptersState.configure(
                perFile = perFileHashes,
                gradle = GradleVersion.current().version,
                root = settings.rootProject.projectDir.path,
                edges = edges,
                collectCacheOrigins = collectCacheOrigins,
                collectDeprecations = collectDeprecations,
                collectLogWarnings = collectLogWarnings,
            )

            // Per-input-file hashes are a v1.x follow-up; warn rather than silently ignore the opt-in.
            // Logged before the all-off return so it fires even if it is the only toggle set (it is a
            // no-op that touches no internal API).
            if (perFileHashes) {
                logger.warn(
                    "[buildhound] internalAdapters.perFileHashes is reserved for a v1.x follow-up and has no effect yet"
                )
            }

            // Nothing enabled ⇒ stop before any internal-API touch. No listener is registered and no
            // `extensions.internalAdapters` key is produced (the collector's accumulator stays empty).
            // This is the "applied the plugin ≠ consented to internal Gradle APIs" guarantee on a daemon
            // that has only ever run all-off builds.
            if (!anyEnabled) return@runCatching

            // The build-operation listener feeds BOTH cache origins (finished/started) and deprecations
            // (progress); register it once per daemon when either rides it. Register-then-confirm: if
            // addListener throws, release the claim so a later build retries rather than losing capture
            // for the JVM's life (review finding, plan 038).
            if ((collectCacheOrigins || collectDeprecations) && InternalAdaptersState.claimRegistration()) {
                runCatching { registerBuildOpListener(settings) }
                    .onFailure {
                        InternalAdaptersState.releaseRegistration()
                        logger.warn(
                            "[buildhound-internal-adapters] listener registration failed (no capture this daemon): {}",
                            it.message,
                        )
                    }
            }

            // Version gate: a Gradle outside the tested range degrades to best-effort capture (the
            // reflection guards handle per-field mismatches) — surface it once so a break is diagnosable.
            if (VersionGate.bucket(GradleVersion.current().version) == GradleBucket.UNKNOWN) {
                logger.info(
                    "[buildhound-internal-adapters] Gradle {} is outside the tested 8.x/9.x range — " +
                        "capture is best-effort",
                    GradleVersion.current().version,
                )
            }

            // Surface the internal-API risk when any catcher is on: these read internal Gradle APIs with
            // no compatibility guarantee, so a Gradle upgrade can silently stop capture. It never fails
            // the build (every path is reflection-guarded), but the user should know the signal can
            // vanish. Config-time only, so it does not repeat on a CC hit.
            val enabled = buildList {
                if (collectCacheOrigins) add("collectCacheOrigins")
                if (collectDeprecations) add("collectDeprecations")
                if (collectLogWarnings) add("collectLogWarnings")
            }
            if (enabled.isNotEmpty()) {
                logger.warn(
                    "[buildhound-internal-adapters] internal-adapters capture enabled ({}) — this reads internal " +
                        "Gradle APIs with no compatibility guarantee; a Gradle upgrade may silently stop capture " +
                        "(the build is never affected).",
                    enabled.joinToString(", "),
                )
            }

            // The deprecation catcher rides the already-registered build-op listener (gated by the toggle
            // inside progress()). The WARN-log catcher needs its own listener — register it once per
            // daemon, only when enabled, so a build that only wants cache/deprecation data pays nothing.
            // Daemon-scoped, so it persists across CC hits; still gated per build by the toggle.
            if (collectLogWarnings && InternalAdaptersState.claimLogListenerRegistration()) {
                runCatching { registerLogListener(settings) }
                    .onFailure {
                        InternalAdaptersState.releaseLogListenerRegistration()
                        logger.warn(
                            "[buildhound-internal-adapters] WARN-log listener registration failed " +
                                "(no log-warning capture this daemon): {}",
                            it.message,
                        )
                    }
            }
        }.onFailure {
            logger.warn("[buildhound-internal-adapters] setup failed (build unaffected): {}", it.message)
        }
    }

    // --- Internal-Gradle-API touch points. Reached only from a toggle-guarded branch above, so their
    // referenced classes link only on consent. ---

    private fun registerBuildOpListener(settings: Settings) {
        val gradle = settings.gradle as GradleInternal
        val manager = gradle.services.get(BuildOperationListenerManager::class.java)
        manager.addListener(BuildOperationAdapter(settings.rootProject.projectDir))
    }

    private fun registerLogListener(settings: Settings) {
        val gradle = settings.gradle as GradleInternal
        gradle.services.get(LoggingOutputInternal::class.java).addOutputEventListener(WarningLogListener())
    }
}
