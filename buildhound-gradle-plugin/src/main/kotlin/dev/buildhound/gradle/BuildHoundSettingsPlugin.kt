package dev.buildhound.gradle

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

        // In a composite, only the root build observes: task events from included builds
        // already reach the root's listener, and a second flow action would consume the
        // DaemonState mark twice and emit duplicate summaries (review finding, plan 003).
        if (settings.gradle.parent != null) {
            logger.info("[buildhound] applied in an included build; the root build's plugin collects")
            return
        }

        DaemonState.configurationRan()

        // Per-apply mailbox for the configuration-time task dictionary. The service reads
        // it through a lazy provider (below); the `whenReady` callback fills it. Gradle
        // evaluates the provider when it finalizes the service parameters — after
        // configuration in every mode — so ordering is always callback-then-read (plan 016).
        val taskMetadataHolder = AtomicReference<Map<String, TaskMetadata>>(emptyMap())
        // Sibling mailbox for the Test-task JUnit XML locations (plan 024), filled by the same
        // `whenReady` callback and replayed from the CC entry on a hit (discovery spike §4a).
        val testLocationsHolder = AtomicReference<Map<String, TestResultLocations>>(emptyMap())

        val collector = settings.gradle.sharedServices.registerIfAbsent(
            TaskEventCollector.SERVICE_NAME,
            TaskEventCollector::class.java,
        ) { spec ->
            spec.parameters.taskMetadata.set(settings.providers.provider { taskMetadataHolder.get() })
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
        val collectTests = extension.tests.collect
        settings.gradle.taskGraph.whenReady { graph ->
            DaemonState.configurationCompleted()
            runCatching {
                check(!failTaskGraphSnapshot) { "task-graph snapshot failpoint" }
                if (buildFeatures.isolatedProjects.active.getOrElse(false)) {
                    logger.info("[buildhound] isolated projects active; task metadata dictionary left empty")
                    return@runCatching
                }
                taskMetadataHolder.set(
                    graph.allTasks.associate { task -> task.path to TaskClassIntrospection.introspect(task.javaClass) },
                )
                // Test-result locations (plan 024): capture each Test task's JUnit XML dir now, at
                // config time (spike §4a) — the XML itself is read in the finalizer. Public Test API
                // only; the decision to treat a task as a Test task is the type-free introspection.
                if (collectTests.getOrElse(true)) {
                    testLocationsHolder.set(
                        graph.allTasks
                            .filter { TestTaskIntrospection.isTestTask(it.javaClass) }
                            .mapNotNull { task -> testLocationOf(task) }
                            .toMap(),
                    )
                }
            }.onFailure {
                logger.warn("[buildhound] task metadata capture failed (build unaffected): {}", it.message)
            }
        }

        // Salt IO happens inside the ValueSource at execution time: config-phase file
        // access is a CC fingerprint input, and creating the salt at apply time would
        // invalidate the next build's cache entry. Only the path is captured here.
        val environment = settings.providers.of(EnvironmentValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.pseudonymize.set(extension.identity.pseudonymize)
            spec.parameters.identitySaltFile.set(saltPath)
        }

        val vcs = settings.providers.of(VcsValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.rootDir.set(settings.rootDir.absolutePath)
            // Test seam + escape hatch (plan 015) for repos where a healthy git needs >10 s.
            spec.parameters.timeoutMillis.set(
                settings.providers.gradleProperty("buildhound.vcs.timeout.ms")
                    .map { raw -> raw.toLongOrNull()?.takeIf { it > 0 } ?: GitExec.DEFAULT_TIMEOUT_MS },
            )
        }

        val ci = settings.providers.of(CiValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
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

        // Flow API is the CC-safe "build finished" hook (spec §3.2). The finalizer
        // assembles the payload and writes it next to the build outputs; the HTML
        // artifact and upload chunks build on it. It must never fail the build.
        flowScope.always(TelemetryFinalizerAction::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.mode.set(extension.mode)
            spec.parameters.tags.set(extension.tags)
            spec.parameters.collector.set(collector)
            spec.parameters.fingerprints.set(fingerprints)
            spec.parameters.buildFailed.set(flowProviders.buildWorkResult.map { it.failure.isPresent })
            spec.parameters.environment.set(environment)
            spec.parameters.vcs.set(vcs)
            spec.parameters.ci.set(ci)
            spec.parameters.benchmark.set(benchmark)
            spec.parameters.processes.set(processes)
            spec.parameters.configurationCacheRequested.set(buildFeatures.configurationCache.requested.getOrElse(false))
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
     */
    private fun testLocationOf(task: Task): Pair<String, TestResultLocations>? {
        val test = task as? Test ?: return null
        val dir = test.reports.junitXml.outputLocation.orNull?.asFile?.absolutePath ?: return null
        val module = task.path.substringBeforeLast(':').ifEmpty { ":" }
        return task.path to TestResultLocations(junitXmlDir = dir, module = module)
    }

    private companion object {
        const val SALT_PATH = ".gradle/buildhound/identity.salt"
        val logger = Logging.getLogger(BuildHoundSettingsPlugin::class.java)
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
