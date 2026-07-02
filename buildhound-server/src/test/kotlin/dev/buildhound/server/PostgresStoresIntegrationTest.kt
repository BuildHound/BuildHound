package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-database round trip (architecture §5: Testcontainers). Skipped where Docker is
 * unavailable (dev sandboxes); CI runners have it. Runs against plain Postgres, which
 * also exercises the migration's guarded TimescaleDB block.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var builds: PostgresBuildStore
    private lateinit var tokens: PostgresTokenStore

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        builds = PostgresBuildStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    private fun payload(buildId: String) = BuildHoundJson.payload.decodeFromString(
        BuildPayload.serializer(),
        """
        {
          "schemaVersion": 1,
          "buildId": "$buildId",
          "startedAt": 1751450000000,
          "finishedAt": 1751450042000,
          "outcome": "SUCCESS",
          "mode": "ci",
          "vcs": {"branch": "main"},
          "derived": {"cacheableHitRate": 0.5}
        }
        """.trimIndent(),
    )

    @Test
    fun `bootstrap is idempotent and tokens resolve by hash`() {
        val first = tokens.ensureProjectWithToken("pilot", sha256Hex("secret-1"))
        val second = tokens.ensureProjectWithToken("pilot", sha256Hex("secret-1"))

        assertEquals(first.id, second.id, "bootstrap must be idempotent")
        assertEquals("pilot", tokens.resolveProject(sha256Hex("secret-1"))?.key)
        assertNull(tokens.resolveProject(sha256Hex("wrong")), "unknown hash resolves nothing")
    }

    @Test
    fun `builds round trip with tenant isolation and dedupe`() {
        val projectA = tokens.ensureProjectWithToken("team-a", sha256Hex("a"))
        val projectB = tokens.ensureProjectWithToken("team-b", sha256Hex("b"))
        val build = payload("round-trip-1")

        assertTrue(builds.save(projectA.id, build))
        assertFalse(builds.save(projectA.id, build), "same tenant + buildId dedupes")
        assertTrue(builds.save(projectB.id, build), "other tenant is independent")

        val loaded = builds.findById(projectA.id, "round-trip-1")
        assertEquals(build, loaded, "jsonb round trip must be lossless")
        assertNull(builds.findById(projectA.id, "missing"))
        assertEquals(1, builds.count(projectA.id))
    }
}
