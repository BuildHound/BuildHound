package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit coverage for [RollupCalculator.pluginCost] (plan 058, research F8 Layer 1). The other
 * [RollupCalculator] functions (`projectCost`/`taskDuration`/`negativeAvoidance`) are covered via
 * [RollupRoutesTest]/[RollupStoresIntegrationTest] rather than duplicated here.
 */
class RollupCalculatorTest {

    private fun row(type: String?, durationMs: Long, buildId: String = "b1") = TaskRow(
        buildId = buildId, userId = null, module = ":app", name = "x", type = type,
        outcome = "EXECUTED", durationMs = durationMs, buildWallMs = durationMs,
    )

    @Test
    fun `an empty window is honestly empty and unavailable, never a divide-by-zero`() {
        val rollup = RollupCalculator.pluginCost(emptyList())
        assertEquals(emptyList(), rollup.plugins)
        assertFalse(rollup.available)
    }

    @Test
    fun `several task types owned by the same plugin fold into one row`() {
        val rows = listOf(
            row("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 4000),
            row("org.jetbrains.kotlin.gradle.tasks.KaptGenerateStubsTask", 1000),
        )
        val rollup = RollupCalculator.pluginCost(rows)
        val kgp = rollup.plugins.single { it.plugin == "Kotlin Gradle Plugin" }
        assertEquals(5000L, kgp.totalMs)
        assertEquals(2, kgp.count)
    }

    @Test
    fun `sharePct is each plugin's fraction of the grand total, rounded to 6dp`() {
        val rows = listOf(
            row("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 3000),
            row("org.gradle.api.tasks.compile.JavaCompile", 1000),
        )
        val rollup = RollupCalculator.pluginCost(rows)
        assertEquals(0.75, rollup.plugins.single { it.plugin == "Kotlin Gradle Plugin" }.sharePct)
        assertEquals(0.25, rollup.plugins.single { it.plugin == "Gradle core" }.sharePct)
    }

    @Test
    fun `the unattributed and gradle-core buckets are both present and distinct`() {
        val rows = listOf(
            row("org.gradle.api.tasks.compile.JavaCompile", 1000),
            row("com.example.WriteVersionTask", 500),
            row(null, 200),
        )
        val rollup = RollupCalculator.pluginCost(rows)
        assertEquals(1000L, rollup.plugins.single { it.plugin == "Gradle core" }.totalMs)
        // The build-script-defined type and the null type both fold into the SAME unattributed row —
        // never dropped, never split into two different "unknown" buckets.
        val unattributed = rollup.plugins.single { it.plugin == "(unattributed)" }
        assertEquals(700L, unattributed.totalMs)
        assertEquals(2, unattributed.count)
    }

    @Test
    fun `available mirrors byTypeAvailable — true only when at least one row carries a type`() {
        assertTrue(RollupCalculator.pluginCost(listOf(row("org.gradle.api.tasks.compile.JavaCompile", 100))).available)
        assertFalse(RollupCalculator.pluginCost(listOf(row(null, 100))).available, "a null-only window is honestly unavailable")
    }

    @Test
    fun `rows are sorted by total time descending with a plugin-label tiebreak`() {
        val rows = listOf(
            row("org.gradle.api.tasks.compile.JavaCompile", 100), // Gradle core
            row("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 500), // Kotlin Gradle Plugin
            row("com.android.build.gradle.tasks.MergeResources", 500), // Android Gradle Plugin — tie on total
        )
        val rollup = RollupCalculator.pluginCost(rows)
        // Both AGP and KGP total 500ms — the label tiebreak resolves it alphabetically.
        assertEquals(listOf("Android Gradle Plugin", "Kotlin Gradle Plugin", "Gradle core"), rollup.plugins.map { it.plugin })
    }

    @Test
    fun `an all-zero-duration window reports 0pct share, never NaN`() {
        val rows = listOf(row("org.gradle.api.tasks.compile.JavaCompile", 0), row(null, 0))
        val rollup = RollupCalculator.pluginCost(rows)
        rollup.plugins.forEach { assertEquals(0.0, it.sharePct) }
    }
}
