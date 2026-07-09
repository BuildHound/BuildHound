package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ChangeDiffBase
import dev.buildhound.commons.payload.ChangedModulesInfo
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres checks for the plan-026 rollups: `save` writes normalized task rows once per build
 * (zero on a duplicate), and each SQL rollup agrees byte-for-byte with the in-memory store over the
 * same fixtures — so `RollupCalculator` is the single source of the aggregation rules. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RollupStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var tokens: PostgresTokenStore

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        TestPayloads.build(
            buildId = "f-1", durationMs = 10_000, startedAt = recent, userId = "u_1",
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile"),
                TestPayloads.task(":app:test", TaskOutcome.EXECUTED, 3000, type = "Test"),
                TestPayloads.task(":lib:compileKotlin", TaskOutcome.EXECUTED, 1000, type = "KotlinCompile"),
            ),
            // Change blast-radius fixtures (plan 063): :app changes downstream :lib work; a multi-module
            // change (f-2) attributes the whole build's other-module time to each changed module.
            changedModules = ChangedModulesInfo(base = ChangeDiffBase.LAST_BUILT_SHA, modules = listOf(":app")),
        ),
        TestPayloads.build(
            buildId = "f-2", durationMs = 9_000, startedAt = recent + 1000, userId = "u_2",
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.FROM_CACHE, 9000, type = "KotlinCompile"),
                TestPayloads.task(":lib:test", TaskOutcome.UP_TO_DATE, 200, type = "Test"),
            ),
            changedModules = ChangedModulesInfo(base = ChangeDiffBase.LAST_BUILT_SHA, modules = listOf(":app", ":lib")),
        ),
        TestPayloads.build(
            buildId = "f-3", durationMs = 12_000, startedAt = recent + 2000, userId = "u_1",
            tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 7000, type = "KotlinCompile")),
            changedModules = ChangedModulesInfo(base = ChangeDiffBase.CI_PR_BASE, modules = listOf(":lib")),
        ),
        // Edge cases the tiebreakers/medians exist for: a null module, a null user, and an EVEN
        // executed count so the negative-avoidance median interpolates (1000,2000 → 1500.0).
        TestPayloads.build(
            buildId = "f-4", durationMs = 8_000, startedAt = recent + 3000, userId = null,
            tasks = listOf(
                TaskExecution(path = "orphan", module = null, type = "Lint", startMs = 0, durationMs = 1000, outcome = TaskOutcome.EXECUTED),
                TaskExecution(path = "orphan", module = null, type = "Lint", startMs = 0, durationMs = 2000, outcome = TaskOutcome.EXECUTED),
                TaskExecution(path = "orphan", module = null, type = "Lint", startMs = 0, durationMs = 3000, outcome = TaskOutcome.UP_TO_DATE),
            ),
        ),
    )

    private fun taskRowCount(projectId: String, buildId: String): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM task_executions WHERE project_id = ?::uuid AND build_id = ?").use { s ->
                s.setString(1, projectId)
                s.setString(2, buildId)
                s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }

    @Test
    fun `save writes task rows once per build and none on a duplicate`() {
        val project = tokens.ensureProjectWithToken("rows-project", sha256Hex("rp"))
        val build = fixtures()[0]
        assertEquals(true, postgresStore.save(project.id, build))
        assertEquals(3, taskRowCount(project.id, build.buildId), "one row per task")
        assertEquals(false, postgresStore.save(project.id, build), "duplicate build")
        assertEquals(3, taskRowCount(project.id, build.buildId), "a duplicate build adds no task rows")
    }

    @Test
    fun `each rollup matches the in-memory store byte-for-byte`() {
        val project = tokens.ensureProjectWithToken("parity-project", sha256Hex("pp"))
        val inMemory = InMemoryBuildStore()
        for (build in fixtures()) {
            postgresStore.save(project.id, build)
            inMemory.save(project.id, build)
        }

        assertEquals(inMemory.projectCost(project.id, 30, now), postgresStore.projectCost(project.id, 30, now), "projectCost")
        assertEquals(inMemory.taskDuration(project.id, 30, now), postgresStore.taskDuration(project.id, 30, now), "taskDuration")
        assertEquals(inMemory.negativeAvoidance(project.id, 30, now), postgresStore.negativeAvoidance(project.id, 30, now), "negativeAvoidance")
        assertEquals(inMemory.pluginCost(project.id, 30, now), postgresStore.pluginCost(project.id, 30, now), "pluginCost")
        assertEquals(
            inMemory.changeBlastRadius(project.id, 30, now),
            postgresStore.changeBlastRadius(project.id, 30, now),
            "changeBlastRadius",
        )
    }

    private fun changedModuleRowCount(projectId: String, buildId: String): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM build_changed_modules WHERE project_id = ?::uuid AND build_id = ?").use { s ->
                s.setString(1, projectId)
                s.setString(2, buildId)
                s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }

    @Test
    fun `save writes one changed-module row per module and none on a duplicate`() {
        val project = tokens.ensureProjectWithToken("changed-modules-project", sha256Hex("cmp"))
        val build = fixtures()[1] // f-2: two changed modules (:app, :lib)
        assertEquals(true, postgresStore.save(project.id, build))
        assertEquals(2, changedModuleRowCount(project.id, build.buildId), "one row per changed module")
        assertEquals(false, postgresStore.save(project.id, build), "duplicate build")
        assertEquals(2, changedModuleRowCount(project.id, build.buildId), "a duplicate build adds no changed-module rows")
    }

    @Test
    fun `change blast-radius ranks a module by the downstream work its changes cause, with store parity`() {
        val project = tokens.ensureProjectWithToken("blast-project", sha256Hex("bp"))
        val inMemory = InMemoryBuildStore()
        for (build in fixtures()) {
            postgresStore.save(project.id, build)
            inMemory.save(project.id, build)
        }
        val rows = postgresStore.changeBlastRadius(project.id, 30, now)
        // Only EXECUTED task time counts as downstream (module != the changed one):
        //   :app changed in f-1 (downstream = :lib's 1000 executed) and f-2 (nothing EXECUTED → 0);
        //     samples {1000, 0} → median 500, changeCount 2, blastScore 1000.
        //   :lib changed in f-2 (nothing EXECUTED → 0) and f-3 (:app's 7000 executed → 7000);
        //     samples {0, 7000} → median 3500, changeCount 2, blastScore 7000.
        // So :lib (7000) ranks above :app (1000).
        assertTrue(rows.any { it.module == ":app" }, "expected :app in the rollup: $rows")
        assertTrue(rows.any { it.module == ":lib" }, "expected :lib in the rollup: $rows")
        assertEquals(":lib", rows.first().module, "the higher blast score ranks first: $rows")
        assertEquals(rows, inMemory.changeBlastRadius(project.id, 30, now), "in-memory and Postgres agree byte-for-byte")
    }

    @Test
    fun `plugin cost folds fixture types by owning-plugin prefix (all KotlinCompile here, so one bucket)`() {
        // fixtures() types are the bare label "KotlinCompile"/"Test"/"Lint" (no FQCN prefix), so they
        // all fold into the honest "(unattributed)" bucket — the parity test above is what proves the
        // FQCN-prefix fold itself agrees between stores; this pins the shape over these fixtures.
        val project = tokens.ensureProjectWithToken("plugin-cost-project", sha256Hex("pcp"))
        for (build in fixtures()) postgresStore.save(project.id, build)
        val rollup = postgresStore.pluginCost(project.id, 30, now)
        assertTrue(rollup.available, "every fixture task carries a type")
        assertEquals(listOf(PluginAttribution.UNATTRIBUTED), rollup.plugins.map { it.plugin })
    }

    @Test
    fun `plugin cost is the days-window, benchmark-INCLUDED convention — the taskDuration sibling, not bottlenecks' fleet-view exclusion`() {
        // Named per the plan-058 "Window-inclusion mismatch" risk: unlike topPlugins (which inherits
        // bottlenecks' benchmark-excluded fleet view), pluginCost deliberately reuses the
        // taskDuration/projectCost/negativeAvoidance convention, which does NOT exclude benchmark
        // builds. A regression that swapped pluginCost onto the benchmark-excluded window would still
        // pass every other test here (none of those fixtures include a benchmark build) — this pins it.
        val project = tokens.ensureProjectWithToken("plugin-cost-benchmark-project", sha256Hex("pcbp"))
        val benchmark = TestPayloads.build(
            buildId = "bench-pc", durationMs = 5_000, startedAt = recent, mode = BuildMode.BENCHMARK,
            tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile")),
        )
        postgresStore.save(project.id, benchmark)
        val inMemory = InMemoryBuildStore()
        inMemory.save(project.id, benchmark)

        val pg = postgresStore.pluginCost(project.id, 30, now)
        assertTrue(pg.available)
        assertEquals(listOf(PluginCostRow("Kotlin Gradle Plugin", 5000, 1, 1.0)), pg.plugins, "the benchmark build's task time must be included, not excluded")
        assertEquals(inMemory.pluginCost(project.id, 30, now), pg, "in-memory and Postgres must agree on benchmark inclusion")
    }

    @Test
    fun `a build older than the window is excluded`() {
        val project = tokens.ensureProjectWithToken("window-project", sha256Hex("wp"))
        val old = TestPayloads.build(
            buildId = "old-1", startedAt = now - 40L * 86_400_000, userId = "u_1",
            tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile")),
        )
        postgresStore.save(project.id, old)
        assertEquals(emptyList(), postgresStore.projectCost(project.id, 30, now), "40-day-old build is outside a 30-day window")
    }
}
