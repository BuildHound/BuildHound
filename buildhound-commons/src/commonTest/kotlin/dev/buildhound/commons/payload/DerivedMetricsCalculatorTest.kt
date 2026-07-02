package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DerivedMetricsCalculatorTest {

    private fun task(outcome: TaskOutcome, startMs: Long = 0, durationMs: Long = 1_000) =
        TaskExecution(path = ":t", startMs = startMs, durationMs = durationMs, outcome = outcome)

    @Test
    fun empty_build_yields_no_metrics() {
        assertNull(DerivedMetricsCalculator.compute(emptyList(), cores = 8))
    }

    @Test
    fun hit_rate_counts_avoided_over_avoided_plus_executed() {
        val tasks = listOf(
            task(TaskOutcome.FROM_CACHE),
            task(TaskOutcome.UP_TO_DATE),
            task(TaskOutcome.EXECUTED),
            task(TaskOutcome.EXECUTED),
            // Skipped/no-source/failed tasks are not part of the ratio.
            task(TaskOutcome.SKIPPED),
            task(TaskOutcome.NO_SOURCE),
            task(TaskOutcome.FAILED),
        )

        assertEquals(0.5, DerivedMetricsCalculator.cacheableHitRate(tasks))
    }

    @Test
    fun hit_rate_is_null_when_nothing_ran_or_was_avoided() {
        assertNull(DerivedMetricsCalculator.cacheableHitRate(listOf(task(TaskOutcome.SKIPPED))))
    }

    @Test
    fun utilization_is_busy_time_over_wall_times_cores() {
        // Two 1s tasks in parallel over a 1s wall on 2 cores → fully utilized.
        val tasks = listOf(
            task(TaskOutcome.EXECUTED, startMs = 0, durationMs = 1_000),
            task(TaskOutcome.EXECUTED, startMs = 0, durationMs = 1_000),
        )

        assertEquals(1.0, DerivedMetricsCalculator.parallelUtilization(tasks, cores = 2))
        assertEquals(0.5, DerivedMetricsCalculator.parallelUtilization(tasks, cores = 4))
    }

    @Test
    fun utilization_is_clamped_and_guards_degenerate_inputs() {
        val overlapping = listOf(
            task(TaskOutcome.EXECUTED, startMs = 0, durationMs = 3_000),
            task(TaskOutcome.EXECUTED, startMs = 0, durationMs = 3_000),
        )
        // 6s busy over 3s wall on 1 core would be 2.0 → clamped.
        assertEquals(1.0, DerivedMetricsCalculator.parallelUtilization(overlapping, cores = 1))

        assertNull(DerivedMetricsCalculator.parallelUtilization(overlapping, cores = null))
        assertNull(DerivedMetricsCalculator.parallelUtilization(overlapping, cores = 0))
        // Zero wall clock (all timestamps identical, zero duration).
        assertNull(DerivedMetricsCalculator.parallelUtilization(listOf(task(TaskOutcome.EXECUTED, durationMs = 0)), cores = 2))
    }

    @Test
    fun compute_fills_only_the_honest_fields() {
        val metrics = DerivedMetricsCalculator.compute(
            listOf(task(TaskOutcome.FROM_CACHE), task(TaskOutcome.EXECUTED)),
            cores = 2,
        )!!

        assertEquals(0.5, metrics.cacheableHitRate)
        assertNull(metrics.avoidedMs)
        assertNull(metrics.criticalPathMs)
        assertNull(metrics.configurationMs)
    }
}
