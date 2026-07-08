package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure percentile/rate/count math for the Prometheus egress snapshot (plan 070). */
class MetricsSnapshotCalculatorTest {

    private fun kpi(durationMs: Long, outcome: String = "SUCCESS", hitRate: Double? = null) =
        BuildKpiRow(outcome = outcome, durationMs = durationMs, hitRate = hitRate)

    @Test
    fun `an empty window omits every KPI, never zeros`() {
        val s = MetricsSnapshotCalculator.compute(windowDays = 30, builds = emptyList(), flakyRecordCount = 0, avoidedMsValues = emptyList())
        assertEquals(30, s.windowDays)
        assertNull(s.p50DurationMs)
        assertNull(s.p95DurationMs)
        assertNull(s.cacheHitRate)
        assertNull(s.successRate)
        assertEquals(emptyMap(), s.buildCountsByOutcome)
        assertNull(s.flakyTestCount)
        assertNull(s.avoidedMs)
    }

    @Test
    fun `p50 and p95 are nearest-rank over 10 builds`() {
        val builds = (1..10).map { kpi(it * 1_000L) }
        val s = MetricsSnapshotCalculator.compute(30, builds, 0, emptyList())
        // Nearest-rank: rank = ceil(q*n). p50 -> ceil(5.0)=5 -> sorted[4] = 5000. p95 -> ceil(9.5)=10 -> sorted[9] = 10000.
        assertEquals(5_000, s.p50DurationMs)
        assertEquals(10_000, s.p95DurationMs)
    }

    @Test
    fun `p50 and p95 agree on a single-element window`() {
        val s = MetricsSnapshotCalculator.compute(30, listOf(kpi(4_200)), 0, emptyList())
        assertEquals(4_200, s.p50DurationMs)
        assertEquals(4_200, s.p95DurationMs)
    }

    @Test
    fun `success rate and windowed build counts by outcome`() {
        val builds = listOf(kpi(1000, "SUCCESS"), kpi(1000, "SUCCESS"), kpi(1000, "FAILED"), kpi(1000, "SUCCESS"))
        val s = MetricsSnapshotCalculator.compute(30, builds, 0, emptyList())
        assertEquals(0.75, s.successRate)
        assertEquals(mapOf("SUCCESS" to 3, "FAILED" to 1), s.buildCountsByOutcome)
    }

    @Test
    fun `success rate is a real zero when every build failed, not omitted`() {
        val s = MetricsSnapshotCalculator.compute(30, listOf(kpi(1000, "FAILED"), kpi(1000, "FAILED")), 0, emptyList())
        assertEquals(0.0, s.successRate)
    }

    @Test
    fun `cache hit rate averages only builds that carry it, omitted when none do`() {
        val withRates = listOf(kpi(1000, hitRate = 0.2), kpi(1000, hitRate = 0.6), kpi(1000, hitRate = null))
        val s = MetricsSnapshotCalculator.compute(30, withRates, 0, emptyList())
        assertEquals(0.4, s.cacheHitRate)

        val noRates = listOf(kpi(1000, hitRate = null))
        assertNull(MetricsSnapshotCalculator.compute(30, noRates, 0, emptyList()).cacheHitRate)
    }

    @Test
    fun `flaky count is passed through when positive, omitted when the detector found nothing`() {
        val builds = listOf(kpi(1000))
        assertEquals(3, MetricsSnapshotCalculator.compute(30, builds, 3, emptyList()).flakyTestCount)
        assertNull(MetricsSnapshotCalculator.compute(30, builds, 0, emptyList()).flakyTestCount)
    }

    @Test
    fun `avoided time sums only the present values, omitted when none are present`() {
        val builds = listOf(kpi(1000))
        assertEquals(3_500L, MetricsSnapshotCalculator.compute(30, builds, 0, listOf(1_000, 2_500)).avoidedMs)
        assertNull(MetricsSnapshotCalculator.compute(30, builds, 0, emptyList()).avoidedMs)
    }
}
