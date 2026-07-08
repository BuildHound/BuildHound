package dev.buildhound.server

import kotlinx.serialization.Serializable

/**
 * Server-local mirror of plan 038's `CacheOrigin` for the remote-cache ROI rate (plan 067, research
 * F17). A distinct enum from [RelocatabilityOrigin] because the ROI rate needs the [LOCAL_HIT] case that
 * detector cares nothing about — the server keeps **no** dependency on `buildhound-internal-adapters`
 * (the plan-039 decoupling invariant; `extensions` is an opaque `JsonElement`), so the wire origin string
 * is parsed to this enum at the [cacheRoiRowsOf] boundary and every downstream comparison is an
 * exhaustive `when`, never a raw string literal. [OTHER] absorbs any origin name the server doesn't
 * recognize (a future or malformed wire value), degrading to "neither a hit nor a countable miss".
 */
enum class CacheRoiOrigin { LOCAL_HIT, REMOTE_HIT, STORED, MISS, OTHER }

/** One task's origin flattened for the ROI rate, tagged with its build [mode] (`CI`/`LOCAL`). */
data class CacheRoiRow(val mode: String, val origin: CacheRoiOrigin)

/**
 * One build's committed build-cache config fact (plan 067) for the config-snapshot summary: [mode] and
 * whether a **remote** backend was configured-and-enabled (`environment.buildCache.remoteEnabled == true`).
 * Emitted only for builds that actually carry the snapshot (post-067 plugin) — see [cacheConfigRowOf].
 */
data class CacheConfigRow(val mode: String, val remoteEnabled: Boolean)

/** Per-build-mode remote/local cache-hit rate (plan 067); the rate denominator excludes `STORED` — see [CacheRoiCalculator]. */
@Serializable
data class CacheRoiModeRow(
    val mode: String,
    val remoteHitRate: Double,
    val localHitRate: Double,
    /** Task executions counted in the rate denominator: `LOCAL_HIT` + `REMOTE_HIT` + `MISS` (not `STORED`). */
    val consideredExecutions: Long,
    val remoteHits: Long,
    val localHits: Long,
)

/**
 * A ranked near-zero-CI-reuse **candidate**, never a confirmed verdict (plan 067, mirroring
 * [NonRelocatableCandidate]/`WarningRow`): cold / first-build CI legitimately shows near-zero remote
 * reuse, so [note] always reads as an investigation prompt. Gated on a configured remote — it never fires
 * on a fleet that never configured a remote cache at all (that is the config-snapshot summary's job).
 */
@Serializable
data class CiReuseCandidate(
    val mode: String,
    val remoteHitRate: Double,
    val consideredExecutions: Long,
    val note: String,
)

/**
 * `GET /v1/rollups/cache-roi` response (plan 067, research F17). Two-tier by design: with the opt-in
 * internal-adapters `collectCacheOrigins` module ON, [remoteHitRateAvailable] is true and [perMode] +
 * [ciReuseCandidate] carry the directional remote-hit signal; with it OFF there is **no** remote-hit rate
 * at all (core `FROM_CACHE` is undifferentiated local/remote — never fabricate one from
 * `cacheableHitRate`), so [remoteHitRateAvailable] is false and the rollup degrades to the config-snapshot
 * summary ([buildsWithConfig] / [remoteConfiguredShare]) — "is a remote cache even configured".
 */
@Serializable
data class CacheRoiRollup(
    val remoteHitRateAvailable: Boolean,
    /** Window builds carrying the plan-067 `environment.buildCache` config snapshot at all. */
    val buildsWithConfig: Int,
    /** Share of [buildsWithConfig] whose remote backend was configured-and-enabled (0.0 when none/empty). */
    val remoteConfiguredShare: Double,
    val perMode: List<CacheRoiModeRow>,
    val ciReuseCandidate: CiReuseCandidate? = null,
)

/**
 * Pure remote-cache ROI aggregation (plan 067, research F17), sibling of [RelocatabilityDetector]/
 * `BottleneckCalculator` — no I/O, so both stores run it over the same windowed payloads and agree
 * byte-for-byte by construction (the plan-026/032/068 parity discipline).
 */
object CacheRoiCalculator {

    /**
     * The rate **denominator is `LOCAL_HIT + REMOTE_HIT + MISS`, excluding `STORED`** (plan 067): a cold
     * cacheable task on a first build emits `STORED` (executed-then-stored) per plan 038's
     * `OriginClassifier`, and a first store is not "missed reuse" — counting it would drag every fresh
     * pipeline's remote-hit rate toward zero and mint false near-zero-reuse candidates. `OTHER`/unknown
     * origins are excluded for the same "not a countable reuse opportunity" reason.
     */
    private fun CacheRoiOrigin.countsInDenominator(): Boolean = when (this) {
        CacheRoiOrigin.LOCAL_HIT, CacheRoiOrigin.REMOTE_HIT, CacheRoiOrigin.MISS -> true
        CacheRoiOrigin.STORED, CacheRoiOrigin.OTHER -> false
    }

    /** A CI remote-hit rate at or below this is "near-zero reuse" — an investigate-candidate, not a verdict. */
    const val NEAR_ZERO_REUSE_RATE: Double = 0.05

    /** Minimum considered CI task executions before a near-zero-reuse candidate is trustworthy (BottleneckCalculator.MIN_SAMPLES spirit). */
    const val MIN_CANDIDATE_EXECUTIONS: Long = 50

    fun compute(originRows: List<CacheRoiRow>, configRows: List<CacheConfigRow>): CacheRoiRollup {
        val buildsWithConfig = configRows.size
        val remoteConfiguredShare =
            if (buildsWithConfig == 0) 0.0 else roundTo6(configRows.count { it.remoteEnabled }.toDouble() / buildsWithConfig)

        // Availability: the opt-in module contributed origin data at all. With it off there are no origin
        // rows and the remote-hit rate is genuinely absent (never synthesized from cacheableHitRate).
        val remoteHitRateAvailable = originRows.isNotEmpty()

        val perMode = originRows
            .filter { it.origin.countsInDenominator() }
            .groupBy { it.mode }
            .map { (mode, rows) ->
                val considered = rows.size.toLong()
                val remoteHits = rows.count { it.origin == CacheRoiOrigin.REMOTE_HIT }.toLong()
                val localHits = rows.count { it.origin == CacheRoiOrigin.LOCAL_HIT }.toLong()
                CacheRoiModeRow(
                    mode = mode,
                    remoteHitRate = if (considered == 0L) 0.0 else roundTo6(remoteHits.toDouble() / considered),
                    localHitRate = if (considered == 0L) 0.0 else roundTo6(localHits.toDouble() / considered),
                    consideredExecutions = considered,
                    remoteHits = remoteHits,
                    localHits = localHits,
                )
            }
            .sortedBy { it.mode } // deterministic ordering

        return CacheRoiRollup(
            remoteHitRateAvailable = remoteHitRateAvailable,
            buildsWithConfig = buildsWithConfig,
            remoteConfiguredShare = remoteConfiguredShare,
            perMode = perMode,
            ciReuseCandidate = ciReuseCandidate(perMode, configRows),
        )
    }

    /**
     * The near-zero-CI-reuse candidate (plan 067). Fires only when: the CI mode has a rate at all
     * ([MIN_CANDIDATE_EXECUTIONS]+ considered executions), that rate is at/below [NEAR_ZERO_REUSE_RATE],
     * **and** a CI build in the window actually configured an enabled remote cache — the plan's "gated on
     * `remoteEnabled=true` in the snapshot to avoid firing on unconfigured fleets". Never a verdict: cold
     * or first-build CI legitimately shows near-zero reuse.
     */
    private fun ciReuseCandidate(perMode: List<CacheRoiModeRow>, configRows: List<CacheConfigRow>): CiReuseCandidate? {
        val ci = perMode.firstOrNull { it.mode == "CI" } ?: return null
        if (ci.consideredExecutions < MIN_CANDIDATE_EXECUTIONS) return null
        if (ci.remoteHitRate > NEAR_ZERO_REUSE_RATE) return null
        val ciRemoteConfigured = configRows.any { it.mode == "CI" && it.remoteEnabled }
        if (!ciRemoteConfigured) return null
        val pct = Math.round(ci.remoteHitRate * 100)
        return CiReuseCandidate(
            mode = "CI",
            remoteHitRate = ci.remoteHitRate,
            consideredExecutions = ci.consideredExecutions,
            note = "CI shows near-zero remote-cache reuse ($pct% over ${ci.consideredExecutions} cacheable task " +
                "executions) despite a configured remote cache — investigate cache reachability and key stability " +
                "(a cold or first-build CI window legitimately shows near-zero reuse).",
        )
    }

    private fun roundTo6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
