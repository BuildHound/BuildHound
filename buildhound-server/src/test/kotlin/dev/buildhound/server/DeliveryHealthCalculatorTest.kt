package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Pure-unit pins for [DeliveryHealthCalculator] (plan 059): the CFR small-sample/INTERRUPTED rules,
 * the recovery-episode walk (open episodes counted separately, never as recoveries), both rerun
 * signals with the sequential-after-FAILED guard and its adversarial near-misses (the concurrent
 * matrix-leg / PR-vs-push exclusion is the load-bearing false-positive control), the wasted-minutes
 * lower bound, deterministic ordering under shuffled input, and the wire shape of the new DTOs
 * (round-trip + transient enrichment fields never serialized).
 */
class DeliveryHealthCalculatorTest {

    private fun row(
        buildId: String,
        outcome: String = "SUCCESS",
        branch: String? = "main",
        pipeline: String? = "ci",
        startedAt: Long = 0,
        finishedAt: Long = startedAt + 60_000,
        sha: String? = null,
        projectKey: String? = "pilot",
        sig: String = "sig-a",
        runAttempt: Int? = null,
        provider: String? = "github-actions",
    ) = DeliveryBuildRow(
        buildId = buildId, branch = branch, pipelineName = pipeline, provider = provider,
        outcome = outcome, startedAtMs = startedAt, finishedAtMs = finishedAt,
        sha = sha, projectKey = projectKey, requestedTasksSig = sig, runAttempt = runAttempt,
    )

    @Test
    fun `CFR excludes INTERRUPTED and honors MIN_SAMPLES`() {
        val rows = listOf(
            // main/ci: 2 SUCCESS + 1 FAILED finished (>= MIN_SAMPLES) + 1 INTERRUPTED (excluded).
            row("m1", "SUCCESS", startedAt = 0),
            row("m2", "SUCCESS", startedAt = 1000),
            row("m3", "FAILED", startedAt = 2000),
            row("m4", "INTERRUPTED", startedAt = 3000),
            // dev/ci: only 2 finished — under MIN_SAMPLES, no row (never a small-sample 50% claim).
            row("d1", "SUCCESS", branch = "dev", startedAt = 0),
            row("d2", "FAILED", branch = "dev", startedAt = 1000),
            row("d3", "INTERRUPTED", branch = "dev", startedAt = 2000),
        )
        val cfr = DeliveryHealthCalculator.compute(rows, 30).changeFailureRate
        assertEquals(1, cfr.size, "the under-sampled dev cohort must be omitted: $cfr")
        val main = cfr.single()
        assertEquals("main", main.branch)
        assertEquals(1, main.failed)
        assertEquals(2, main.succeeded, "INTERRUPTED must not count as SUCCESS or FAILED")
        assertEquals(0.333333, main.changeFailureRate)
    }

    @Test
    fun `a recovery episode spans the first FAILED to the next SUCCESS - an open episode counts separately`() {
        val rows = listOf(
            // main: red at t=10s (first FAILED finish), a second FAILED must NOT reset the episode
            // start, green again at t=100s → one recovery of 90s.
            row("f1", "FAILED", startedAt = 0, finishedAt = 10_000),
            row("f2", "FAILED", startedAt = 20_000, finishedAt = 30_000),
            row("s1", "SUCCESS", startedAt = 90_000, finishedAt = 100_000),
            // dev: fails and never recovers — an open episode, not a recovery.
            row("d1", "FAILED", branch = "dev", startedAt = 0, finishedAt = 5_000),
        )
        val recovery = DeliveryHealthCalculator.compute(rows, 30).timeToGreen
        val main = recovery.single { it.branch == "main" }
        assertEquals(1, main.recoveries)
        assertEquals(90_000L, main.medianRecoveryMs, "episode must be measured from the FIRST failed finish")
        assertEquals(90_000L, main.p90RecoveryMs)
        assertFalse(main.openEpisode)

        val dev = recovery.single { it.branch == "dev" }
        assertEquals(0, dev.recoveries, "an open (still-red) episode must never be counted as a recovery")
        assertNull(dev.medianRecoveryMs)
        assertTrue(dev.openEpisode)
    }

    @Test
    fun `an INTERRUPTED build neither opens nor closes a recovery episode`() {
        val rows = listOf(
            row("i1", "INTERRUPTED", startedAt = 0, finishedAt = 0),
            row("f1", "FAILED", startedAt = 10_000, finishedAt = 20_000),
            row("i2", "INTERRUPTED", startedAt = 30_000, finishedAt = 30_000),
            row("s1", "SUCCESS", startedAt = 40_000, finishedAt = 50_000),
        )
        val main = DeliveryHealthCalculator.compute(rows, 30).timeToGreen.single()
        assertEquals(1, main.recoveries)
        assertEquals(30_000L, main.medianRecoveryMs)
    }

    @Test
    fun `runAttempt greater than 1 is an authoritative RUN_ATTEMPT rerun, counted once even when the heuristic also fires`() {
        val rows = listOf(
            row("f1", "FAILED", sha = "abc", startedAt = 0, finishedAt = 60_000),
            // Sequential same-key rerun that ALSO carries runAttempt=2: the authoritative signal wins;
            // it must not be double-counted as a same-key candidate.
            row("r1", "SUCCESS", sha = "abc", startedAt = 120_000, finishedAt = 180_000, runAttempt = 2),
        )
        val tax = DeliveryHealthCalculator.compute(rows, 30).retryTax
        assertEquals(1, tax.runAttemptReruns)
        assertEquals(0, tax.sameKeyCandidates)
        assertEquals(listOf("r1"), tax.rerunBuildIds)
        assertEquals(1, tax.chainCount)
    }

    @Test
    fun `the same-key heuristic fires only for a sequential build after a FAILED prior`() {
        val rows = listOf(
            row("f1", "FAILED", sha = "abc", startedAt = 0, finishedAt = 60_000),
            row("r1", "SUCCESS", sha = "abc", startedAt = 120_000, finishedAt = 180_000),
        )
        val tax = DeliveryHealthCalculator.compute(rows, 30).retryTax
        assertEquals(0, tax.runAttemptReruns)
        assertEquals(1, tax.sameKeyCandidates)
        assertEquals(listOf("r1"), tax.rerunBuildIds)
    }

    @Test
    fun `near-miss - a build starting exactly at the prior FAILED finish is not a rerun`() {
        // startedAt > prior.finishedAt is STRICT: equality is boundary-overlap, not sequential.
        val rows = listOf(
            row("f1", "FAILED", sha = "abc", startedAt = 0, finishedAt = 60_000),
            row("x1", "SUCCESS", sha = "abc", startedAt = 60_000, finishedAt = 120_000),
        )
        assertEquals(0, DeliveryHealthCalculator.compute(rows, 30).retryTax.chainCount)
    }

    @Test
    fun `near-miss - a sequential same-key build after a SUCCESS prior is not a rerun`() {
        val rows = listOf(
            row("s1", "SUCCESS", sha = "abc", startedAt = 0, finishedAt = 60_000),
            row("x1", "SUCCESS", sha = "abc", startedAt = 120_000, finishedAt = 180_000),
        )
        assertEquals(0, DeliveryHealthCalculator.compute(rows, 30).retryTax.chainCount)
    }

    @Test
    fun `near-miss - a different requestedTasks signature or a missing sha never chains`() {
        val rows = listOf(
            row("f1", "FAILED", sha = "abc", sig = "sig-a", startedAt = 0, finishedAt = 60_000),
            // Same sha, different requested tasks: a different job, not a rerun of the failed one.
            row("x1", "SUCCESS", sha = "abc", sig = "sig-b", startedAt = 120_000, finishedAt = 180_000),
            // No sha at all: no same-change identity to chain on.
            row("f2", "FAILED", sha = null, startedAt = 0, finishedAt = 60_000, branch = "dev"),
            row("x2", "SUCCESS", sha = null, startedAt = 120_000, finishedAt = 180_000, branch = "dev"),
        )
        assertEquals(0, DeliveryHealthCalculator.compute(rows, 30).retryTax.chainCount)
    }

    @Test
    fun `concurrent same-sha matrix legs and PR-vs-push overlap are never reruns`() {
        val rows = listOf(
            // Two JDK-matrix legs on one sha: leg1 fails at t=100s, leg2 started at t=50s while leg1
            // was still running — overlapping, so leg2 must never read as a rerun of leg1.
            row("leg1", "FAILED", sha = "abc", startedAt = 0, finishedAt = 100_000),
            row("leg2", "SUCCESS", sha = "abc", startedAt = 50_000, finishedAt = 150_000),
            // PR-vs-push on one sha, also overlapping in time.
            row("pr", "FAILED", sha = "def", startedAt = 0, finishedAt = 80_000),
            row("push", "SUCCESS", sha = "def", startedAt = 40_000, finishedAt = 120_000),
        )
        val tax = DeliveryHealthCalculator.compute(rows, 30).retryTax
        assertEquals(0, tax.chainCount, "overlapping same-sha builds must never be miscounted as reruns: $tax")
        assertEquals(0, tax.sameKeyCandidates)
        assertTrue(tax.rerunBuildIds.isEmpty())
    }

    @Test
    fun `an intervening SUCCESS recovers the same-key group - a later non-overlapping build must not stay flagged`() {
        val rows = listOf(
            // f1 fails; s1 is a genuine sequential rerun of f1 and IS flagged.
            row("f1", "FAILED", sha = "abc", startedAt = 0, finishedAt = 100),
            row("s1", "SUCCESS", sha = "abc", startedAt = 120, finishedAt = 150),
            // s2 comes long after s1 recovered the key. The most-recently-FINISHED same-key build
            // before s2 started is s1 (SUCCESS), not the stale f1 — s2 must NOT be flagged, even
            // though an early FAILED still sits in the group (a nightly rebuild of a pinned tag must
            // never be miscounted as a rerun just because the key once failed).
            row("s2", "SUCCESS", sha = "abc", startedAt = 1_000_000, finishedAt = 1_000_050),
        )
        val tax = DeliveryHealthCalculator.compute(rows, 30).retryTax
        assertEquals(setOf("s1"), tax.rerunBuildIds.toSet(), "s1 is the rerun; s2 is a fresh build after recovery: $tax")
        assertEquals(1, tax.sameKeyCandidates)
    }

    @Test
    fun `a concurrent leg finishing more recently does not shadow an earlier FAILED leg that finished later`() {
        val rows = listOf(
            // f1 fails, finishing at t=100s. c1 is a concurrent leg on the same sha that finishes
            // earlier (t=60s) but is not the most-recently-finished build before r1 starts (t=200s) —
            // f1 (finishes at 100s) is more recent than c1 (finishes at 60s), so r1 IS a rerun of f1.
            row("f1", "FAILED", sha = "xyz", startedAt = 0, finishedAt = 100),
            row("c1", "SUCCESS", sha = "xyz", startedAt = 20, finishedAt = 60),
            row("r1", "SUCCESS", sha = "xyz", startedAt = 200, finishedAt = 250),
        )
        val tax = DeliveryHealthCalculator.compute(rows, 30).retryTax
        assertEquals(setOf("r1"), tax.rerunBuildIds.toSet(), "the most-recently-finished build before r1 started is FAILED f1, not SUCCESS c1: $tax")
        assertEquals(1, tax.sameKeyCandidates)
    }

    @Test
    fun `a garbage runAttempt attribute parses to null, never a throw`() {
        assertNull(DeliveryHealthCalculator.parseRunAttempt("garbage"))
        assertNull(DeliveryHealthCalculator.parseRunAttempt(""))
        assertNull(DeliveryHealthCalculator.parseRunAttempt(null))
        assertEquals(2, DeliveryHealthCalculator.parseRunAttempt("2"))
    }

    @Test
    fun `wasted minutes are the sum of rerun durations, and one same-key group is one chain`() {
        val rows = listOf(
            row("f1", "FAILED", sha = "abc", startedAt = 0, finishedAt = 60_000),
            row("r1", "FAILED", sha = "abc", startedAt = 120_000, finishedAt = 180_000), // 60s rerun
            row("r2", "SUCCESS", sha = "abc", startedAt = 240_000, finishedAt = 330_000), // 90s rerun
        )
        val tax = DeliveryHealthCalculator.compute(rows, 30).retryTax
        assertEquals(2, tax.sameKeyCandidates)
        assertEquals(1, tax.chainCount, "both reruns chase the same (projectKey, sha, sig) — one chain")
        assertEquals(2.5, tax.wastedCiMinutesLowerBound, "60s + 90s = 2.5 min, summed over the uncapped set")
        assertEquals(150_000L, tax.wastedMsLowerBound)
        assertEquals(listOf("r2", "r1"), tax.rerunBuildIds, "most-recent-first, deterministic")
    }

    @Test
    fun `capping rerunBuildIds at MAX_RERUN_BUILD_IDS keeps the most recent reruns, not the oldest`() {
        // 60 sequential same-key reruns chained off one initial FAILED — well over the cap of 50. Each
        // rerun's most-recently-finished prior is FAILED (the one right before it), so all 60 chain.
        val rows = mutableListOf(row("f0", "FAILED", sha = "cap", startedAt = 0, finishedAt = 1_000))
        for (i in 1..60) {
            val start = i * 10_000L
            rows += row("r$i", "FAILED", sha = "cap", startedAt = start, finishedAt = start + 1_000)
        }
        val tax = DeliveryHealthCalculator.compute(rows, 30).retryTax
        assertEquals(60, tax.sameKeyCandidates, "all 60 reruns are same-key candidates")
        assertEquals(
            1.0,
            tax.wastedCiMinutesLowerBound,
            "60 reruns x 1s each = 60s = 1 min, summed over the UNCAPPED set even though the id list is capped",
        )
        assertEquals(
            DeliveryHealthCalculator.MAX_RERUN_BUILD_IDS,
            tax.rerunBuildIds.size,
            "rerunBuildIds is capped at MAX_RERUN_BUILD_IDS even though 60 candidates exist",
        )
        // Most recent 50 by startedAt: r60 down to r11 — the oldest (r1..r10) are dropped.
        assertEquals(
            (60 downTo 11).map { "r$it" },
            tax.rerunBuildIds,
            "the cap keeps the most recent reruns, not the oldest",
        )
    }

    @Test
    fun `lead time renders build-only medians with connector fields null`() {
        val rows = listOf(
            row("a", "SUCCESS", startedAt = 0, finishedAt = 100_000),
            row("b", "FAILED", startedAt = 1000, finishedAt = 201_000),
            row("c", "SUCCESS", startedAt = 2000, finishedAt = 302_000),
            row("i", "INTERRUPTED", startedAt = 3000, finishedAt = 3000), // synthetic duration, excluded
        )
        val lead = DeliveryHealthCalculator.compute(rows, 30).leadTime.single()
        assertEquals(3, lead.buildCount)
        assertEquals(200_000L, lead.medianDurationMs)
        assertNull(lead.medianQueuedMs, "connector fields stay null in the build-only core")
        assertNull(lead.medianGradleSharePct)
        assertEquals(3, lead.enrichmentSamples.size)
        assertEquals("c", lead.enrichmentSamples.first().buildId, "samples are most-recent-first")
    }

    @Test
    fun `output is deterministic regardless of row arrival order`() {
        val rows = listOf(
            row("m1", "SUCCESS", startedAt = 0),
            row("m2", "FAILED", startedAt = 1000, sha = "abc"),
            row("m3", "SUCCESS", startedAt = 2000),
            row("m4", "SUCCESS", startedAt = 70_000, finishedAt = 130_000, sha = "abc"),
            row("d1", "FAILED", branch = "dev", startedAt = 0),
            row("d2", "SUCCESS", branch = "dev", startedAt = 1000),
            row("d3", "SUCCESS", branch = "dev", startedAt = 2000),
        )
        val forward = DeliveryHealthCalculator.compute(rows, 30)
        val reversed = DeliveryHealthCalculator.compute(rows.reversed(), 30)
        val shuffled = DeliveryHealthCalculator.compute(rows.shuffled(java.util.Random(42)), 30)
        assertEquals(forward, reversed)
        assertEquals(forward, shuffled)
    }

    @Test
    fun `empty rows yield an empty rollup, never a crash`() {
        val rollup = DeliveryHealthCalculator.compute(emptyList(), 30)
        assertTrue(rollup.changeFailureRate.isEmpty())
        assertTrue(rollup.timeToGreen.isEmpty())
        assertTrue(rollup.leadTime.isEmpty())
        assertEquals(0, rollup.retryTax.chainCount)
        assertEquals(0.0, rollup.retryTax.wastedCiMinutesLowerBound)
        assertFalse(rollup.connectorDataAvailable)
    }

    @Test
    fun `the rollup DTO round-trips and its transient enrichment fields never reach the wire`() {
        val rollup = DeliveryHealthRollup(
            period = 30,
            changeFailureRate = listOf(CfrRow(branch = "main", pipelineName = "ci", failed = 1, succeeded = 2, changeFailureRate = 0.333333)),
            timeToGreen = listOf(RecoveryRow(branch = "main", pipelineName = "ci", recoveries = 1, medianRecoveryMs = 90_000, p90RecoveryMs = 90_000)),
            leadTime = listOf(
                LeadTimeRow(
                    branch = "main", pipelineName = "ci", buildCount = 3, medianDurationMs = 200_000,
                    medianQueuedMs = 42_000, medianGradleSharePct = 0.35,
                    enrichmentSamples = listOf(DeliverySample("b1", 60_000)),
                ),
            ),
            retryTax = RetryTaxSummary(
                chainCount = 1, rerunBuildIds = listOf("r1"), wastedCiMinutesLowerBound = 1.0,
                runAttemptReruns = 1, sameKeyCandidates = 0,
                wastedMsLowerBound = 60_000, rerunSamples = listOf(DeliverySample("r1", 60_000)),
            ),
            connectorDataAvailable = true,
            flakyRerunTax = listOf(FlakyRerunCandidate(module = ":app", className = "com.example.CartTest", rerunBuildCount = 1, wastedCiMinutesLowerBound = 1.0)),
        )
        val json = Json.encodeToString(DeliveryHealthRollup.serializer(), rollup)
        // The in-process enrichment handoffs are @Transient — never serialized (wire-shape pin).
        assertFalse(json.contains("enrichmentSamples"), json)
        assertFalse(json.contains("wastedMsLowerBound"), json)
        assertFalse(json.contains("rerunSamples"), json)
        val decoded = Json.decodeFromString(DeliveryHealthRollup.serializer(), json)
        // Round-trip equals the original with the transient fields back at their defaults.
        val withoutTransients = rollup.copy(
            leadTime = rollup.leadTime.map { it.copy(enrichmentSamples = emptyList()) },
            retryTax = rollup.retryTax.copy(wastedMsLowerBound = 0, rerunSamples = emptyList()),
        )
        assertEquals(withoutTransients, decoded)
    }
}
