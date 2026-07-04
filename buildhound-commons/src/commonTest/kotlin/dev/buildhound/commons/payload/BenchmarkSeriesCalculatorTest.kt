package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BenchmarkSeriesCalculatorTest {

    @Test
    fun `empty series is null`() {
        assertNull(BenchmarkSeriesCalculator.summarize(emptyList()))
    }

    @Test
    fun `single element is its own p50, p90, and min`() {
        val s = BenchmarkSeriesCalculator.summarize(listOf(5_000)) ?: error("non-null")
        assertEquals(5_000, s.p50)
        assertEquals(5_000, s.p90)
        assertEquals(5_000, s.min)
        assertEquals(1, s.count)
    }

    @Test
    fun `nearest-rank percentiles over an odd count, unsorted input`() {
        // sorted: 1,2,3 → p50 rank ceil(1.5)=2 → 2; p90 rank ceil(2.7)=3 → 3; min 1.
        val s = BenchmarkSeriesCalculator.summarize(listOf(3_000, 1_000, 2_000)) ?: error("non-null")
        assertEquals(2_000, s.p50)
        assertEquals(3_000, s.p90)
        assertEquals(1_000, s.min)
        assertEquals(3, s.count)
    }

    @Test
    fun `nearest-rank percentiles over an even count`() {
        // sorted 1..4 → p50 rank ceil(2.0)=2 → 2; p90 rank ceil(3.6)=4 → 4; min 1.
        val s = BenchmarkSeriesCalculator.summarize(listOf(1_000, 2_000, 3_000, 4_000)) ?: error("non-null")
        assertEquals(2_000, s.p50)
        assertEquals(4_000, s.p90)
        assertEquals(1_000, s.min)
    }

    @Test
    fun `p90 lands on the ninth of ten`() {
        val s = BenchmarkSeriesCalculator.summarize((1..10).map { it * 1_000L }) ?: error("non-null")
        assertEquals(5_000, s.p50) // rank ceil(5.0)=5 → 5000
        assertEquals(9_000, s.p90) // rank ceil(9.0)=9 → 9000
        assertEquals(1_000, s.min)
        assertEquals(10, s.count)
    }
}
