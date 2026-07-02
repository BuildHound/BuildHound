package dev.buildhound.gradle

import dev.buildhound.commons.payload.ConfigurationCacheState
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input

/**
 * Runs once, after the build finished (spec §3.2). Will assemble the payload, write the
 * standalone HTML artifact, and upload. Hard rule: never fails the build — every error is
 * logged at warn and swallowed.
 */
class TelemetryFinalizerAction : FlowAction<TelemetryFinalizerAction.Parameters> {

    interface Parameters : FlowParameters {
        @get:Input
        val enabled: Property<Boolean>

        @get:ServiceReference
        val collector: Property<TaskEventCollector>

        @get:Input
        val buildFailed: Property<Boolean>

        @get:Input
        val environment: Property<CollectedEnvironment>

        @get:Input
        val configurationCacheRequested: Property<Boolean>
    }

    override fun execute(parameters: Parameters) {
        runCatching {
            // Consume the heuristic mark first, unconditionally: a stale mark would make
            // the next build in this daemon misreport its CC state.
            val execution = DaemonState.executionRan()
            // Master switch (spec §3.4): nothing is probed, assembled, or logged when off —
            // the environment provider is never queried, so no salt is created either.
            if (!parameters.enabled.getOrElse(true)) return@runCatching

            val tasks = parameters.collector.get().snapshot()
            val byOutcome = tasks.groupingBy { it.outcome }.eachCount()
            val outcome = if (parameters.buildFailed.get()) "FAILED" else "SUCCESS"
            logger.lifecycle("[buildhound] captured {} task event(s), build {}, outcomes: {}", tasks.size, outcome, byOutcome)

            val ccState = configurationCacheState(parameters.configurationCacheRequested.getOrElse(false), execution)
            val env = parameters.environment.orNull
            // Identity fields are deliberately never logged (spec §3.7).
            logger.lifecycle(
                "[buildhound] environment: os={}/{}, cores={}, ramMb={}, gradle={}, jdk={}, daemonReused={}, cc={}",
                env?.os, env?.arch, env?.cores, env?.ramMb, env?.gradleVersion, env?.jdkVersion,
                execution.daemonReused, ccState,
            )
            // TODO(phase 1): payload assembly, derived metrics, HTML artifact, spool + upload.
        }.onFailure {
            logger.warn("[buildhound] telemetry finalization failed (build unaffected): {}", it.message)
        }
    }

    private fun configurationCacheState(requested: Boolean, execution: DaemonState.Execution): ConfigurationCacheState =
        when {
            !requested -> ConfigurationCacheState.DISABLED
            execution.configuredThisBuild -> ConfigurationCacheState.MISS_STORED
            else -> ConfigurationCacheState.HIT
        }

    private companion object {
        val logger = Logging.getLogger(TelemetryFinalizerAction::class.java)
    }
}
