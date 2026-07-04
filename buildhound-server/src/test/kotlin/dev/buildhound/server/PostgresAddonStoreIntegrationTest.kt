package dev.buildhound.server

import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-Postgres addon jsonb storage (plan 039): the `V8__addon_data` migration runs on a clean DB,
 * jsonb values round-trip, upsert overwrites, and tenants are isolated. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresAddonStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var store: PostgresAddonStore
    private lateinit var tokens: PostgresTokenStore

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource) // includes V8__addon_data on a clean DB
        store = PostgresAddonStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    @Test
    fun `a jsonb value round-trips and upsert overwrites`() {
        val project = tokens.ensureProjectWithToken("addon-rt", sha256Hex("art"))
        store.put(project.id, "test-sharding", "shard-plan", buildJsonObject { put("shards", JsonPrimitive(4)) })
        assertEquals(4, store.get(project.id, "test-sharding", "shard-plan")!!.jsonObject.getValue("shards").jsonPrimitive.content.toInt())

        // Same key upserts (PK on (project, addon, key)).
        store.put(project.id, "test-sharding", "shard-plan", buildJsonObject { put("shards", JsonPrimitive(8)) })
        assertEquals(8, store.get(project.id, "test-sharding", "shard-plan")!!.jsonObject.getValue("shards").jsonPrimitive.content.toInt())
        assertEquals(setOf("shard-plan"), store.all(project.id, "test-sharding").keys)

        assertNull(store.get(project.id, "test-sharding", "absent"))
    }

    @Test
    fun `addon data is tenant-isolated and addon-scoped`() {
        val a = tokens.ensureProjectWithToken("addon-a", sha256Hex("aa"))
        val b = tokens.ensureProjectWithToken("addon-b", sha256Hex("ab"))
        store.put(a.id, "test-sharding", "k", JsonPrimitive("a-value"))
        // Tenant B sees nothing of A; and a different addon id under A sees nothing either.
        assertNull(store.get(b.id, "test-sharding", "k"))
        assertNull(store.get(a.id, "other-addon", "k"))
        assertEquals(emptyMap(), store.all(b.id, "test-sharding"))
    }
}
