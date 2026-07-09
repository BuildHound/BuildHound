package dev.buildhound.gradle

import dev.buildhound.commons.payload.ArtifactSize
import dev.buildhound.commons.payload.BuildHoundCollectorRegistry
import dev.buildhound.commons.payload.BuildHoundExtensionContributor
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.ExtensionContributionContext
import dev.buildhound.commons.payload.FingerprintInfo
import dev.buildhound.commons.payload.JvmArtifactSize
import dev.buildhound.commons.payload.PayloadScrubber
import dev.buildhound.commons.payload.ProjectEvaluation
import dev.buildhound.commons.payload.StartMarker
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.TestTelemetryInfo
import dev.buildhound.report.ReportAssets
import java.io.File
import java.util.ServiceLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Runs once, after the build finished (spec §3.2): assembles the schema-v1 payload and
 * writes it to `build/buildhound/build-payload.json` (HTML artifact + upload build on
 * it in later chunks). Hard rule: never fails the build — every error is logged at
 * warn and swallowed.
 */
@Suppress("UnstableApiUsage")
class TelemetryFinalizerAction : FlowAction<TelemetryFinalizerAction.Parameters> {

    interface Parameters : FlowParameters {
        @get:Input
        val enabled: Property<Boolean>

        @get:Input
        val mode: Property<TelemetryMode>

        @get:Input
        val tags: MapProperty<String, String>

        @get:ServiceReference
        val collector: Property<TaskEventCollector>

        /**
         * Detected AGP/KGP/KSP versions (plan 046). A finalizer parameter — not a [collector] service
         * parameter — because the value is only known after `whenReady` runs, and in a composite build
         * an included build's task can instantiate the collector service (freezing its parameters)
         * during the root's configuration, before `whenReady`. The finalizer's parameters are resolved
         * after configuration, so the mailbox is populated by then; the value is replayed on a CC hit.
         */
        @get:Input
        @get:Optional
        val toolchain: Property<DetectedToolchain>

        /**
         * JVM archive-task output locations (plan 072, research F22), delivered on the same
         * after-configuration Flow-action channel as [toolchain] — resolved after `whenReady` fills the
         * mailbox, immune to the composite-build service-param freeze, replayed on a CC hit. Locations
         * only; the finalizer reads `File.length()` at execution time, gated on the task's
         * produced-output outcome + `File.exists()` (only-what-ran). Empty when no archive task ran.
         */
        @get:Input
        @get:Optional
        val jvmArtifacts: ListProperty<JvmArtifactLocation>

        /**
         * Task path → static type/cacheable/nonCacheableReason dictionary (plan 016), delivered as a
         * finalizer parameter rather than a collector-service parameter (plan 056, closes plan 045).
         * The finalizer is the dictionary's sole reader, so — exactly like [toolchain] (plan 046) —
         * it rides a Flow-action parameter, resolved after configuration, immune to the
         * composite-build hazard where an included build's task-finish event instantiates the
         * collector *service* (freezing its params) before the root's `whenReady` fills the mailbox
         * (plan 044). Missing keys (isolated projects, a capture failure) join to null — a total
         * lookup, never a partial one.
         */
        @get:Input
        val taskMetadata: MapProperty<String, TaskMetadata>

        @get:Input
        @get:Optional
        val fingerprints: Property<CollectedFingerprints>

        @get:Input
        val buildFailed: Property<Boolean>

        /** Build-failure detail (plan 044); absent on a successful build. */
        @get:Input
        @get:Optional
        val failure: Property<CollectedFailure>

        @get:Input
        val environment: Property<CollectedEnvironment>

        /**
         * Invocation-switch & performance-flag posture (plan 051). Always set, like [environment]/
         * [vcs]: [InvocationValueSource.obtain] returns an empty (not absent) [CollectedInvocation]
         * when disabled, so this is never actually optional.
         */
        @get:Input
        val invocation: Property<CollectedInvocation>

        @get:Input
        val vcs: Property<CollectedVcs>

        /**
         * Change blast-radius attribution (plan 063, research F13); optional/absent when no diff base
         * resolved (no CI target branch and no `last-built-sha` file, git absent/timeout/detached HEAD).
         */
        @get:Input
        @get:Optional
        val changedModules: Property<CollectedChangedModules>

        @get:Input
        @get:Optional
        val ci: Property<CollectedCi>

        /** Benchmark activation context (plan 030); optional/absent on non-benchmark builds. */
        @get:Input
        @get:Optional
        val benchmark: Property<CollectedBenchmark>

        /** JVM process snapshot (plan 029); optional/empty when disabled or JDK tools are absent. */
        @get:Input
        @get:Optional
        val processes: ListProperty<CollectedProcess>

        /**
         * Declared build-structure inventory (plan 069, research F19). Always set, like
         * [environment]/[invocation]: [BuildStructureValueSource.obtain] returns an empty (not
         * absent) [CollectedBuildStructure] when disabled or uncaptured (master switch off at apply
         * time, or a guarded walk/probe failure), so this is never actually optional.
         */
        @get:Input
        val buildStructure: Property<CollectedBuildStructure>

        /**
         * Isolated-projects activation flag (plan 069): the plugin already computes this at
         * `whenReady` but had never shipped it. A plain scalar baked at apply() time — parallel to
         * [configurationCacheRequested] — so it stays accurate on both a CC store and a replayed hit.
         */
        @get:Input
        val isolatedProjectsActive: Property<Boolean>

        /**
         * Wrapper & startup-phase telemetry (plan 066, research F16). Always set, like
         * [environment]/[invocation]: [WrapperValueSource.obtain] returns an empty (not absent)
         * [CollectedWrapper] when disabled or every probe degraded, so this is never actually
         * optional.
         */
        @get:Input
        val wrapper: Property<CollectedWrapper>

        /**
         * Committed build-cache config snapshot (plan 067, research F17), delivered on the same
         * after-configuration Flow-action channel as [toolchain] — resolved after `settingsEvaluated`
         * fills the mailbox, baked into the CC entry, replayed on a hit (cache config is stable across
         * builds). Optional/empty when uncaptured (a guarded `settingsEvaluated` read failure).
         */
        @get:Input
        @get:Optional
        val buildCache: Property<BuildCacheConfigSnapshot>

        @get:Input
        val configurationCacheRequested: Property<Boolean>

        @get:Input
        val projectKey: Property<String>

        @get:Input
        val requestedTasks: ListProperty<String>

        @get:Input
        val outputDir: Property<String>

        @get:Input
        val htmlReportEnabled: Property<Boolean>

        @get:Input
        val testsCollect: Property<Boolean>

        /** Internal failure-injection seam (`buildhound.internal.failTestCollection`); default off. */
        @get:Input
        val failTestCollection: Property<Boolean>

        @get:Input
        val kotlinBundle: Property<Boolean>

        @get:Input
        @get:Optional
        val kotlinReportOutput: Property<String>

        @get:Input
        @get:Optional
        val kotlinJsonDirectory: Property<String>

        @get:Input
        val rootDir: Property<String>

        @get:Input
        @get:Optional
        val serverUrl: Property<String>

        @get:Input
        @get:Optional
        val serverToken: Property<String>

        @get:Input
        val localBuildsEnabled: Property<Boolean>

        @get:Input
        val requireOptInFile: Property<Boolean>

        /** uploadInBackground (plan 027): a local build spools instead of an inline upload attempt. */
        @get:Input
        val uploadInBackground: Property<Boolean>

        /** Override for the opt-in marker path (`buildhound.optin.file`); default ~/.buildhound/optin. */
        @get:Input
        @get:Optional
        val optInFile: Property<String>
    }

    override fun execute(parameters: Parameters) {
        runCatching {
            // Consume the heuristic mark first, unconditionally: a stale mark would make
            // the next build in this daemon misreport its CC state.
            val execution = DaemonState.executionRan()
            // Drain (read-then-clear) the per-project config-timings sidecar next, also
            // unconditionally — before the enabled/mode short-circuits and regardless of CC state
            // (052 review fix). The beforeProject/afterProject writer is gated only by the
            // apply-time master switch, so a DSL-disabled (enabled=false / mode=DISABLED) build
            // still writes timing files; if this finalizer skipped the clear, a LATER enabled build
            // would read them and misattribute the previous build's timings to itself. Clearing and
            // *reporting* are separate decisions: whether the drained records enter the payload is
            // decided below (null on a CC hit; moot on the early returns). Own guard — not the
            // outer one — so a corrupt/locked dir degrades to "no block" without aborting the rest
            // of finalization or writing a failure marker for a disabled build.
            val drainedEvaluations = runCatching {
                parameters.rootDir.orNull?.let { readProjectEvaluations(File(it)) }
            }.getOrElse {
                logger.info("[buildhound] config-timings sidecar drain failed (build unaffected): {}", it.message)
                null
            }
            // Master switch (spec §3.4): nothing is probed, assembled, or logged when off —
            // the value-source providers are never queried, so no salt is created either.
            if (!parameters.enabled.getOrElse(true)) return@runCatching
            val configuredMode = parameters.mode.getOrElse(TelemetryMode.AUTO)
            // disabled behaves like enabled=false: nothing is probed (spec §3.4).
            if (configuredMode == TelemetryMode.DISABLED) return@runCatching

            val ci = parameters.ci.orNull
            val benchmark = parameters.benchmark.orNull
            val mode = PayloadAssembler.resolveMode(configuredMode, ci, benchmark) ?: return@runCatching

            val collector = parameters.collector.get()
            // Task type/cacheable/nonCacheableReason dictionary (plan 016) joined here, not in the
            // collector's onFinish (plan 056, closes plan 045): the finalizer is now the dictionary's
            // sole reader, so it rides the same after-configuration Flow-action channel as toolchain
            // (plan 046) and is immune to the composite-build service-param freeze (plan 044). A
            // total lookup — a task with no dictionary entry (isolated projects, capture failure)
            // simply keeps its already-null fields, never a partial join.
            val taskMetadata = parameters.taskMetadata.getOrElse(emptyMap())
            val tasks = joinTaskMetadata(collector.snapshot(), taskMetadata)
            // AGP/KGP/KSP versions detected at config time (plan 046); replayed on a CC hit.
            val toolchain = parameters.toolchain.getOrElse(DetectedToolchain())
            // Lost-build reconciliation (plan 033): before this build's own payload, delete this
            // build's marker (it finalized → not interrupted) and synthesize+route an INTERRUPTED
            // build for any *other* stale marker in `started/`. Best-effort; never fails the build.
            reconcileStartMarkers(parameters, ownBuildId = collector.buildId)
            val ccState = configurationCacheState(parameters.configurationCacheRequested.getOrElse(false), execution)
            // Config-cache hit skips configuration entirely; entry-load time is not
            // measured, so report 0 rather than the (absent) marks. Otherwise the marked
            // duration, or null when unmeasurable (plan 016).
            val configurationMs = if (ccState == ConfigurationCacheState.HIT) 0L else execution.configurationMs
            // Per-project configuration-time attribution (plan 052): beforeProject/afterProject write
            // directly at configuration time, so on a CC hit configuration never ran this build and
            // whatever the unconditional drain above collected can only be a prior build's leftovers —
            // mirror the configurationMs HIT branch above and report null rather than misattributing
            // stale data to this build. On a non-HIT build the drained records are this build's own
            // afterProject writes: every finalizer pass — enabled or not, HIT or not — clears the dir,
            // so a narrower next invocation never inherits a wider build's leftover per-project files
            // (the plan's stale-file-correctness risk).
            val projectEvaluations = if (ccState == ConfigurationCacheState.HIT) null else drainedEvaluations
            // Build-level input fingerprints (plan 022). Per-task capture is deferred (see the
            // plan §8 divergence); the schema's `tasks` map stays reserved for it.
            val fingerprints = FingerprintInfo(build = parameters.fingerprints.orNull?.build.orEmpty())

            // Kotlin build-report bundling (plan 023): warn once if misconfigured on a Kotlin
            // build, then bundle whatever the wired json directory holds in this build's window.
            val startedAtMs = tasks.minOfOrNull { it.startMs } ?: System.currentTimeMillis()
            val bundleKotlin = parameters.kotlinBundle.getOrElse(true)
            val hasKotlinCompilations = tasks.any { task ->
                task.type?.contains("KotlinCompile") == true ||
                    task.path.substringAfterLast(':').let { it.startsWith("compile") && it.contains("Kotlin") }
            }
            KotlinReportBundler.warnIfMisconfigured(
                enabled = bundleKotlin,
                reportOutput = parameters.kotlinReportOutput.orNull,
                jsonDirectory = parameters.kotlinJsonDirectory.orNull,
                hasKotlinCompilations = hasKotlinCompilations,
                warn = { logger.warn(it) },
            )
            val kotlin = if (bundleKotlin) {
                // rootDir so a relative kotlin.build.report.json.directory resolves like KGP does
                // (against the root project), not against the daemon's working directory.
                KotlinReportBundler.bundle(
                    parameters.kotlinJsonDirectory.orNull,
                    startedAtMs,
                    parameters.rootDir.orNull,
                    { logger.warn(it) },
                )
            } else {
                null
            }

            // Test results (plan 024): parse each executed Test task's JUnit XML. Locations are
            // captured at config time and delivered here via the sidecar file (plan 044), with the
            // collector service param as the classpath-path fallback (see below). The widened
            // TestCollectionResult (plan 053, research F3) also carries the flag-authoritative
            // degraded state for tasks whose JUnit XML was disabled — mutually exclusive per task
            // with an entry in `results`, only reachable inside this same testsCollect guard so
            // `collect = false` never produces a note.
            val testCollection = if (parameters.testsCollect.getOrElse(true)) {
                // Prefer the durable sidecar (plan 044): the service param is frozen empty in a
                // composite build (included-build task events instantiate the collector before
                // whenReady). Fall back to the param on the classpath path / if the file is absent.
                val locations = parameters.rootDir.orNull
                    ?.let { TestLocationSidecar.read(File(it)) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: collector.snapshotLocations()
                TestResultCollector.collect(
                    locations = locations,
                    taskOutcomes = tasks.associate { it.path to it.outcome },
                    warn = { logger.warn(it) },
                    failInjection = parameters.failTestCollection.getOrElse(false),
                )
            } else {
                TestResultCollector.TestCollectionResult(emptyList(), emptyList())
            }
            val tests = testCollection.results
            val testTelemetry = TestTelemetryInfo(testCollection.xmlDisabledTasks)
                .takeIf { it.xmlDisabledTasks.isNotEmpty() }

            // Android artifact sizes (plan 031): read the JSON-line files the AGP size tasks wrote.
            // A genuine read failure (corrupt/locked dir) propagates to the finalizer's outer
            // runCatching → warn + marker, never a failed build (§3 finalizer read).
            val artifacts = parameters.rootDir.orNull?.let { readArtifacts(File(it)) }.orEmpty()

            // JVM archive sizes (plan 072, research F22): cross-reference each config-time archive
            // location against this build's task outcomes and measure File.length() only for archive
            // tasks that actually produced output. Boot builds both `jar` (plain classifier) and
            // `bootJar` by default (Boot 2.5+), so a default Boot module contributes two rows here; the
            // produced-output-outcome + File.exists() filter also correctly handles a user-disabled
            // `jar`, whose declared path would otherwise report a stale/absent artifact.
            // Runs inside the finalizer's outer runCatching → warn + marker, never a failed build.
            val jvmArtifacts = readJvmArtifacts(
                parameters.jvmArtifacts.getOrElse(emptyList()),
                tasks.associate { it.path to it.outcome },
            )

            // Addon extensions (plan 039): ServiceLoader-discover contributors on the settings
            // classpath and merge their JSON sections. Execution-time only (adds no CC input — the
            // functional test asserts CC reuse); every failure degrades to no extensions. Each
            // contributor is individually guarded inside the registry so one bad addon can't suppress
            // another, and the whole block is wrapped so discovery itself can never fail the build.
            val extensions = runCatching {
                val contributors = ServiceLoader.load(BuildHoundExtensionContributor::class.java, javaClass.classLoader).toList()
                if (contributors.isEmpty()) {
                    emptyMap()
                } else {
                    BuildHoundCollectorRegistry.collect(
                        contributors,
                        ExtensionContributionContext(
                            projectKey = parameters.projectKey.orNull,
                            mode = mode,
                            tasks = tasks,
                            ci = PayloadAssembler.ciInfo(ci),
                        ),
                        onWarn = { logger.warn("[buildhound] {}", it) },
                    )
                }
            }.getOrElse {
                logger.warn("[buildhound] addon extension discovery skipped (build unaffected): {}", it.message)
                emptyMap()
            }

            // avoidedMs + criticalPath edges are core `derived` inputs sourced from the opt-in
            // internal-adapters module (plan 038). Core stays internal-API-free: it reads two
            // well-known optional fields out of the addon's opaque JSON, never a Gradle-internal type.
            val (avoidedMs, dependencyEdges) = internalAdaptersDerivedInputs(extensions)

            val payload = PayloadAssembler.assemble(
                // Shared with the collector's start-marker (plan 033): same id both places so this
                // build's own marker is the one deleted during reconciliation above.
                buildId = collector.buildId,
                projectKey = parameters.projectKey.orNull,
                mode = mode,
                buildFailed = parameters.buildFailed.get(),
                failure = parameters.failure.orNull,
                requestedTasks = parameters.requestedTasks.getOrElse(emptyList()),
                tasks = tasks,
                environment = parameters.environment.orNull,
                invocation = parameters.invocation.orNull,
                vcs = parameters.vcs.orNull,
                ci = ci,
                configurationCache = ccState,
                daemonReused = execution.daemonReused,
                tags = parameters.tags.getOrElse(emptyMap()),
                nowMs = System.currentTimeMillis(),
                projectRoots = scrubRoots(parameters.rootDir.orNull),
                configurationMs = configurationMs,
                fingerprints = fingerprints,
                kotlin = kotlin,
                tests = tests,
                testTelemetry = testTelemetry,
                processes = parameters.processes.getOrElse(emptyList()),
                benchmark = benchmark,
                artifacts = artifacts,
                jvmArtifacts = jvmArtifacts,
                // Declared build-structure inventory + isolated-projects flag (plan 069); the former
                // is a plugin-side DTO the assembler maps to the wire BuildStructureInfo (null when
                // unknown), the latter rides in environment.isolatedProjects.
                buildStructure = parameters.buildStructure.orNull,
                isolatedProjects = parameters.isolatedProjectsActive.getOrElse(false),
                wrapper = parameters.wrapper.orNull,
                // Committed build-cache config snapshot (plan 067): the assembler maps this plugin-side
                // DTO into environment.buildCache (null when uncaptured / every field null).
                buildCache = parameters.buildCache.orNull,
                // Change blast-radius attribution (plan 063): plugin-side DTO → wire ChangedModulesInfo;
                // null when no diff base resolved.
                changedModules = parameters.changedModules.orNull,
                projectEvaluations = projectEvaluations.orEmpty(),
                extensions = extensions,
                avoidedMs = avoidedMs,
                dependencyEdges = dependencyEdges,
                agp = toolchain.agp,
                kgp = toolchain.kgp,
                ksp = toolchain.ksp,
                springBoot = toolchain.springBoot,
            )

            // Record this build's HEAD sha as the diff base for the NEXT local iterative build (plan
            // 063): under `.gradle` (not `build/`) so it survives `clean`, like the identity salt.
            // Best-effort — a write failure degrades the *next* build's LAST_BUILT_SHA base to absent,
            // never this build's outcome.
            writeLastBuiltSha(parameters.rootDir.orNull, parameters.vcs.orNull?.sha)

            // Counts only — a misconfigured build could put a secret in a tag/reason, so
            // keys and values never reach the log (plan 019).
            payload.caps?.let { caps ->
                logger.warn(
                    "[buildhound] payload capped to budget: {} tag(s), {} value(s), {} reason(s) dropped; " +
                        "{} task(s) dropped; {} addon extension(s) dropped",
                    caps.droppedTags, caps.droppedValues, caps.droppedExecutionReasons, caps.droppedTasks,
                    caps.droppedExtensions,
                )
            }

            val payloadFile = writePayload(payload, parameters.outputDir.get())
            if (parameters.htmlReportEnabled.getOrElse(true)) {
                // Wire-format JSON (not the pretty file) — the artifact doubles as an
                // offline payload copy (spec §3.8); render() escapes for the HTML context.
                // The payload is already scrubbed at assembly (§3.7, plan 007). The local artifact
                // may carry a fuller (still-scrubbed) failure stacktrace than the wire payload (plan 044).
                val reportPayload = reportPayload(payload, parameters.failure.orNull, scrubRoots(parameters.rootDir.orNull))
                val html = ReportAssets.render(BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), reportPayload))
                val htmlFile = File(payloadFile.parentFile, "buildhound-report.html")
                htmlFile.writeText(html)
                logger.lifecycle("[buildhound] report written: {}", htmlFile)
            }
            val byOutcome = tasks.groupingBy { it.outcome }.eachCount()
            val testCount = tests.sumOf { task -> task.classes.sumOf { it.passed + it.failed + it.skipped } }
            logger.lifecycle(
                "[buildhound] build {}: {} task(s) {}, {} test(s), mode={}, cc={}, hitRate={}",
                payload.outcome, tasks.size, byOutcome, testCount, mode, ccState,
                payload.derived?.cacheableHitRate?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "n/a",
            )
            logger.lifecycle("[buildhound] payload written: {} (buildId={})", payloadFile, payload.buildId)

            val decision = UploadGate.decide(
                enabled = true, // enabled/disabled already short-circuited above
                serverUrl = parameters.serverUrl.orNull,
                mode = mode,
                localBuildsEnabled = parameters.localBuildsEnabled.getOrElse(true),
                requireOptInFile = parameters.requireOptInFile.getOrElse(true),
                optInFileExists = optInMarkerExists(parameters.optInFile.orNull),
            )
            when (decision) {
                is UploadGate.Decision.Upload -> {
                    val json = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
                    // uploadInBackground opts a LOCAL build out of blocking on the send (plan 027):
                    // spool directly, no inline attempt and no drain (a later CI/foreground build drains).
                    // CI/benchmark always upload inline — short-lived agents can't defer to a next build.
                    val deferLocal = parameters.uploadInBackground.getOrElse(false) && mode == BuildMode.LOCAL
                    PayloadUploader(
                        baseUrl = decision.url,
                        token = parameters.serverToken.orNull,
                        spoolDir = File(parameters.outputDir.get(), "spool"),
                    ).use { uploader ->
                        if (deferLocal) {
                            uploader.spoolDirectly(payload.buildId, json)
                            logger.lifecycle("[buildhound] upload deferred (uploadInBackground): spooled for the next build")
                        } else {
                            uploader.drainSpool()
                            uploader.uploadOrSpool(payload.buildId, json)
                        }
                    }
                }
                is UploadGate.Decision.Skip ->
                    logger.info("[buildhound] upload skipped: {}", decision.reason)
            }
        }.onFailure { failure ->
            logger.warn("[buildhound] telemetry finalization failed (build unaffected): {}", failure.message)
            parameters.writeFailureMarker(failure)
        }
    }

    /**
     * Lost-build reconciliation (plan 033): delete this build's own start-marker (it reached
     * finalization, so it is not interrupted), then for every *other* stale marker in `started/`
     * synthesize an `INTERRUPTED` payload and route it through the same gate/uploader as a normal
     * build. Bounded + TTL-pruned via [MarkerReconciler] so a dead server can't grow the dir. Wholly
     * best-effort: the outer body and each marker are guarded, so nothing here can fail or hang the
     * build. A corrupt marker is deleted (it can never parse) rather than retried forever.
     */
    private fun reconcileStartMarkers(parameters: Parameters, ownBuildId: String) {
        runCatching {
            val startedDir = File(parameters.outputDir.get(), "started")
            // Own marker first, unconditionally — even if the scan below finds nothing.
            runCatching { File(startedDir, "$ownBuildId.json").delete() }
            val files = startedDir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return@runCatching
            val found = files.mapNotNull { file ->
                if (file.name == "$ownBuildId.json") return@mapNotNull null
                runCatching {
                    file to BuildHoundJson.payload.decodeFromString(StartMarker.serializer(), file.readText())
                }.getOrElse {
                    runCatching { file.delete() } // corrupt/partial marker → drop, never retry or throw
                    null
                }
            }
            val byId = found.associate { (file, marker) -> marker.buildId to file }
            val plan = MarkerReconciler.plan(found.map { it.second }, nowMs = System.currentTimeMillis())
            for (buildId in plan.prune) runCatching { byId[buildId]?.delete() }
            for (marker in plan.reconcile) {
                runCatching {
                    routeInterruptedBuild(parameters, marker)
                    byId[marker.buildId]?.delete()
                }.onFailure {
                    // Genuine error (e.g. disk) → leave the marker for a later build (TTL bounds it);
                    // an upload failure already spooled+retries. Never stop the other markers.
                    logger.info("[buildhound] interrupted-build reconcile deferred for {}: {}", marker.buildId, it.message)
                }
            }
            // Bound the local interrupted/ mirror the same way markers + the spool are bounded
            // (plan 033 reviews): drop copies older than the marker TTL, by mtime. Best-effort.
            val cutoff = System.currentTimeMillis() - MarkerReconciler.TTL_MS
            File(parameters.outputDir.get(), "interrupted")
                .listFiles { file -> file.isFile && file.name.endsWith(".json") && file.lastModified() < cutoff }
                ?.forEach { file -> runCatching { file.delete() } }
        }.onFailure { logger.info("[buildhound] start-marker reconciliation skipped (build unaffected): {}", it.message) }
    }

    /**
     * Persist a reconciled `INTERRUPTED` build locally (a distinct per-buildId file — never clobbering
     * the current build's `build-payload.json`) so a lost build is visible even with no server, then
     * upload/spool it through the same [UploadGate]/[PayloadUploader] as a normal build of the dead
     * build's own mode (a local build with no opt-in is written locally and not uploaded).
     */
    private fun routeInterruptedBuild(parameters: Parameters, marker: StartMarker) {
        val payload = PayloadAssembler.assembleInterrupted(marker, scrubRoots(parameters.rootDir.orNull))
        val json = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
        val localDir = File(parameters.outputDir.get(), "interrupted").apply { mkdirs() }
        File(localDir, "${payload.buildId}.json").writeText(prettyJson.encodeToString(BuildPayload.serializer(), payload))
        val decision = UploadGate.decide(
            enabled = true,
            serverUrl = parameters.serverUrl.orNull,
            mode = marker.mode,
            localBuildsEnabled = parameters.localBuildsEnabled.getOrElse(true),
            requireOptInFile = parameters.requireOptInFile.getOrElse(true),
            optInFileExists = optInMarkerExists(parameters.optInFile.orNull),
        )
        when (decision) {
            is UploadGate.Decision.Upload ->
                PayloadUploader(
                    baseUrl = decision.url,
                    token = parameters.serverToken.orNull,
                    spoolDir = File(parameters.outputDir.get(), "spool"),
                ).use { uploader -> uploader.uploadOrSpool(payload.buildId, json) }
            is UploadGate.Decision.Skip ->
                logger.info("[buildhound] interrupted build {} kept local: {}", payload.buildId, decision.reason)
        }
    }

    /**
     * Spec §3.2: failures leave a marker so CI can surface "telemetry was lost" without
     * the build ever failing. Sibling of the output dir so it works when the output dir
     * itself is the problem; best effort, never throws.
     */
    private fun Parameters.writeFailureMarker(failure: Throwable) {
        runCatching {
            val marker = File(outputDir.get() + "-failure.marker")
            marker.parentFile?.mkdirs()
            marker.writeText("telemetry finalization failed: ${failure::class.java.name}\n")
        }
    }

    private fun optInMarkerExists(overridePath: String?): Boolean = runCatching {
        val marker = overridePath?.let(::File)
            ?: File(System.getProperty("user.home"), ".buildhound/optin")
        marker.exists()
    }.getOrDefault(false)

    /**
     * The payload rendered into the local HTML artifact (plan 044): identical to the wire payload,
     * except the failure stacktrace is a fuller, still-scrubbed copy. The written/uploaded JSON keeps
     * the ~8 KiB cap the scrubber applied; the local, zero-network artifact can show more frames for
     * debugging. Scrub stays non-negotiable even locally — the raw trace is re-scrubbed here so no
     * absolute path or secret ever reaches the file. Returns [payload] unchanged when there is no
     * failure trace to expand.
     */
    private fun reportPayload(payload: BuildPayload, failure: CollectedFailure?, roots: List<String>): BuildPayload {
        val rawTrace = failure?.stackTrace ?: return payload
        val existing = payload.failure ?: return payload
        return payload.copy(failure = existing.copy(stackTrace = PayloadScrubber.scrubText(rawTrace, roots)))
    }

    /** Plain and canonical forms: reason text may carry either on symlinked checkouts. */
    private fun scrubRoots(rootDir: String?): List<String> {
        if (rootDir == null) return emptyList()
        val canonical = runCatching { File(rootDir).canonicalPath }.getOrNull()
        return listOfNotNull(rootDir, canonical).distinct()
    }

    /**
     * Reads the Android size tasks' JSON-line files (plan 031) under `<root>/build/buildhound/artifacts`.
     * Missing dir → empty. `readText` is intentionally unguarded so a genuinely corrupt/locked file
     * propagates to the finalizer's outer runCatching (→ warn + marker); per-line parse errors are
     * swallowed by [ArtifactRecordIo].
     */
    private fun readArtifacts(root: File): List<ArtifactSize> {
        val files = File(root, "build/buildhound/artifacts")
            .listFiles { file -> file.name.endsWith(".jsonl") } ?: return emptyList()
        return ArtifactRecordIo.parseAll(files.sortedBy { it.name }.map { it.readText() })
    }

    /**
     * Reads (then clears) the `beforeProject`/`afterProject` sidecar under
     * `<root>/.gradle/buildhound/config-timings` (plan 052). Called unconditionally at the top of
     * [execute] — the drain must run even on a DSL-disabled or CC-hit build (052 review fix), so the
     * call site guards it with its own `runCatching` (a corrupt/locked directory degrades to no
     * block, never an aborted finalization or a failure marker for a disabled build); malformed
     * per-project files are skipped defensively inside [ProjectEvalRecordIo].
     */
    private fun readProjectEvaluations(root: File): List<ProjectEvaluation> =
        ProjectEvalRecordIo.readAndClear(File(root, ".gradle/buildhound/config-timings"))

    /**
     * Records this build's HEAD [sha] as the diff base for the next iterative build (plan 063), at
     * `<rootDir>/.gradle/buildhound/last-built-sha` — under `.gradle` (survives `clean`), the salt /
     * config-timings precedent. Fully guarded: a null [rootDir]/[sha] (git absent, detached HEAD) or a
     * write failure is swallowed — the base for the *next* build just degrades to absent, never a
     * failed build. The sha is already-shipped `vcs` data — no new PII surface.
     */
    private fun writeLastBuiltSha(rootDir: String?, sha: String?) {
        if (rootDir == null || sha == null) return
        runCatching {
            val file = File(rootDir, ".gradle/buildhound/last-built-sha")
            file.parentFile?.mkdirs()
            file.writeText(sha)
        }.onFailure { logger.info("[buildhound] last-built-sha write skipped (build unaffected): {}", it.message) }
    }

    private fun writePayload(payload: BuildPayload, outputDir: String): File {
        val dir = File(outputDir).apply { mkdirs() }
        val file = File(dir, "build-payload.json")
        file.writeText(prettyJson.encodeToString(BuildPayload.serializer(), payload))
        return file
    }

    private fun configurationCacheState(requested: Boolean, execution: DaemonState.Execution): ConfigurationCacheState =
        when {
            !requested -> ConfigurationCacheState.DISABLED
            execution.configuredThisBuild -> ConfigurationCacheState.MISS_STORED
            else -> ConfigurationCacheState.HIT
        }

    private companion object {
        val logger = Logging.getLogger(TelemetryFinalizerAction::class.java)

        /** Wire format stays [BuildHoundJson.payload]; pretty printing is for the local file only. */
        val prettyJson = Json(from = BuildHoundJson.payload) { prettyPrint = true }
    }
}

/**
 * Outcomes for which a JVM archive task actually produced its output (plan 072) — the
 * only-what-ran gate. SKIPPED/NO_SOURCE/FAILED (and an absent join) leave the artifact unmeasured.
 */
private val PRODUCED_OUTPUT_OUTCOMES = setOf(TaskOutcome.EXECUTED, TaskOutcome.UP_TO_DATE, TaskOutcome.FROM_CACHE)

/**
 * Measures each config-time JVM archive [location] (plan 072, research F22): the size is read only
 * when the location's [JvmArtifactLocation.taskPath] resolved to a produced-output outcome
 * (EXECUTED/UP_TO_DATE/FROM_CACHE — never SKIPPED/NO_SOURCE/FAILED, nor an absent join) **and** the
 * archive file exists on disk. This is the load-bearing "measure-only-what-ran": Boot builds both
 * `jar` (plain classifier) and `bootJar` by default (Boot 2.5+), so a default Boot module yields two
 * rows out of this function; the gate also correctly handles a user-disabled `jar`, whose declared
 * path would otherwise report a stale/absent artifact. `File.length()` runs here at execution time
 * (no config-phase read → no CC fingerprint input); the absolute [JvmArtifactLocation.archivePath]
 * never enters the returned [JvmArtifactSize] (spec §3.7 — only size + module + kind ship).
 */
internal fun readJvmArtifacts(
    locations: List<JvmArtifactLocation>,
    taskOutcomes: Map<String, TaskOutcome>,
): List<JvmArtifactSize> =
    locations.mapNotNull { location ->
        if (taskOutcomes[location.taskPath] !in PRODUCED_OUTPUT_OUTCOMES) return@mapNotNull null
        val file = File(location.archivePath)
        if (!file.exists()) return@mapNotNull null
        JvmArtifactSize(module = location.module, kind = location.kind, sizeBytes = file.length())
    }

/**
 * Joins the task path → static type/cacheable/nonCacheableReason dictionary (plan 016) onto the
 * collector's task snapshot (plan 056, closes plan 045): a total lookup — a task with no [dictionary]
 * entry (isolated projects, a capture failure) simply keeps its already-null fields, never a partial
 * join.
 */
internal fun joinTaskMetadata(tasks: List<TaskExecution>, dictionary: Map<String, TaskMetadata>): List<TaskExecution> =
    tasks.map { task ->
        val meta = dictionary[task.path] ?: return@map task
        task.copy(type = meta.type, cacheable = meta.cacheable, nonCacheableReason = meta.nonCacheableReason)
    }

/**
 * Reads the two `derived` inputs (`avoidedMs`, `dependencyEdges`) out of the internal-adapters
 * module's opaque `extensions["internalAdapters"]` block (plan 038), defensively — a missing/malformed
 * shape degrades to `(null, null)`, never a throw. Core stays internal-API-free: this touches only
 * JSON, and only two well-known optional keys of a reserved extension.
 */
internal fun internalAdaptersDerivedInputs(extensions: Map<String, JsonElement>): Pair<Long?, Map<String, List<String>>?> {
    val block = extensions["internalAdapters"] ?: return null to null
    return runCatching {
        val obj = block.jsonObject
        val avoidedMs = obj["avoidedMs"]?.jsonPrimitive?.longOrNull
        val edges = obj["dependencyEdges"]?.jsonObject?.mapValues { (_, v) ->
            v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
        avoidedMs to edges
    }.getOrElse { null to null }
}
