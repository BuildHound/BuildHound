package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegressionEngineTest {

    private val settings = ProjectSettings(warnZ = 3.5, failZ = 5.0)

    private fun duration(value: Double) = MetricInput("durationMs", value, MetricDirection.HIGHER_BAD)

    @Test
    fun `median handles odd and even windows`() {
        assertEquals(2.0, RegressionEngine.median(listOf(1.0, 3.0, 2.0)))
        assertEquals(2.5, RegressionEngine.median(listOf(1.0, 2.0, 3.0, 4.0)))
    }

    @Test
    fun `mad is the median absolute deviation`() {
        val values = listOf(1.0, 1.0, 1.0, 1.0, 10.0) // median 1, deviations 0,0,0,0,9 → mad 0
        assertEquals(1.0, RegressionEngine.median(values))
        assertEquals(0.0, RegressionEngine.mad(values, 1.0))
        val spread = listOf(1.0, 2.0, 3.0, 4.0, 5.0) // median 3, deviations 2,1,0,1,2 → mad 1
        assertEquals(1.0, RegressionEngine.mad(spread, 3.0))
    }

    @Test
    fun `requestedTasks signature is order-invariant and matches the md5 the sql backfill computes`() {
        assertEquals(
            RegressionEngine.requestedTasksSignature(listOf("a", "b")),
            RegressionEngine.requestedTasksSignature(listOf("b", "a")),
        )
        // md5("a\nb") — pinned so the V3 backfill and the app never diverge.
        assertEquals("8cdeb44417f3c26826595d5820cf5700", RegressionEngine.requestedTasksSignature(listOf("a", "b")))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", RegressionEngine.requestedTasksSignature(emptyList()))
        // Colon/letter-bearing Gradle task paths: Kotlin code-unit sort == SQL `ORDER BY 1 COLLATE "C"`,
        // so this md5 must match `md5(':app:assembleDebug' || E'\n' || ':app:test' || E'\n' || 'build')`.
        assertEquals(
            "8ff5295233bbb33d6391eed53416093c",
            RegressionEngine.requestedTasksSignature(listOf(":app:test", "build", ":app:assembleDebug")),
        )
    }

    @Test
    fun `branch class is main only on the configured default branch`() {
        assertEquals("main", RegressionEngine.branchClass("main", "main"))
        assertEquals("pr", RegressionEngine.branchClass("feature/x", "main"))
        assertEquals("pr", RegressionEngine.branchClass(null, "main"))
    }

    @Test
    fun `a cold key with fewer than three baseline points is INSUFFICIENT_DATA, never FAIL`() {
        val verdict = RegressionEngine.evaluate(
            inputs = listOf(duration(9_999.0)),
            baselines = mapOf("durationMs" to listOf(100.0, 100.0)),
            settings = settings,
            baselineKey = "k",
        )
        assertEquals(VerdictStatus.INSUFFICIENT_DATA.name, verdict.status)
    }

    @Test
    fun `a duration far above the baseline is FAIL, a mild rise is WARN`() {
        // A spread baseline so MAD is non-zero (median 1000, MAD 50) and the robust z applies.
        val baseline = listOf(900.0, 950.0, 1000.0, 1050.0, 1100.0, 1000.0, 1000.0)
        assertEquals(50.0, RegressionEngine.mad(baseline, 1000.0))

        val bigFail = RegressionEngine.evaluate(listOf(duration(5000.0)), mapOf("durationMs" to baseline), settings, "k")
        assertEquals(VerdictStatus.FAIL.name, bigFail.status, bigFail.metrics.toString())

        // A rise sized to land between warnZ and failZ.
        val warnValue = 1000.0 + (settings.warnZ + 0.2) * 50.0 / 0.6745
        val warn = RegressionEngine.evaluate(listOf(duration(warnValue)), mapOf("durationMs" to baseline), settings, "k")
        assertEquals(VerdictStatus.WARN.name, warn.status, warn.metrics.toString())
    }

    @Test
    fun `direction is metric-aware — a hit-rate drop is bad, a rise is fine`() {
        val baseline = List(10) { 0.80 } + listOf(0.81, 0.79)
        val hitRate = { v: Double -> MetricInput("cacheableHitRate", v, MetricDirection.LOWER_BAD, budgetable = false) }
        val drop = RegressionEngine.evaluate(listOf(hitRate(0.20)), mapOf("cacheableHitRate" to baseline), settings, "k")
        assertEquals(VerdictStatus.FAIL.name, drop.status)
        val rise = RegressionEngine.evaluate(listOf(hitRate(0.99)), mapOf("cacheableHitRate" to baseline), settings, "k")
        assertEquals(VerdictStatus.PASS.name, rise.status)
    }

    @Test
    fun `a degenerate zero-MAD baseline uses the greater-than-double-median fallback`() {
        val flat = List(5) { 1000.0 } // MAD 0
        val fail = RegressionEngine.evaluate(listOf(duration(2500.0)), mapOf("durationMs" to flat), settings, "k")
        assertEquals(VerdictStatus.FAIL.name, fail.status)
        val ok = RegressionEngine.evaluate(listOf(duration(1500.0)), mapOf("durationMs" to flat), settings, "k")
        assertEquals(VerdictStatus.PASS.name, ok.status)
    }

    @Test
    fun `a budget breach forces FAIL regardless of the z-score`() {
        val baseline = List(10) { 1000.0 } + listOf(1010.0, 990.0)
        val budgeted = settings.copy(budgets = mapOf("durationMs" to 1200.0))
        // 1100 is well within the baseline z (PASS) but over the 1200? no — under. Use 1300 > budget.
        val verdict = RegressionEngine.evaluate(listOf(duration(1300.0)), mapOf("durationMs" to baseline), budgeted, "k")
        val metric = verdict.metrics.single()
        assertEquals(VerdictStatus.FAIL.name, metric.status)
        assertEquals(1200.0, metric.budget)
        assertEquals(VerdictStatus.FAIL.name, verdict.status)
    }

    @Test
    fun `overall verdict is the worst metric and insufficient data never overrides a pass`() {
        val baseline = List(10) { 1000.0 } + listOf(1010.0, 990.0)
        val verdict = RegressionEngine.evaluate(
            inputs = listOf(
                duration(1000.0), // PASS
                MetricInput("custom.size", 5.0, MetricDirection.HIGHER_BAD), // no baseline → INSUFFICIENT
            ),
            baselines = mapOf("durationMs" to baseline),
            settings = settings,
            baselineKey = "k",
        )
        assertEquals(VerdictStatus.PASS.name, verdict.status, "PASS outranks INSUFFICIENT_DATA")
    }

    @Test
    fun `built-in metrics carry duration and hit rate with correct directions`() {
        val payload = TestPayloads.build(durationMs = 4000, hitRate = 0.5)
        val metrics = RegressionEngine.builtInMetrics(payload).associateBy { it.name }
        assertEquals(MetricDirection.HIGHER_BAD, metrics["durationMs"]?.direction)
        assertEquals(4000.0, metrics["durationMs"]?.value)
        assertEquals(MetricDirection.LOWER_BAD, metrics["cacheableHitRate"]?.direction)
        assertTrue(metrics["cacheableHitRate"]?.budgetable == false)
    }
}
