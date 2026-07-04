package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.FingerprintInfo
import dev.buildhound.report.ReportAssets
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
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

        @get:Input
        @get:Optional
        val fingerprints: Property<CollectedFingerprints>

        @get:Input
        val buildFailed: Property<Boolean>

        @get:Input
        val environment: Property<CollectedEnvironment>

        @get:Input
        val vcs: Property<CollectedVcs>

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
            // Master switch (spec §3.4): nothing is probed, assembled, or logged when off —
            // the value-source providers are never queried, so no salt is created either.
            if (!parameters.enabled.getOrElse(true)) return@runCatching
            val configuredMode = parameters.mode.getOrElse(TelemetryMode.AUTO)
            // disabled behaves like enabled=false: nothing is probed (spec §3.4).
            if (configuredMode == TelemetryMode.DISABLED) return@runCatching

            val ci = parameters.ci.orNull
            val benchmark = parameters.benchmark.orNull
            val mode = PayloadAssembler.resolveMode(configuredMode, ci, benchmark) ?: return@runCatching

            val tasks = parameters.collector.get().snapshot()
            val ccState = configurationCacheState(parameters.configurationCacheRequested.getOrElse(false), execution)
            // Config-cache hit skips configuration entirely; entry-load time is not
            // measured, so report 0 rather than the (absent) marks. Otherwise the marked
            // duration, or null when unmeasurable (plan 016).
            val configurationMs = if (ccState == ConfigurationCacheState.HIT) 0L else execution.configurationMs
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
                KotlinReportBundler.bundle(parameters.kotlinJsonDirectory.orNull, startedAtMs, { logger.warn(it) })
            } else {
                null
            }

            // Test results (plan 024): parse each executed Test task's JUnit XML. Locations were
            // captured at config time and ride the collector service (replayed on a CC hit).
            val tests = if (parameters.testsCollect.getOrElse(true)) {
                TestResultCollector.collect(
                    locations = parameters.collector.get().snapshotLocations(),
                    taskOutcomes = tasks.associate { it.path to it.outcome },
                    warn = { logger.warn(it) },
                    failInjection = parameters.failTestCollection.getOrElse(false),
                )
            } else {
                emptyList()
            }

            val payload = PayloadAssembler.assemble(
                buildId = UUID.randomUUID().toString(),
                projectKey = parameters.projectKey.orNull,
                mode = mode,
                buildFailed = parameters.buildFailed.get(),
                requestedTasks = parameters.requestedTasks.getOrElse(emptyList()),
                tasks = tasks,
                environment = parameters.environment.orNull,
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
                processes = parameters.processes.getOrElse(emptyList()),
                benchmark = benchmark,
            )

            // Counts only — a misconfigured build could put a secret in a tag/reason, so
            // keys and values never reach the log (plan 019).
            payload.caps?.let { caps ->
                logger.warn(
                    "[buildhound] payload capped to budget: {} tag(s), {} value(s), {} reason(s) dropped; {} task(s) dropped",
                    caps.droppedTags, caps.droppedValues, caps.droppedExecutionReasons, caps.droppedTasks,
                )
            }

            val payloadFile = writePayload(payload, parameters.outputDir.get())
            if (parameters.htmlReportEnabled.getOrElse(true)) {
                // Wire-format JSON (not the pretty file) — the artifact doubles as an
                // offline payload copy (spec §3.8); render() escapes for the HTML context.
                // The payload is already scrubbed at assembly (§3.7, plan 007).
                val html = ReportAssets.render(BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload))
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

    /** Plain and canonical forms: reason text may carry either on symlinked checkouts. */
    private fun scrubRoots(rootDir: String?): List<String> {
        if (rootDir == null) return emptyList()
        val canonical = runCatching { File(rootDir).canonicalPath }.getOrNull()
        return listOfNotNull(rootDir, canonical).distinct()
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
