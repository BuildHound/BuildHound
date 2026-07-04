package dev.buildhound.server

import dev.buildhound.server.connector.CiRun
import dev.buildhound.server.connector.CiRunStatus
import dev.buildhound.server.connector.CiSpan
import dev.buildhound.server.connector.PostgresCiSpanStore
import dev.buildhound.server.connector.SpanKind
import dev.buildhound.server.connector.SpanResult
import java.util.UUID
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
 * Real-Postgres checks for the plan-028 `ci_runs` table: the jsonb tree round-trips (incl. the
 * server-only `workerName`), `saveRun` is an idempotent upsert on `(project, build)`, and `findRun`
 * is tenant-scoped. Runs against plain Postgres so the migration's guarded TimescaleDB block still
 * executes. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CiSpanStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var store: PostgresCiSpanStore
    private lateinit var projectId: String

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        store = PostgresCiSpanStore(dataSource)
        projectId = PostgresTokenStore(dataSource)
            .ensureProjectWithToken("ci-span", sha256Hex("t"), TokenScope.ALL).id
    }

    @Test
    fun `saveRun round-trips the tree, status, and worker name`() {
        val run = CiRun(
            spans = listOf(
                CiSpan("r1", SpanKind.STAGE, "Build", startMs = 0, finishMs = 300, result = SpanResult.SUCCEEDED, workerName = "agent-7"),
                CiSpan("r2", SpanKind.STEP, "Gradle", startMs = 5, finishMs = 200, result = SpanResult.FAILED, parentId = "r1"),
            ),
            queuedMs = 5000, startedAt = 1000, finishedAt = 301000,
        )
        store.saveRun(projectId, "b1", "azure-devops", "42", run, CiRunStatus.OK)

        val stored = store.findRun(projectId, "b1")!!
        assertEquals(CiRunStatus.OK, stored.status)
        assertEquals(2, stored.run!!.spans.size)
        assertEquals(5000, stored.run.queuedMs)
        assertEquals("agent-7", stored.run.spans.single { it.id == "r1" }.workerName)
        assertEquals(SpanResult.FAILED, stored.run.spans.single { it.id == "r2" }.result)
    }

    @Test
    fun `saveRun is an idempotent upsert on project and build`() {
        store.saveRun(
            projectId, "b2", "azure-devops", "42",
            CiRun(spans = listOf(CiSpan("r1", SpanKind.STAGE, "a")), startedAt = 0, finishedAt = null),
            CiRunStatus.PENDING,
        )
        // Second save of the same (project, build) — a completed re-fetch — overwrites, never duplicates.
        store.saveRun(
            projectId, "b2", "azure-devops", "42",
            CiRun(spans = listOf(CiSpan("r1", SpanKind.STAGE, "a"), CiSpan("r2", SpanKind.STEP, "b")), startedAt = 0, finishedAt = 10),
            CiRunStatus.OK,
        )
        val stored = store.findRun(projectId, "b2")!!
        assertEquals(CiRunStatus.OK, stored.status)
        assertEquals(2, stored.run!!.spans.size)
    }

    @Test
    fun `an UNCONFIGURED run stores a null tree`() {
        store.saveRun(projectId, "b3", "azure-devops", null, run = null, status = CiRunStatus.UNCONFIGURED)
        val stored = store.findRun(projectId, "b3")!!
        assertEquals(CiRunStatus.UNCONFIGURED, stored.status)
        assertNull(stored.run)
    }

    @Test
    fun `findRun is tenant-scoped`() {
        store.saveRun(projectId, "b4", "azure-devops", "42", CiRun(finishedAt = 1), CiRunStatus.OK)
        assertNull(store.findRun(UUID.randomUUID().toString(), "b4"))
    }
}
