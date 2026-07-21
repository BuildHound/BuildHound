package dev.buildhound.server

import dev.buildhound.commons.payload.TestUnitKey
import kotlinx.serialization.Serializable

/**
 * One build's outcome for one test class — the flattened row both stores feed [FlakyDetector] (the
 * plan-032 parity discipline: in-memory flattens payloads, Postgres reads `test_class_outcomes`, both
 * call the same pure detector). [sha] is null when the build had no VCS context (excluded from the
 * cross-run signal, which requires the *same* sha). [retryFlakyCases] counts this class's cases that
 * failed-then-passed within the build (the intra-run retry signal).
 */
data class ClassOutcome(
    val buildId: String,
    val startedAtMs: Long,
    val sha: String?,
    val module: String?,
    val classFqcn: String,
    val passed: Int,
    val failed: Int,
    val retryFlakyCases: Int,
)

/** Which signal flagged a class as flaky (plan 036). */
enum class FlakySignal { RETRY, CROSS_RUN, BOTH }

/**
 * One flaky (module, class) record over the window (plan 036). [flakeRate] = flaky-evidence builds /
 * [sampleCount]; [affectedBuildIds] is capped. Class-level is the detection grain (the failing
 * build's per-case detail names the offending case); [caseName] is reserved for a future per-case grain.
 */
@Serializable
data class FlakyRecord(
    val module: String? = null,
    val className: String,
    val caseName: String? = null,
    val signal: String,
    val flakeRate: Double,
    val sampleCount: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val affectedBuildIds: List<String>,
)

/**
 * Pure two-signal flaky detection (plan 036), the single source both stores defer to. Signals:
 * **retry** (a case failed-then-passed within one build) and **cross-run** (the same `(sha, unitKey)`
 * reached both a passed-only and a failed build — the *same-sha* requirement is the confounder guard
 * that keeps a fix-between-runs from reading as flaky). [minSamples]/[minFlakeRate] suppress one-offs;
 * thresholds live here in code, not docs.
 */
object FlakyDetector {

    const val MIN_SAMPLES = 3
    const val MIN_FLAKE_RATE = 0.05
    const val MAX_AFFECTED_BUILDS = 20

    /**
     * Defensive ceiling on per-class outcome rows read into memory for one flaky query (plan 036 §6
     * "cap rows"). Both stores fetch the **most recent** rows up to this bound — Postgres via
     * `ORDER BY started_at DESC … LIMIT`, in-memory via the identical sort + `take` — so an
     * authenticated tenant with a class-name-churning or huge-monorepo history can't pull an unbounded
     * result set onto the ingest hot path (the alert hook re-reads on every test-carrying build). Set
     * generously: below it both stores see the *identical full set* (detection is order-invariant, so
     * parity is exact); only in the pathological over-cap regime does truncation bound memory.
     */
    const val MAX_OUTCOME_ROWS = 200_000

    fun detect(
        rows: List<ClassOutcome>,
        minSamples: Int = MIN_SAMPLES,
        minFlakeRate: Double = MIN_FLAKE_RATE,
    ): List<FlakyRecord> =
        rows.groupBy { TestUnitKey.of(it.module, it.classFqcn) }
            .mapNotNull { (_, group) ->
                val builds = group.map { it.buildId }.distinct()
                val sampleCount = builds.size
                if (sampleCount < minSamples) return@mapNotNull null

                // Cross-run: a sha is divergent when it has ≥1 green (ran, all passed) and ≥1 red build.
                val crossRunBuilds = group.filter { it.sha != null }
                    .groupBy { it.sha }
                    .filterValues { sameSha ->
                        sameSha.any { it.failed == 0 && it.passed > 0 } && sameSha.any { it.failed > 0 }
                    }
                    .values.flatten().map { it.buildId }.toSet()

                // Retry: any build where a case in this class failed-then-passed.
                val retryBuilds = group.filter { it.retryFlakyCases > 0 }.map { it.buildId }.toSet()

                val retry = retryBuilds.isNotEmpty()
                val crossRun = crossRunBuilds.isNotEmpty()
                if (!retry && !crossRun) return@mapNotNull null

                val evidence = crossRunBuilds + retryBuilds
                val flakeRate = roundTo6(evidence.size.toDouble() / sampleCount)
                if (flakeRate < minFlakeRate) return@mapNotNull null

                val signal = when {
                    retry && crossRun -> FlakySignal.BOTH
                    retry -> FlakySignal.RETRY
                    else -> FlakySignal.CROSS_RUN
                }
                val first = group.minByOrNull { it.startedAtMs }!!
                FlakyRecord(
                    module = first.module,
                    className = first.classFqcn,
                    signal = signal.name,
                    flakeRate = flakeRate,
                    sampleCount = sampleCount,
                    firstSeenMs = group.minOf { it.startedAtMs },
                    lastSeenMs = group.maxOf { it.startedAtMs },
                    // Deterministic: the evidence builds, oldest-first, capped.
                    affectedBuildIds = group.filter { it.buildId in evidence }
                        .sortedBy { it.startedAtMs }.map { it.buildId }.distinct().take(MAX_AFFECTED_BUILDS),
                )
            }
            .sortedWith(
                compareByDescending<FlakyRecord> { it.flakeRate }
                    .thenBy { it.module ?: "" }
                    .thenBy { it.className }
            )

    /** Count of a class's cases (within one build's failed-or-retried list) that failed then passed. */
    fun retryFlakyCaseCount(outcomesPerCase: List<List<String>>): Int =
        outcomesPerCase.count { failedThenPassed(it) }

    /** True when the ordered outcome sequence has a FAILED (or ERROR) immediately-or-eventually followed by PASSED. */
    fun failedThenPassed(outcomes: List<String>): Boolean {
        val firstFail = outcomes.indexOfFirst { it == "FAILED" || it == "ERROR" }
        if (firstFail < 0) return false
        return outcomes.drop(firstFail + 1).any { it == "PASSED" }
    }

    private fun roundTo6(value: Double): Double = Math.round(value * SIX_DECIMAL_FACTOR) / SIX_DECIMAL_FACTOR
}
