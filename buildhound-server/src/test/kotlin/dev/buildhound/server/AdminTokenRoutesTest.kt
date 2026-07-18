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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /v1/admin/tokens` (plan 097): auth/scope matrix, response shape, the minted plaintext
 * authenticating ingest, and the 6-hour activation window — first-use activation, the auth-path
 * deadline rejection (defense-in-depth, independent of the sweep), and the bootstrap-token exemption.
 * The clock is injected via [InMemoryTokenStore]'s [InstantSource] so the deadline moves without a
 * real sleep. The sweep itself (store-level `deleteExpiredUnactivatedTokens`) is covered here too;
 * Postgres-layer parity lives in [PostgresStoresIntegrationTest].
 */
class AdminTokenRoutesTest {

    private class MutableInstantSource(private var current: Instant) : InstantSource {
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }

    private class Fx(val stores: ServerStores, val clock: MutableInstantSource)

    private fun fx(): Fx {
        val clock = MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"))
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore(clock))
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("admin-token"), TokenScope.ADMIN)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        return Fx(stores, clock)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private fun payloadJson(buildId: String) = """
        {
          "schemaVersion": 1,
          "buildId": "$buildId",
          "startedAt": 1,
          "finishedAt": 2,
          "outcome": "SUCCESS",
          "mode": "ci"
        }
    """.trimIndent()

    private fun mintedToken(body: String): String =
        Regex("\"token\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private suspend fun ApplicationTestBuilder.ingestWith(token: String, buildId: String) =
        client.post("/v1/builds") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson(buildId))
        }

    @Test
    fun `minting requires an admin-scoped token`() = testApplication {
        val fx = fx(); appWith(fx)

        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/tokens").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/v1/admin/tokens") { header("Authorization", "Bearer read-token") }.status,
        )
    }

    @Test
    fun `mint returns 201 with an ingest-scope token and an ISO-8601 deadline`() = testApplication {
        val fx = fx(); appWith(fx)

        val response = client.post("/v1/admin/tokens") { header("Authorization", "Bearer admin-token") }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"scope\":\"ingest\""), body)
        // 6h after the fixture's fixed clock start — proves the store's clock, not wall time, was used.
        assertTrue(body.contains("\"expiresUnusedAt\":\"2026-01-01T06:00:00Z\""), body)
        assertTrue(Regex("\"token\":\"[A-Za-z0-9_-]{20,}\"").containsMatchIn(body), body)
    }

    @Test
    fun `the minted plaintext authenticates POST v1 builds`() = testApplication {
        val fx = fx(); appWith(fx)
        val minted = client.post("/v1/admin/tokens") { header("Authorization", "Bearer admin-token") }.bodyAsText()

        val ingest = ingestWith(mintedToken(minted), "minted-1")

        assertEquals(HttpStatusCode.Accepted, ingest.status)
    }

    @Test
    fun `an unactivated token past its deadline is rejected even without the sweep having run`() = testApplication {
        val fx = fx(); appWith(fx)
        val minted = client.post("/v1/admin/tokens") { header("Authorization", "Bearer admin-token") }.bodyAsText()
        val token = mintedToken(minted)

        fx.clock.advance(Duration.ofHours(6).plusSeconds(1)) // past the deadline, never used

        val ingest = ingestWith(token, "minted-2")

        assertEquals(HttpStatusCode.Unauthorized, ingest.status, "auth-path predicate rejects it independent of the sweep")
    }

    @Test
    fun `an activated token keeps working long past its original deadline`() = testApplication {
        val fx = fx(); appWith(fx)
        val minted = client.post("/v1/admin/tokens") { header("Authorization", "Bearer admin-token") }.bodyAsText()
        val token = mintedToken(minted)

        // First use, well inside the 6h window — flips activated_at.
        assertEquals(HttpStatusCode.Accepted, ingestWith(token, "minted-3").status)

        fx.clock.advance(Duration.ofDays(30)) // long past the original 6h deadline

        val second = ingestWith(token, "minted-4")

        assertEquals(HttpStatusCode.Accepted, second.status, "activation makes the original deadline moot")
    }

    @Test
    fun `a bootstrap token with no activation deadline is unaffected by the clock`() = testApplication {
        val fx = fx(); appWith(fx)

        fx.clock.advance(Duration.ofDays(3650))

        val response = client.get("/v1/admin/retention") { header("Authorization", "Bearer admin-token") }

        assertEquals(HttpStatusCode.OK, response.status, "NULL expires_unused_at means no deadline, ever")
    }

    @Test
    fun `sweep deletes only expired unactivated tokens`() {
        val clock = MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"))
        val tokens = InMemoryTokenStore(clock)
        val project = tokens.ensureProjectWithToken("pilot", sha256Hex("bootstrap"))
        tokens.mintToken(project.id, sha256Hex("never-used"), TokenScope.INGEST)
        tokens.mintToken(project.id, sha256Hex("activated"), TokenScope.INGEST)
        assertNotNull(tokens.resolve(sha256Hex("activated")), "activates on first resolve")

        clock.advance(Duration.ofHours(6).plusSeconds(1))
        val deleted = tokens.deleteExpiredUnactivatedTokens()

        assertEquals(1, deleted)
        assertNull(tokens.resolve(sha256Hex("never-used")), "unactivated + expired is swept")
        assertNotNull(tokens.resolve(sha256Hex("activated")), "an activated token survives the sweep")
        assertNotNull(tokens.resolve(sha256Hex("bootstrap")), "a NULL-deadline bootstrap token is never swept")
    }
}
