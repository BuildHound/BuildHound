package dev.buildhound.gradle

import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import java.util.concurrent.ConcurrentLinkedQueue
import org.gradle.api.provider.MapProperty
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
 *
 * [Params.taskMetadata] carries the configuration-time task dictionary (path → static
 * type/cacheable/reason, plan 016). It is the shared home for per-task config-time data:
 * plan 024 later *extends* this same interface with `testResultLocations` rather than
 * re-deriving from `None`. The provider is replayed verbatim on a config-cache hit, so
 * the metadata survives when the `whenReady` callback that built it never runs.
 */
abstract class TaskEventCollector : BuildService<TaskEventCollector.Params>, OperationCompletionListener {

    interface Params : BuildServiceParameters {
        val taskMetadata: MapProperty<String, TaskMetadata>
    }

    private val tasks = ConcurrentLinkedQueue<TaskExecution>()

    // Read once, lazily, off the event thread's hot path; empty under isolated projects.
    private val metadata: Map<String, TaskMetadata> by lazy { parameters.taskMetadata.getOrElse(emptyMap()) }

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val result = event.result
        val path = event.descriptor.taskPath
        val meta = metadata[path]
        tasks += TaskExecution(
            path = path,
            module = path.substringBeforeLast(':').ifEmpty { ":" },
            type = meta?.type,
            startMs = result.startTime,
            durationMs = result.endTime - result.startTime,
            outcome = result.toOutcome(),
            cacheable = meta?.cacheable,
            nonCacheableReason = meta?.nonCacheableReason,
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
