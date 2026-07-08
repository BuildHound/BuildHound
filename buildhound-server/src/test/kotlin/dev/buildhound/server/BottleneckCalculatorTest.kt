package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit coverage for [BottleneckCalculator.compute]'s [BottlenecksRollup.topPlugins] fold (plan
 * 058, research F8 Layer 1). The rest of `compute` (regressions, new/vanished, cache-miss hotspots, KPI
 * deltas) is covered via [BottleneckRoutesTest]/[BottleneckStoresIntegrationTest] rather than duplicated
 * here.
 */
class BottleneckCalculatorTest {

    private fun task(type: String?, durationMs: Long, buildId: String = "b1", outcome: String = "EXECUTED") = TaskRow(
        buildId = buildId, userId = null, module = ":app", name = "x", type = type,
        outcome = outcome, durationMs = durationMs, buildWallMs = durationMs,
    )

    private fun build(outcome: String = "SUCCESS", durationMs: Long = 1000L) = BuildKpiRow(outcome, durationMs, hitRate = null)

    @Test
    fun `topPlugins folds currentTasks by owning plugin, ranked by total time`() {
        val rollup = BottleneckCalculator.compute(
            currentTasks = listOf(
                task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 4000),
                task("com.android.build.gradle.tasks.MergeResources", 2000),
                task("com.example.MyTask", 500),
            ),
            priorTasks = emptyList(),
            currentBuilds = listOf(build()),
            priorBuilds = emptyList(),
            period = 7,
        )
        assertEquals(
            listOf("Kotlin Gradle Plugin", "Android Gradle Plugin", "(unattributed)"),
            rollup.topPlugins.map { it.key },
        )
        assertEquals(4000, rollup.topPlugins[0].currentMs)
    }

    @Test
    fun `topPlugins folds every outcome, not just EXECUTED — mirrors slowestWork`() {
        val rollup = BottleneckCalculator.compute(
            currentTasks = listOf(
                task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 3000, outcome = "EXECUTED"),
                task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 9000, outcome = "FROM_CACHE"),
            ),
            priorTasks = emptyList(),
            currentBuilds = listOf(build()),
            priorBuilds = emptyList(),
            period = 7,
        )
        val kgp = rollup.topPlugins.single()
        assertEquals(12000, kgp.currentMs, "both EXECUTED and FROM_CACHE time count toward total plugin time")
        assertEquals(2, kgp.count)
    }

    @Test
    fun `topPlugins inherits whatever window currentTasks was already filtered to (period, benchmark-excluded)`() {
        // BottleneckCalculator itself does no windowing/exclusion — that's the store's job (payloadsBetween
        // excludes benchmark builds before currentTasks even reaches compute). Passing only the
        // already-filtered rows here proves topPlugins introduces no *additional* filtering of its own.
        val rollup = BottleneckCalculator.compute(
            currentTasks = listOf(task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 1000)),
            priorTasks = emptyList(),
            currentBuilds = listOf(build()),
            priorBuilds = emptyList(),
            period = 7,
        )
        assertEquals(1, rollup.topPlugins.size)
        assertEquals(1000, rollup.topPlugins.single().currentMs)
    }

    @Test
    fun `an empty current window yields an empty topPlugins ranking, never a crash`() {
        val rollup = BottleneckCalculator.compute(
            currentTasks = emptyList(),
            priorTasks = listOf(task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 1000)),
            currentBuilds = emptyList(),
            priorBuilds = listOf(build()),
            period = 7,
        )
        assertTrue(rollup.topPlugins.isEmpty())
    }

    @Test
    fun `topPlugins does not set a module — a plugin spans many modules, so a single owner would mislead`() {
        val rollup = BottleneckCalculator.compute(
            currentTasks = listOf(task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 1000)),
            priorTasks = emptyList(),
            currentBuilds = listOf(build()),
            priorBuilds = emptyList(),
            period = 7,
        )
        assertEquals(null, rollup.topPlugins.single().module)
    }

    @Test
    fun `topPluginsAvailable is true once at least one current task carries a type`() {
        val rollup = BottleneckCalculator.compute(
            currentTasks = listOf(task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 1000)),
            priorTasks = emptyList(),
            currentBuilds = listOf(build()),
            priorBuilds = emptyList(),
            period = 7,
        )
        assertTrue(rollup.topPluginsAvailable)
    }

    @Test
    fun `topPluginsAvailable is false when every current task has a null type — isolated-projects degradation`() {
        // Under isolated projects (research risk register) every task type is null, so topPlugins folds
        // entirely into "(unattributed)" — a non-empty list that must not be mistaken for real data.
        val rollup = BottleneckCalculator.compute(
            currentTasks = listOf(task(null, 1000), task(null, 2000)),
            priorTasks = emptyList(),
            currentBuilds = listOf(build()),
            priorBuilds = emptyList(),
            period = 7,
        )
        assertFalse(rollup.topPluginsAvailable)
        assertEquals(listOf("(unattributed)"), rollup.topPlugins.map { it.key }, "still folds — the UI, not the calculator, must gate on the flag")
    }

    @Test
    fun `topPluginsAvailable is false on an empty current window`() {
        val rollup = BottleneckCalculator.compute(
            currentTasks = emptyList(),
            priorTasks = listOf(task("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", 1000)),
            currentBuilds = emptyList(),
            priorBuilds = listOf(build()),
            period = 7,
        )
        assertFalse(rollup.topPluginsAvailable)
    }
}
