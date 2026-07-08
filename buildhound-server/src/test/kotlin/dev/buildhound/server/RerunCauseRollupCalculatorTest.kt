package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RerunCauseRollupCalculatorTest {

    private fun row(
        buildId: String,
        durationMs: Long,
        reasons: List<String> = emptyList(),
        outcome: String = "EXECUTED",
        type: String? = "KotlinCompile",
    ) = TaskRow(
        buildId = buildId, userId = null, module = ":app", name = "x", type = type,
        outcome = outcome, durationMs = durationMs, buildWallMs = durationMs, executionReasons = reasons,
    )

    @Test
    fun `an empty window is honestly empty, never a divide-by-zero`() {
        val rollup = RerunCauseRollupCalculator.compute(emptyList())
        assertEquals(emptyList(), rollup.buckets)
        assertEquals(0.0, rollup.unclassifiedSharePct)
        assertEquals(0, rollup.executedTaskCount)
        assertNull(rollup.cascadeRate)
        assertNull(rollup.buildLogicStormCandidate)
    }

    @Test
    fun `non-EXECUTED outcomes never contribute to bucket coverage`() {
        val rows = listOf(
            row("b1", 1000, reasons = listOf("Value of input property 'x' has changed."), outcome = "FROM_CACHE"),
            row("b1", 500, reasons = emptyList(), outcome = "UP_TO_DATE"),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertEquals(0, rollup.executedTaskCount)
        assertEquals(emptyList(), rollup.buckets)
    }

    @Test
    fun `a multi-reason task contributes its full duration to every distinct bucket it touches`() {
        // One EXECUTED task with both a SOURCE and an IMPL_CLASSPATH reason: overlap, not a partition.
        val rows = listOf(
            row(
                "b1", 1000,
                reasons = listOf(
                    "Value of input property 'x' has changed.",
                    "Class path of task ':app:compileJava' has changed from 'a' to 'b'.",
                ),
            ),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        val bySource = rollup.buckets.single { it.cause == RerunCause.SOURCE.name }
        val byImpl = rollup.buckets.single { it.cause == RerunCause.IMPL_CLASSPATH.name }
        assertEquals(1000L, bySource.durationMs)
        assertEquals(1000L, byImpl.durationMs)
        assertEquals(1.0, bySource.sharePct, "the task's full duration counts toward SOURCE")
        assertEquals(1.0, byImpl.sharePct, "…AND toward IMPL_CLASSPATH — shares overlap, they don't sum to 1.0 total")
    }

    @Test
    fun `an all-capped build reads as 100pct UNCLASSIFIED coverage, not a spurious real bucket`() {
        // Every executed task's reason list was emptied (PayloadCapper stage-1 under byte pressure).
        val rows = listOf(row("b1", 1000, reasons = emptyList()), row("b1", 2000, reasons = emptyList()))
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertEquals(1, rollup.buckets.size)
        val unclassified = rollup.buckets.single()
        assertEquals(RerunCause.UNCLASSIFIED.name, unclassified.cause)
        assertEquals(1.0, unclassified.sharePct)
        assertEquals(1.0, rollup.unclassifiedSharePct, "unclassifiedSharePct mirrors the bucket row")
    }

    @Test
    fun `all-zero-duration EXECUTED tasks report 0pct share, never NaN`() {
        // Every EXECUTED task recorded 0ms — totalMs is 0, so a naive durationSum/totalMs divides 0.0 by
        // 0.0. Pins the explicit zero-guard (mirrors the cascade loop's buildTotalMs <= 0L guard).
        val rows = listOf(
            row("b1", 0, reasons = listOf("Value of input property 'x' has changed.")),
            row("b1", 0, reasons = listOf("No history is available.")),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertEquals(2, rollup.executedTaskCount)
        assertEquals(2, rollup.buckets.size)
        rollup.buckets.forEach { bucket ->
            assertEquals(0.0, bucket.sharePct, "share must be an explicit 0.0, never NaN, for a zero-duration window")
            assertTrue(!bucket.sharePct.isNaN())
        }
    }

    @Test
    fun `type-null (isolated-projects) rows still classify — reason taxonomy is execution-time, not type-keyed`() {
        val rows = listOf(row("b1", 1000, reasons = listOf("No history is available."), type = null))
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertEquals(RerunCause.OUTPUT_MISSING.name, rollup.buckets.single().cause)
    }

    @Test
    fun `a build whose executed work is majority classpath-impl or upstream-output is CASCADE`() {
        val rows = listOf(
            // 700ms of a 1000ms build's executed work touches IMPL_CLASSPATH — 70% > 50% threshold.
            row("cascade-1", 700, reasons = listOf("Class path of task ':x' has changed from 'a' to 'b'.")),
            row("cascade-1", 300, reasons = listOf("Value of input property 'y' has changed.")),
            // A build where only source changes fired — CONTAINED.
            row("contained-1", 1000, reasons = listOf("Value of input property 'z' has changed.")),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertEquals(1, rollup.cascadeBuildCount)
        assertEquals(1, rollup.containedBuildCount)
        assertEquals(0.5, rollup.cascadeRate)
    }

    @Test
    fun `the cascade threshold is exclusive — exactly 50pct does not tip into CASCADE`() {
        val rows = listOf(
            row("b1", 500, reasons = listOf("Class path of task ':x' has changed from 'a' to 'b'.")),
            row("b1", 500, reasons = listOf("Value of input property 'y' has changed.")),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertEquals(0, rollup.cascadeBuildCount)
        assertEquals(1, rollup.containedBuildCount)
    }

    @Test
    fun `the build-logic-storm candidate is null below the threshold`() {
        // 20% IMPL_CLASSPATH coverage — below the 30% threshold.
        val rows = listOf(
            row("b1", 200, reasons = listOf("Class path of task ':x' has changed from 'a' to 'b'.")),
            row("b1", 800, reasons = listOf("Value of input property 'y' has changed.")),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertNull(rollup.buildLogicStormCandidate)
    }

    @Test
    fun `the storm-candidate threshold is exclusive — exactly 30pct does not surface the candidate`() {
        // Mirrors the cascade detector's exactly-50pct boundary test: 30% IMPL_CLASSPATH coverage sits
        // exactly on BUILD_LOGIC_STORM_SHARE_THRESHOLD, and the compute() check is a strict '>', so this
        // must NOT surface the candidate.
        val rows = listOf(
            row("b1", 300, reasons = listOf("Class path of task ':x' has changed from 'a' to 'b'.")),
            row("b1", 700, reasons = listOf("Value of input property 'y' has changed.")),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        assertNull(rollup.buildLogicStormCandidate)
    }

    @Test
    fun `the build-logic-storm candidate fires only above its share threshold`() {
        // 40% IMPL_CLASSPATH coverage — above the 30% threshold.
        val rows = listOf(
            row("b1", 400, reasons = listOf("Class path of task ':x' has changed from 'a' to 'b'.")),
            row("b1", 600, reasons = listOf("Value of input property 'y' has changed.")),
        )
        val rollup = RerunCauseRollupCalculator.compute(rows)
        val candidate = rollup.buildLogicStormCandidate
        assertTrue(candidate != null, "40% IMPL_CLASSPATH coverage must surface the storm candidate")
        assertEquals(0.4, candidate.sharePct)
        assertTrue(candidate.message.contains("build-logic"), candidate.message)
    }
}
