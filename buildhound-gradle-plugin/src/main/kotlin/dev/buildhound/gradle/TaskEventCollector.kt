package dev.buildhound.gradle

import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import java.util.concurrent.ConcurrentLinkedQueue
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskExecutionResult
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult

/**
 * Build service receiving [TaskFinishEvent]s for every task in the build (spec §3.2).
 * Registered from settings via `BuildEventsListenerRegistry.onTaskCompletion`, which is
 * configuration-cache safe and replays on cache hits.
 */
abstract class TaskEventCollector : BuildService<BuildServiceParameters.None>, OperationCompletionListener {

    private val tasks = ConcurrentLinkedQueue<TaskExecution>()

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val result = event.result
        tasks += TaskExecution(
            path = event.descriptor.taskPath,
            module = event.descriptor.taskPath.substringBeforeLast(':').ifEmpty { ":" },
            startMs = result.startTime,
            durationMs = result.endTime - result.startTime,
            outcome = result.toOutcome(),
            incremental = (result as? TaskExecutionResult)?.isIncremental ?: false,
            executionReasons = (result as? TaskExecutionResult)?.executionReasons.orEmpty(),
        )
    }

    fun snapshot(): List<TaskExecution> = tasks.toList()

    private fun org.gradle.tooling.events.OperationResult.toOutcome(): TaskOutcome = when (this) {
        is TaskSuccessResult -> when {
            isFromCache -> TaskOutcome.FROM_CACHE
            isUpToDate -> TaskOutcome.UP_TO_DATE
            else -> TaskOutcome.EXECUTED
        }
        is TaskFailureResult -> TaskOutcome.FAILED
        is TaskSkippedResult -> TaskOutcome.SKIPPED
        else -> TaskOutcome.EXECUTED
    }

    companion object {
        const val SERVICE_NAME: String = "buildhoundTaskEventCollector"
    }
}
