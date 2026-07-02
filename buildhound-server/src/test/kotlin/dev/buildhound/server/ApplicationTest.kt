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
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTest {

    private fun payloadJson(buildId: String = "11111111-2222-3333-4444-555555555555") = """
        {
          "schemaVersion": 1,
          "buildId": "$buildId",
          "startedAt": 1751450000000,
          "finishedAt": 1751450042000,
          "outcome": "SUCCESS",
          "requestedTasks": ["build"],
          "mode": "ci"
        }
    """.trimIndent()

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) }
        return out.toByteArray()
    }

    /** Test stores with one seeded tenant; the token is plaintext only in tests. */
    private class Fixture(val stores: ServerStores, val project: ProjectRef)

    private fun fixture(token: String = "test-token", projectKey: String = "pilot"): Fixture {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken(projectKey, sha256Hex(token))
        return Fixture(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fixture: Fixture) {
        application { buildHoundModule(fixture.stores) }
    }

    @Test
    fun `health endpoint responds ok without auth`() = testApplication {
        appWith(fixture())

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun `ingest fails closed without or with an unknown token`() = testApplication {
        appWith(fixture())

        val missing = client.post("/v1/builds") {
            contentType(ContentType.Application.Json)
            setBody(payloadJson())
        }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)

        val wrong = client.post("/v1/builds") {
            header("Authorization", "Bearer nope")
            contentType(ContentType.Application.Json)
            setBody(payloadJson())
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun `ingest accepts an authed payload and dedupes per project`() = testApplication {
        val fixture = fixture()
        appWith(fixture)

        val first = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson())
        }
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertTrue(first.bodyAsText().contains("accepted"))

        val second = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson())
        }
        assertEquals(HttpStatusCode.Accepted, second.status)
        assertTrue(second.bodyAsText().contains("duplicate"))
        assertEquals(1, fixture.stores.builds.count(fixture.project.id))
    }

    @Test
    fun `tenancy comes from the token not the payload`() = testApplication {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val projectA = stores.tokens.ensureProjectWithToken("team-a", sha256Hex("token-a"))
        val projectB = stores.tokens.ensureProjectWithToken("team-b", sha256Hex("token-b"))
        application { buildHoundModule(stores) }

        for (token in listOf("token-a", "token-b")) {
            val response = client.post("/v1/builds") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payloadJson()) // same buildId for both tenants
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertTrue(response.bodyAsText().contains("accepted"), "no cross-tenant dedupe")
        }
        assertEquals(1, stores.builds.count(projectA.id))
        assertEquals(1, stores.builds.count(projectB.id))
        assertNull(stores.builds.findById(projectA.id, "no-such-build"))
    }

    @Test
    fun `ingest decodes gzip bodies`() = testApplication {
        val fixture = fixture()
        appWith(fixture)

        val response = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            header("Content-Encoding", "gzip")
            contentType(ContentType.Application.Json)
            setBody(gzip(payloadJson(buildId = "gzip-build")))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(1, fixture.stores.builds.count(fixture.project.id))
    }

    @Test
    fun `ingest rejects corrupt gzip`() = testApplication {
        appWith(fixture())

        val response = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            header("Content-Encoding", "gzip")
            contentType(ContentType.Application.Json)
            setBody(byteArrayOf(0x1f, 0x0b, 1, 2, 3))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `ingest rejects malformed payloads`() = testApplication {
        appWith(fixture())

        val response = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"not": "a build payload"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `ingest rejects payloads from a newer major schema`() = testApplication {
        appWith(fixture())

        val response = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `zip bomb protection caps decompressed size`() {
        // 100 MB of zeros compresses tiny; the bounded gunzip must refuse to inflate it.
        val bomb = gzip("0".repeat(1024 * 1024)) // 1 MB plain, but use a tiny limit to prove the guard
        assertNull(gunzipBounded(bomb, limit = 1024))
    }
}
