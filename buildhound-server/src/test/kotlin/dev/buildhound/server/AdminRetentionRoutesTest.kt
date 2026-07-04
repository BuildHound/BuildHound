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

/** `/v1/admin/retention` auth/scope/validation/tenant + defaults-on-absence (plan 042), no socket. */
class AdminRetentionRoutesTest {

    @Test
    fun `allowsAdmin accepts only the admin and all scopes`() {
        assertTrue(TokenScope.allowsAdmin(TokenScope.ADMIN))
        assertTrue(TokenScope.allowsAdmin(TokenScope.ALL))
        assertFalse(TokenScope.allowsAdmin(TokenScope.READ))
        assertFalse(TokenScope.allowsAdmin(TokenScope.INGEST))
        assertFalse(TokenScope.allowsAdmin(TokenScope.ADDON))
    }

    @Test
    fun `RetentionConfig validation catches out-of-range and inverted windows`() {
        assertEquals(null, RetentionConfig(90, 395).validationError())
        assertFalse(RetentionConfig(0, 395).validationError() == null)   // rawDays < 1
        assertFalse(RetentionConfig(90, 3651).validationError() == null) // buildDays > max
        assertFalse(RetentionConfig(200, 100).validationError() == null) // buildDays < rawDays
    }

    private class Fx(val stores: ServerStores)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("admin-token"), TokenScope.ADMIN)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        return Fx(stores)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.putRetention(json: String, token: String = "admin-token") =
        client.put("/v1/admin/retention") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(json)
        }

    @Test
    fun `the admin namespace requires an admin-scoped token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/retention").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/retention") { header("Authorization", "Bearer read-token") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/retention") { header("Authorization", "Bearer admin-token") }.status)
    }

    @Test
    fun `GET returns the spec defaults when the project has no row`() = testApplication {
        val fx = fx(); appWith(fx)
        val body = client.get("/v1/admin/retention") { header("Authorization", "Bearer admin-token") }.bodyAsText()
        assertTrue(body.contains("\"rawDays\":90"), body)
        assertTrue(body.contains("\"buildDays\":395"), body)
    }

    @Test
    fun `PUT persists a valid window and GET reads it back`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, putRetention("""{"rawDays":30,"buildDays":180}""").status)
        val body = client.get("/v1/admin/retention") { header("Authorization", "Bearer admin-token") }.bodyAsText()
        assertTrue(body.contains("\"rawDays\":30"), body)
        assertTrue(body.contains("\"buildDays\":180"), body)
    }

    @Test
    fun `PUT rejects invalid windows with 400 and a read token with 403`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.BadRequest, putRetention("""{"rawDays":0,"buildDays":180}""").status)
        assertEquals(HttpStatusCode.BadRequest, putRetention("""{"rawDays":200,"buildDays":100}""").status)
        assertEquals(HttpStatusCode.BadRequest, putRetention("not json").status)
        assertEquals(HttpStatusCode.Forbidden, putRetention("""{"rawDays":30,"buildDays":180}""", token = "read-token").status)
    }

    @Test
    fun `retention is tenant-scoped — one project's window is invisible to another`() = testApplication {
        val fx = fx(); appWith(fx)
        putRetention("""{"rawDays":30,"buildDays":180}""") // pilot
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-admin"), TokenScope.ADMIN)
        val body = client.get("/v1/admin/retention") { header("Authorization", "Bearer other-admin") }.bodyAsText()
        // The other tenant still sees the defaults, never pilot's 30/180.
        assertTrue(body.contains("\"rawDays\":90"), body)
    }

    @Test
    fun `setting retention does not clobber a project's regression settings and vice versa`() {
        // Store-level: the two accessors share a row but never overwrite each other's columns.
        val store = InMemorySettingsStore()
        store.put("p", ProjectSettings(baselineN = 42))
        store.setRetention("p", RetentionConfig(30, 180))
        assertEquals(42, store.get("p")!!.baselineN)
        assertEquals(30, store.retention("p").rawDays)
    }
}
