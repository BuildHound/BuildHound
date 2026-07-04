package dev.buildhound.server

import dev.buildhound.commons.payload.TaskOutcome
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres retention purge (plan 042): `purgeOlderThan` deletes builds + their raw task rows past
 * the windows and nothing newer, is tenant-scoped, and the batched delete completes for a large
 * fixture. Runs the V10 ALTER on a clean DB. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionPurgeIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var builds: PostgresBuildStore
    private lateinit var tokens: PostgresTokenStore

    private val now = 2_000_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource) // includes V10__retention
        builds = PostgresBuildStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    private fun seed(projectId: String, buildId: String, ageDays: Long, withTask: Boolean) {
        val started = now - ageDays * dayMs
        val tasks = if (withTask) {
            listOf(TestPayloads.task(path = ":app:compile", outcome = TaskOutcome.EXECUTED, durationMs = 100))
        } else {
            emptyList()
        }
        builds.save(projectId, TestPayloads.build(buildId = buildId, startedAt = started, tasks = tasks))
    }

    private fun rawRowCount(projectId: String): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM task_executions WHERE project_id = ?::uuid").use { st ->
                st.setString(1, projectId)
                st.executeQuery().use { it.next(); it.getInt(1) }
            }
        }

    @Test
    fun `purge deletes builds and raw rows past the windows and keeps newer ones, tenant-scoped`() {
        val p = tokens.ensureProjectWithToken("retp", sha256Hex("rp")).id
        val q = tokens.ensureProjectWithToken("retq", sha256Hex("rq")).id
        seed(p, "p-old", ageDays = 200, withTask = true)   // build+raw past both windows
        seed(p, "p-fresh", ageDays = 10, withTask = true)  // within both → kept
        seed(q, "q-old", ageDays = 200, withTask = true)   // another tenant → untouched

        val buildCutoff = now - 100 * dayMs
        val purged = builds.purgeOlderThan(p, buildCutoff, buildCutoff)

        assertEquals(1L, purged.builds, "one build past the window")
        assertEquals(1L, purged.rawRows, "its one raw task row")
        assertNull(builds.findById(p, "p-old"))
        assertEquals("p-fresh", builds.findById(p, "p-fresh")?.buildId)
        assertEquals(1, rawRowCount(p), "only the fresh build's raw row remains for p")
        // Tenant isolation: q's old build and raw row are untouched.
        assertEquals("q-old", builds.findById(q, "q-old")?.buildId)
        assertEquals(1, rawRowCount(q))
    }

    @Test
    fun `raw rows purge on their own shorter window while the build survives`() {
        val p = tokens.ensureProjectWithToken("retsplit", sha256Hex("rs")).id
        seed(p, "s1", ageDays = 60, withTask = true) // build kept (395d window), raw purged (30d window)

        val buildCutoff = now - 395 * dayMs
        val rawCutoff = now - 30 * dayMs
        val purged = builds.purgeOlderThan(p, buildCutoff, rawCutoff)

        assertEquals(0L, purged.builds, "the 60d build is within the 395d build window")
        assertEquals(1L, purged.rawRows, "its raw row is past the 30d raw window")
        assertEquals("s1", builds.findById(p, "s1")?.buildId, "the build row survives")
        assertEquals(0, rawRowCount(p), "the raw row is gone")
    }

    @Test
    fun `batched purge completes for a fixture larger than one batch`() {
        val p = tokens.ensureProjectWithToken("retbig", sha256Hex("rb")).id
        repeat(120) { seed(p, "big-$it", ageDays = 500, withTask = false) }
        val purged = builds.purgeOlderThan(p, now - 100 * dayMs, now - 100 * dayMs)
        assertEquals(120L, purged.builds)
        assertEquals(0L, builds.count(p))
    }
}
