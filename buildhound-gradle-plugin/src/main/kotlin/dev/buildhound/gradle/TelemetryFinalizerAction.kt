package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ConfigurationCacheState
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
        val buildFailed: Property<Boolean>

        @get:Input
        val environment: Property<CollectedEnvironment>

        @get:Input
        val vcs: Property<CollectedVcs>

        @get:Input
        @get:Optional
        val ci: Property<CollectedCi>

        @get:Input
        val configurationCacheRequested: Property<Boolean>

        @get:Input
        val projectKey: Property<String>

        @get:Input
        val requestedTasks: ListProperty<String>

        @get:Input
        val outputDir: Property<String>
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
            val mode = PayloadAssembler.resolveMode(configuredMode, ci) ?: return@runCatching

            val tasks = parameters.collector.get().snapshot()
            val ccState = configurationCacheState(parameters.configurationCacheRequested.getOrElse(false), execution)
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
            )

            val payloadFile = writePayload(payload, parameters.outputDir.get())
            val byOutcome = tasks.groupingBy { it.outcome }.eachCount()
            logger.lifecycle(
                "[buildhound] build {}: {} task(s) {}, mode={}, cc={}, hitRate={}",
                payload.outcome, tasks.size, byOutcome, mode, ccState,
                payload.derived?.cacheableHitRate?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "n/a",
            )
            logger.lifecycle("[buildhound] payload written: {} (buildId={})", payloadFile, payload.buildId)
            // TODO(phase 1): HTML artifact, gzip upload with spool/retry.
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
