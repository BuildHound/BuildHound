package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DerivedMetricsCalculatorTest {

    private fun task(
        outcome: TaskOutcome,
        startMs: Long = 0,
        durationMs: Long = 1_000,
        cacheable: Boolean? = null,
    ) = TaskExecution(path = ":t", startMs = startMs, durationMs = durationMs, outcome = outcome, cacheable = cacheable)

    @Test
    fun empty_build_yields_no_metrics() {
        assertNull(DerivedMetricsCalculator.compute(emptyList(), cores = 8))
    }

    @Test
    fun hit_rate_is_over_cacheable_only_denominator() {
        // Non-cacheable EXECUTED work must not dilute the denominator (plan 016): only the
        // three cacheable tasks are cache-relevant — 2 avoided of 3 → 2/3.
        val tasks = listOf(
            task(TaskOutcome.FROM_CACHE, cacheable = true),
            task(TaskOutcome.UP_TO_DATE, cacheable = true),
            task(TaskOutcome.EXECUTED, cacheable = true),
            // Non-cacheable work is excluded entirely, whatever its outcome.
            task(TaskOutcome.EXECUTED, cacheable = false),
            task(TaskOutcome.EXECUTED, cacheable = false),
            // Skipped/no-source/failed are never part of the ratio.
            task(TaskOutcome.SKIPPED, cacheable = true),
            task(TaskOutcome.NO_SOURCE, cacheable = true),
            task(TaskOutcome.FAILED, cacheable = true),
        )

        assertEquals(2.0 / 3.0, DerivedMetricsCalculator.cacheableHitRate(tasks))
    }

    @Test
    fun from_cache_proves_cacheability_even_when_the_static_flag_missed_it() {
        // A cacheIf {}-only task reads as cacheable=false/null statically, but a FROM_CACHE
        // outcome proves it was cacheable — it stays cache-relevant. One other task carries
        // a flag so the payload is not treated as legacy/degraded.
        val nullFlag = listOf(
            task(TaskOutcome.FROM_CACHE, cacheable = null),
            task(TaskOutcome.EXECUTED, cacheable = true),
        )
        assertEquals(0.5, DerivedMetricsCalculator.cacheableHitRate(nullFlag))

        val falseFlag = listOf(
            task(TaskOutcome.FROM_CACHE, cacheable = false),
            task(TaskOutcome.EXECUTED, cacheable = true),
        )
        assertEquals(0.5, DerivedMetricsCalculator.cacheableHitRate(falseFlag))
    }

    @Test
    fun hit_rate_is_null_when_no_cacheable_flags_exist() {
        // Isolated-projects degradation / legacy pre-016 payloads: every flag null → gap,
        // not the old mixed-denominator number.
        assertNull(
            DerivedMetricsCalculator.cacheableHitRate(
                listOf(task(TaskOutcome.FROM_CACHE), task(TaskOutcome.EXECUTED), task(TaskOutcome.UP_TO_DATE)),
            ),
        )
    }

    @Test
    fun hit_rate_is_null_when_flags_present_but_nothing_is_cache_relevant() {
        // Flags exist (so not legacy) but every cacheable task was skipped and every run
        // task was non-cacheable → empty denominator → null.
        assertNull(
            DerivedMetricsCalculator.cacheableHitRate(
                listOf(task(TaskOutcome.EXECUTED, cacheable = false), task(TaskOutcome.SKIPPED, cacheable = true)),
            ),
        )
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
    fun compute_fills_the_honest_fields_and_passes_configuration_ms_through() {
        val metrics = DerivedMetricsCalculator.compute(
            listOf(task(TaskOutcome.FROM_CACHE, cacheable = true), task(TaskOutcome.EXECUTED, cacheable = true)),
            cores = 2,
            configurationMs = 900,
        )!!

        assertEquals(0.5, metrics.cacheableHitRate)
        assertNull(metrics.avoidedMs)
        assertNull(metrics.criticalPathMs)
        assertEquals(900, metrics.configurationMs)
    }

    @Test
    fun compute_leaves_configuration_ms_null_by_default() {
        val metrics = DerivedMetricsCalculator.compute(listOf(task(TaskOutcome.EXECUTED, cacheable = true)), cores = 2)!!
        assertNull(metrics.configurationMs)
    }
}
