package dev.buildhound.commons.payload

import kotlinx.serialization.json.JsonElement

/**
 * Payload budgets (plan 019). Entry counts and key/value lengths mirror the spec-§5
 * metric-CLI numbers; 20 MiB of uncompressed JSON gzips comfortably under the 8 MiB spool
 * ceiling. The limits are a parameter so tests can use tiny values; [DEFAULT] is the only
 * production configuration.
 */
data class PayloadCaps(
    val maxTags: Int = 100,
    val maxValues: Int = 100,
    val maxKeyChars: Int = 100,
    val maxValueChars: Int = 300,
    val maxReasonsPerTask: Int = 10,
    val maxReasonChars: Int = 500,
    val maxTasks: Int = 20_000,
    val maxArtifacts: Int = 200,
    /** Top-N slowest-first `projectEvaluations` retained per payload (plan 052); a monorepo can exceed this. */
    val maxProjectEvaluations: Int = 500,
    /** `testTelemetry.xmlDisabledTasks` entries retained per payload (plan 053); mirrors [maxProjectEvaluations] — a monorepo's Test-task count can exceed this. */
    val maxXmlDisabledTasks: Int = 500,
    /** `changedModules.modules` entries retained per payload (plan 063); a monorepo's changed-module count can exceed this. */
    val maxChangedModules: Int = 500,
    /** Total byte budget for all addon `extensions` entries (plan 039); largest dropped first. */
    val maxExtensionsBytes: Int = 256 * 1024,
    val maxPayloadBytes: Int = 20 * 1024 * 1024,
) {
    companion object {
        val DEFAULT: PayloadCaps = PayloadCaps()
    }
}

/**
 * Enforces [PayloadCaps] on a [BuildPayload] — pure, deterministic, idempotent. The build
 * envelope always survives; overflow follows spec §3.9 order (drop per-task execution
 * reasons first, then truncate the task array with summary counts).
 *
 * The `kotlin` section counts toward the byte budget ([encodedSize] measures the whole payload)
 * but is not itself reduced here — it is bounded upstream at collection: the plugin's
 * `KotlinReportParser`/`KotlinReportBundler` cap it to ≤ 200 tasks, each with bounded reasons,
 * phase keys, and path length, so its worst-case size is a few hundred KB and cannot dominate
 * the budget. Revisit (add a kotlin-trim stage) if that section ever grows unbounded fields.
 *
 * Used by the plugin as the final assembly step (after the scrubber, so secret patterns see
 * whole values) and by the server as a defensive clamp at ingest. A server re-cap **merges**
 * its counts into the payload's existing [CapsSummary] rather than overwriting it, so the two
 * layers' drops add up honestly. A compliant payload is returned unchanged with `caps == null`.
 *
 * KMP-pure: kotlin stdlib + [BuildHoundJson] only, so both plugin and server run identical logic.
 */
object PayloadCapper {

    fun cap(payload: BuildPayload, caps: PayloadCaps = PayloadCaps.DEFAULT): BuildPayload {
        val tags = capMap(payload.tags, caps.maxTags, caps.maxKeyChars, caps.maxValueChars)
        val values = capMap(payload.values, caps.maxValues, caps.maxKeyChars, caps.maxValueChars)

        var droppedReasons = 0
        var truncatedReasons = 0
        var truncatedNonCacheable = 0
        val reasonCapped = payload.tasks.map { task ->
            var result = task
            val reasons = task.executionReasons
            if (reasons.size > caps.maxReasonsPerTask || reasons.any { it.length > caps.maxReasonChars }) {
                val kept = reasons.take(caps.maxReasonsPerTask)
                droppedReasons += reasons.size - kept.size
                val truncated = kept.map {
                    if (it.length > caps.maxReasonChars) { truncatedReasons++; it.substring(0, caps.maxReasonChars) } else it
                }
                result = result.copy(executionReasons = truncated)
            }
            val nonCacheable = task.nonCacheableReason
            if (nonCacheable != null && nonCacheable.length > caps.maxReasonChars) {
                truncatedNonCacheable++
                result = result.copy(nonCacheableReason = nonCacheable.substring(0, caps.maxReasonChars))
            }
            result
        }

        var droppedTasks = 0
        val droppedOutcomes = LinkedHashMap<String, Int>()

        // Task-array cap: retain all FAILED, then the longest by durationMs (ties by path).
        var tasks = reasonCapped
        if (reasonCapped.size > caps.maxTasks) {
            val keep = rankedIndices(reasonCapped).take(caps.maxTasks).toSet()
            reasonCapped.forEachIndexed { index, task -> if (index !in keep) recordDrop(task, droppedOutcomes) }
            droppedTasks += reasonCapped.size - keep.size
            tasks = reasonCapped.filterIndexed { index, _ -> index in keep }
        }

        // Benchmark seedRef is free-text env (plan 030); scenario/isolationMode are allowlist-bounded.
        // Truncate an over-long seedRef defensively (a silent truncation, not a countable drop).
        val cappedBenchmark = payload.benchmark?.let { b ->
            val seed = b.seedRef
            if (seed != null && seed.length > caps.maxValueChars) b.copy(seedRef = seed.substring(0, caps.maxValueChars)) else b
        }

        // Artifacts (plan 031 Android, plan 072 JVM): the plugin caps at assembly, but a hostile/foreign
        // ingest can POST an unbounded array — keep the largest N (they carry the signal), drop + count
        // the rest. Each list is capped independently largest-first; both drops sum into the single
        // droppedArtifacts counter (the two lists share the per-payload artifact budget).
        var droppedArtifacts = 0
        val cappedArtifacts = payload.artifacts?.let { a ->
            val android = if (a.android.size <= caps.maxArtifacts) {
                a.android
            } else {
                val kept = a.android.sortedByDescending { it.sizeBytes }.take(caps.maxArtifacts)
                droppedArtifacts += a.android.size - kept.size
                kept
            }
            val jvm = if (a.jvm.size <= caps.maxArtifacts) {
                a.jvm
            } else {
                val kept = a.jvm.sortedByDescending { it.sizeBytes }.take(caps.maxArtifacts)
                droppedArtifacts += a.jvm.size - kept.size
                kept
            }
            if (droppedArtifacts == 0) a else a.copy(android = android, jvm = jvm)
        }

        // Per-project configuration-time attribution (plan 052): the plugin already sorts slowest-first
        // and caps at assembly, but a hostile/foreign ingest can POST an unbounded list — keep the N
        // slowest (they carry the signal, same rationale as artifacts), drop + count the rest.
        var droppedProjectEvaluations = 0
        val cappedProjectEvaluations = payload.projectEvaluations?.let { list ->
            if (list.size <= caps.maxProjectEvaluations) {
                list
            } else {
                val kept = list.sortedByDescending { it.evaluationMs }.take(caps.maxProjectEvaluations)
                droppedProjectEvaluations = list.size - kept.size
                kept
            }
        }

        // Disabled-JUnit-XML task notes (plan 053, review): the plugin already emits this sorted and
        // rarely more than a handful of entries, but a hostile/foreign ingest can POST an unbounded
        // array — keep the first N alphabetically (deterministic, same ordering the plugin already
        // uses), drop + count the rest. Same rationale as projectEvaluations/artifacts above.
        var droppedXmlDisabledTasks = 0
        val cappedTestTelemetry = payload.testTelemetry?.let { tt ->
            if (tt.xmlDisabledTasks.size <= caps.maxXmlDisabledTasks) {
                tt
            } else {
                val kept = tt.xmlDisabledTasks.sorted().take(caps.maxXmlDisabledTasks)
                droppedXmlDisabledTasks = tt.xmlDisabledTasks.size - kept.size
                tt.copy(xmlDisabledTasks = kept)
            }
        }

        // Changed modules (plan 063): the plugin already emits this sorted (distinct Gradle paths), but a
        // hostile/foreign ingest can POST an unbounded array — keep the first N alphabetically
        // (deterministic, same ordering the plugin already uses), drop + count the rest. Same rationale
        // as xmlDisabledTasks/projectEvaluations/artifacts above.
        var droppedChangedModules = 0
        val cappedChangedModules = payload.changedModules?.let { cm ->
            if (cm.modules.size <= caps.maxChangedModules) {
                cm
            } else {
                val kept = cm.modules.sorted().take(caps.maxChangedModules)
                droppedChangedModules = cm.modules.size - kept.size
                cm.copy(modules = kept)
            }
        }

        // Addon extensions (plan 039): opaque, addon-authored JSON that core does not scrub. It must
        // not balloon the payload, so bound the whole map to its own byte budget by dropping the
        // largest entries first (they carry the most abuse potential) until it fits. Runs on both the
        // plugin's assembly and the server's defensive ingest re-cap — a hostile POST is bounded too.
        // This budget is independent of maxPayloadBytes: extensions are bounded here first and are NOT
        // touched by the later total-byte stages (which shed reasons/tasks), so under total-byte
        // pressure a sub-budget extensions map is retained while tasks are dropped — deliberate, since
        // 256 KiB of extensions can never dominate the 20 MiB envelope.
        var droppedExtensions = 0
        var cappedExtensions = payload.extensions
        if (cappedExtensions.isNotEmpty()) {
            var total = cappedExtensions.values.sumOf { encodedElementSize(it) }
            if (total > caps.maxExtensionsBytes) {
                val kept = LinkedHashMap(cappedExtensions) // preserves insertion order of survivors
                for (entry in cappedExtensions.entries.sortedByDescending { encodedElementSize(it.value) }) {
                    if (total <= caps.maxExtensionsBytes) break
                    total -= encodedElementSize(entry.value)
                    kept.remove(entry.key)
                    droppedExtensions++
                }
                cappedExtensions = kept
            }
        }

        // Strip any prior caps summary while working so the byte budget measures the same
        // shape on every pass (the summary is re-attached at the end) — this keeps re-capping
        // idempotent: the summary block itself must not count toward the budget it records.
        var working = payload.copy(
            tags = tags.map, values = values.map, tasks = tasks,
            benchmark = cappedBenchmark, artifacts = cappedArtifacts, extensions = cappedExtensions,
            projectEvaluations = cappedProjectEvaluations, testTelemetry = cappedTestTelemetry,
            changedModules = cappedChangedModules, caps = null,
        )

        // Byte budget (spec §3.9 stages), only walked when the payload is actually oversized.
        if (encodedSize(working) > caps.maxPayloadBytes) {
            // Stage 1: drop all remaining execution reasons.
            val remaining = working.tasks.sumOf { it.executionReasons.size }
            if (remaining > 0) {
                droppedReasons += remaining
                working = working.copy(tasks = working.tasks.map {
                    if (it.executionReasons.isEmpty()) it else it.copy(executionReasons = emptyList())
                })
            }
            // Stage 2: halve the retained task set (same FAILED-first ranking) until it fits.
            // keepCount = size/2 strictly decreases and hits 0 at size 1, so the loop always
            // terminates; if the envelope alone still exceeds the budget it exits with an empty
            // task array (the build envelope always survives — best-effort for the envelope).
            while (encodedSize(working) > caps.maxPayloadBytes && working.tasks.isNotEmpty()) {
                val keepCount = working.tasks.size / 2
                val keep = rankedIndices(working.tasks).take(keepCount).toSet()
                working.tasks.forEachIndexed { index, task -> if (index !in keep) recordDrop(task, droppedOutcomes) }
                droppedTasks += working.tasks.size - keep.size
                working = working.copy(tasks = working.tasks.filterIndexed { index, _ -> index in keep })
            }
        }

        val fresh = CapsSummary(
            droppedTags = tags.dropped,
            droppedValues = values.dropped,
            truncatedValues = tags.truncated + values.truncated,
            droppedExecutionReasons = droppedReasons,
            truncatedExecutionReasons = truncatedReasons,
            truncatedNonCacheableReasons = truncatedNonCacheable,
            droppedTasks = droppedTasks,
            droppedTaskOutcomes = droppedOutcomes,
            droppedArtifacts = droppedArtifacts,
            droppedExtensions = droppedExtensions,
            droppedProjectEvaluations = droppedProjectEvaluations,
            droppedXmlDisabledTasks = droppedXmlDisabledTasks,
            droppedChangedModules = droppedChangedModules,
        )

        // Nothing countable to record and nothing was previously recorded → the input is compliant,
        // except a possibly-truncated seedRef (a silent truncation carries no CapsSummary count).
        if (fresh.isEmpty() && payload.caps == null) return if (cappedBenchmark == payload.benchmark) payload else working
        return working.copy(caps = merge(payload.caps, fresh))
    }

    private class MapResult(val map: Map<String, String>, val dropped: Int, val truncated: Int)

    private fun capMap(map: Map<String, String>, maxEntries: Int, maxKeyChars: Int, maxValueChars: Int): MapResult {
        if (map.isEmpty()) return MapResult(map, 0, 0)
        var dropped = 0
        var truncated = 0
        val kept = LinkedHashMap<String, String>()
        for ((key, value) in map) {
            // Truncating a key could collide with another key, so an over-long key drops.
            if (key.length > maxKeyChars) { dropped++; continue }
            if (kept.size >= maxEntries) { dropped++; continue }
            kept[key] = if (value.length > maxValueChars) { truncated++; value.substring(0, maxValueChars) } else value
        }
        return if (dropped == 0 && truncated == 0) MapResult(map, 0, 0) else MapResult(kept, dropped, truncated)
    }

    /** Indices of [tasks] ranked FAILED-first, then longest by durationMs, ties by path. */
    private fun rankedIndices(tasks: List<TaskExecution>): List<Int> =
        tasks.indices.sortedWith(
            compareByDescending<Int> { tasks[it].outcome == TaskOutcome.FAILED }
                .thenByDescending { tasks[it].durationMs }
                .thenBy { tasks[it].path },
        )

    private fun recordDrop(task: TaskExecution, outcomes: LinkedHashMap<String, Int>) {
        outcomes[task.outcome.name] = (outcomes[task.outcome.name] ?: 0) + 1
    }

    // One full encode measures the true byte size across ALL fields (not just the capped
    // maps/tasks — e.g. ci.attributes, buildUrl), so the total-byte bound stays honest. Run
    // unconditionally; at pilot scale a sub-millisecond encode per assembly/ingest is fine.
    private fun encodedSize(payload: BuildPayload): Int =
        BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload).encodeToByteArray().size

    private fun encodedElementSize(element: JsonElement): Int =
        BuildHoundJson.payload.encodeToString(JsonElement.serializer(), element).encodeToByteArray().size

    private fun CapsSummary.isEmpty(): Boolean =
        droppedTags == 0 && droppedValues == 0 && truncatedValues == 0 &&
            droppedExecutionReasons == 0 && truncatedExecutionReasons == 0 && truncatedNonCacheableReasons == 0 &&
            droppedTasks == 0 && droppedTaskOutcomes.isEmpty() && droppedArtifacts == 0 && droppedExtensions == 0 &&
            droppedProjectEvaluations == 0 && droppedXmlDisabledTasks == 0 && droppedChangedModules == 0

    private fun merge(existing: CapsSummary?, fresh: CapsSummary): CapsSummary {
        if (existing == null) return fresh
        val outcomes = LinkedHashMap(existing.droppedTaskOutcomes)
        for ((outcome, count) in fresh.droppedTaskOutcomes) outcomes[outcome] = (outcomes[outcome] ?: 0) + count
        return CapsSummary(
            droppedTags = existing.droppedTags + fresh.droppedTags,
            droppedValues = existing.droppedValues + fresh.droppedValues,
            truncatedValues = existing.truncatedValues + fresh.truncatedValues,
            droppedExecutionReasons = existing.droppedExecutionReasons + fresh.droppedExecutionReasons,
            truncatedExecutionReasons = existing.truncatedExecutionReasons + fresh.truncatedExecutionReasons,
            truncatedNonCacheableReasons = existing.truncatedNonCacheableReasons + fresh.truncatedNonCacheableReasons,
            droppedTasks = existing.droppedTasks + fresh.droppedTasks,
            droppedTaskOutcomes = outcomes,
            droppedArtifacts = existing.droppedArtifacts + fresh.droppedArtifacts,
            droppedExtensions = existing.droppedExtensions + fresh.droppedExtensions,
            droppedProjectEvaluations = existing.droppedProjectEvaluations + fresh.droppedProjectEvaluations,
            droppedXmlDisabledTasks = existing.droppedXmlDisabledTasks + fresh.droppedXmlDisabledTasks,
            droppedChangedModules = existing.droppedChangedModules + fresh.droppedChangedModules,
        )
    }
}
