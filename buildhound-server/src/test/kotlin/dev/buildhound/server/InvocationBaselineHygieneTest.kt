package dev.buildhound.server

import dev.buildhound.commons.payload.InvocationInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression-baseline hygiene (plan 051): `rerunTasks`/`refreshDependencies` builds have zero
 * avoidance by design (the same rationale that excluded `INTERRUPTED` in plan 033), so
 * `baselineWindow` must exclude them. Exercised directly against [InMemoryBuildStore] (fast, no
 * Docker); [RegressionStoresIntegrationTest] pins the same scenario against Postgres so the two
 * stores agree byte-for-byte (plan-025 parity oracle).
 */
class InvocationBaselineHygieneTest {

    private val projectId = "proj-051"

    @Test
    fun `baseline window excludes rerunTasks and refreshDependencies builds`() {
        val builds = InMemoryBuildStore()
        val sig = RegressionEngine.requestedTasksSignature(listOf("build"))

        // Five well-behaved SUCCESS builds matching the key.
        repeat(5) { i ->
            builds.save(
                projectId,
                TestPayloads.build(buildId = "normal-$i", durationMs = 1000, startedAt = 1_000_000L + i * 1000),
            )
        }
        // A --rerun-tasks build: matches every other hot column, but must never enter the window.
        builds.save(
            projectId,
            TestPayloads.build(
                buildId = "rerun",
                durationMs = 999_000,
                startedAt = 2_000_000L,
                invocation = InvocationInfo(rerunTasks = true),
            ),
        )
        // A --refresh-dependencies build: same.
        builds.save(
            projectId,
            TestPayloads.build(
                buildId = "refresh",
                durationMs = 999_000,
                startedAt = 3_000_000L,
                invocation = InvocationInfo(refreshDependencies = true),
            ),
        )
        // A build with no environment/invocation block at all must still be INCLUDED — absence is
        // not evidence of a rerun (the null-safe `!= true` guard, mirrored by Postgres' IS DISTINCT
        // FROM 'true', both treat NULL as "not true").
        builds.save(projectId, TestPayloads.build(buildId = "no-invocation", durationMs = 1000, startedAt = 4_000_000L))

        val window = builds.baselineWindow(
            projectId,
            defaultBranch = "main",
            query = BaselineQuery("android-ci", sig, "CI"),
            excludingBuildId = "candidate",
            n = 20,
        )

        assertEquals(6, window.size, "5 normal + the no-invocation build; rerun/refresh must be excluded")
        assertTrue(window.all { it.durationMs == 1000L }, "the 999s outliers must not have entered the window")
    }
}
