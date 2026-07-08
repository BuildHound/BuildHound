package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.ToolchainInfo
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres parity for the plan-032 bottlenecks + toolchain rollups: both stores fetch raw rows
 * for the two windows and defer to the shared [BottleneckCalculator]/[ToolchainCalculator], so their
 * output must agree byte-for-byte over the same fixtures — regressions, new/vanished flags, cache-miss
 * hotspots, KPI deltas, and toolchain distribution/behind. The window boundary excludes >2×period
 * builds from both windows. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BottleneckStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var tokens: PostgresTokenStore

    private val now = 2_000_000_000_000L
    private val current = now - 3_600_000 // inside the 7-day current window
    private val prior = now - 10L * 86_400_000 // inside the prior 7..14-day window
    private val ancient = now - 20L * 86_400_000 // older than 2×period → excluded from both windows

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        // Prior window: KotlinCompile @1000 (×2 builds → count 2), VanishedType @500 (only here).
        TestPayloads.build(
            buildId = "b-p1", durationMs = 4000, startedAt = prior, userId = "u_1", hitRate = 0.1,
            toolchain = ToolchainInfo(gradle = "8.9", jdk = "21"),
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 1000, type = "KotlinCompile"),
                TestPayloads.task(":app:vanish", TaskOutcome.EXECUTED, 500, type = "VanishedType"),
            ),
        ),
        TestPayloads.build(
            buildId = "b-p2", durationMs = 6000, startedAt = prior + 1000, userId = "u_2", outcome = BuildOutcome.FAILED, hitRate = 0.3,
            toolchain = ToolchainInfo(gradle = "8.10", jdk = "21"),
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 1000, type = "KotlinCompile"),
                TestPayloads.task(":app:vanish", TaskOutcome.EXECUTED, 500, type = "VanishedType"),
            ),
        ),
        // Current window: KotlinCompile regresses to 5000 (count 2), NewType only here, a cacheable
        // EXECUTED miss, an UP_TO_DATE cache hit slower than the executed median (negative avoidance).
        TestPayloads.build(
            buildId = "b-c1", durationMs = 8000, startedAt = current, userId = "u_1", hitRate = 0.7,
            toolchain = ToolchainInfo(gradle = "8.10", jdk = "21"),
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile"),
                TestPayloads.task(":app:new", TaskOutcome.EXECUTED, 800, type = "NewType"),
                TestPayloads.task(":app:res", TaskOutcome.EXECUTED, 1200, type = "CacheMissType", cacheable = true),
            ),
        ),
        TestPayloads.build(
            buildId = "b-c2", durationMs = 9000, startedAt = current + 1000, userId = null, hitRate = 0.9,
            toolchain = ToolchainInfo(gradle = "8.10", jdk = "21"),
            tasks = listOf(
                TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile"),
                TestPayloads.task(":app:new", TaskOutcome.EXECUTED, 800, type = "NewType"),
                // Executed median for KotlinCompile in this window is 5000; a 9000ms cache hit exceeds it.
                TestPayloads.task(":app:compileKotlin", TaskOutcome.FROM_CACHE, 9000, type = "KotlinCompile"),
            ),
        ),
        // A benchmark build in the current window — excluded from the fleet view in both stores.
        TestPayloads.build(
            buildId = "b-bench", durationMs = 30_000, startedAt = current + 2000, mode = BuildMode.BENCHMARK,
            toolchain = ToolchainInfo(gradle = "9.0", jdk = "21"),
            tasks = listOf(TestPayloads.task(":app:bench", TaskOutcome.EXECUTED, 30_000, type = "BenchType")),
        ),
        // Older than 2×period → outside both windows for period=7 (and outside a 30-day toolchain window? no,
        // 20 days < 30 — so it IS in a 30-day toolchain window; used to check the toolchain window instead).
        TestPayloads.build(
            buildId = "b-old", durationMs = 3000, startedAt = ancient, userId = "u_5",
            toolchain = ToolchainInfo(gradle = "7.6", jdk = "17"),
            tasks = listOf(TestPayloads.task(":app:old", TaskOutcome.EXECUTED, 1234, type = "OldOnlyType")),
        ),
    )

    private fun seedBoth(project: ProjectRef, inMemory: InMemoryBuildStore) {
        for (build in fixtures()) {
            postgresStore.save(project.id, build)
            inMemory.save(project.id, build)
        }
    }

    @Test
    fun `bottlenecks agree byte-for-byte across the two windows`() {
        val project = tokens.ensureProjectWithToken("bn-parity", sha256Hex("bnp"))
        val inMemory = InMemoryBuildStore()
        seedBoth(project, inMemory)
        assertEquals(
            inMemory.bottlenecks(project.id, 7, now),
            postgresStore.bottlenecks(project.id, 7, now),
            "bottlenecks rollup",
        )
    }

    @Test
    fun `toolchain adoption agrees byte-for-byte`() {
        val project = tokens.ensureProjectWithToken("tc-parity", sha256Hex("tcp"))
        val inMemory = InMemoryBuildStore()
        seedBoth(project, inMemory)
        assertEquals(
            inMemory.toolchainAdoption(project.id, 30, now),
            postgresStore.toolchainAdoption(project.id, 30, now),
            "toolchain rollup",
        )
    }

    @Test
    fun `the computed rollup carries the expected regressions, flags, and honest cache data`() {
        val project = tokens.ensureProjectWithToken("bn-shape", sha256Hex("bns"))
        val inMemory = InMemoryBuildStore()
        seedBoth(project, inMemory)
        val r = postgresStore.bottlenecks(project.id, 7, now)

        val kotlinCompile = r.regressedTasks.single { it.key == "KotlinCompile" }
        assertEquals(5000, kotlinCompile.currentMs)
        assertEquals(1000, kotlinCompile.priorMs)
        assertEquals(4000, kotlinCompile.deltaMs)
        assertTrue(r.regressedTasks.single { it.key == "NewType" }.isNew)
        assertTrue(r.regressedTasks.single { it.key == "VanishedType" }.isVanished)

        assertTrue(r.cacheDataAvailable, "a cacheable flag is present in the current window")
        assertEquals(setOf("CacheMissType"), r.cacheMissHotspots.map { it.key }.toSet())
        // The FROM_CACHE @9000 hit beats the executed median → negative avoidance for KotlinCompile.
        assertTrue(r.negativeAvoidance.any { it.key == "KotlinCompile" })

        // buildCount: 2 current (benchmark excluded) vs 2 prior. successRate: 1.0 now, 0.5 prior (one FAILED).
        assertEquals(2.0, r.buildCount.current)
        assertEquals(2.0, r.buildCount.prior)
        assertEquals(1.0, r.successRate.current)
        assertEquals(0.5, r.successRate.prior)

        assertTrue(r.slowestWork.none { it.key == "BenchType" }, "benchmark work is not a fleet bottleneck")
        assertTrue(
            r.regressedTasks.none { it.key == "OldOnlyType" } && r.slowestWork.none { it.key == "OldOnlyType" },
            "a 20-day-old build is outside both 7-day windows",
        )

        // Top plugins (plan 058): every fixture type here is a bare label ("KotlinCompile", "NewType",
        // …), not a real FQCN, so all current-window tasks fold into one honest "(unattributed)" row —
        // 5000+800+1200 (b-c1) + 5000+800+9000 (b-c2, incl. the FROM_CACHE hit — topPlugins folds every
        // outcome, like slowestWork) = 21800 over 6 tasks. A leaked benchmark/old-build task would
        // inflate this total, so the exact value doubles as the exclusion check.
        assertEquals(1, r.topPlugins.size, "$r")
        val onlyPlugin = r.topPlugins.single()
        assertEquals(PluginAttribution.UNATTRIBUTED, onlyPlugin.key)
        assertEquals(21800, onlyPlugin.currentMs)
        assertEquals(6, onlyPlugin.count)
    }

    @Test
    fun `toolchain distribution and behind list are computed and benchmark-excluded`() {
        val project = tokens.ensureProjectWithToken("tc-shape", sha256Hex("tcs"))
        val inMemory = InMemoryBuildStore()
        seedBoth(project, inMemory)
        val r = postgresStore.toolchainAdoption(project.id, 30, now)

        assertTrue(r.gradle.available)
        // 8.9(1), 8.10(3), 7.6(1) — benchmark 9.0 excluded. Latest observed = 8.10 → 8.9 and 7.6 behind.
        assertTrue(r.gradle.versions.none { it.version == "9.0" }, "benchmark toolchain is excluded")
        assertEquals(setOf("8.9", "7.6"), r.gradle.behind.map { it.version }.toSet())
        assertFalse(r.agp.available || r.kgp.available || r.ksp.available, "agp/kgp/ksp not collected yet")
    }

    @Test
    fun `the jdk dimension groups by major with a p50 duration — the daemon-JDK fleet comparison`() {
        val project = tokens.ensureProjectWithToken("tc-jdk", sha256Hex("tcj"))
        val inMemory = InMemoryBuildStore()
        seedBoth(project, inMemory)
        val r = postgresStore.toolchainAdoption(project.id, 30, now)

        // Non-benchmark builds carry jdk "21" ×4 (durations 4s/6s/8s/9s → nearest-rank p50 6s) and
        // jdk "17" ×1 (3s) — the benchmark build's jdk is excluded like the gradle dimension's.
        val jdk21 = r.jdk.versions.single { it.version == "21" }
        val jdk17 = r.jdk.versions.single { it.version == "17" }
        assertEquals(4, jdk21.builds)
        assertEquals(6000, jdk21.durationP50Ms, "plan-065 p50 per jdk major (Postgres duration_ms hot column)")
        assertEquals(3000, jdk17.durationP50Ms)
        assertEquals(listOf("17"), r.jdk.behind.map { it.version }, "behind works on majors")
        // Only the jdk dimension is fed durations — gradle rows honestly report no p50.
        assertTrue(r.gradle.versions.all { it.durationP50Ms == null })
        // And the widened jdk dimension keeps byte-for-byte store parity (the full-rollup parity
        // test above covers it too; re-asserted here so a jdk-only regression names this feature).
        assertEquals(inMemory.toolchainAdoption(project.id, 30, now).jdk, r.jdk)
    }

    @Test
    fun `benchmarkSeries slices by workersMax in both stores with byte parity`() {
        val project = tokens.ensureProjectWithToken("bn-workers", sha256Hex("bnw"))
        val inMemory = InMemoryBuildStore()
        // Benchmark iterations of one scenario: two at workersMax=8, one at 4, one without any.
        listOf(
            Triple("w-1", 3_000L, 8),
            Triple("w-2", 1_000L, 8),
            Triple("w-3", 2_000L, 4),
        ).forEachIndexed { index, (id, duration, workers) ->
            val payload = TestPayloads.build(
                buildId = id, durationMs = duration, startedAt = current + index * 1000L,
                mode = BuildMode.BENCHMARK,
                benchmark = dev.buildhound.commons.payload.BenchmarkInfo(scenario = "clean", iteration = index + 1),
                workersMax = workers,
            )
            postgresStore.save(project.id, payload)
            inMemory.save(project.id, payload)
        }
        val noWorkers = TestPayloads.build(
            buildId = "w-none", durationMs = 9_000, startedAt = current + 5000,
            mode = BuildMode.BENCHMARK,
            benchmark = dev.buildhound.commons.payload.BenchmarkInfo(scenario = "clean", iteration = 9),
        )
        postgresStore.save(project.id, noWorkers)
        inMemory.save(project.id, noWorkers)

        val pg = postgresStore.benchmarkSeries(project.id, null, null, null, 30, now, workersMax = 8)
        val mem = inMemory.benchmarkSeries(project.id, null, null, null, 30, now, workersMax = 8)
        assertEquals(mem, pg, "workersMax-sliced series must keep store parity")
        // Only w-1/w-2 match: a build without workersMax never matches a filter (honest, no guess).
        assertEquals(listOf("w-1", "w-2"), pg.single().points.map { it.buildId }.sorted())
        // Unfiltered keeps all four points.
        assertEquals(4, postgresStore.benchmarkSeries(project.id, null, null, null, 30, now).single().points.size)
    }
}
