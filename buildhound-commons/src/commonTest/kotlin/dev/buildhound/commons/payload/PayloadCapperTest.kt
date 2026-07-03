package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PayloadCapperTest {

    private fun payload(
        tags: Map<String, String> = emptyMap(),
        values: Map<String, String> = emptyMap(),
        tasks: List<TaskExecution> = emptyList(),
        caps: CapsSummary? = null,
    ) = BuildPayload(
        buildId = "b", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.SUCCESS,
        tags = tags, values = values, tasks = tasks, caps = caps,
    )

    private fun task(
        path: String,
        outcome: TaskOutcome = TaskOutcome.EXECUTED,
        durationMs: Long = 100,
        reasons: List<String> = emptyList(),
        nonCacheableReason: String? = null,
    ) = TaskExecution(
        path = path, startMs = 0, durationMs = durationMs, outcome = outcome,
        executionReasons = reasons, nonCacheableReason = nonCacheableReason,
    )

    @Test
    fun excess_tags_are_dropped_in_map_order_and_counted() {
        val capped = PayloadCapper.cap(
            payload(tags = mapOf("a" to "1", "b" to "2", "c" to "3")),
            PayloadCaps(maxTags = 2),
        )
        assertEquals(mapOf("a" to "1", "b" to "2"), capped.tags)
        assertEquals(1, capped.caps?.droppedTags)
    }

    @Test
    fun over_long_value_is_truncated_and_counted() {
        val capped = PayloadCapper.cap(payload(tags = mapOf("k" to "abcdef")), PayloadCaps(maxValueChars = 3))
        assertEquals("abc", capped.tags["k"])
        assertEquals(1, capped.caps?.truncatedValues)
    }

    @Test
    fun over_long_key_drops_the_entry_rather_than_truncating() {
        val capped = PayloadCapper.cap(payload(tags = mapOf("ok" to "1", "toolong" to "2")), PayloadCaps(maxKeyChars = 2))
        assertEquals(mapOf("ok" to "1"), capped.tags)
        assertEquals(1, capped.caps?.droppedTags)
    }

    @Test
    fun values_map_is_capped_identically_to_tags() {
        val capped = PayloadCapper.cap(
            payload(values = mapOf("a" to "xxxx", "b" to "y")),
            PayloadCaps(maxValues = 1, maxValueChars = 2),
        )
        assertEquals(mapOf("a" to "xx"), capped.values)
        assertEquals(1, capped.caps?.droppedValues)
        assertEquals(1, capped.caps?.truncatedValues)
    }

    @Test
    fun reasons_are_capped_per_task_by_count_and_length() {
        val capped = PayloadCapper.cap(
            payload(tasks = listOf(task(":a", reasons = listOf("abcdef", "second", "third")))),
            PayloadCaps(maxReasonsPerTask = 1, maxReasonChars = 3),
        )
        assertEquals(listOf("abc"), capped.tasks.single().executionReasons)
        assertEquals(2, capped.caps?.droppedExecutionReasons)
        assertEquals(1, capped.caps?.truncatedExecutionReasons)
    }

    @Test
    fun non_cacheable_reason_is_truncated_and_counted() {
        val capped = PayloadCapper.cap(
            payload(tasks = listOf(task(":a", nonCacheableReason = "way too long"))),
            PayloadCaps(maxReasonChars = 4),
        )
        assertEquals("way ", capped.tasks.single().nonCacheableReason)
        assertEquals(1, capped.caps?.truncatedNonCacheableReasons)
    }

    @Test
    fun task_array_cap_retains_failed_then_longest_and_sums_dropped_outcomes() {
        val tasks = listOf(
            task(":short", durationMs = 10),
            task(":failed", outcome = TaskOutcome.FAILED, durationMs = 5),
            task(":long", durationMs = 100),
            task(":mid", durationMs = 50),
        )
        val capped = PayloadCapper.cap(payload(tasks = tasks), PayloadCaps(maxTasks = 2))
        assertEquals(setOf(":failed", ":long"), capped.tasks.map { it.path }.toSet())
        assertEquals(2, capped.caps?.droppedTasks)
        assertEquals(mapOf("EXECUTED" to 2), capped.caps?.droppedTaskOutcomes)
    }

    @Test
    fun byte_budget_stage_one_drops_reasons_before_any_task() {
        val bigReason = "x".repeat(2000)
        val tasks = (1..5).map { task(":t$it", reasons = listOf(bigReason)) }
        val capped = PayloadCapper.cap(
            payload(tasks = tasks),
            PayloadCaps(maxPayloadBytes = 1500, maxReasonChars = 100_000, maxReasonsPerTask = 100),
        )
        assertTrue(capped.tasks.all { it.executionReasons.isEmpty() }, "stage 1 drops all reasons")
        assertEquals(5, capped.tasks.size, "no task dropped once reasons are gone")
        assertEquals(0, capped.caps?.droppedTasks)
        assertTrue((capped.caps?.droppedExecutionReasons ?: 0) >= 5)
    }

    @Test
    fun byte_budget_stage_two_halves_tasks_until_it_fits_keeping_the_envelope() {
        val tasks = (1..8).map { task(":" + "x".repeat(500) + it, durationMs = it.toLong()) }
        val capped = PayloadCapper.cap(
            payload(tasks = tasks),
            PayloadCaps(maxPayloadBytes = 1200, maxTasks = 1000, maxReasonChars = 100_000),
        )
        assertTrue(capped.tasks.size < 8, "tasks halved to fit: ${capped.tasks.size}")
        assertTrue((capped.caps?.droppedTasks ?: 0) > 0)
        assertEquals("b", capped.buildId, "the build envelope always survives")
    }

    @Test
    fun compliant_payload_is_returned_unchanged_with_null_caps() {
        val input = payload(tags = mapOf("team" to "mobile"), tasks = listOf(task(":a", reasons = listOf("ok"))))
        val result = PayloadCapper.cap(input, PayloadCaps.DEFAULT)
        assertSame(input, result)
        assertNull(result.caps)
    }

    @Test
    fun capping_is_idempotent() {
        val caps = PayloadCaps(maxTags = 1)
        val once = PayloadCapper.cap(payload(tags = mapOf("a" to "1", "b" to "2")), caps)
        val twice = PayloadCapper.cap(once, caps)
        assertEquals(once, twice)
    }

    @Test
    fun byte_budget_capping_is_idempotent() {
        // The trickiest path: re-capping must NOT keep shrinking. The capper measures size
        // with its own caps block stripped, so the summary it records can't tip the payload
        // back over budget on the next pass.
        val tasks = (1..8).map { task(":" + "x".repeat(500) + it, durationMs = it.toLong()) }
        val caps = PayloadCaps(maxPayloadBytes = 1200, maxTasks = 1000, maxReasonChars = 100_000)
        val once = PayloadCapper.cap(payload(tasks = tasks), caps)
        val twice = PayloadCapper.cap(once, caps)
        assertEquals(once, twice, "re-capping a byte-capped payload changes nothing")
    }

    @Test
    fun re_capping_merges_counts_rather_than_overwriting() {
        val first = PayloadCapper.cap(payload(tags = mapOf("a" to "1", "b" to "2")), PayloadCaps(maxTags = 1))
        assertEquals(1, first.caps?.droppedTags)
        // A tighter second pass drops the remaining tag; its count adds to the first's.
        val second = PayloadCapper.cap(first, PayloadCaps(maxTags = 0))
        assertEquals(2, second.caps?.droppedTags)
    }

    @Test
    fun derived_metrics_pass_through_untouched() {
        val input = payload(tags = mapOf("a" to "1")).copy(derived = DerivedMetrics(cacheableHitRate = 0.5))
        val result = PayloadCapper.cap(input, PayloadCaps(maxTags = 0))
        assertEquals(0.5, result.derived?.cacheableHitRate)
    }
}
