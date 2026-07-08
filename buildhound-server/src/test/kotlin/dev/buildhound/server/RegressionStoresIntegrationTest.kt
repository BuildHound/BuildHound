package dev.buildhound.server

import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-database checks for the plan-025 migration + stores (architecture §5: Testcontainers).
 * Skipped without Docker; CI has it. Exercises the V3 hot columns, the baseline-window SQL, metric
 * upsert idempotency + lazy correlation, verdict persistence, and settings jsonb round-trips.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegressionStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var builds: PostgresBuildStore
    private lateinit var tokens: PostgresTokenStore
    private lateinit var metrics: PostgresMetricStore
    private lateinit var verdicts: PostgresVerdictStore
    private lateinit var settings: PostgresSettingsStore

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        builds = PostgresBuildStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
        metrics = PostgresMetricStore(dataSource)
        verdicts = PostgresVerdictStore(dataSource)
        settings = PostgresSettingsStore(dataSource)
    }

    @Test
    fun `baseline window matches on the hot columns and excludes the candidate, PR, and non-success`() {
        val project = tokens.ensureProjectWithToken("baseline-project", sha256Hex("bp"))
        // Six SUCCESS main builds matching the key, plus a PR build and a FAILED build that must not count.
        repeat(6) { i -> builds.save(project.id, TestPayloads.build(buildId = "b-$i", durationMs = 1000, startedAt = 1_000_000L + i * 1000)) }
        builds.save(project.id, TestPayloads.build(buildId = "pr", durationMs = 1000, branch = "feature/x", startedAt = 1_100_000L))
        builds.save(project.id, TestPayloads.build(buildId = "failed", durationMs = 1000, outcome = dev.buildhound.commons.payload.BuildOutcome.FAILED, startedAt = 1_200_000L))

        val sig = RegressionEngine.requestedTasksSignature(listOf("build"))
        val window = builds.baselineWindow(project.id, "main", BaselineQuery("android-ci", sig, "CI"), excludingBuildId = "b-0", n = 20)

        assertEquals(5, window.size, "excludes the candidate, the PR build, and the failed build")
        assertTrue(window.all { it.durationMs == 1000L })
        assertTrue(window.all { it.hitRate == null }, "these fixtures carry no hit rate")
    }

    @Test
    fun `baseline window excludes rerunTasks and refreshDependencies builds and agrees with the in-memory store`() {
        val project = tokens.ensureProjectWithToken("baseline-hygiene-project", sha256Hex("bhp"))
        val inMemory = InMemoryBuildStore()
        fun seed(payload: dev.buildhound.commons.payload.BuildPayload) {
            builds.save(project.id, payload)
            inMemory.save(project.id, payload)
        }

        repeat(5) { i -> seed(TestPayloads.build(buildId = "h-$i", durationMs = 1000, startedAt = 1_000_000L + i * 1000)) }
        seed(
            TestPayloads.build(
                buildId = "rerun",
                durationMs = 999_000,
                startedAt = 2_000_000L,
                invocation = dev.buildhound.commons.payload.InvocationInfo(rerunTasks = true),
            ),
        )
        seed(
            TestPayloads.build(
                buildId = "refresh",
                durationMs = 999_000,
                startedAt = 3_000_000L,
                invocation = dev.buildhound.commons.payload.InvocationInfo(refreshDependencies = true),
            ),
        )
        // No environment/invocation at all — absence must not be mistaken for a rerun.
        seed(TestPayloads.build(buildId = "no-invocation", durationMs = 1000, startedAt = 4_000_000L))

        val sig = RegressionEngine.requestedTasksSignature(listOf("build"))
        val query = BaselineQuery("android-ci", sig, "CI")
        val pgWindow = builds.baselineWindow(project.id, "main", query, excludingBuildId = "candidate", n = 20)
        val memWindow = inMemory.baselineWindow(project.id, "main", query, excludingBuildId = "candidate", n = 20)

        assertEquals(6, pgWindow.size, "5 normal + the no-invocation build; rerun/refresh must be excluded")
        assertTrue(pgWindow.all { it.durationMs == 1000L })
        // plan-025 parity oracle: in-memory and Postgres must agree byte-for-byte (same order too —
        // both sort newest-first on startedAt).
        assertEquals(memWindow, pgWindow)
    }

    @Test
    fun `resolveBuildId finds the newest build for a provider and run id`() {
        val project = tokens.ensureProjectWithToken("correlate-project", sha256Hex("cp"))
        builds.save(project.id, TestPayloads.build(buildId = "old", provider = "azure-devops", runId = "run-9", startedAt = 1_000L))
        builds.save(project.id, TestPayloads.build(buildId = "new", provider = "azure-devops", runId = "run-9", startedAt = 2_000L))
        assertEquals("new", builds.resolveBuildId(project.id, "azure-devops", "run-9"))
        assertNull(builds.resolveBuildId(project.id, "azure-devops", "nope"))
    }

    @Test
    fun `metric upsert is idempotent and lazily correlates by run`() {
        val project = tokens.ensureProjectWithToken("metric-project", sha256Hex("mp"))
        // Posted before the build exists → null build_id, provider/run kept.
        metrics.upsert(project.id, MetricRecord(scope = "apk", name = "size", value = 10.0, provider = "azure-devops", runId = "r-1"))
        metrics.upsert(project.id, MetricRecord(scope = "apk", name = "size", value = 12.0, provider = "azure-devops", runId = "r-1"))
        assertEquals(setOf("apk size"), metrics.correlationKeys(project.id, null, "azure-devops", "r-1"))

        metrics.correlate(project.id, "azure-devops", "r-1", "the-build")
        val forBuild = metrics.forBuild(project.id, "the-build")
        assertEquals(1, forBuild.size, "the re-post upserted, not duplicated")
        assertEquals(12.0, forBuild.single().value, "the later value wins")
    }

    @Test
    fun `verdicts persist with their detail and expose the latest status per key`() {
        val project = tokens.ensureProjectWithToken("verdict-project", sha256Hex("vp"))
        val key = "android-ci|sig|main|CI"
        val metricsDetail = listOf(MetricVerdict("durationMs", 5000.0, 1000.0, 20.0, 40.0, null, VerdictStatus.FAIL.name))
        verdicts.save(project.id, "v-1", Verdict(VerdictStatus.FAIL.name, metricsDetail, key))
        val loaded = verdicts.find(project.id, "v-1")
        assertNotNull(loaded)
        assertEquals(VerdictStatus.FAIL.name, loaded.status)
        assertEquals("durationMs", loaded.metrics.single().name)
        assertNotNull(loaded.evaluatedAt)

        verdicts.save(project.id, "v-2", Verdict(VerdictStatus.PASS.name, emptyList(), key))
        assertEquals(VerdictStatus.PASS.name, verdicts.latestStatusForKey(project.id, key, excludingBuildId = "v-999"))
        assertEquals(VerdictStatus.FAIL.name, verdicts.latestStatusForKey(project.id, key, excludingBuildId = "v-2"))
    }

    @Test
    fun `settings round-trip budgets and alert channels as jsonb`() {
        val project = tokens.ensureProjectWithToken("settings-project", sha256Hex("sp"))
        assertNull(settings.get(project.id), "no row → null (caller uses defaults)")

        val cfg = ProjectSettings(
            baselineN = 15, defaultBranch = "trunk", warnZ = 3.0, failZ = 6.0,
            budgets = mapOf("durationMs" to 5000.0, "apk.size" to 90.0),
            alertChannels = listOf(AlertChannel("slack", "https://hooks.slack.com/x")),
        )
        settings.put(project.id, cfg)
        assertEquals(cfg, settings.get(project.id), "jsonb round trip must be lossless")

        val updated = cfg.copy(baselineN = 30)
        settings.put(project.id, updated)
        assertEquals(30, settings.get(project.id)?.baselineN, "put upserts")
    }

    @Test
    fun `retention defaults on absence, upserts, and shares the row without clobbering regression columns`() {
        val project = tokens.ensureProjectWithToken("retention-project", sha256Hex("rp"))
        // No row yet → spec defaults (90/395).
        assertEquals(RetentionConfig.DEFAULT, settings.retention(project.id))

        // setRetention on a project with no row creates it with regression columns at their defaults.
        settings.setRetention(project.id, RetentionConfig(30, 180))
        assertEquals(RetentionConfig(30, 180), settings.retention(project.id))

        // A later regression put must not touch the retention columns…
        settings.put(project.id, ProjectSettings(baselineN = 15))
        assertEquals(RetentionConfig(30, 180), settings.retention(project.id), "put must not clobber retention")

        // …and a retention update must not touch the regression columns.
        settings.setRetention(project.id, RetentionConfig(45, 365))
        assertEquals(15, settings.get(project.id)?.baselineN, "setRetention must not clobber regression settings")
        assertEquals(RetentionConfig(45, 365), settings.retention(project.id), "setRetention upserts")
    }
}
