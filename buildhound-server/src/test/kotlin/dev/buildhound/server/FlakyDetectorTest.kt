package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlakyDetectorTest {

    private var clock = 1_000L
    private fun row(
        buildId: String,
        sha: String?,
        cls: String = "com.example.FooTest",
        module: String? = ":app",
        passed: Int = 5,
        failed: Int = 0,
        retry: Int = 0,
    ) = ClassOutcome(buildId, startedAtMs = clock++, sha, module, cls, passed, failed, retry)

    @Test
    fun `failedThenPassed detects a retry flake, not a persistent failure`() {
        assertTrue(FlakyDetector.failedThenPassed(listOf("FAILED", "PASSED")))
        assertTrue(FlakyDetector.failedThenPassed(listOf("FAILED", "FAILED", "PASSED")))
        assertTrue(FlakyDetector.failedThenPassed(listOf("ERROR", "PASSED")))
        assertFalse(FlakyDetector.failedThenPassed(listOf("FAILED", "FAILED")))
        assertFalse(FlakyDetector.failedThenPassed(listOf("PASSED")))
        assertFalse(FlakyDetector.failedThenPassed(listOf("PASSED", "FAILED")), "pass-then-fail is not a retry flake")
    }

    @Test
    fun `the retry signal fires when a case failed-then-passed within a build`() {
        val rows = listOf(
            row("b1", "sha-a"),
            row("b2", "sha-a", retry = 1), // a case in this class failed then passed
            row("b3", "sha-a"),
        )
        val record = FlakyDetector.detect(rows).single()
        assertEquals(FlakySignal.RETRY.name, record.signal)
        assertEquals("com.example.FooTest", record.className)
        assertEquals(3, record.sampleCount)
    }

    @Test
    fun `the cross-run signal fires on same-sha green+red divergence`() {
        val rows = listOf(
            row("b1", "sha-a", passed = 5, failed = 0),
            row("b2", "sha-a", passed = 4, failed = 1), // same sha, now red
            row("b3", "sha-a", passed = 5, failed = 0),
        )
        val record = FlakyDetector.detect(rows).single()
        assertEquals(FlakySignal.CROSS_RUN.name, record.signal)
        assertEquals(1.0, record.flakeRate)
    }

    @Test
    fun `differing shas are a regression, not cross-run flakiness`() {
        val rows = listOf(
            row("b1", "sha-a", failed = 0),
            row("b2", "sha-b", failed = 1), // failed at a DIFFERENT sha → a real regression, not flaky
            row("b3", "sha-c", failed = 0),
        )
        assertTrue(FlakyDetector.detect(rows).isEmpty(), "same-sha requirement excludes cross-commit regressions")
    }

    @Test
    fun `BOTH signal when retry and cross-run both fire`() {
        val rows = listOf(
            row("b1", "sha-a", failed = 0),
            row("b2", "sha-a", failed = 1), // cross-run red
            row("b3", "sha-a", failed = 0, retry = 1), // retry flake
        )
        assertEquals(FlakySignal.BOTH.name, FlakyDetector.detect(rows).single().signal)
    }

    @Test
    fun `a class-only-passing build is not divergent even when the build failed elsewhere`() {
        // failed is the CLASS's failed count; an unrelated build-level failure never makes it red.
        val rows = listOf(row("b1", "sha-a"), row("b2", "sha-a"), row("b3", "sha-a"))
        assertTrue(FlakyDetector.detect(rows).isEmpty(), "an all-green class is never flaky")
    }

    @Test
    fun `min samples suppresses a two-build divergence`() {
        val rows = listOf(row("b1", "sha-a", failed = 0), row("b2", "sha-a", failed = 1))
        assertTrue(FlakyDetector.detect(rows).isEmpty(), "2 < minSamples")
    }

    @Test
    fun `min flake rate suppresses a lone retry in a large sample`() {
        val rows = (1..25).map { row("b$it", "sha-a", retry = if (it == 1) 1 else 0) }
        assertTrue(FlakyDetector.detect(rows).isEmpty(), "1/25 = 0.04 < 0.05")
    }

    @Test
    fun `records are ordered by flake rate descending`() {
        val rows = listOf(
            // Class A: 1 of 4 divergent-ish via retry → lower rate.
            row("a1", "sha-a", cls = "A"), row("a2", "sha-a", cls = "A"),
            row("a3", "sha-a", cls = "A"), row("a4", "sha-a", cls = "A", retry = 1),
            // Class B: same-sha green+red across all 3 → rate 1.0.
            row("b1", "sha-b", cls = "B", failed = 0), row("b2", "sha-b", cls = "B", failed = 1),
            row("b3", "sha-b", cls = "B", failed = 0),
        )
        val records = FlakyDetector.detect(rows)
        assertEquals(2, records.size)
        assertEquals("B", records.first().className, "highest flake rate first")
        assertTrue(records.first().flakeRate >= records.last().flakeRate)
    }

    @Test
    fun `retryFlakyCaseCount counts only fail-then-pass sequences`() {
        val outcomes = listOf(
            listOf("FAILED", "PASSED"), // flaky
            listOf("FAILED", "FAILED"), // persistent
            listOf("PASSED"), // clean
        )
        assertEquals(1, FlakyDetector.retryFlakyCaseCount(outcomes))
    }

    @Test
    fun `classOutcomesOf sums a class split across two Test tasks into one build-scoped row`() {
        // A class reported by two Test tasks in one build (test + integrationTest, or plan-040 shards):
        // one green shard (5/0), one red shard (0/1). Must collapse to a single row so the in-memory
        // store and Postgres (PK per module/class + ON CONFLICT) agree by construction.
        val payload = TestPayloads.build(
            buildId = "b1", sha = "sha-a",
            tests = listOf(
                TestPayloads.testTask(passed = 5, failed = 0),
                TestPayloads.testTask(passed = 0, failed = 1),
            ),
        )
        val rows = classOutcomesOf(payload)
        assertEquals(1, rows.size, "exactly one (module, class) row per build")
        assertEquals(5, rows.single().passed)
        assertEquals(1, rows.single().failed)
        // The merged row is red (failed>0) — never simultaneously green — so a single build can't look
        // divergent to the cross-run signal.
        assertFalse(rows.single().passed > 0 && rows.single().failed == 0)
    }
}
