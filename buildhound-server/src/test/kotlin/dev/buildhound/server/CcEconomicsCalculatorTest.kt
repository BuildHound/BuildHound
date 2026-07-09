package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.ConfigurationCacheState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure coverage for the plan-064 CC-reuse classification + percentiles. The `ciReuseClass` cascade is a
 * load-bearing advisory gate, so every threshold boundary ([CcEconomicsCalculator.MIN_REQUESTED_FOR_CLASS]
 * /[CI_DOMINANT_SHARE]/[HEALTHY_REUSE]) is pinned here (the plan's load-bearing-gate discipline).
 */
class CcEconomicsCalculatorTest {

    private fun row(
        buildId: String = "b",
        startedAt: Long = 0,
        mode: BuildMode = BuildMode.CI,
        ccState: ConfigurationCacheState? = null,
        configurationMs: Long? = null,
        ccLoadMs: Long? = null,
        ccEntrySizeBytes: Long? = null,
    ) = CcBuildRow(
        buildId = buildId, startedAt = startedAt, mode = mode, ccState = ccState,
        configurationMs = configurationMs, ccLoadMs = ccLoadMs, ccEntrySizeBytes = ccEntrySizeBytes,
        userId = null, hostnameHash = null, fingerprints = emptyMap(),
    )

    private fun requesting(n: Int, hits: Int, mode: BuildMode): List<CcBuildRow> =
        (0 until n).map { i ->
            row(
                buildId = "b$i",
                mode = mode,
                ccState = if (i < hits) ConfigurationCacheState.HIT else ConfigurationCacheState.MISS_STORED,
            )
        }

    @Test
    fun `an empty window and an all-null-cc window are INSUFFICIENT_DATA`() {
        assertEquals(CiReuseClass.INSUFFICIENT_DATA, CcEconomicsCalculator.compute(emptyList()).ciReuseClass)
        val report = CcEconomicsCalculator.compute(List(10) { row(ccState = null) })
        assertEquals(CiReuseClass.INSUFFICIENT_DATA, report.ciReuseClass)
        assertEquals(0, report.ccObservedBuilds)
        assertEquals(0, report.ccRequestedBuilds)
        assertNull(report.reuseRate)
    }

    @Test
    fun `a CC-observed but never-requested window is DISABLED`() {
        // DISABLED + INCOMPATIBLE are observed-but-not-requested (like each other); reuseRate is null.
        val rows = List(6) { row(buildId = "d$it", ccState = ConfigurationCacheState.DISABLED) } +
            row(buildId = "inc", ccState = ConfigurationCacheState.INCOMPATIBLE)
        val report = CcEconomicsCalculator.compute(rows)
        assertEquals(CiReuseClass.DISABLED, report.ciReuseClass)
        assertEquals(7, report.ccObservedBuilds)
        assertEquals(0, report.ccRequestedBuilds)
        assertNull(report.reuseRate)
    }

    @Test
    fun `below the minimum requesting builds is INSUFFICIENT_DATA even with perfect reuse`() {
        // 4 requesting builds (< MIN_REQUESTED_FOR_CLASS = 5), all HIT — still too few to judge.
        assertEquals(4, CcEconomicsCalculator.MIN_REQUESTED_FOR_CLASS - 1)
        val report = CcEconomicsCalculator.compute(requesting(n = 4, hits = 4, mode = BuildMode.LOCAL))
        assertEquals(CiReuseClass.INSUFFICIENT_DATA, report.ciReuseClass)
        assertEquals(1.0, report.reuseRate, "the rate is still reported, only the verdict is withheld")
    }

    @Test
    fun `reuse at or above the healthy threshold is REUSE_HEALTHY on any window`() {
        // Exactly at the boundary (3/6 = 0.5) → healthy (>= is inclusive), even CI-dominant.
        val boundary = CcEconomicsCalculator.compute(requesting(n = 6, hits = 3, mode = BuildMode.CI))
        assertEquals(0.5, boundary.reuseRate)
        assertEquals(CiReuseClass.REUSE_HEALTHY, boundary.ciReuseClass, "HEALTHY wins over EPHEMERAL when reuse is healthy")
    }

    @Test
    fun `a CI-dominant below-healthy window is EPHEMERAL_CI_EXPECTED_ZERO (annotation, not alarm)`() {
        // Zero reuse on all-CI — the F14 archetype: expected, never a "turn it off".
        assertEquals(
            CiReuseClass.EPHEMERAL_CI_EXPECTED_ZERO,
            CcEconomicsCalculator.compute(requesting(n = 5, hits = 0, mode = BuildMode.CI)).ciReuseClass,
        )
        // ciShare exactly at the 0.5 boundary with below-healthy reuse → still expected (>= inclusive):
        // 6 requesting (3 CI, 3 LOCAL), 1 HIT → reuse 0.166, ciShare 0.5.
        val boundaryRows = (0 until 3).map { row(buildId = "ci$it", mode = BuildMode.CI, ccState = ConfigurationCacheState.MISS_STORED) } +
            row(buildId = "l0", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.HIT) +
            (1 until 3).map { row(buildId = "l$it", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.MISS_STORED) }
        val boundary = CcEconomicsCalculator.compute(boundaryRows)
        assertEquals(CiReuseClass.EPHEMERAL_CI_EXPECTED_ZERO, boundary.ciReuseClass)
    }

    @Test
    fun `a not-CI-dominant below-healthy window is REUSE_DEGRADED`() {
        // All-LOCAL, 1/5 reuse — reuse should be high on dev machines, so this is worth a look.
        assertEquals(
            CiReuseClass.REUSE_DEGRADED,
            CcEconomicsCalculator.compute(requesting(n = 5, hits = 1, mode = BuildMode.LOCAL)).ciReuseClass,
        )
        // Just below the CI-dominant boundary (2 CI / 5 = 0.4) with below-healthy reuse → DEGRADED.
        val rows = (0 until 2).map { row(buildId = "ci$it", mode = BuildMode.CI, ccState = ConfigurationCacheState.MISS_STORED) } +
            row(buildId = "l0", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.HIT) +
            (1 until 3).map { row(buildId = "l$it", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.MISS_STORED) }
        assertEquals(CiReuseClass.REUSE_DEGRADED, CcEconomicsCalculator.compute(rows).ciReuseClass)
    }

    @Test
    fun `p50s fold over the right subsets and are null when a subset is empty`() {
        val rows = listOf(
            row(buildId = "h1", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.HIT, ccLoadMs = 10, ccEntrySizeBytes = 1000),
            row(buildId = "h2", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.HIT, ccLoadMs = 30, ccEntrySizeBytes = 3000),
            row(buildId = "m1", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.MISS_STORED, configurationMs = 800, ccEntrySizeBytes = 2000),
            row(buildId = "m2", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.MISS_STORED, configurationMs = 400, ccEntrySizeBytes = 4000),
            // A DISABLED build's configurationMs must NOT enter the store-cost p50 (only MISS_STORED does).
            row(buildId = "d1", mode = BuildMode.LOCAL, ccState = ConfigurationCacheState.DISABLED, configurationMs = 9999),
        )
        val report = CcEconomicsCalculator.compute(rows)
        // Nearest-rank p50 of [400,800] → rank ceil(0.5*2)=1 → 400 (store cost over MISS_STORED only).
        assertEquals(400, report.storeCostMsP50)
        // p50 of HIT ccLoadMs [10,30] → 10.
        assertEquals(10, report.loadMsP50)
        // entry size over the 4 requesting builds [1000,2000,3000,4000] → rank ceil(0.5*4)=2 → 2000.
        assertEquals(2000, report.entrySizeBytesP50)

        // No HIT carrying ccLoadMs → loadMsP50 null even though other fields exist (the always-null-slot reality).
        val noLoad = CcEconomicsCalculator.compute(requesting(n = 5, hits = 0, mode = BuildMode.CI))
        assertNull(noLoad.loadMsP50)
        assertNull(noLoad.storeCostMsP50, "no configurationMs captured → store-cost p50 null")
    }

    @Test
    fun `counters and rounded reuse rate are exact`() {
        // 6 requesting: 2 HIT, 4 MISS_STORED, plus 1 DISABLED → observed 7, requested 6, reuse 2/6 rounded.
        val rows = requesting(n = 6, hits = 2, mode = BuildMode.LOCAL) +
            row(buildId = "d", ccState = ConfigurationCacheState.DISABLED)
        val report = CcEconomicsCalculator.compute(rows)
        assertEquals(7, report.ccObservedBuilds)
        assertEquals(6, report.ccRequestedBuilds)
        assertEquals(2, report.ccHitBuilds)
        assertEquals(4, report.ccMissStoredBuilds)
        assertEquals(0.333333, report.reuseRate)
    }
}
