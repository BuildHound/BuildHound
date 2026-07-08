package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for [CohortComparator] (plan 057): median-delta / %change / robust-z, the
 * reference-cohort selection, the insufficient-data and zero-MAD guard rails, and the
 * single-cohort/unknown-tag-key degenerate cases. No I/O — mirrors [RegressionEngineTest]'s style.
 */
class CohortComparatorTest {

    private fun raw(value: String, durationsMs: List<Long>) = TagCohortRaw(value, points = emptyList(), durationsMs = durationsMs)

    @Test
    fun `median delta, pct change, and robust-z compute against the largest (reference) cohort`() {
        // Reference "false": median 100, MAD 5 (deviations 20,5,0,5,40 -> sorted 0,5,5,20,40 -> median 5).
        val reference = raw("false", listOf(80, 95, 100, 105, 140))
        // "true" is clearly faster: median 60 -> z = 0.6745 * (60-100)/5 = -5.396, |z| >= WARN_Z.
        val faster = raw("true", listOf(50, 55, 60, 65, 70))

        val result = CohortComparator.compare("r8", listOf(reference, faster))

        assertEquals("false", result.delta?.referenceValue)
        val row = result.delta!!.comparisons.single()
        assertEquals("true", row.value)
        assertEquals(-40L, row.medianDeltaMs)
        assertEquals(-0.4, row.pctChange!!, 1e-9)
        assertEquals(-5.396, row.robustZ!!, 1e-9)
        assertEquals("DISTINGUISHABLE", row.status)
    }

    @Test
    fun `the reference cohort is the one with the most (finished) builds`() {
        val small = raw("a", listOf(10, 20, 30))
        val large = raw("b", listOf(10, 20, 30, 40, 50, 60, 70))
        val medium = raw("c", listOf(10, 20, 30, 40, 50))

        val result = CohortComparator.compare("k", listOf(small, large, medium))

        assertEquals("b", result.delta?.referenceValue, "the cohort with the most samples must be the reference")
        assertEquals(setOf("a", "c"), result.delta!!.comparisons.map { it.value }.toSet())
    }

    @Test
    fun `a cohort under MIN_BASELINE builds is INSUFFICIENT_DATA, never a claimed delta`() {
        val reference = raw("false", listOf(80, 95, 100, 105, 140))
        val tooFew = raw("true", listOf(50, 60)) // 2 < RegressionEngine.MIN_BASELINE (3)

        val result = CohortComparator.compare("r8", listOf(reference, tooFew))

        val row = result.delta!!.comparisons.single()
        assertEquals("INSUFFICIENT_DATA", row.status)
        assertNull(row.robustZ, "insufficient data must never carry a claimed z-score")
    }

    @Test
    fun `a zero-MAD reference never crashes and falls back to INDISTINGUISHABLE`() {
        // Every reference build takes exactly the same duration -> MAD is degenerate (zero).
        val reference = raw("false", listOf(100, 100, 100, 100))
        val other = raw("true", listOf(50, 55, 60))

        val result = CohortComparator.compare("r8", listOf(reference, other))

        val row = result.delta!!.comparisons.single()
        assertNull(row.robustZ, "a zero MAD must yield no z, not a divide-by-zero")
        assertEquals("INDISTINGUISHABLE", row.status)
        assertEquals(-45L, row.medianDeltaMs, "the raw delta is still reported even without a z-score")
    }

    @Test
    fun `a single-cohort tag has no comparison`() {
        val only = raw("false", listOf(80, 95, 100, 105, 140))

        val result = CohortComparator.compare("r8", listOf(only))

        assertEquals(1, result.cohorts.size)
        assertEquals("false", result.delta?.referenceValue)
        assertTrue(result.delta!!.comparisons.isEmpty(), "a single cohort has nothing to compare against")
    }

    @Test
    fun `an unknown tag key (no raw cohorts) is an empty comparison, not a crash`() {
        val result = CohortComparator.compare("nonexistent", emptyList())

        assertTrue(result.cohorts.isEmpty())
        assertNull(result.delta)
    }

    @Test
    fun `cohorts are capped to the top 6 by sample count for a readable legend`() {
        val raws = (1..8).map { i -> raw("v$i", (1..i).map { it.toLong() * 10 }) }

        val result = CohortComparator.compare("k", raws)

        assertEquals(CohortComparator.MAX_COHORTS, result.cohorts.size)
        // The largest 6 by sample count survive: v8 (8 samples) down through v3 (3 samples).
        assertEquals(setOf("v8", "v7", "v6", "v5", "v4", "v3"), result.cohorts.map { it.value }.toSet())
    }

    @Test
    fun `direction is duration - a slower cohort gets a positive delta (higher = worse for coloring)`() {
        val reference = raw("false", listOf(80, 95, 100, 105, 140)) // median 100
        val slower = raw("true", listOf(130, 140, 150, 160, 170)) // median 150, clearly slower

        val result = CohortComparator.compare("r8", listOf(reference, slower))

        val row = result.delta!!.comparisons.single()
        assertTrue(row.medianDeltaMs > 0, "a slower cohort must carry a positive delta so the UI can color it as worse")
        assertTrue(row.pctChange!! > 0)
    }

    @Test
    fun `median duration and sample count are reported per cohort regardless of comparison status`() {
        val reference = raw("false", listOf(80, 95, 100, 105, 140))
        val other = raw("true", listOf(50, 55, 60))

        val result = CohortComparator.compare("r8", listOf(reference, other))

        val refSeries = result.cohorts.single { it.value == "false" }
        assertEquals(5, refSeries.sampleCount)
        assertEquals(100L, refSeries.medianDurationMs)
        val otherSeries = result.cohorts.single { it.value == "true" }
        assertEquals(3, otherSeries.sampleCount)
        assertEquals(55L, otherSeries.medianDurationMs)
    }
}
