package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ConfigurationCacheState
import kotlinx.serialization.Serializable

/**
 * One windowed build's configuration-cache-economics facts (plan 064, research F14) — the flattened row
 * both stores feed into the two pure detectors below (the plan-026/032/068 fold-in-Kotlin parity
 * posture). Carries the salt-stream identity + salted fingerprints [CcFlipFlopDetector] needs alongside
 * the reuse-classification + percentile inputs. Not serialized — an internal store↔calculator boundary.
 */
data class CcBuildRow(
    val buildId: String,
    val startedAt: Long,
    val mode: BuildMode,
    val ccState: ConfigurationCacheState?,
    /** Configuration (store) cost — `derived.configurationMs`; the store-cost p50 reads it on MISS_STORED. */
    val configurationMs: Long?,
    /** CC entry-load proxy — `derived.ccLoadMs`; the load p50 reads it on HIT (currently always null — see its doc). */
    val ccLoadMs: Long?,
    val ccEntrySizeBytes: Long?,
    /** Salt-stream grouping keys for the flip-flop detector (both must be present to participate). */
    val userId: String?,
    val hostnameHash: String?,
    /** Salted `fingerprints.build` (plan 022); empty when uncaptured — an empty map never "matches" another. */
    val fingerprints: Map<String, String>,
)

/** Flatten one payload into a [CcBuildRow] (plan 064); never null — every windowed build classifies. */
internal fun ccBuildRowOf(payload: BuildPayload): CcBuildRow =
    CcBuildRow(
        buildId = payload.buildId,
        startedAt = payload.startedAt,
        mode = payload.mode,
        ccState = payload.environment?.configurationCache,
        configurationMs = payload.derived?.configurationMs,
        ccLoadMs = payload.derived?.ccLoadMs,
        ccEntrySizeBytes = payload.derived?.ccEntrySizeBytes,
        userId = payload.environment?.userId,
        hostnameHash = payload.environment?.hostnameHash,
        fingerprints = payload.fingerprints?.build.orEmpty(),
    )

/**
 * Advisory CI-reuse classification (plan 064, research F14) — an **annotation, never a "turn it off"
 * fix**. F14's own guidance is "enable CC on CI for parallelism + early breakage; do not focus on cache
 * reuse", so [EPHEMERAL_CI_EXPECTED_ZERO] exists precisely to *defuse* a below-healthy reuse rate on a
 * CI-dominant window rather than alarm on it; [REUSE_DEGRADED] is reserved for the non-CI (local/dev)
 * windows where high reuse *is* the expectation. Sibling of [RerunCause]/[FlakySignal]/[WarningCategory]
 * — a server-local advisory enum, never a Gradle-API type.
 */
@Serializable
enum class CiReuseClass {
    /** Not enough CC-requesting builds in the window to judge reuse (honest gap, plan 005). */
    INSUFFICIENT_DATA,

    /** The window observed CC state, but no build requested it (every CC-carrying build was DISABLED/INCOMPATIBLE). */
    DISABLED,

    /**
     * CI-dominant window with below-healthy reuse — **expected**, not a defect: ephemeral CI agents
     * rarely carry a warm CC entry across jobs (F14). Near-zero is the archetype the name calls out.
     */
    EPHEMERAL_CI_EXPECTED_ZERO,

    /** Reuse rate at or above [CcEconomicsCalculator.HEALTHY_REUSE] (any window). */
    REUSE_HEALTHY,

    /** Below-healthy reuse on a **not**-CI-dominant window (local/dev, where reuse should be high) — worth a look. */
    REUSE_DEGRADED,
}

/**
 * One flip-flop finding (plan 064, research F14): a `MISS_STORED` build whose salted `fingerprints.build`
 * map is identical to a strictly-earlier build's within the same single-machine salt stream — the CC
 * entry should have hit but was re-stored, so some untracked input is thrashing the key. [priorBuildId]
 * is the earliest such matching build in the stream. [note] is a label only — no fingerprint value ever
 * ships (spec §3.7; the map is compared, never emitted).
 */
@Serializable
data class CcFlipFlopFinding(
    val buildId: String,
    val startedAt: Long,
    val priorBuildId: String,
    val note: String,
)

/**
 * `GET /v1/rollups/cc-economics` response (plan 064, research F14): the advisory CI-reuse class + the
 * reuse counters + store/load/entry-size p50s + flip-flop findings, computed by [CcEconomicsCalculator]
 * from the windowed [CcBuildRow]s both stores flatten identically.
 */
@Serializable
data class CcEconomicsReport(
    val ciReuseClass: CiReuseClass,
    /** Builds in the window carrying a CC state (HIT/MISS_STORED/DISABLED/INCOMPATIBLE) — the "known posture" set. */
    val ccObservedBuilds: Int,
    /** Builds that requested CC (HIT or MISS_STORED) — the reuse-rate denominator. */
    val ccRequestedBuilds: Int,
    val ccHitBuilds: Int,
    val ccMissStoredBuilds: Int,
    /** `ccHit / ccRequested`, rounded to 6 dp; null when no build requested CC. */
    val reuseRate: Double? = null,
    /** p50 configuration (store) cost over MISS_STORED builds' `configurationMs`; null when none. */
    val storeCostMsP50: Long? = null,
    /**
     * p50 of the `ccLoadMs` proxy over HIT builds; null when none (currently always null — see
     * `DerivedMetrics.ccLoadMs`).
     */
    val loadMsP50: Long? = null,
    /** p50 of `ccEntrySizeBytes` over CC-requesting builds; null when none. */
    val entrySizeBytesP50: Long? = null,
    val flipFlops: List<CcFlipFlopFinding> = emptyList(),
)

/**
 * Pure CC-economics rollup (plan 064, research F14), sibling of [RelocatabilityDetector]/`BottleneckCalculator`
 * — no Ktor/storage types, so both stores compute it identically from the same windowed rows (byte-for-byte
 * parity, the plan-026 discipline). The reuse classification is a deterministic if/else cascade over three
 * named threshold constants (each boundary pinned in `CcEconomicsCalculatorTest`); percentiles fold in Kotlin
 * via [NearestRankPercentile] (no SQL `percentile_cont`, so no parity-drift trap).
 */
object CcEconomicsCalculator {

    /**
     * A window needs at least this many CC-requesting builds before a reuse verdict is honest (not
     * INSUFFICIENT_DATA).
     */
    const val MIN_REQUESTED_FOR_CLASS: Int = 5

    /**
     * At or above this share of CI builds, the window is "CI-dominant" — below-healthy reuse reads
     * as expected, not degraded.
     */
    const val CI_DOMINANT_SHARE: Double = 0.5

    /** Reuse rate at or above this is healthy on any window. */
    const val HEALTHY_REUSE: Double = 0.5

    fun compute(rows: List<CcBuildRow>): CcEconomicsReport {
        val observed = rows.filter { it.ccState != null }
        val requested = observed.filter {
            it.ccState == ConfigurationCacheState.HIT || it.ccState == ConfigurationCacheState.MISS_STORED
        }
        val hits = requested.filter { it.ccState == ConfigurationCacheState.HIT }
        val missStored = requested.filter { it.ccState == ConfigurationCacheState.MISS_STORED }
        val reuseRate =
            if (requested.isEmpty()) null else roundTo6(hits.size.toDouble() / requested.size)
        val ciShare =
            if (requested.isEmpty()) 0.0
            else requested.count { it.mode == BuildMode.CI }.toDouble() / requested.size

        val ciReuseClass = when {
            observed.isEmpty() -> CiReuseClass.INSUFFICIENT_DATA
            requested.isEmpty() -> CiReuseClass.DISABLED
            requested.size < MIN_REQUESTED_FOR_CLASS -> CiReuseClass.INSUFFICIENT_DATA
            (reuseRate ?: 0.0) >= HEALTHY_REUSE -> CiReuseClass.REUSE_HEALTHY
            ciShare >= CI_DOMINANT_SHARE -> CiReuseClass.EPHEMERAL_CI_EXPECTED_ZERO
            else -> CiReuseClass.REUSE_DEGRADED
        }

        return CcEconomicsReport(
            ciReuseClass = ciReuseClass,
            ccObservedBuilds = observed.size,
            ccRequestedBuilds = requested.size,
            ccHitBuilds = hits.size,
            ccMissStoredBuilds = missStored.size,
            reuseRate = reuseRate,
            storeCostMsP50 = p50(missStored.mapNotNull { it.configurationMs }),
            loadMsP50 = p50(hits.mapNotNull { it.ccLoadMs }),
            entrySizeBytesP50 = p50(requested.mapNotNull { it.ccEntrySizeBytes }),
            flipFlops = CcFlipFlopDetector.detect(rows),
        )
    }

    private fun p50(values: List<Long>): Long? =
        if (values.isEmpty()) null else NearestRankPercentile.of(values, P50_QUANTILE)

    private fun roundTo6(value: Double): Double = Math.round(value * SIX_DECIMAL_FACTOR) / SIX_DECIMAL_FACTOR
}

/**
 * Pure configuration-cache flip-flop detection (plan 064, research F14), sibling of
 * [FingerprintVolatilityDetector]. Groups rows by `(userId, hostnameHash)` — one machine's salt stream,
 * so identical salted `fingerprints.build` maps mean identical raw inputs (plan 022 divergence note) and
 * never compare across salt streams — orders each stream by `(startedAt, buildId)`, and flags every
 * `MISS_STORED` whose fingerprint map equals a strictly-earlier **CC-requesting** build's in the same
 * stream: an entry for those exact inputs existed (an earlier HIT used one, or an earlier MISS_STORED
 * wrote one), yet this build re-stored — so an untracked input is invalidating the key. A DISABLED prior
 * stored no entry, so it is **not** evidence of a flip-flop (plan-064 narrowing over the plan text's bare
 * "a strictly-earlier build's": the prior must itself be HIT/MISS_STORED).
 */
object CcFlipFlopDetector {

    /**
     * Defensive ceiling on findings returned for one query (house convention: every array capped,
     * oldest-first kept).
     */
    const val MAX_FINDINGS: Int = 500

    private fun CcBuildRow.requestedCc(): Boolean =
        ccState == ConfigurationCacheState.HIT || ccState == ConfigurationCacheState.MISS_STORED

    fun detect(rows: List<CcBuildRow>): List<CcFlipFlopFinding> =
        rows
            // A row participates only with a full salt-stream identity AND a non-empty fingerprint map: a
            // null userId/hostnameHash (strict mode / legacy payload) has no stream to compare within, and
            // an empty map has nothing to match (never a false "identical inputs").
            .filter { it.userId != null && it.hostnameHash != null && it.fingerprints.isNotEmpty() }
            .groupBy { it.userId to it.hostnameHash }
            .values
            .flatMap { stream ->
                val ordered = stream.sortedWith(compareBy<CcBuildRow> { it.startedAt }.thenBy { it.buildId })
                // O(n^2) worst case per stream — the subList().firstOrNull() below rescans the
                // earlier-rows prefix for every row. Bounded by MAX_CC_ECONOMICS_ROWS (20k rows across the
                // whole call), so a pathological single-stream worst case is on the order of a few hundred
                // million small-map comparisons. Accepted at pilot scale; revisit with a fingerprint-map
                // hash index (row -> prior-row-with-same-fingerprints) if per-tenant row volume grows
                // enough to make this call latency-visible.
                ordered.mapIndexedNotNull { index, row ->
                    if (row.ccState != ConfigurationCacheState.MISS_STORED) return@mapIndexedNotNull null
                    // The earliest strictly-earlier CC-requesting build in this stream with an identical
                    // fingerprint map (ordered ascending, so firstOrNull is deterministically the earliest).
                    val prior = ordered.subList(0, index)
                        .firstOrNull { it.requestedCc() && it.fingerprints == row.fingerprints }
                        ?: return@mapIndexedNotNull null
                    CcFlipFlopFinding(
                        buildId = row.buildId,
                        startedAt = row.startedAt,
                        priorBuildId = prior.buildId,
                        note = "Configuration cache re-stored (MISS_STORED) despite build inputs identical to an " +
                            "earlier build on this machine — an untracked input is invalidating the entry; " +
                            "investigate what changed between the two builds.",
                    )
                }
            }
            .sortedWith(compareBy<CcFlipFlopFinding> { it.startedAt }.thenBy { it.buildId })
            .take(MAX_FINDINGS)
}
