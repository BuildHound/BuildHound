package dev.buildhound.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One build's delivery-health inputs (plan 059, research F9) — the raw shape both stores fetch over
 * the window and hand to [DeliveryHealthCalculator] (the plan-026/032 parity discipline). In-memory
 * flattens payloads; Postgres reads hot columns (`branch`/`pipeline_name`/`ci_provider`/`outcome`/
 * `started_at`/`finished_at`/`requested_tasks_sig`) plus jsonb extracts (`vcs.sha`, `projectKey`,
 * `ci.attributes.runAttempt`). [runAttempt] is parsed via [DeliveryHealthCalculator.parseRunAttempt]
 * (`toIntOrNull` — a garbage attribute string degrades to null, never a throw). [provider] rides
 * along per the plan-059 row shape: `runAttempt` is populated only by GitHub Actions today, so the
 * provider label documents which signal family a build can contribute to (informational in v1).
 */
data class DeliveryBuildRow(
    val buildId: String,
    val branch: String?,
    val pipelineName: String?,
    val provider: String?,
    val outcome: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val sha: String?,
    val projectKey: String?,
    val requestedTasksSig: String,
    val runAttempt: Int?,
)

/**
 * Change-failure-rate **proxy** for one (branch, pipeline) cohort (plan 059): `FAILED / (SUCCESS +
 * FAILED)` over the window — the per-branch/pipeline refinement of plan-032's fleet `successRate`
 * KPI, not a new signal. INTERRUPTED builds are excluded (plan 033: never finalized, synthetic
 * duration — neither a pass nor a deploy-adjacent failure). This is a CI failure share over ingested
 * builds, **not** real DORA change-failure-rate (no deployment data) — label it a proxy everywhere.
 */
@Serializable
data class CfrRow(
    val branch: String? = null,
    val pipelineName: String? = null,
    val failed: Int,
    val succeeded: Int,
    val changeFailureRate: Double,
)

/**
 * Time-to-green for one (branch, pipeline) cohort (plan 059): recovery episodes walk the cohort's
 * builds oldest-first; an episode opens at the **first** FAILED build (when the branch went red) and
 * closes at the next SUCCESS (when it went green again) — `recoveryMs = success.finishedAt −
 * firstFailed.finishedAt`. A **CI-recovery proxy, not production MTTR** (no incident data): the DTO,
 * the dashboard caption, and the plan's exit criteria all say so. [openEpisode] flags a cohort still
 * red at window end — counted separately, never folded into the recovery stats as a fake recovery.
 * [medianRecoveryMs]/[p90RecoveryMs] are null when no episode closed inside the window.
 */
@Serializable
data class RecoveryRow(
    val branch: String? = null,
    val pipelineName: String? = null,
    val recoveries: Int,
    val medianRecoveryMs: Long? = null,
    val p90RecoveryMs: Long? = null,
    val openEpisode: Boolean = false,
)

/**
 * Lead-time **contribution** for one (branch, pipeline) cohort (plan 059): how much wall-clock a
 * change spends in the build/CI pipeline — not full commit-to-production lead time (no deploy
 * events). [medianDurationMs] is build-only (SUCCESS/FAILED finished builds, plan-033 exclusion) and
 * always renders; [medianQueuedMs]/[medianGradleSharePct] are route-level best-effort enrichment
 * from `ci_runs` (plan 028) — null when no connector has enriched this cohort's builds (the
 * degradation boundary: the parity-tested core is build-only, the connector layer degrades).
 */
@Serializable
data class LeadTimeRow(
    val branch: String? = null,
    val pipelineName: String? = null,
    val buildCount: Int,
    val medianDurationMs: Long,
    val medianQueuedMs: Long? = null,
    val medianGradleSharePct: Double? = null,
    /**
     * The most recent finished builds backing this row (id + Gradle wall ms), for the route's bounded
     * `ciSpans.findRun` enrichment pass — capped at [DeliveryHealthCalculator.ENRICHMENT_SAMPLE_CAP]
     * so the point-lookup fan-out stays bounded. Wire-invisible (`@Transient`): purely an in-process
     * handoff from store to route.
     */
    @Transient val enrichmentSamples: List<DeliverySample> = emptyList(),
)

/** One (buildId, Gradle wall ms) enrichment sample carried transiently on [LeadTimeRow]/[RetryTaxSummary]. */
data class DeliverySample(val buildId: String, val durationMs: Long)

/** Which signal identified a rerun build (plan 059): authoritative GHA attempt counter vs. the heuristic. */
enum class RerunSignal { RUN_ATTEMPT, SAME_KEY_CANDIDATE }

/**
 * The retry tax (plan 059, research F9 — Parry et al. 2022: "rerun the failing build" is the most
 * common flakiness response). [wastedCiMinutesLowerBound] is Σ rerun-build duration — a **lower
 * bound**: Gradle wall-clock only (checkout/setup excluded) until the route upgrades enriched builds
 * to pipeline wall-clock from `ci_runs`. [runAttemptReruns] (authoritative, `runAttempt > 1` — GHA)
 * vs [sameKeyCandidates] (heuristic, labeled **candidate** — never a confirmed rerun) is the signal
 * split. [rerunBuildIds] is oldest-first, capped at [DeliveryHealthCalculator.MAX_RERUN_BUILD_IDS].
 */
@Serializable
data class RetryTaxSummary(
    val chainCount: Int,
    val rerunBuildIds: List<String>,
    val wastedCiMinutesLowerBound: Double,
    val runAttemptReruns: Int,
    val sameKeyCandidates: Int,
    /** Total wasted ms behind the minutes figure — wire-invisible, for the route's pipeline-wall upgrade. */
    @Transient val wastedMsLowerBound: Long = 0,
    /** Per-rerun-build Gradle wall ms (capped like [rerunBuildIds]) — wire-invisible enrichment handoff. */
    @Transient val rerunSamples: List<DeliverySample> = emptyList(),
)

/**
 * One flaky class ranked by the CI minutes spent rerunning builds it affected (plan 059): the
 * intersection of [FlakyRecord.affectedBuildIds] (plan 036) with the window's rerun builds. A ranked
 * **candidate**, never a confirmed cause (the plan-057 honesty discipline) — not every rerun is
 * flakiness, and not every flaky-affected rerun was rerun *because* of that class.
 */
@Serializable
data class FlakyRerunCandidate(
    val module: String? = null,
    val className: String,
    val rerunBuildCount: Int,
    val wastedCiMinutesLowerBound: Double,
)

/**
 * `GET /v1/rollups/delivery-health` response (plan 059): three DORA **proxies** over already-ingested
 * build rows — zero new collection, no git-history/deployment mining, inside the spec-§1 Git/DORA
 * non-goal by construction. [connectorDataAvailable] flips true only when the route found a `ci_runs`
 * row (plan 028/041); [flakyRerunTax] is route-level enrichment too — both stay at their defaults in
 * the build-only parity core both stores compute.
 */
@Serializable
data class DeliveryHealthRollup(
    val period: Int,
    val changeFailureRate: List<CfrRow>,
    val timeToGreen: List<RecoveryRow>,
    val leadTime: List<LeadTimeRow>,
    val retryTax: RetryTaxSummary,
    val connectorDataAvailable: Boolean = false,
    val flakyRerunTax: List<FlakyRerunCandidate> = emptyList(),
)

/**
 * Pure delivery-health math (plan 059), the single source both stores defer to (the plan-026/032
 * parity discipline: both fetch the same windowed [DeliveryBuildRow]s — benchmark builds excluded,
 * the bottlenecks/toolchain fleet-view convention — and fold them through this). Rows are sorted
 * internally with a `(startedAt, buildId)` tie-break, so output never depends on arrival order.
 *
 * Retry-tax detection: `runAttempt > 1` ⇒ [RerunSignal.RUN_ATTEMPT] (authoritative — GHA populates
 * the attribute). The all-provider heuristic ([RerunSignal.SAME_KEY_CANDIDATE]) groups by
 * `(projectKey, sha, requestedTasksSig)` and flags a build only when a **prior same-key build is
 * FAILED and this build started strictly after that one finished** (sequential, non-overlapping) —
 * concurrent JDK-matrix legs and PR-vs-push builds on one sha overlap in time and are therefore
 * never miscounted as reruns (the plan's false-positive guard, adversarially pinned in tests).
 */
object DeliveryHealthCalculator {

    /** Finished builds a (branch, pipeline) cohort needs before its CFR is claimed (small-sample honesty). */
    const val MIN_SAMPLES = 3

    /** Per-family row cap — the established top-25 convention ([RollupCalculator.TOP_N]). */
    const val TOP_N = RollupCalculator.TOP_N

    /** Wire + enrichment cap on rerun build ids (bounds the response and the route's findRun fan-out). */
    const val MAX_RERUN_BUILD_IDS = 50

    /** Most-recent finished builds per lead-time row sampled for queue/share enrichment at the route. */
    const val ENRICHMENT_SAMPLE_CAP = 10

    /** `ci.attributes["runAttempt"]` is an untrusted string — garbage degrades to null, never a throw. */
    fun parseRunAttempt(value: String?): Int? = value?.toIntOrNull()

    fun compute(rows: List<DeliveryBuildRow>, period: Int): DeliveryHealthRollup {
        // One deterministic order for everything below: arrival order (map iteration, SQL result
        // order) must never leak into the output (the BottleneckCalculator discipline).
        val sorted = rows.sortedWith(compareBy({ it.startedAtMs }, { it.buildId }))
        val cohorts = sorted.groupBy { it.branch to it.pipelineName }

        return DeliveryHealthRollup(
            period = period,
            changeFailureRate = cfrRows(cohorts),
            timeToGreen = recoveryRows(cohorts),
            leadTime = leadTimeRows(cohorts),
            retryTax = retryTax(sorted),
        )
    }

    private fun cfrRows(cohorts: Map<Pair<String?, String?>, List<DeliveryBuildRow>>): List<CfrRow> =
        cohorts.mapNotNull { (key, group) ->
            // INTERRUPTED excluded (plan 033): never finalized, so neither a pass nor a failure here.
            val failed = group.count { it.outcome == "FAILED" }
            val succeeded = group.count { it.outcome == "SUCCESS" }
            val finished = failed + succeeded
            if (finished < MIN_SAMPLES) return@mapNotNull null // small-sample honesty, never a 1/1=100% row
            CfrRow(
                branch = key.first,
                pipelineName = key.second,
                failed = failed,
                succeeded = succeeded,
                changeFailureRate = roundTo6(failed.toDouble() / finished),
            )
        }.sortedWith(
            compareByDescending<CfrRow> { it.changeFailureRate }
                .thenByDescending { it.failed }
                .thenBy { it.branch ?: "" }
                .thenBy { it.pipelineName ?: "" },
        ).take(TOP_N)

    private fun recoveryRows(cohorts: Map<Pair<String?, String?>, List<DeliveryBuildRow>>): List<RecoveryRow> =
        cohorts.mapNotNull { (key, group) ->
            // Episode walk over finished builds only (INTERRUPTED is neither red nor green): open at
            // the FIRST FAILED, close at the next SUCCESS. recoveryMs spans finish-to-finish — red
            // starts when the failing build finishes, green returns when the succeeding one does.
            var openFailedFinishedAt: Long? = null
            val recoveries = mutableListOf<Long>()
            for (build in group) {
                when {
                    build.outcome == "FAILED" && openFailedFinishedAt == null -> openFailedFinishedAt = build.finishedAtMs
                    build.outcome == "SUCCESS" && openFailedFinishedAt != null -> {
                        recoveries.add((build.finishedAtMs - openFailedFinishedAt!!).coerceAtLeast(0))
                        openFailedFinishedAt = null
                    }
                }
            }
            val openEpisode = openFailedFinishedAt != null
            if (recoveries.isEmpty() && !openEpisode) return@mapNotNull null
            RecoveryRow(
                branch = key.first,
                pipelineName = key.second,
                recoveries = recoveries.size,
                medianRecoveryMs = recoveries.takeIf { it.isNotEmpty() }
                    ?.let { Math.round(RegressionEngine.median(it.map { v -> v.toDouble() })) },
                p90RecoveryMs = recoveries.takeIf { it.isNotEmpty() }?.let { NearestRankPercentile.of(it, 0.90) },
                openEpisode = openEpisode,
            )
        }.sortedWith(
            compareByDescending<RecoveryRow> { it.recoveries }
                .thenByDescending { it.openEpisode }
                .thenBy { it.branch ?: "" }
                .thenBy { it.pipelineName ?: "" },
        ).take(TOP_N)

    private fun leadTimeRows(cohorts: Map<Pair<String?, String?>, List<DeliveryBuildRow>>): List<LeadTimeRow> =
        cohorts.mapNotNull { (key, group) ->
            // Finished builds only (plan 033): an INTERRUPTED build's duration is synthetic.
            val finished = group.filter { it.outcome == "SUCCESS" || it.outcome == "FAILED" }
            if (finished.isEmpty()) return@mapNotNull null
            val durations = finished.map { (it.finishedAtMs - it.startedAtMs).coerceAtLeast(0) }
            LeadTimeRow(
                branch = key.first,
                pipelineName = key.second,
                buildCount = finished.size,
                medianDurationMs = Math.round(RegressionEngine.median(durations.map { it.toDouble() })),
                // Most recent first, deterministic tie-break — the bounded set the route enriches.
                enrichmentSamples = finished
                    .sortedWith(compareByDescending<DeliveryBuildRow> { it.startedAtMs }.thenByDescending { it.buildId })
                    .take(ENRICHMENT_SAMPLE_CAP)
                    .map { DeliverySample(it.buildId, (it.finishedAtMs - it.startedAtMs).coerceAtLeast(0)) },
            )
        }.sortedWith(
            compareByDescending<LeadTimeRow> { it.buildCount }
                .thenBy { it.branch ?: "" }
                .thenBy { it.pipelineName ?: "" },
        ).take(TOP_N)

    private fun retryTax(sorted: List<DeliveryBuildRow>): RetryTaxSummary {
        // Heuristic (all providers): same-(projectKey, sha, requestedTasksSig) groups, sha required —
        // without a sha there is no "same change" identity to chain on.
        val sameKeyRerunIds = mutableSetOf<String>()
        val chainKeyByBuild = mutableMapOf<String, String>()
        sorted.filter { it.sha != null }
            .groupBy { Triple(it.projectKey, it.sha, it.requestedTasksSig) }
            .forEach { (chainKey, group) ->
                for (i in group.indices) {
                    val build = group[i]
                    chainKeyByBuild[build.buildId] = "${chainKey.first} ${chainKey.second} ${chainKey.third}"
                    // Candidate only when a PRIOR same-key build FAILED and this one started strictly
                    // after it finished — overlapping (concurrent matrix/PR-vs-push) legs never count.
                    if (group.subList(0, i).any { prior -> prior.outcome == "FAILED" && build.startedAtMs > prior.finishedAtMs }) {
                        sameKeyRerunIds.add(build.buildId)
                    }
                }
            }

        // Authoritative: runAttempt > 1 (GHA). A build both signals flag counts once, as RUN_ATTEMPT.
        val runAttemptIds = sorted.filter { (it.runAttempt ?: 0) > 1 }.map { it.buildId }.toSet()
        val sameKeyOnly = sameKeyRerunIds - runAttemptIds

        val reruns = sorted.filter { it.buildId in runAttemptIds || it.buildId in sameKeyOnly }
        // A chain = one same-key group with ≥1 rerun; a rerun with no sha (RUN_ATTEMPT only) is its
        // own chain — deterministic either way.
        val chainCount = reruns.map { chainKeyByBuild[it.buildId] ?: "solo ${it.buildId}" }.distinct().size
        val wastedMs = reruns.sumOf { (it.finishedAtMs - it.startedAtMs).coerceAtLeast(0) }
        val capped = reruns.take(MAX_RERUN_BUILD_IDS)

        return RetryTaxSummary(
            chainCount = chainCount,
            rerunBuildIds = capped.map { it.buildId },
            wastedCiMinutesLowerBound = minutes(wastedMs),
            runAttemptReruns = runAttemptIds.size,
            sameKeyCandidates = sameKeyOnly.size,
            wastedMsLowerBound = wastedMs,
            rerunSamples = capped.map { DeliverySample(it.buildId, (it.finishedAtMs - it.startedAtMs).coerceAtLeast(0)) },
        )
    }

    /** ms → minutes at one decimal — deterministic rounding shared with the route's upgrade pass. */
    fun minutes(ms: Long): Double = Math.round(ms / 60_000.0 * 10.0) / 10.0

    private fun roundTo6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
