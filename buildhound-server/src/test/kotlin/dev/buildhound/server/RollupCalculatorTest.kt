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

    // --- Change blast-radius (plan 063, research F13) ---

    private fun blastBuild(id: String, changed: List<String>, executed: Map<String, Long>) =
        ChangeBlastBuild(buildId = id, changedModules = changed, executedMsByModule = executed)

    @Test
    fun `downstream is the build's executed time in every OTHER module`() {
        // One build: :app changed; :app executed 1000, :core executed 3000, root(null) executed 500.
        // Downstream for :app = 3000 + 500 = 3500 (total 4500 minus :app's own 1000).
        val builds = listOf(
            blastBuild("b1", listOf(":app"), mapOf(":app" to 1000L, ":core" to 3000L, ChangeBlastBuild.NULL_MODULE_KEY to 500L)),
        )
        val row = RollupCalculator.changeBlastRadius(builds).single()
        assertEquals(":app", row.module)
        assertEquals(1, row.changeCount)
        assertEquals(3500L, row.medianDownstreamMs)
        assertEquals(3500L, row.blastScore)
    }

    @Test
    fun `changeCount counts distinct builds and the median is over their downstream samples`() {
        // :app changed in three builds with downstream 1000, 3000, 2000 → median 2000, changeCount 3.
        val builds = listOf(
            blastBuild("b1", listOf(":app"), mapOf(":app" to 100L, ":core" to 1000L)),
            blastBuild("b2", listOf(":app"), mapOf(":app" to 100L, ":core" to 3000L)),
            blastBuild("b3", listOf(":app"), mapOf(":app" to 100L, ":core" to 2000L)),
        )
        val row = RollupCalculator.changeBlastRadius(builds).single()
        assertEquals(3, row.changeCount)
        assertEquals(2000L, row.medianDownstreamMs)
        assertEquals(6000L, row.blastScore, "median 2000 × changeCount 3")
    }

    @Test
    fun `an even sample count medians the two middles (floor of their average)`() {
        val builds = listOf(
            blastBuild("b1", listOf(":app"), mapOf(":core" to 1000L)),
            blastBuild("b2", listOf(":app"), mapOf(":core" to 2001L)),
        )
        val row = RollupCalculator.changeBlastRadius(builds).single()
        assertEquals(1500L, row.medianDownstreamMs, "(1000 + 2001) / 2 floored")
    }

    @Test
    fun `a build changing several modules attributes its downstream to each (shared, over-counted)`() {
        // Honest heuristic: both :app and :core get the whole build's other-module downstream.
        val builds = listOf(
            blastBuild("b1", listOf(":app", ":core"), mapOf(":app" to 1000L, ":core" to 2000L, ":lib" to 500L)),
        )
        val rows = RollupCalculator.changeBlastRadius(builds).associateBy { it.module }
        assertEquals(2500L, rows.getValue(":app").medianDownstreamMs, "total 3500 minus :app's own 1000")
        assertEquals(1500L, rows.getValue(":core").medianDownstreamMs, "total 3500 minus :core's own 2000")
    }

    @Test
    fun `rows are ranked by blast score descending with a module tiebreak`() {
        val builds = listOf(
            // :b and :c tie on blastScore (both 1000); the module name breaks the tie alphabetically.
            blastBuild("b1", listOf(":a"), mapOf(":x" to 5000L)),
            blastBuild("b2", listOf(":c"), mapOf(":x" to 1000L)),
            blastBuild("b3", listOf(":b"), mapOf(":x" to 1000L)),
        )
        val ranked = RollupCalculator.changeBlastRadius(builds).map { it.module }
        assertEquals(listOf(":a", ":b", ":c"), ranked)
    }

    @Test
    fun `an empty window is honestly empty`() {
        assertEquals(emptyList(), RollupCalculator.changeBlastRadius(emptyList()))
    }

    @Test
    fun `a build with a changed module but no executed tasks contributes zero downstream`() {
        val builds = listOf(blastBuild("b1", listOf(":app"), emptyMap()))
        val row = RollupCalculator.changeBlastRadius(builds).single()
        assertEquals(0L, row.medianDownstreamMs)
        assertEquals(1, row.changeCount)
    }
}
