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
 * type/cacheable/reason, plan 016); [Params.testResultLocations] carries the JUnit XML output
 * directory of each `Test` task (path → dir/module, plan 024). Both are the shared home for
 * per-task config-time data. Each provider is replayed verbatim on a config-cache hit, so the
 * data survives when the `whenReady` callback that built it never runs.
 */
abstract class TaskEventCollector : BuildService<TaskEventCollector.Params>, OperationCompletionListener {

    interface Params : BuildServiceParameters {
        val taskMetadata: MapProperty<String, TaskMetadata>

        /** Test-task path → JUnit XML output location (plan 024); empty under isolated projects. */
        val testResultLocations: MapProperty<String, TestResultLocations>
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

    /**
     * Test-task JUnit XML locations captured at configuration time (plan 024). Consumed once in
     * the finalizer (not per task-finish event), so it is read directly from the parameter rather
     * than cached like [metadata]. Empty under isolated projects or when test collection is off.
     */
    fun snapshotLocations(): Map<String, TestResultLocations> = parameters.testResultLocations.getOrElse(emptyMap())

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
