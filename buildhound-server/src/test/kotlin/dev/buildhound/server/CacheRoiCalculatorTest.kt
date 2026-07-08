package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure remote-cache ROI aggregation (plan 067, research F17): denominator, two-tier availability, CI-reuse gating. */
class CacheRoiCalculatorTest {

    private fun origin(mode: String, o: CacheRoiOrigin) = CacheRoiRow(mode, o)

    @Test
    fun `an empty window is an honest empty state, not a fabricated rate`() {
        val roi = CacheRoiCalculator.compute(emptyList(), emptyList())
        assertEquals(false, roi.remoteHitRateAvailable)
        assertEquals(0, roi.buildsWithConfig)
        assertEquals(0.0, roi.remoteConfiguredShare)
        assertTrue(roi.perMode.isEmpty())
        assertNull(roi.ciReuseCandidate)
    }

    @Test
    fun `config snapshot only (opt-in module off) degrades to the config summary with no remote-hit rate`() {
        // Four builds carry the plan-067 snapshot; three configured an enabled remote — but no origin
        // data at all, so there is genuinely no remote-hit rate to report (never synthesized).
        val configRows = listOf(
            CacheConfigRow("CI", remoteEnabled = true),
            CacheConfigRow("CI", remoteEnabled = true),
            CacheConfigRow("CI", remoteEnabled = false),
            CacheConfigRow("LOCAL", remoteEnabled = true),
        )
        val roi = CacheRoiCalculator.compute(originRows = emptyList(), configRows = configRows)
        assertEquals(false, roi.remoteHitRateAvailable, "no origin data → the remote-hit rate is genuinely unavailable")
        assertEquals(4, roi.buildsWithConfig)
        assertEquals(0.75, roi.remoteConfiguredShare, "3 of 4 configured an enabled remote")
        assertTrue(roi.perMode.isEmpty())
        assertNull(roi.ciReuseCandidate)
    }

    @Test
    fun `STORED and OTHER are excluded from the rate denominator`() {
        // CI: REMOTE_HIT + LOCAL_HIT + MISS count (denominator 3); STORED (cold first store) + OTHER do not.
        val rows = listOf(
            origin("CI", CacheRoiOrigin.REMOTE_HIT),
            origin("CI", CacheRoiOrigin.LOCAL_HIT),
            origin("CI", CacheRoiOrigin.MISS),
            origin("CI", CacheRoiOrigin.STORED),
            origin("CI", CacheRoiOrigin.OTHER),
        )
        val roi = CacheRoiCalculator.compute(rows, configRows = emptyList())
        assertEquals(true, roi.remoteHitRateAvailable)
        val ci = roi.perMode.single { it.mode == "CI" }
        assertEquals(3, ci.consideredExecutions, "STORED + OTHER excluded from the denominator")
        assertEquals(1, ci.remoteHits)
        assertEquals(1, ci.localHits)
        assertEquals(roundTo6(1.0 / 3.0), ci.remoteHitRate)
        assertEquals(roundTo6(1.0 / 3.0), ci.localHitRate)
    }

    @Test
    fun `per-mode rows are grouped and sorted deterministically`() {
        val rows = listOf(
            origin("LOCAL", CacheRoiOrigin.REMOTE_HIT),
            origin("CI", CacheRoiOrigin.MISS),
            origin("CI", CacheRoiOrigin.REMOTE_HIT),
        )
        val roi = CacheRoiCalculator.compute(rows, configRows = emptyList())
        assertEquals(listOf("CI", "LOCAL"), roi.perMode.map { it.mode }, "modes sorted for determinism")
        assertEquals(0.5, roi.perMode.single { it.mode == "CI" }.remoteHitRate)
        assertEquals(1.0, roi.perMode.single { it.mode == "LOCAL" }.remoteHitRate)
    }

    @Test
    fun `a near-zero CI reuse candidate fires when remote is configured and there are enough executions`() {
        val misses = List(60) { origin("CI", CacheRoiOrigin.MISS) }
        val rows = misses + origin("CI", CacheRoiOrigin.REMOTE_HIT) // 61 considered, 1 remote hit ≈ 1.6%
        val configRows = listOf(CacheConfigRow("CI", remoteEnabled = true))

        val roi = CacheRoiCalculator.compute(rows, configRows)
        val candidate = roi.ciReuseCandidate ?: error("expected a near-zero CI reuse candidate")
        assertEquals("CI", candidate.mode)
        assertEquals(61, candidate.consideredExecutions)
        assertTrue(candidate.remoteHitRate <= CacheRoiCalculator.NEAR_ZERO_REUSE_RATE)
        // Never a verdict: the note must read as an investigation prompt acknowledging the cold-CI caveat.
        assertTrue(candidate.note.contains("investigate", ignoreCase = true), candidate.note)
        assertTrue(candidate.note.contains("legitimately", ignoreCase = true), candidate.note)
    }

    @Test
    fun `the candidate never fires on an unconfigured fleet, even at zero reuse`() {
        val rows = List(60) { origin("CI", CacheRoiOrigin.MISS) } // 0% reuse, plenty of samples
        // No config row with remoteEnabled → the plan's "gated on remoteEnabled=true" bars the candidate.
        val roi = CacheRoiCalculator.compute(rows, configRows = listOf(CacheConfigRow("CI", remoteEnabled = false)))
        assertNull(roi.ciReuseCandidate, "a fleet with no configured remote is not a misconfiguration candidate")
        // The rate itself is still reported (availability is independent of the candidate gate).
        assertEquals(0.0, roi.perMode.single { it.mode == "CI" }.remoteHitRate)
    }

    @Test
    fun `the candidate never fires below the minimum sample size`() {
        val rows = List(10) { origin("CI", CacheRoiOrigin.MISS) } // 0% reuse but only 10 executions
        val roi = CacheRoiCalculator.compute(rows, configRows = listOf(CacheConfigRow("CI", remoteEnabled = true)))
        assertNull(roi.ciReuseCandidate, "too few CI executions to trust a near-zero claim")
    }

    @Test
    fun `the candidate never fires when CI reuse is healthy`() {
        val rows = List(30) { origin("CI", CacheRoiOrigin.REMOTE_HIT) } + List(30) { origin("CI", CacheRoiOrigin.MISS) }
        val roi = CacheRoiCalculator.compute(rows, configRows = listOf(CacheConfigRow("CI", remoteEnabled = true)))
        assertEquals(0.5, roi.perMode.single { it.mode == "CI" }.remoteHitRate)
        assertNull(roi.ciReuseCandidate, "a 50% remote-hit rate is not near-zero")
    }

    private fun roundTo6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
