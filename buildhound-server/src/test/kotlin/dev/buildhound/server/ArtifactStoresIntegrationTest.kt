package dev.buildhound.server

import dev.buildhound.commons.payload.ArtifactSize
import dev.buildhound.commons.payload.ArtifactSizes
import dev.buildhound.commons.payload.ArtifactType
import dev.buildhound.commons.payload.BuildPayload
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres checks for plan 031: ingest writes `apk_sizes` rows (once per build — a duplicate
 * adds none), and the jsonb-free `artifactTrends` rollup agrees with the in-memory store. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArtifactStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var postgresStore: PostgresBuildStore
    private lateinit var inMemory: InMemoryBuildStore
    private lateinit var projectId: String

    private val now = 2_000_000_000_000L
    private val recent = now - 3_600_000

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        postgresStore = PostgresBuildStore(dataSource)
        inMemory = InMemoryBuildStore()
        projectId = PostgresTokenStore(dataSource).ensureProjectWithToken("artifacts", sha256Hex("t"), TokenScope.ALL).id
        fixtures().forEach { payload ->
            postgresStore.save(projectId, payload)
            inMemory.save(projectId, payload)
        }
    }

    private fun fixtures(): List<BuildPayload> = listOf(
        artifactBuild("a-1", 0, listOf(art(":app", "release", ArtifactType.APK, 8_000), art(":app", "release", ArtifactType.AAB, 6_000))),
        artifactBuild("a-2", 1, listOf(art(":app", "release", ArtifactType.APK, 9_000))),
        artifactBuild("l-1", 2, listOf(art(":lib", "release", ArtifactType.AAR, 200))),
        // A plain build without artifacts must contribute no apk_sizes rows.
        TestPayloads.build(buildId = "plain", startedAt = recent),
    )

    private fun art(module: String, variant: String, type: ArtifactType, size: Long) = ArtifactSize(variant, module, type, size)
    private fun artifactBuild(id: String, i: Int, list: List<ArtifactSize>) =
        TestPayloads.build(buildId = id, startedAt = recent + i * 1000L, artifacts = ArtifactSizes(android = list))

    @Test
    fun `artifact trends match the in-memory store byte-for-byte`() {
        val pg = postgresStore.artifactTrends(projectId, BuildFilter(), 30, now)
        val mem = inMemory.artifactTrends(projectId, BuildFilter(), 30, now)
        assertEquals(mem, pg, "Postgres apk_sizes rollup must equal the in-memory rollup")

        val appApk = pg.single { it.module == ":app" && it.type == "APK" }
        assertEquals(8_500, appApk.avgSizeBytes) // (8000 + 9000)/2
        assertEquals(9_000, appApk.maxSizeBytes)
        assertEquals(2, appApk.builds)
    }

    @Test
    fun `a duplicate build inserts no additional artifact rows`() {
        // Its own project so it never pollutes the parity fixtures (shared PER_CLASS instance).
        val dupProject = PostgresTokenStore(dataSource).ensureProjectWithToken("dup-proj", sha256Hex("d"), TokenScope.ALL).id
        val dup = artifactBuild("dup", 3, listOf(art(":app", "debug", ArtifactType.APK, 1_000)))
        assertEquals(true, postgresStore.save(dupProject, dup))
        assertEquals(false, postgresStore.save(dupProject, dup)) // ON CONFLICT DO NOTHING on the build
        val debug = postgresStore.artifactTrends(dupProject, BuildFilter(), 30, now).single { it.variant == "debug" }
        assertEquals(1, debug.builds, "re-ingest must not duplicate apk_sizes rows")
    }
}
