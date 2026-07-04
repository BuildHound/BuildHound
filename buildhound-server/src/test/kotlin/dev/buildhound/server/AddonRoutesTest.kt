package dev.buildhound.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/v1/addons/{addonId}` auth/scope/allowlist/tenant + jsonb round-trip (plan 039), no socket. */
class AddonRoutesTest {

    private val addonId = "test-sharding"

    @Test
    fun `allowsAddon accepts only the addon and all scopes`() {
        assertTrue(TokenScope.allowsAddon(TokenScope.ADDON))
        assertTrue(TokenScope.allowsAddon(TokenScope.ALL))
        assertFalse(TokenScope.allowsAddon(TokenScope.READ))
        assertFalse(TokenScope.allowsAddon(TokenScope.INGEST))
    }

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(
            InMemoryBuildStore(), InMemoryTokenStore(),
            addons = InMemoryAddonStore(),
            registeredAddons = setOf(addonId),
        )
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("addon-token"), TokenScope.ADDON)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.putData(key: String, json: String, token: String = "addon-token") =
        client.put("/v1/addons/$addonId/data/$key") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(json)
        }

    private suspend fun ApplicationTestBuilder.getData(key: String, token: String = "addon-token") =
        client.get("/v1/addons/$addonId/data/$key") { header("Authorization", "Bearer $token") }

    @Test
    fun `the addon namespace requires an addon-scoped token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/addons/$addonId/data").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/addons/$addonId/data") { header("Authorization", "Bearer read-token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/addons/$addonId/data") { header("Authorization", "Bearer ingest-token") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/addons/$addonId/data") { header("Authorization", "Bearer addon-token") }.status)
    }

    @Test
    fun `an unregistered addon id is a flat 404`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/addons/not-a-real-addon/data") { header("Authorization", "Bearer addon-token") }.status,
        )
    }

    @Test
    fun `a jsonb value round-trips through put then get`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, putData("shard-plan", """{"shards":4,"ref":"main"}""").status)
        val body = getData("shard-plan").bodyAsText()
        assertTrue(body.contains("\"shards\":4"), body)
        assertTrue(body.contains("\"ref\":\"main\""), body)
        // Absent key is a 404, not an empty 200.
        assertEquals(HttpStatusCode.NotFound, getData("no-such-key").status)
    }

    @Test
    fun `a malformed key is rejected and an invalid json body is a 400`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.BadRequest, putData("bad key!", """{"x":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, putData("good-key", "not json").status)
    }

    @Test
    fun `addon data is tenant-isolated`() = testApplication {
        val fx = fx(); appWith(fx)
        putData("shard-plan", """{"shards":4}""")
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-addon"), TokenScope.ADDON)
        // Another tenant's addon token sees none of pilot's data.
        val otherAll = client.get("/v1/addons/$addonId/data") { header("Authorization", "Bearer other-addon") }
        assertEquals("{}", otherAll.bodyAsText().trim())
        assertEquals(HttpStatusCode.NotFound, getData("shard-plan", token = "other-addon").status)
    }
}
