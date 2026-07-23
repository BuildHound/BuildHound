package dev.buildhound.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /v1/whoami` (plan 101): any valid token resolves to its own `{projectKey, scope}` — the
 * dashboard's persistence gate (only `read` tokens go to localStorage). Also pins the two auth
 * side-effects the dashboard relies on: `no-store` (the body names the tenant a live credential
 * belongs to) and first-use activation of a freshly minted token.
 */
class WhoamiRoutesTest {

    private class MutableInstantSource(private var current: Instant) : InstantSource {
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }

    private class Fx(val stores: ServerStores, val clock: MutableInstantSource)

    private fun fx(): Fx {
        val clock = MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"))
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore(clock))
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("all-token"))
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("admin-token"), TokenScope.ADMIN)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, clock)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    @Test
    fun `whoami requires a valid token`() = testApplication {
        val fx = fx(); appWith(fx)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/whoami").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/whoami") { header("Authorization", "Bearer nope") }.status,
        )
    }

    @Test
    fun `whoami returns the caller's own projectKey and scope for any scope`() = testApplication {
        val fx = fx(); appWith(fx)

        val matrix = listOf(
            "read-token" to "read",
            "all-token" to "all",
            "admin-token" to "admin",
            // The dashboard's rejection branch keys off this: an ingest token resolves fine here,
            // the client then refuses to store it because the scope can't read.
            "ingest-token" to "ingest",
        )
        for ((token, scope) in matrix) {
            val response = client.get("/v1/whoami") { header("Authorization", "Bearer $token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"projectKey\":\"pilot\""), body)
            assertTrue(body.contains("\"scope\":\"$scope\""), body)
        }
    }

    @Test
    fun `whoami responses are never cached`() = testApplication {
        val fx = fx(); appWith(fx)

        val response = client.get("/v1/whoami") { header("Authorization", "Bearer read-token") }

        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `whoami counts as first use and activates a freshly minted token`() = testApplication {
        val fx = fx(); appWith(fx)
        val minted = client.post("/v1/admin/tokens") {
            header("Authorization", "Bearer admin-token")
            contentType(ContentType.Application.Json)
            setBody("""{"scope":"read"}""")
        }.bodyAsText()
        val token = Regex("\"token\":\"([^\"]+)\"").find(minted)!!.groupValues[1]

        // The dashboard's token-entry whoami is the token's first use — inside the 6h window.
        assertEquals(HttpStatusCode.OK, client.get("/v1/whoami") { header("Authorization", "Bearer $token") }.status)

        fx.clock.advance(Duration.ofDays(30)) // long past the original 6h deadline

        val afterDeadline = client.get("/v1/builds") { header("Authorization", "Bearer $token") }

        assertEquals(HttpStatusCode.OK, afterDeadline.status, "whoami activation makes the deadline moot")
    }
}
