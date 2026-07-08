package dev.buildhound.internaladapters

import org.gradle.api.internal.tasks.SnapshotTaskInputsBuildOperationType
import org.gradle.api.internal.tasks.execution.ExecuteTaskBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheLocalLoadBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheLocalStoreBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheRemoteLoadBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheRemoteStoreBuildOperationType
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationListener
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationProgressEvent
import org.gradle.internal.operations.OperationStartEvent

/**
 * The one place internal Gradle build-operation types are touched (plan 038). Subscribes to
 * `SnapshotTaskInputs` (cache key), `ExecuteTask` (task path for correlation), `ExecuteWork`
 * (caching-disabled + origin), and `BuildCache{Local,Remote}{Load,Store}` (origin split), correlating
 * each op back to its enclosing task and merging into the current-build [Accumulator]. Every
 * `finished`/`started` body is `runCatching`-guarded and every uncertain getter goes through
 * reflection, so a Gradle-version mismatch degrades a field to "unknown" and never throws — the
 * listener can never fail the build (spec §3.1). Cache keys are salted here (execution-time, [rootDir]
 * available) so the collector only assembles.
 */
class BuildOperationAdapter(private val rootDir: java.io.File) : BuildOperationListener {

    private val state = InternalAdaptersState

    init {
        // The project root for scrubbing caching-disabled free text (§3.7); stable per daemon.
        state.setProjectRoot(rootDir.path)
    }

    override fun started(descriptor: BuildOperationDescriptor, event: OperationStartEvent) {
        // Task/parent correlation is only consumed by the cache data paths in finished() — gate it on
        // the cache toggle so a build that enabled *only* a warning catcher accumulates nothing here.
        if (!state.collectCacheOrigins()) return
        runCatching {
            val acc = state.accumulator()
            val id = descriptor.id?.id ?: return
            descriptor.parentId?.id?.let { acc.parentOf[id] = it }
            val details = descriptor.details
            if (details is ExecuteTaskBuildOperationType.Details) {
                acc.taskPathOf[id] = details.taskPath
            }
        }
    }

    /**
     * Deprecation-warning catcher (plan 044), opt-in via `internalAdapters.collectDeprecations`. Gradle
     * emits each deprecation as build-operation *progress* whose details implement
     * `DeprecatedUsageProgressDetails`; we read the summary (+ advice) reflectively — a version rename
     * degrades to no capture, never a throw — and never touch the detail's stack trace (it carries
     * absolute paths). Deduped + bounded in the accumulator; scrubbed by the collector.
     */
    override fun progress(operationIdentifier: OperationIdentifier, event: OperationProgressEvent) {
        if (!state.collectDeprecations()) return
        runCatching {
            val details = event.details ?: return
            if (!isType(details, DEPRECATION_DETAILS_TYPE)) return
            val summary = callString(details, "getSummary") ?: return
            val advice = callString(details, "getAdvice")
            state.accumulator().addDeprecation(if (advice != null) "$summary $advice" else summary)
        }
    }

    override fun finished(descriptor: BuildOperationDescriptor, event: OperationFinishEvent) {
        // Cache origin/key accumulation is gated on its own toggle (plan 074): deprecations ride the
        // progress() path above, so a deprecations-only build must not collect cache telemetry here.
        if (!state.collectCacheOrigins()) return
        runCatching {
            val acc = state.accumulator()
            val result = event.result ?: return
            val opId = descriptor.id?.id
            when {
                result is SnapshotTaskInputsBuildOperationType.Result -> {
                    val path = acc.taskPathFor(opId) ?: return
                    val salted = SaltHasher.hash(saltOrNull(), result.hashBytes)
                    if (salted != null) acc.forPath(path).cacheKeyRaw = result.hashBytes
                }
                isType(result, "org.gradle.operations.execution.ExecuteWorkBuildOperationType\$Result") -> {
                    val path = acc.taskPathFor(opId) ?: return
                    val t = acc.forPath(path)
                    t.executed = true
                    callString(result, "getCachingDisabledReasonMessage")?.let { t.cachingDisabledReason = it }
                    callString(result, "getCachingDisabledReasonCategory")?.let { t.cachingDisabledCategory = it }
                    callString(result, "getOriginBuildInvocationId")?.let { t.originBuildInvocationId = it }
                    callBytes(result, "getOriginBuildCacheKeyBytes")?.let { t.originCacheKeyRaw = it }
                    callLong(result, "getOriginExecutionTime")?.let { t.originExecutionTimeMs = it }
                }
                result is BuildCacheLocalLoadBuildOperationType.Result ->
                    acc.taskPathFor(opId)?.let { if (callBool(result, "isHit") == true) acc.forPath(it).localLoadHit = true }
                result is BuildCacheRemoteLoadBuildOperationType.Result ->
                    acc.taskPathFor(opId)?.let { if (callBool(result, "isHit") == true) acc.forPath(it).remoteLoadHit = true }
                result is BuildCacheLocalStoreBuildOperationType.Result ->
                    acc.taskPathFor(opId)?.let { acc.forPath(it).stored = true }
                result is BuildCacheRemoteStoreBuildOperationType.Result ->
                    acc.taskPathFor(opId)?.let { acc.forPath(it).stored = true }
            }
        }
    }

    private fun saltOrNull(): ByteArray? {
        state.salt()?.let { return it }
        val fresh = runCatching {
            SaltHasher.readOrCreateSalt(java.io.File(rootDir, ".gradle/buildhound/internal-adapters.salt"))
        }.getOrNull()
        state.setSalt(fresh)
        return fresh
    }

    // --- Reflection helpers: a missing/renamed getter on some Gradle version returns null, never throws. ---

    private fun isType(obj: Any, fqcn: String): Boolean =
        generateSequence(obj.javaClass as Class<*>?) { it.superclass }.any { it.name == fqcn } ||
            obj.javaClass.interfaces.any { it.name == fqcn }

    private fun callString(obj: Any, method: String): String? =
        runCatching { obj.javaClass.getMethod(method).invoke(obj) as? String }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun callBool(obj: Any, method: String): Boolean? =
        runCatching { obj.javaClass.getMethod(method).invoke(obj) as? Boolean }.getOrNull()

    private fun callBytes(obj: Any, method: String): ByteArray? =
        runCatching { obj.javaClass.getMethod(method).invoke(obj) as? ByteArray }.getOrNull()

    private fun callLong(obj: Any, method: String): Long? =
        runCatching { (obj.javaClass.getMethod(method).invoke(obj) as? Number)?.toLong() }.getOrNull()

    private companion object {
        // Verified against Gradle 9.6.1: the deprecation progress detail impl
        // (DefaultDeprecatedUsageProgressDetails) implements this interface. Matched by name via
        // reflection so a package rename on another version degrades to no capture, never a crash.
        const val DEPRECATION_DETAILS_TYPE = "org.gradle.internal.featurelifecycle.DeprecatedUsageProgressDetails"
    }
}
