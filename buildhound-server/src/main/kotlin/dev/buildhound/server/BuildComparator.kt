package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TaskOutcome
import kotlinx.serialization.Serializable

/** One side of a comparison — declared (non-hashed) build metadata for the header. */
@Serializable
data class CompareBuildRef(
    val buildId: String,
    val startedAt: Long,
    val outcome: String,
    val mode: String,
    val branch: String? = null,
    val sha: String? = null,
    /** The payload's root-project name (plan 079); powers the dashboard's same-project comparison guard. Additive. */
    val projectKey: String? = null,
)

/**
 * One differing input between the two builds, ranked by [coverage] — the share of the misses in
 * B that this input could explain. Build-scoped inputs cover all misses (1.0); task-scoped inputs
 * cover the fraction of miss tasks whose fingerprint for this key differs. Values are the salted
 * hashes (or declared field values for the toolchain/environment diffs).
 */
@Serializable
data class CompareDiff(
    val key: String,
    val scope: String, // BUILD | TASK | FIELD
    val valueA: String? = null,
    val valueB: String? = null,
    val differingTaskCount: Int = 0,
    val coverage: Double = 1.0,
    val note: String? = null,
)

/**
 * Comparison of two builds A and B (plan 022, spec §5). A is treated as the "fast/cached" build,
 * B as the one whose cache misses we explain: [missesToExplain] are tasks that executed in B but
 * were avoided in A. [diffs] rank the differing inputs by how much of those misses each could
 * account for. All input values are salted hashes — no plaintext.
 */
@Serializable
data class CompareResult(
    val a: CompareBuildRef,
    val b: CompareBuildRef,
    val requestedTasksMatch: Boolean,
    val missesToExplain: List<String>,
    val diffs: List<CompareDiff>,
)

/** Pure comparison logic — plain-unit-testable, no storage or Ktor types (server §5). */
object BuildComparator {

    fun compare(a: BuildPayload, b: BuildPayload): CompareResult {
        val misses = missesToExplain(a, b)
        val diffs = buildList {
            addAll(buildLevelDiffs(a, b))
            addAll(fieldDiffs(a, b))
            addAll(taskLevelDiffs(a, b, misses))
        }.sortedWith(compareByDescending<CompareDiff> { it.coverage }.thenBy { it.key })

        return CompareResult(
            a = ref(a),
            b = ref(b),
            requestedTasksMatch = a.requestedTasks == b.requestedTasks,
            missesToExplain = misses,
            diffs = diffs,
        )
    }

    private fun ref(p: BuildPayload) = CompareBuildRef(
        buildId = p.buildId,
        startedAt = p.startedAt,
        outcome = p.outcome.name,
        mode = p.mode.name,
        branch = p.vcs?.branch,
        sha = p.vcs?.sha,
        projectKey = p.projectKey,
    )

    /** Tasks that ran in B but were avoided in A — the cache misses worth explaining. */
    private fun missesToExplain(a: BuildPayload, b: BuildPayload): List<String> {
        val avoidedInA = a.tasks
            .filter { it.outcome == TaskOutcome.FROM_CACHE || it.outcome == TaskOutcome.UP_TO_DATE }
            .map { it.path }
            .toSet()
        return b.tasks
            .filter { (it.outcome == TaskOutcome.EXECUTED || it.outcome == TaskOutcome.FAILED) && it.path in avoidedInA }
            .map { it.path }
            .distinct()
            .sorted()
    }

    /** Build-level fingerprint keys whose salted hash differs (or is present on only one side). */
    private fun buildLevelDiffs(a: BuildPayload, b: BuildPayload): List<CompareDiff> {
        val fa = a.fingerprints?.build.orEmpty()
        val fb = b.fingerprints?.build.orEmpty()
        return (fa.keys + fb.keys).sorted()
            .filter { fa[it] != fb[it] }
            .map { key -> CompareDiff(key, "BUILD", fa[key], fb[key], coverage = 1.0, note = NOTES[key]) }
    }

    /** Declared toolchain/environment fields that differ (not hashed — safe to show in full). */
    private fun fieldDiffs(a: BuildPayload, b: BuildPayload): List<CompareDiff> {
        val fields = listOf(
            "toolchain.jdk" to Pair(a.toolchain?.jdk, b.toolchain?.jdk),
            "toolchain.gradle" to Pair(a.toolchain?.gradle, b.toolchain?.gradle),
            "toolchain.agp" to Pair(a.toolchain?.agp, b.toolchain?.agp),
            "toolchain.kgp" to Pair(a.toolchain?.kgp, b.toolchain?.kgp),
            "environment.os" to Pair(a.environment?.os, b.environment?.os),
            "environment.arch" to Pair(a.environment?.arch, b.environment?.arch),
        )
        return fields
            .filter { (_, v) -> v.first != v.second && (v.first != null || v.second != null) }
            .map { (key, v) -> CompareDiff(key, "FIELD", v.first, v.second, coverage = 1.0, note = NOTES[key]) }
    }

    /**
     * Per-task fingerprint keys, ranked by how many misses they differ on. Reserved for the
     * per-Test capture add-on (plan 022 §8): today `tasks` maps are empty, so this yields nothing.
     */
    private fun taskLevelDiffs(a: BuildPayload, b: BuildPayload, misses: List<String>): List<CompareDiff> {
        if (misses.isEmpty()) return emptyList()
        val ta = a.fingerprints?.tasks.orEmpty()
        val tb = b.fingerprints?.tasks.orEmpty()
        val perKeyDiffCount = LinkedHashMap<String, Int>()
        for (path in misses) {
            val ma = ta[path].orEmpty()
            val mb = tb[path].orEmpty()
            for (key in ma.keys + mb.keys) {
                if (ma[key] != mb[key]) perKeyDiffCount[key] = (perKeyDiffCount[key] ?: 0) + 1
            }
        }
        return perKeyDiffCount.map { (key, count) ->
            CompareDiff(key, "TASK", differingTaskCount = count, coverage = count.toDouble() / misses.size, note = NOTES[key])
        }
    }

    /**
     * Cross-referenced by [FingerprintVolatilityDetector] (plan 068) for its fallback note on a
     * volatile built-in key (e.g. `jdk.home`) that doesn't match its own credential/timestamp/run-id
     * name patterns — reuse, not extraction: this shipped, tested plan-022 catalog stays put, and
     * plan 068 only reads it through this accessor.
     */
    internal fun explanatoryNote(key: String): String? = NOTES[key]

    /** Static known-volatile-input catalog (research §4/§5); explanations, keeping the plugin dumb. */
    private val NOTES: Map<String, String> = mapOf(
        "jdk.home" to "JDK install path differs — Gradle keys the cache on the JDK major only, so a different home (even same major) can miss (android-cache-fix JdkImageWorkaround).",
        "jdk.vendor" to "JDK vendor differs.",
        "jdk.version" to "Full JDK runtime version differs (Gradle keys on the major only).",
        "file.encoding" to "Default file encoding differs — affects compilation and text I/O caching.",
        "user.language" to "Default locale language differs.",
        "user.country" to "Default locale country differs.",
        "timezone" to "Default timezone differs.",
        "gradle.parallel" to "Parallel execution setting differs.",
        "gradle.maxWorkers" to "Max worker count differs.",
        "toolchain.jdk" to "Declared JDK version differs.",
        "toolchain.gradle" to "Gradle version differs.",
    )
}
