package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.StartMarker
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
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
 * The CC-safe fields a [StartMarker] needs that the collector cannot observe itself (plan 033),
 * resolved at configuration time and carried into the service. Deliberately excludes ci/vcs: a
 * build-service parameter bakes into the config-cache entry and replays stale on a hit, so a value
 * source here would be unreliable on the CC-enabled CI builds that matter (see [StartMarker]).
 * [mode] is resolved with no CI context. `null` context (from a disabled build) means "write no
 * marker".
 */
data class MarkerContext(
    val startedDir: String,
    val projectKey: String?,
    val requestedTasks: List<String>,
    val mode: BuildMode,
) : java.io.Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

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

        /** Start-marker context (plan 033); absent → no marker (build disabled). Optional. */
        val markerContext: Property<MarkerContext>
    }

    private val tasks = ConcurrentLinkedQueue<TaskExecution>()

    /**
     * The build id, minted once per build and shared with the finalizer (plan 033): the collector's
     * start-marker and the eventual finalized payload must carry the same id so the finalizer can
     * delete its own marker. The finalizer reads this via its `@ServiceReference` to this same
     * service instance. `by lazy` is `SYNCHRONIZED`, so a race between the first task event and the
     * finalizer still mints exactly one id.
     */
    val buildId: String by lazy { UUID.randomUUID().toString() }

    private val markerWritten = AtomicBoolean(false)

    // Read once, lazily, off the event thread's hot path; empty under isolated projects.
    private val metadata: Map<String, TaskMetadata> by lazy { parameters.taskMetadata.getOrElse(emptyMap()) }

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val result = event.result
        // Start-marker (plan 033): write once, on the first observed task, so a build that never
        // finalizes (OOM kill, agent eviction) leaves a trace the next build reconciles into an
        // INTERRUPTED payload. Best-effort, execution-time IO only (arch §2 rule 9) — a failure is
        // logged at info and never propagates.
        if (markerWritten.compareAndSet(false, true)) {
            runCatching { writeStartMarker(result.startTime) }
                .onFailure { logger.info("[buildhound] start-marker not written (build unaffected): {}", it.message) }
        }
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

    /** Serialize the [StartMarker] to `<startedDir>/<buildId>.json`; caller guards against throwing. */
    private fun writeStartMarker(startedAtMs: Long) {
        val context = parameters.markerContext.orNull ?: return // disabled/unresolved → no marker
        val marker = StartMarker(
            buildId = buildId,
            startedAtMs = startedAtMs,
            mode = context.mode,
            projectKey = context.projectKey,
            requestedTasks = context.requestedTasks,
        )
        val dir = File(context.startedDir).apply { mkdirs() }
        File(dir, "$buildId.json").writeText(BuildHoundJson.payload.encodeToString(StartMarker.serializer(), marker))
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
        private val logger = Logging.getLogger(TaskEventCollector::class.java)
    }
}
