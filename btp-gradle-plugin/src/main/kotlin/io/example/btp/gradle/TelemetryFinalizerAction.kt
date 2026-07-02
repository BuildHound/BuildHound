package io.example.btp.gradle

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
        @get:ServiceReference
        val collector: Property<TaskEventCollector>

        @get:Input
        val buildFailed: Property<Boolean>
    }

    override fun execute(parameters: Parameters) {
        runCatching {
            val tasks = parameters.collector.get().snapshot()
            val byOutcome = tasks.groupingBy { it.outcome }.eachCount()
            val outcome = if (parameters.buildFailed.get()) "FAILED" else "SUCCESS"
            logger.lifecycle("[btp] captured {} task event(s), build {}, outcomes: {}", tasks.size, outcome, byOutcome)
            // TODO(phase 1): payload assembly, derived metrics, HTML artifact, spool + upload.
        }.onFailure {
            logger.warn("[btp] telemetry finalization failed (build unaffected): {}", it.message)
        }
    }

    private companion object {
        val logger = Logging.getLogger(TelemetryFinalizerAction::class.java)
    }
}
