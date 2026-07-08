package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
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
 * Real-Postgres checks for plan 060: `warnings` reads straight from `builds.payload->'tasks'` (no
 * `task_executions` column, no migration) and agrees byte-for-byte between the in-memory and Postgres
 * stores over identical fixtures covering all three rule families, a clean/full-rebuild build that
 * must be excluded from NON_INCREMENTAL_AP's denominator, a benchmark build (fleet-view exclusion,
 * cf. [RerunCauseStoresIntegrationTest]), and a build outside the window (boundary exclusion).
 * Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarningStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var inMemory: InMemoryBuildStore
    private lateinit var tokens: PostgresTokenStore
    private lateinit var projectId: String

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000
    private val period = 30

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        tokens = PostgresTokenStore(dataSource)
        projectId = tokens.ensureProjectWithToken("warnings", sha256Hex("w"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        // ALWAYS_RUN: 3 builds, a task that always executes with a matching reason.
        TestPayloads.build(
            buildId = "ar-1", durationMs = 100, startedAt = recent,
            tasks = listOf(TestPayloads.task(":app:customTask", TaskOutcome.EXECUTED, 100, executionReasons = listOf("Task.upToDateWhen is false."))),
        ),
        TestPayloads.build(
            buildId = "ar-2", durationMs = 100, startedAt = recent + 1000,
            tasks = listOf(TestPayloads.task(":app:customTask", TaskOutcome.EXECUTED, 100, executionReasons = listOf("Task.upToDateWhen is false."))),
        ),
        TestPayloads.build(
            buildId = "ar-3", durationMs = 100, startedAt = recent + 2000,
            tasks = listOf(TestPayloads.task(":app:customTask", TaskOutcome.EXECUTED, 100, executionReasons = listOf("Task.upToDateWhen is false."))),
        ),
        // NON_INCREMENTAL_AP: 3 non-clean builds where kapt is persistently non-incremental.
        TestPayloads.build(
            buildId = "ni-1", durationMs = 200, startedAt = recent + 3000,
            tasks = listOf(TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 200, incremental = false)),
        ),
        TestPayloads.build(
            buildId = "ni-2", durationMs = 200, startedAt = recent + 4000,
            tasks = listOf(TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 200, incremental = false)),
        ),
        TestPayloads.build(
            buildId = "ni-3", durationMs = 200, startedAt = recent + 5000,
            tasks = listOf(TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 200, incremental = false)),
        ),
        // A 4th kapt build that is a clean/full-rebuild (a cache-relevant sibling shows zero
        // avoidance) — must be EXCLUDED from NON_INCREMENTAL_AP's denominator.
        TestPayloads.build(
            buildId = "ni-clean", durationMs = 700, startedAt = recent + 6000,
            tasks = listOf(
                TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 200, incremental = false),
                TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 500, cacheable = true),
            ),
        ),
        // DYNAMIC_DEBUG_VALUES: 3 builds where the AGP manifest task never hits UP_TO_DATE.
        TestPayloads.build(
            buildId = "dd-1", durationMs = 80, startedAt = recent + 7000,
            tasks = listOf(TestPayloads.task(":app:processDebugManifest", TaskOutcome.EXECUTED, 80, type = "com.android.build.gradle.tasks.ManifestProcessorTask")),
        ),
        TestPayloads.build(
            buildId = "dd-2", durationMs = 80, startedAt = recent + 8000,
            tasks = listOf(TestPayloads.task(":app:processDebugManifest", TaskOutcome.EXECUTED, 80, type = "com.android.build.gradle.tasks.ManifestProcessorTask")),
        ),
        TestPayloads.build(
            buildId = "dd-3", durationMs = 80, startedAt = recent + 9000,
            tasks = listOf(TestPayloads.task(":app:processDebugManifest", TaskOutcome.EXECUTED, 80, type = "com.android.build.gradle.tasks.ManifestProcessorTask")),
        ),
        // A benchmark build carrying an ALWAYS_RUN-shaped task — both stores must exclude it.
        TestPayloads.build(
            buildId = "bench-1", durationMs = 100, startedAt = recent + 10000, mode = BuildMode.BENCHMARK,
            tasks = listOf(TestPayloads.task(":app:customTask", TaskOutcome.EXECUTED, 100, executionReasons = listOf("Task.upToDateWhen is false."))),
        ),
        // A build well outside the window — must not count toward any group's buildsObserved.
        TestPayloads.build(
            buildId = "old-1", durationMs = 100, startedAt = now - (period + 5).toLong() * 86_400_000,
            tasks = listOf(TestPayloads.task(":app:customTask", TaskOutcome.EXECUTED, 100, executionReasons = listOf("Task.upToDateWhen is false."))),
        ),
    )

    @Test
    fun `warnings agrees byte-for-byte between stores across all three rule families`() {
        val pg = postgresStore.warnings(projectId, period, now)
        val mem = inMemory.warnings(projectId, period, now)
        assertEquals(mem, pg, "warnings must agree byte-for-byte between stores")

        val alwaysRun = pg.warnings.single { it.category == WarningCategory.ALWAYS_RUN.name }
        assertEquals(3, alwaysRun.buildsObserved, "the benchmark and out-of-window builds must not count")
        assertEquals(3, alwaysRun.buildsAffected)
        assertEquals("Task.upToDateWhen is false.", alwaysRun.evidenceReason)

        val nonIncrementalAp = pg.warnings.single { it.category == WarningCategory.NON_INCREMENTAL_AP.name }
        assertEquals(3, nonIncrementalAp.buildsObserved, "the clean/full-rebuild build must be excluded from the denominator")
        assertEquals(3, nonIncrementalAp.buildsAffected)

        val dynamicDebug = pg.warnings.single { it.category == WarningCategory.DYNAMIC_DEBUG_VALUES.name }
        assertEquals(3, dynamicDebug.buildsObserved)
        assertEquals(3, dynamicDebug.buildsAffected)

        assertTrue(pg.typeDataAvailable, "the DYNAMIC_DEBUG_VALUES fixture carries a real AGP type")
    }

    @Test
    fun `the window boundary excludes an older build until the window widens enough to include it`() {
        // old-1 sits (period + 5) = 35 days back: outside a 30-day window, inside a 40-day one.
        val within30Days = postgresStore.warnings(projectId, 30, now)
        val within40Days = postgresStore.warnings(projectId, 40, now)
        assertEquals(inMemory.warnings(projectId, 30, now), within30Days, "byte-for-byte parity holds at the narrower window too")
        assertEquals(inMemory.warnings(projectId, 40, now), within40Days, "byte-for-byte parity holds at the wider window too")

        val alwaysRunAt30 = within30Days.warnings.single { it.category == WarningCategory.ALWAYS_RUN.name }
        val alwaysRunAt40 = within40Days.warnings.single { it.category == WarningCategory.ALWAYS_RUN.name }
        assertEquals(3, alwaysRunAt30.buildsObserved, "old-1 (35 days back) must be excluded from a 30-day window")
        assertEquals(4, alwaysRunAt40.buildsObserved, "old-1 must be included once the window widens past 35 days")
    }

    /**
     * Isolates `incremental` read-back specifically (a separate project so it doesn't interact with
     * [fixtures]'s all-`false` NON_INCREMENTAL_AP group). The main byte-for-byte test above never
     * exercises `incremental = true`, so it can't catch a stuck-to-`false` extraction bug — the exact
     * field `task_executions` lacks, i.e. the whole reason this route reads jsonb at all. If Postgres's
     * `(t ->> 'incremental')::boolean` extraction were broken (wrong key, stuck cast), Postgres would
     * read every occurrence as `incremental = false` and the rule would incorrectly fire, while
     * in-memory (reading the real payload) would not — `assertEquals` below would catch that divergence.
     */
    @Test
    fun `incremental is read back correctly — a genuinely incremental group must not fire, on both stores`() {
        val incrementalProjectId = tokens.ensureProjectWithToken("warnings-incremental", sha256Hex("wi"), TokenScope.ALL).id
        val builds = (1..3).map { i ->
            TestPayloads.build(
                buildId = "inc-$i", durationMs = 200, startedAt = recent + i * 1000L,
                tasks = listOf(TestPayloads.task(":app:kaptDebugKotlin", TaskOutcome.EXECUTED, 200, incremental = true)),
            )
        }
        builds.forEach { payload ->
            postgresStore.save(incrementalProjectId, payload)
            inMemory.save(incrementalProjectId, payload)
        }

        val pg = postgresStore.warnings(incrementalProjectId, period, now)
        val mem = inMemory.warnings(incrementalProjectId, period, now)
        assertEquals(mem, pg, "warnings must agree byte-for-byte for a genuinely-incremental group too")
        assertTrue(
            pg.warnings.none { it.category == WarningCategory.NON_INCREMENTAL_AP.name },
            "a group that is incremental=true on every occurrence must never fire NON_INCREMENTAL_AP: $pg",
        )
    }
}
