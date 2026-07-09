package dev.buildhound.gradle

import dev.buildhound.commons.payload.JvmArtifactKind
import dev.buildhound.internaladapters.InternalAdaptersWiring
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.build.event.BuildEventsListenerRegistry

/**
 * Entry point, applied in `settings.gradle.kts` (spec §3.1). Everything registered here must be
 * configuration-cache safe: no `Project` references at execution time, state flows through
 * providers and build-service parameters only.
 */
@Suppress("UnstableApiUsage")
abstract class BuildHoundSettingsPlugin @Inject constructor(
    private val eventsListenerRegistry: BuildEventsListenerRegistry,
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
    private val buildFeatures: BuildFeatures,
) : Plugin<Settings> {

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("buildhound", BuildHoundExtension::class.java)
        // Config overrides (plan 027): apply `buildhound.<key>`/`BUILDHOUND_<KEY>` as the convention
        // fallback so precedence is explicit-DSL > override > default. server.token is NOT overridable.
        val overrides = ConfigOverrides(settings.providers) { logger.info(it) }
        // The env/property master switch, resolved at apply time so it can gate the beforeProject
        // reaction below (a DSL `enabled = false` is additionally honored by the finalizer).
        val masterEnabled = overrides.bool("enabled") ?: true
        extension.enabled.convention(masterEnabled)
        extension.mode.convention(overrides.mode("mode") ?: TelemetryMode.AUTO)
        extension.identity.pseudonymize.convention(overrides.bool("identity.pseudonymize") ?: true)
        extension.htmlReport.enabled.convention(overrides.bool("htmlReport.enabled") ?: true)
        extension.localBuilds.enabled.convention(overrides.bool("localBuilds.enabled") ?: true)
        extension.localBuilds.requireOptInFile.convention(overrides.bool("localBuilds.requireOptInFile") ?: true)
        extension.upload.uploadInBackground.convention(overrides.bool("upload.uploadInBackground") ?: false)
        overrides.string("server.url")?.let { extension.server.url.convention(it) }
        extension.kotlinReports.bundle.convention(true)
        extension.tests.collect.convention(true)
        extension.processProbe.enabled.convention(overrides.bool("processProbe.enabled") ?: true)
        // internalAdapters {} (plan 074): bundled internal-Gradle-API capture, every toggle off by
        // default. Read at whenReady (post-DSL) and handed to the module's wiring; all-off touches no
        // internal API. Standard override precedence (like htmlReport.enabled/processProbe.enabled):
        // explicit DSL value > buildhound.internalAdapters.* override > false.
        extension.internalAdapters.collectCacheOrigins.convention(overrides.bool("internalAdapters.collectCacheOrigins") ?: false)
        extension.internalAdapters.collectDeprecations.convention(overrides.bool("internalAdapters.collectDeprecations") ?: false)
        extension.internalAdapters.collectLogWarnings.convention(overrides.bool("internalAdapters.collectLogWarnings") ?: false)
        extension.internalAdapters.perFileHashes.convention(overrides.bool("internalAdapters.perFileHashes") ?: false)

        // In a composite, only the root build observes: task events from included builds
        // already reach the root's listener, and a second flow action would consume the
        // DaemonState mark twice and emit duplicate summaries (review finding, plan 003).
        if (settings.gradle.parent != null) {
            logger.info("[buildhound] applied in an included build; the root build's plugin collects")
            return
        }

        DaemonState.configurationRan()

        // Per-apply mailbox for the configuration-time task dictionary, filled by the `whenReady`
        // callback below. Delivered to the finalizer as a Flow-action parameter (plan 056, closes
        // plan 045) rather than a collector-service parameter: a service parameter is what an
        // included build's task-finish events freeze empty before `whenReady` runs in a composite
        // (plan 044); a Flow-action parameter resolves after configuration regardless, the same
        // channel plan 046 validated for the toolchain mailbox below.
        val taskMetadataHolder = AtomicReference<Map<String, TaskMetadata>>(emptyMap())
        // Sibling mailbox for the Test-task JUnit XML locations (plan 024), filled by the same
        // `whenReady` callback and replayed from the CC entry on a hit (discovery spike §4a).
        val testLocationsHolder = AtomicReference<Map<String, TestResultLocations>>(emptyMap())
        // Sibling mailbox for the detected AGP/KGP/KSP/Spring-Boot versions (plan 046, plan 072),
        // filled by the same `whenReady` callback and replayed from the CC entry on a hit.
        val toolchainHolder = AtomicReference(DetectedToolchain())
        // Sibling mailbox for the JVM archive-task output locations (plan 072, research F22), filled by
        // the same `whenReady` callback (from graph.allTasks — only tasks scheduled this build) and
        // replayed from the CC entry on a hit via the finalizer's jvmArtifacts param below. Locations
        // only — the File.length() read happens at Flow time (no config-phase file read → no CC input).
        val jvmArtifactsHolder = AtomicReference<List<JvmArtifactLocation>>(emptyList())
        // Sibling mailbox for the declared build-structure inventory (plan 069, research F19),
        // filled by `projectsLoaded` — earlier than `whenReady`, since the descriptor tree only
        // populates after the settings script's include(...) calls run (apply() itself is too
        // early); NOT `settingsEvaluated` (the plan's original choice) — `Gradle.includedBuilds`
        // throws there (verified empirically), and is only safe to read from `projectsLoaded` on.
        // Replayed from the CC entry on a hit via the BuildStructureValueSource parameters below
        // (same channel as toolchainHolder).
        val buildStructureHolder = AtomicReference(CapturedBuildStructure())
        // Sibling mailbox for the committed build-cache config snapshot (plan 067, research F17),
        // filled by `settingsEvaluated` — the earliest hook where the settings-script `buildCache {}`
        // block is guaranteed fully evaluated (never at apply(), where it may be empty). Reading only
        // `settings.buildCache` here touches no `Gradle.includedBuilds` (the call that forced the other
        // walks off `settingsEvaluated`), so this hook is safe. Replayed from the CC entry on a hit via
        // the finalizer's buildCache param below — correct, because cache *configuration* is stable
        // across builds (unlike per-build timings). Public API only; no internal Gradle type.
        val buildCacheHolder = AtomicReference(BuildCacheConfigSnapshot())
        settings.gradle.settingsEvaluated { s ->
            runCatching { buildCacheHolder.set(BuildCacheConfigReader.snapshot(s.buildCache)) }
                .onFailure { logger.warn("[buildhound] build-cache config snapshot failed (build unaffected): {}", it.message) }
        }
        // Sibling mailbox for the change blast-radius module-dir index (plan 063, research F13): the
        // Gradle `path → projectDir-relative-to-root` map, filled at `settingsEvaluated` — the earliest
        // hook where the descriptor tree is populated (post-`include()`) and reading it touches no
        // `Gradle.includedBuilds` (the call that forced plan 069's walk off `settingsEvaluated`). Baked
        // into the ChangedModulesValueSource params below (same channel as buildStructureHolder) and
        // replayed on a CC hit — correct, since module structure is CC-keyed. Its own guard so a walk
        // failure degrades the index to empty (→ honest `unattributedChanges`), never a failed build.
        val moduleDirIndexHolder = AtomicReference<Map<String, String>>(emptyMap())
        settings.gradle.settingsEvaluated { s ->
            runCatching { moduleDirIndexHolder.set(ModuleDirIndexWalker.walk(s)) }
                .onFailure { logger.warn("[buildhound] module-dir index walk failed (build unaffected): {}", it.message) }
        }
        // Internal test seam (mirrors the other `buildhound.internal.*` failpoints): when any of
        // these is set, its value is reported verbatim instead of walking the project graph — lets
        // the TestKit suite exercise the whenReady→service→payload channel (and its CC replay)
        // without a heavy, version-coupled real AGP/KGP/KSP build. Absent in every real build.
        val toolchainSeam = DetectedToolchain(
            agp = settings.providers.gradleProperty("buildhound.internal.toolchain.agp").orNull,
            kgp = settings.providers.gradleProperty("buildhound.internal.toolchain.kgp").orNull,
            ksp = settings.providers.gradleProperty("buildhound.internal.toolchain.ksp").orNull,
            springBoot = settings.providers.gradleProperty("buildhound.internal.toolchain.springBoot").orNull,
        )

        val collector = settings.gradle.sharedServices.registerIfAbsent(
            TaskEventCollector.SERVICE_NAME,
            TaskEventCollector::class.java,
        ) { spec ->
            spec.parameters.testResultLocations.set(settings.providers.provider { testLocationsHolder.get() })
            // Start-marker context (plan 033): only CC-stable fields (no ci/vcs value source — a
            // service param bakes and replays stale on a hit). Null when telemetry is off → no marker.
            // Mode is resolved with no CI context (an AUTO build's marker is LOCAL); the connector
            // fallback upgrades genuine CI cases. requestedTasks is part of the CC key, so it is never
            // stale on a hit; projectKey/startedDir are stable across builds.
            spec.parameters.markerContext.set(
                settings.providers.provider {
                    if (!extension.enabled.get()) return@provider null
                    val markerMode = PayloadAssembler.resolveMode(extension.mode.get(), ci = null, benchmark = null)
                        ?: return@provider null
                    MarkerContext(
                        startedDir = File(settings.rootDir, "build/buildhound/started").absolutePath,
                        projectKey = settings.rootProject.name,
                        requestedTasks = settings.startParameter.taskNames.toList(),
                        mode = markerMode,
                    )
                },
            )
        }

        eventsListenerRegistry.onTaskCompletion(collector)

        val saltPath = File(settings.rootDir, SALT_PATH).absolutePath

        // Task type/cacheable dictionary + configuration-duration end mark (plan 016).
        // Registering the callback is isolated-projects safe; only `allTasks` is not, so
        // the IP gate runs before the graph is touched. Runs at configuration time only,
        // so capturing `settings` here is CC-safe (Talaiot precedent). Never fails a build.
        // Internal test seam only (not the user-facing CI truthiness rule): set to any
        // value but "false" to arm the "dictionary walk throws" failpoint.
        val failTaskGraphSnapshot = settings.providers
            .gradleProperty("buildhound.internal.failTaskGraphSnapshot")
            .map { it != "false" }
            .getOrElse(false)
        // Internal test seam, same shape as failTaskGraphSnapshot above: forces the
        // projectsLoaded descriptor walk (plan 069) to throw, exercising its never-fail
        // degrade-to-null path without a heavy real-monorepo fixture.
        val failBuildStructureSnapshot = settings.providers
            .gradleProperty("buildhound.internal.failBuildStructureSnapshot")
            .map { it != "false" }
            .getOrElse(false)
        val collectTests = extension.tests.collect
        settings.gradle.taskGraph.whenReady { graph ->
            DaemonState.configurationCompleted()
            val isolatedProjects = buildFeatures.isolatedProjects.active.getOrElse(false)
            runCatching {
                check(!failTaskGraphSnapshot) { "task-graph snapshot failpoint" }
                if (isolatedProjects) {
                    logger.info("[buildhound] isolated projects active; task metadata dictionary left empty")
                } else {
                    taskMetadataHolder.set(
                        graph.allTasks.associate { task -> task.path to TaskClassIntrospection.introspect(task.javaClass) },
                    )
                    // Test-result locations (plan 024): capture each Test task's JUnit XML dir now, at
                    // config time (spike §4a) — the XML itself is read in the finalizer. Public Test API
                    // only; the decision to treat a task as a Test task is the type-free introspection.
                    if (collectTests.getOrElse(true)) {
                        val locations = graph.allTasks
                            .filter { TestTaskIntrospection.isTestTask(it.javaClass) }
                            .mapNotNull { task -> testLocationOf(task) }
                            .toMap()
                        testLocationsHolder.set(locations)
                        // Durable channel (plan 044): in a composite build the collector service is
                        // instantiated by included-build task events *before* this callback runs, freezing
                        // its params empty — so the finalizer reads these locations from the sidecar file
                        // instead. Under .gradle (like the salt) so it survives `clean` and tracks the CC
                        // entry. The holder/param above stays as the classpath-path fallback. The write
                        // respects the master switch (spec §3.4) — a disabled build must touch nothing on
                        // disk, parity with the salt/start-marker.
                        if (extension.enabled.get() && extension.mode.get() != TelemetryMode.DISABLED) {
                            TestLocationSidecar.write(settings.rootDir, locations)
                        }
                    }
                }
            }.onFailure {
                logger.warn("[buildhound] task metadata capture failed (build unaffected): {}", it.message)
            }
            // Toolchain versions (plan 046): its own guard, after the higher-stakes task/test capture,
            // so a detection failure can never block them. The seam wins for tests; otherwise walk every
            // configured project (not just those with a task in this invocation, so a narrow request like
            // `:core:common:assemble` still sees AGP) — skipped under isolated projects, where a
            // cross-project walk is illegal and the dimensions degrade to null like the task dictionary.
            runCatching {
                toolchainHolder.set(
                    when {
                        !toolchainSeam.isEmpty() -> toolchainSeam
                        isolatedProjects -> DetectedToolchain()
                        else -> ToolchainDetection.detect(settings.gradle.rootProject.allprojects)
                    },
                )
            }.onFailure {
                logger.warn("[buildhound] toolchain detection failed (build unaffected): {}", it.message)
            }
            // JVM archive-size locations (plan 072, research F22): its own guard, after the higher-stakes
            // task/test capture, so a failure can never block them. Filter graph.allTasks (only tasks
            // scheduled this build = "what's built") to core-Gradle AbstractArchiveTask whose name is a
            // known JVM archive; record the output *location* only (no file read → no CC input). Skipped
            // under isolated projects, where the graph walk is illegal — degrades to empty like the task
            // dictionary and toolchain triple. AbstractArchiveTask is public core API always on the plugin
            // classpath, so — unlike the plan-031 AGP path — there is no external classloader to fault.
            runCatching {
                jvmArtifactsHolder.set(
                    if (isolatedProjects) {
                        emptyList()
                    } else {
                        graph.allTasks.mapNotNull { task -> jvmArtifactLocationOf(task) }
                    },
                )
            }.onFailure {
                logger.warn("[buildhound] jvm artifact-location capture failed (build unaffected): {}", it.message)
            }
            // Internal-adapters capture (plan 074): driven from here, post-DSL, so the toggles are set.
            // The wiring is fully guarded and no-ops when every effective toggle is off — the point where
            // "applied the plugin" stops short of "consented to internal Gradle APIs". Registering the
            // daemon listeners here (not at apply) mirrors the WARN-log listener's original site; on a CC
            // hit whenReady is skipped and capture rides the daemon-static listener from the first miss.
            //
            // The master switch gates every toggle: with telemetry off (enabled=false or mode=DISABLED)
            // the finalizer short-circuits before the read-and-clear collector runs, so a capturing build
            // here would leave stale rows in the daemon-static accumulator that a later on-build would
            // emit. Forcing the toggles false when off means the wiring still calls configure() (resetting
            // the daemon-static toggles, so a lingering listener from an earlier on-build gates to no-op)
            // but registers nothing and captures nothing — parity with the other master-gated collectors.
            val telemetryOn = extension.enabled.get() && extension.mode.get() != TelemetryMode.DISABLED
            runCatching {
                InternalAdaptersWiring.install(
                    settings = settings,
                    graph = graph,
                    collectCacheOrigins = telemetryOn && extension.internalAdapters.collectCacheOrigins.get(),
                    collectDeprecations = telemetryOn && extension.internalAdapters.collectDeprecations.get(),
                    collectLogWarnings = telemetryOn && extension.internalAdapters.collectLogWarnings.get(),
                    perFileHashes = telemetryOn && extension.internalAdapters.perFileHashes.get(),
                )
            }.onFailure {
                logger.warn("[buildhound] internal-adapters wiring failed (build unaffected): {}", it.message)
            }
        }

        // Salt IO happens inside the ValueSource at execution time: config-phase file
        // access is a CC fingerprint input, and creating the salt at apply time would
        // invalidate the next build's cache entry. Only the path is captured here.
        val environment = settings.providers.of(EnvironmentValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.pseudonymize.set(extension.identity.pseudonymize)
            spec.parameters.identitySaltFile.set(saltPath)
            // Plaintext workers.max (plan 065): the same CC-safe start-parameter scalar the
            // fingerprints (plan 022) and invocation posture (plan 051) capture below.
            spec.parameters.workersMax.set(settings.startParameter.maxWorkerCount)
        }

        val vcs = settings.providers.of(VcsValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.rootDir.set(settings.rootDir.absolutePath)
            // Test seam + escape hatch (plan 015) for repos where a healthy git needs >10 s.
            spec.parameters.timeoutMillis.set(
                settings.providers.gradleProperty("buildhound.vcs.timeout.ms")
                    .map { raw -> raw.toLongOrNull()?.takeIf { it > 0 } ?: GitExec.DEFAULT_TIMEOUT_MS },
            )
            // Discover the enclosing repo from a nested Gradle root (plan 050). Only an explicit
            // "false" confines the probes to rootDir (pre-050, fail-closed); absent or an
            // unrecognized value keeps the default-on discovery via the ValueSource's getOrElse(true),
            // so a typo never silently disables it (fail toward the default, not toward confined).
            spec.parameters.searchParents.set(
                settings.providers.gradleProperty("buildhound.vcs.searchParents")
                    .map { !it.trim().equals("false", ignoreCase = true) },
            )
        }

        val ci = settings.providers.of(CiValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
        }

        // Change blast-radius attribution (plan 063, research F13): the module-dir index is baked from
        // the `settingsEvaluated` walk above (CC-keyed module structure); every git call happens inside
        // obtain() at execution time (the VcsValueSource CC rationale) so nothing here becomes a CC
        // fingerprint input and the diff stays fresh across a CC hit. targetBranch is wired from the CI
        // context's PR base ref (execution-time value source), the vcs.timeout/searchParents knobs are
        // shared with VcsValueSource, and lastShaPath is the previous-build HEAD file the finalizer writes.
        val changedModules = settings.providers.of(ChangedModulesValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.rootDir.set(settings.rootDir.absolutePath)
            spec.parameters.timeoutMillis.set(
                settings.providers.gradleProperty("buildhound.vcs.timeout.ms")
                    .map { raw -> raw.toLongOrNull()?.takeIf { it > 0 } ?: GitExec.DEFAULT_TIMEOUT_MS },
            )
            spec.parameters.searchParents.set(
                settings.providers.gradleProperty("buildhound.vcs.searchParents")
                    .map { !it.trim().equals("false", ignoreCase = true) },
            )
            spec.parameters.lastShaPath.set(File(settings.rootDir, LAST_BUILT_SHA_PATH).absolutePath)
            // CI PR base ref (plan 041): a merge-base diff scoped to the PR's own changes. An absent CI
            // context, or a CI context with no target branch, both leave this unset (`.map` on an absent
            // provider stays absent) → obtain() falls through to the last-built-sha base.
            spec.parameters.targetBranch.set(ci.map { it.targetBranch })
            spec.parameters.moduleDirIndex.set(settings.providers.provider { moduleDirIndexHolder.get() })
        }

        // Benchmark activation (plan 030): reads BUILDHOUND_BENCHMARK_* at execution time (no CC input),
        // same discipline as CiValueSource. A present, valid scenario forces mode=benchmark.
        val benchmark = settings.providers.of(BenchmarkValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
        }

        // Android artifact-size collector (plan 031): a per-project reaction wired from the settings
        // context. beforeProject is the isolated-projects-safe hook; the collector class-references no
        // AGP (its AGP-touching delegates are runCatching-guarded), so a non-Android build links
        // nothing and even an AGP link failure degrades to no artifacts, never a failed build. The
        // measuring tasks run at execution time; the Flow finalizer reads their output at build end.
        if (masterEnabled) {
            installAndroidArtifactCollector(settings.gradle, File(settings.rootDir, "build/buildhound/artifacts"))
            // Per-project configuration-time attribution (plan 052): a per-project reaction wired the
            // same way — beforeProject/afterProject are themselves the isolated-projects-safe hooks,
            // deliberately NOT further gated on isolated projects (there is no cross-project state here
            // to degrade). Under `.gradle` (not `build/`) so a same-invocation `clean` can't wipe it (the
            // plan-044 rationale, same as the identity salt / test-location sidecar).
            installProjectEvaluationCollector(settings.gradle, File(settings.rootDir, ".gradle/buildhound/config-timings"))
            // Build-structure inventory (plan 069, research F19): projectsLoaded is the earliest
            // configuration-time hook where both the descriptor tree AND includedBuilds are safe to
            // read (settingsEvaluated throws on Gradle.includedBuilds, verified empirically) — still
            // before any project's build script evaluates. Gated on the raw master switch, like the
            // two installers above — not the DSL-overridable extension.enabled — so a later
            // `buildhound { enabled = true }` cannot re-arm a walk this switch already skipped (the
            // same accepted limitation those two installers carry).
            settings.gradle.projectsLoaded {
                runCatching {
                    check(!failBuildStructureSnapshot) { "build-structure snapshot failpoint" }
                    buildStructureHolder.set(BuildStructureWalker.walk(settings))
                }.onFailure {
                    logger.warn("[buildhound] build-structure descriptor walk failed (build unaffected): {}", it.message)
                }
            }
        }

        // End-of-build JVM process probe (plan 029). enabled is master AND the block toggle; the exec
        // timeout is the plan-015 test-seam/escape-hatch property. All exec is inside obtain() at
        // execution time, so CC store/reuse and isolated projects are unaffected.
        val processes = settings.providers.of(ProcessProbeValueSource::class.java) { spec ->
            spec.parameters.enabled.set(
                extension.enabled.zip(extension.processProbe.enabled) { master, probe -> master && probe },
            )
            spec.parameters.timeoutMillis.set(
                settings.providers.gradleProperty("buildhound.processprobe.timeout.ms")
                    .map { raw -> raw.toLongOrNull()?.takeIf { it > 0 } ?: GitExec.DEFAULT_TIMEOUT_MS },
            )
            // Internal seam: TestKit can't PATH-shadow the daemon's jps, so the timeout
            // failure-injection test points this at a fake hanging binary. Absent → "jps".
            spec.parameters.jpsExecutable.set(
                settings.providers.gradleProperty("buildhound.internal.processprobe.jps"),
            )
        }

        // Build-level input fingerprints (plan 022). Allowlists flow as ValueSource params and
        // resolve at execution (after the DSL runs); Gradle-property *values* are pre-resolved
        // via `providers.gradleProperty` (they are CC inputs already). parallel/maxWorkers are
        // CC-safe start-parameter scalars captured here.
        val fingerprints = settings.providers.of(FingerprintValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.identitySaltFile.set(saltPath)
            spec.parameters.systemProperties.set(extension.fingerprints.systemProperties)
            spec.parameters.envVars.set(extension.fingerprints.envVars)
            spec.parameters.gradleProperties.set(
                // An unset property (or one set to "") is treated as absent — no gradleProp-<name>
                // key. providers.gradleProperty stays a CC input even resolved eagerly here.
                extension.fingerprints.gradleProperties.map { names ->
                    names.associateWith { name -> settings.providers.gradleProperty(name).getOrElse("") }
                        .filterValues { it.isNotEmpty() }
                },
            )
            spec.parameters.parallel.set(settings.startParameter.isParallelProjectExecutionEnabled)
            spec.parameters.maxWorkers.set(settings.startParameter.maxWorkerCount)
        }

        // Invocation-switch & performance-flag posture (plan 051). The seven StartParameter scalars,
        // plus the -P project-properties map (also only reachable at apply()-time), are baked
        // config-time params (same narrowing as the fingerprints parallel/maxWorkers above); only the
        // two gradle.properties *locations* are captured here — reading them is deferred to the
        // ValueSource's obtain() (execution time), so the allowlist block re-freshens on a CC hit.
        val invocation = settings.providers.of(InvocationValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.buildCacheEnabled.set(settings.startParameter.isBuildCacheEnabled)
            spec.parameters.offline.set(settings.startParameter.isOffline)
            spec.parameters.rerunTasks.set(settings.startParameter.isRerunTasks)
            spec.parameters.refreshDependencies.set(settings.startParameter.isRefreshDependencies)
            spec.parameters.configureOnDemand.set(settings.startParameter.isConfigureOnDemand)
            spec.parameters.maxWorkerCount.set(settings.startParameter.maxWorkerCount)
            spec.parameters.parallel.set(settings.startParameter.isParallelProjectExecutionEnabled)
            spec.parameters.projectPropertiesPath.set(File(settings.rootDir, "gradle.properties").absolutePath)
            spec.parameters.gradleUserHomePropertiesPath.set(
                File(settings.startParameter.gradleUserHomeDir, "gradle.properties").absolutePath,
            )
            // android.* keys are AGP-read project properties, not Gradle properties: their real
            // command-line override channel is -P (StartParameter.projectProperties), not -D. Baked
            // here alongside the scalars above (StartParameter is only reachable at apply()-time);
            // GradlePropertyProvenance picks this vs. the -D sysprop channel per key family (051 review).
            spec.parameters.cliProjectProperties.set(settings.startParameter.projectProperties)
        }

        // Build-structure inventory (plan 069, research F19): the projectsLoaded walk above bakes
        // the descriptor map + counts into these ValueSource parameters at configuration time; obtain()
        // runs the filesystem .exists() probes at execution time (VcsValueSource/FingerprintValueSource
        // CC rationale), so nothing here becomes a configuration-cache fingerprint input, and the
        // probes stay fresh across a CC hit like those two ValueSources' own probes do.
        val buildStructure = settings.providers.of(BuildStructureValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.rootDir.set(settings.rootDir.absolutePath)
            spec.parameters.projectCount.set(settings.providers.provider { buildStructureHolder.get().projectCount })
            spec.parameters.maxDepth.set(settings.providers.provider { buildStructureHolder.get().maxDepth })
            spec.parameters.includedBuildCount.set(
                settings.providers.provider { buildStructureHolder.get().includedBuildCount },
            )
            spec.parameters.descriptors.set(settings.providers.provider { buildStructureHolder.get().descriptors })
        }

        // Wrapper & startup-phase telemetry (plan 066, research F16): only the properties/jar
        // *locations* + the resolved Gradle User Home dir are captured here; every file read,
        // hash, and GUH dist probe happens in obtain() at execution time (same CC rationale as
        // fingerprints/invocation above), so nothing here becomes a configuration-cache input.
        val wrapper = settings.providers.of(WrapperValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.propertiesFile.set(
                File(settings.rootDir, "gradle/wrapper/gradle-wrapper.properties").absolutePath,
            )
            spec.parameters.jarFile.set(File(settings.rootDir, "gradle/wrapper/gradle-wrapper.jar").absolutePath)
            spec.parameters.gradleUserHomeDir.set(settings.startParameter.gradleUserHomeDir.absolutePath)
        }

        // Flow API is the CC-safe "build finished" hook (spec §3.2). The finalizer
        // assembles the payload and writes it next to the build outputs; the HTML
        // artifact and upload chunks build on it. It must never fail the build.
        flowScope.always(TelemetryFinalizerAction::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.mode.set(extension.mode)
            spec.parameters.tags.set(extension.tags)
            spec.parameters.collector.set(collector)
            // AGP/KGP/KSP versions (plan 046): a finalizer parameter (not a collector service param) so
            // the provider resolves after configuration — after `whenReady` fills the mailbox — even in
            // a composite build where an included build's task instantiates the collector early. The
            // resolved value is baked into the CC entry and replayed on a hit.
            spec.parameters.toolchain.set(settings.providers.provider { toolchainHolder.get() })
            // JVM archive-size locations (plan 072, research F22): the same after-configuration
            // Flow-action channel as [toolchain] — resolved after `whenReady` fills the mailbox, baked
            // into the CC entry, replayed on a hit. The finalizer reads File.length() at execution time.
            spec.parameters.jvmArtifacts.set(settings.providers.provider { jvmArtifactsHolder.get() })
            // Task type/cacheable dictionary (plan 016), same finalizer-parameter channel as
            // [toolchain] above (plan 056, closes plan 045): the finalizer is the dictionary's sole
            // reader, so it no longer rides the collector-service param that a composite build's
            // included-build task events freeze empty before `whenReady` runs (plan 044).
            spec.parameters.taskMetadata.set(settings.providers.provider { taskMetadataHolder.get() })
            spec.parameters.fingerprints.set(fingerprints)
            spec.parameters.buildFailed.set(flowProviders.buildWorkResult.map { it.failure.isPresent })
            // Failure detail (plan 044): extract class/message/stacktrace from the failing Throwable
            // inside the provider map — runs at finalization (execution time), so it stays CC-safe and
            // the output is a plain serializable holder. Absent (no value) on a successful build.
            spec.parameters.failure.set(
                flowProviders.buildWorkResult.map { result ->
                    result.failure.map(FailureExtractor::extract).orElse(null)
                },
            )
            spec.parameters.environment.set(environment)
            spec.parameters.invocation.set(invocation)
            spec.parameters.vcs.set(vcs)
            // Change blast-radius attribution (plan 063): the ValueSource re-obtains on a CC hit like
            // vcs (same replay caveat) — an absent (no base resolvable) value leaves the block off the payload.
            spec.parameters.changedModules.set(changedModules)
            spec.parameters.ci.set(ci)
            spec.parameters.benchmark.set(benchmark)
            spec.parameters.processes.set(processes)
            spec.parameters.buildStructure.set(buildStructure)
            // Isolated-projects flag (plan 069, research F19): the plugin already computes this at
            // whenReady (line ~140 above) but had never shipped it. A plain scalar baked at apply()
            // time — parallel to configurationCacheRequested below — so it stays accurate whether the
            // finalizer runs after a CC store or replays a CC hit.
            spec.parameters.isolatedProjectsActive.set(buildFeatures.isolatedProjects.active.getOrElse(false))
            spec.parameters.wrapper.set(wrapper)
            // Build-cache config snapshot (plan 067, research F17): the same after-configuration
            // Flow-action channel as [toolchain] — the provider resolves after `settingsEvaluated` fills
            // the mailbox, is baked into the CC entry, and replays on a hit (correct: cache config is
            // stable across builds). Public Settings.buildCache read only; no URL/path ever captured.
            spec.parameters.buildCache.set(settings.providers.provider { buildCacheHolder.get() })
            spec.parameters.configurationCacheRequested.set(buildFeatures.configurationCache.requested.getOrElse(false))
            // org.gradle.configuration-cache.parallel flag (plan 064, research F14): a provider read of
            // the Gradle property — a tracked CC input, resolved after config and replayed on a hit —
            // never System.getProperty. BuildFeatures surfaces only .requested/.active, so the flag has
            // to come from the property (plan-064 divergence note). Unset → the Optional param stays absent.
            spec.parameters.configurationCacheParallel.set(
                settings.providers.gradleProperty("org.gradle.configuration-cache.parallel").map { it.toBoolean() },
            )
            // Lazy: the settings script sets rootProject.name after apply() runs.
            spec.parameters.projectKey.set(settings.providers.provider { settings.rootProject.name })
            spec.parameters.requestedTasks.set(settings.startParameter.taskNames.toList())
            spec.parameters.outputDir.set(File(settings.rootDir, "build/buildhound").absolutePath)
            spec.parameters.htmlReportEnabled.set(extension.htmlReport.enabled)
            // KGP build-report bundling (plan 023): read-only capture of KGP's own report
            // properties (never mutated) + our bundle toggle. gradle properties are CC inputs.
            spec.parameters.kotlinBundle.set(extension.kotlinReports.bundle)
            spec.parameters.kotlinReportOutput.set(settings.providers.gradleProperty("kotlin.build.report.output"))
            spec.parameters.kotlinJsonDirectory.set(settings.providers.gradleProperty("kotlin.build.report.json.directory"))
            // Test collection (plan 024): the toggle, plus an internal failure-injection seam
            // (any value but "false" arms the "collection throws" failpoint) mirroring the
            // task-graph-snapshot failpoint above.
            spec.parameters.testsCollect.set(extension.tests.collect)
            spec.parameters.failTestCollection.set(
                settings.providers.gradleProperty("buildhound.internal.failTestCollection")
                    .map { it != "false" }
                    .orElse(false),
            )
            spec.parameters.rootDir.set(settings.rootDir.absolutePath)
            spec.parameters.serverUrl.set(extension.server.url)
            spec.parameters.serverToken.set(extension.server.token)
            spec.parameters.localBuildsEnabled.set(extension.localBuilds.enabled)
            spec.parameters.requireOptInFile.set(extension.localBuilds.requireOptInFile)
            // uploadInBackground (plan 027): a local build spools instead of attempting the inline upload.
            spec.parameters.uploadInBackground.set(extension.upload.uploadInBackground)
            // Test seam + advanced override; default (~/.buildhound/optin) resolves at execution.
            spec.parameters.optInFile.set(settings.providers.gradleProperty("buildhound.optin.file"))
        }
    }

    /**
     * The JUnit XML dir + module for one `Test` task, via the public Test report API (plan 024).
     * Runs at configuration time inside `whenReady`, so resolving the output location is CC-safe
     * (spike §4a). Null when the task is not a `Test` or the location is unresolvable.
     *
     * [TestResultLocations.junitXmlRequired] (plan 053, research F3) is a sibling read on the same
     * `DirectoryReport` — `Report.getRequired(): Property<Boolean>`, public API, no internal Gradle
     * type. It must run here, in `whenReady`, never in `apply()`: `required` (like `outputLocation`)
     * only reflects the task's real configuration after `afterEvaluate`, which `whenReady` guarantees
     * and `apply()` does not.
     */
    private fun testLocationOf(task: Task): Pair<String, TestResultLocations>? {
        val test = task as? Test ?: return null
        val dir = test.reports.junitXml.outputLocation.orNull?.asFile?.absolutePath ?: return null
        val module = task.path.substringBeforeLast(':').ifEmpty { ":" }
        val junitXmlRequired = test.reports.junitXml.required.getOrElse(true)
        return task.path to TestResultLocations(junitXmlDir = dir, module = module, junitXmlRequired = junitXmlRequired)
    }

    /**
     * The output location + kind for one core-Gradle JVM archive task (plan 072, research F22), or null
     * when [task] is not an [AbstractArchiveTask] whose name is a known JVM archive
     * (`bootJar`/`bootWar`/`jar`/`war`). Runs at configuration time inside `whenReady`, so resolving the
     * archive-file *location* is CC-safe (a resolved path, not a file read — mirrors [testLocationOf]).
     * The `bootJar`/`bootWar` names cover the Spring Boot deliverables; `jar`/`war` the plain archives.
     */
    private fun jvmArtifactLocationOf(task: Task): JvmArtifactLocation? {
        val archive = task as? AbstractArchiveTask ?: return null
        val kind = JVM_ARCHIVE_KINDS[task.name] ?: return null
        val archivePath = archive.archiveFile.orNull?.asFile?.absolutePath ?: return null
        val module = task.path.substringBeforeLast(':').ifEmpty { ":" }
        return JvmArtifactLocation(module = module, kind = kind, taskPath = task.path, archivePath = archivePath)
    }

    private companion object {
        const val SALT_PATH = ".gradle/buildhound/identity.salt"

        /** Previous-build HEAD sha, the LAST_BUILT_SHA diff base (plan 063); under `.gradle` so it survives `clean`. */
        const val LAST_BUILT_SHA_PATH = ".gradle/buildhound/last-built-sha"
        val logger = Logging.getLogger(BuildHoundSettingsPlugin::class.java)

        /**
         * Canonical JVM archive task names → [JvmArtifactKind] (plan 072). The Spring Boot deliverables
         * (`bootJar`/`bootWar`) plus the plain Java/War archives; a task must ALSO be an
         * [AbstractArchiveTask] to match, so an unrelated task that happens to share one of these names
         * is not captured.
         */
        val JVM_ARCHIVE_KINDS: Map<String, JvmArtifactKind> = mapOf(
            "bootJar" to JvmArtifactKind.BOOT_JAR,
            "bootWar" to JvmArtifactKind.BOOT_WAR,
            "jar" to JvmArtifactKind.JAR,
            "war" to JvmArtifactKind.WAR,
        )
    }
}

/**
 * Registers the [AndroidArtifactCollector] `beforeProject` reaction (plan 031). Deliberately a
 * top-level function so the `IsolatedAction` captures only [artifactsDir] (a serializable File) —
 * never the plugin instance, which is not serializable across the isolated-projects/CC boundary.
 */
@Suppress("UnstableApiUsage")
private fun installAndroidArtifactCollector(gradle: org.gradle.api.invocation.Gradle, artifactsDir: File) {
    gradle.lifecycle.beforeProject { project ->
        runCatching { AndroidArtifactCollector.install(project, artifactsDir) }
            .onFailure { project.logger.info("[buildhound] android artifact collector unavailable: {}", it::class.java.simpleName) }
    }
}

/**
 * Registers the per-project configuration-time collector (plan 052, research §F2): `beforeProject`
 * marks the start via a project-scoped extra property — the only state the two `IsolatedAction`s can
 * share, since each runs isolated and cannot see a plugin-captured mutable holder (narrowing 2) —
 * and `afterProject` computes the elapsed time and overwrites this project's sidecar file under
 * [timingsDir]. Deliberately a top-level function, like [installAndroidArtifactCollector], so both
 * `IsolatedAction`s capture only [timingsDir] (a serializable `File`) — never the plugin instance.
 *
 * Not isolated-projects-gated: `beforeProject`/`afterProject` are themselves the sanctioned
 * isolated-projects-safe hooks (unlike `taskGraph.allTasks`), and each project's timing is entirely
 * self-contained — there is no cross-project state here that could need to degrade under IP.
 */
@Suppress("UnstableApiUsage")
private fun installProjectEvaluationCollector(gradle: org.gradle.api.invocation.Gradle, timingsDir: File) {
    gradle.lifecycle.beforeProject { project ->
        runCatching { project.extensions.extraProperties.set(PROJECT_EVAL_START_KEY, System.nanoTime()) }
            .onFailure { project.logger.info("[buildhound] project-evaluation timing start unavailable: {}", it::class.java.simpleName) }
    }
    gradle.lifecycle.afterProject { project ->
        runCatching {
            val extra = project.extensions.extraProperties
            if (!extra.has(PROJECT_EVAL_START_KEY)) return@runCatching
            val startNanos = extra.get(PROJECT_EVAL_START_KEY) as? Long ?: return@runCatching
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            ProjectEvalRecordIo.write(timingsDir, project.path, elapsedMs)
        }.onFailure { project.logger.info("[buildhound] project-evaluation timing capture unavailable: {}", it::class.java.simpleName) }
    }
}

/** Project-scoped `extraProperties` key correlating [installProjectEvaluationCollector]'s two hooks. */
private const val PROJECT_EVAL_START_KEY = "dev.buildhound.projectEvalStartNanos"
