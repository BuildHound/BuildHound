package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import io.ktor.client.request.get
import io.ktor.client.request.head
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
import java.sql.SQLException
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    // A failed build carrying plan-044 failure detail + the opt-in internal-adapters warning block,
    // so a round-trip test can prove both survive ingest → store → GET (the wire the dashboard/report
    // render from, plan 045). \n / \t stay literal here — JSON unescapes them into the stacktrace.
    private fun failurePayloadJson(buildId: String = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") = """
        {
          "schemaVersion": 1,
          "buildId": "$buildId",
          "startedAt": 1751450000000,
          "finishedAt": 1751450005000,
          "outcome": "FAILED",
          "requestedTasks": ["build"],
          "mode": "ci",
          "failure": {
            "exceptionClass": "org.gradle.api.GradleException",
            "message": "Execution failed for task ':app:compileKotlin'",
            "stackTrace": "org.gradle.api.GradleException: boom\n\tat org.example.Widget.build(Widget.java:42)"
          },
          "extensions": {
            "internalAdapters": {
              "schemaVersion": 1,
              "gradleVersion": "9.6.1",
              "deprecations": ["The Foo API has been deprecated."],
              "logWarnings": ["warning: bar() in Baz has been deprecated"],
              "droppedWarnings": 3
            }
          }
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
    fun `health endpoint answers HEAD with the same status as GET`() = testApplication {
        appWith(fixture())

        // Load balancers and uptime monitors probe with HEAD (plan 088 live finding).
        assertEquals(client.get("/health").status, client.head("/health").status)
        assertEquals(HttpStatusCode.OK, client.head("/health").status)
        assertEquals("", client.head("/health").bodyAsText())
    }

    @Test
    fun `authenticated HEAD mirrors GET on a query endpoint including response headers`() = testApplication {
        appWith(fixture())

        val get = client.get("/v1/builds") { header("Authorization", "Bearer test-token") }
        val head = client.head("/v1/builds") { header("Authorization", "Bearer test-token") }

        assertEquals(HttpStatusCode.OK, get.status)
        assertEquals(get.status, head.status)
        // GET's headers must survive body suppression — X-Total-Count is what a HEAD caller reads.
        assertNotNull(head.headers["X-Total-Count"])
        assertEquals(get.headers["X-Total-Count"], head.headers["X-Total-Count"])
        assertEquals("", head.bodyAsText())
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
    fun `ingest classifies program-limit SQLSTATE 54xxx as a permanent 400`() = testApplication {
        // 54xxx (e.g. an index tuple over the btree cap) is data-shaped and deterministic — a retry can
        // never succeed, so it must be 400 (plugin warns-and-drops), never 503 (plugin spools forever).
        val stores = ServerStores(
            builds = object : BuildStore by InMemoryBuildStore() {
                override fun save(projectId: String, payload: BuildPayload): Boolean =
                    throw SQLException("index row size exceeds maximum", "54000")
            },
            tokens = InMemoryTokenStore(),
        )
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("test-token"))
        application { buildHoundModule(stores) }
        val response = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status, "program-limit-exceeded is permanent")
    }

    @Test
    fun `ingest classifies a connection-shaped SQLException as a retryable 503`() = testApplication {
        val stores = ServerStores(
            builds = object : BuildStore by InMemoryBuildStore() {
                override fun save(projectId: String, payload: BuildPayload): Boolean =
                    throw SQLException("connection lost", "08006")
            },
            tokens = InMemoryTokenStore(),
        )
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("test-token"))
        application { buildHoundModule(stores) }
        val response = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson())
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "an outage stays retryable")
    }

    @Test
    fun `build detail response carries failure detail and opaque warning extensions`() = testApplication {
        appWith(fixture())
        val buildId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        val ingest = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(failurePayloadJson(buildId))
        }
        assertEquals(HttpStatusCode.Accepted, ingest.status)

        val detail = client.get("/v1/builds/$buildId") {
            header("Authorization", "Bearer test-token")
        }
        assertEquals(HttpStatusCode.OK, detail.status)

        // The bytes the dashboard/report fetch (plan 045) must still carry what plan 044 collected:
        // if the detail response were ever swapped for a projection DTO, these would vanish. This is
        // the one link the JS smoke harness cannot cover — it stubs fetch with canned bodies.
        val body = detail.bodyAsText()
        assertTrue(body.contains("org.gradle.api.GradleException"), body)
        assertTrue(body.contains("Execution failed for task ':app:compileKotlin'"), body)
        assertTrue(body.contains("org.example.Widget.build(Widget.java:42)"), body)
        assertTrue(body.contains("The Foo API has been deprecated."), body)
        assertTrue(body.contains("bar() in Baz has been deprecated"), body)

        // And it decodes back into typed failure + the opaque internal-adapters extension intact.
        val decoded = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), body)
        assertEquals("org.gradle.api.GradleException", decoded.failure?.exceptionClass)
        // Pin the multi-line structure the fixture comment claims — the `\n`/`\t` must survive intact,
        // not collapse to one line, so the rendered <pre> keeps real stack frames.
        assertEquals(
            "org.gradle.api.GradleException: boom\n\tat org.example.Widget.build(Widget.java:42)",
            decoded.failure?.stackTrace,
        )
        assertTrue(decoded.extensions.containsKey("internalAdapters"), "internalAdapters extension dropped")
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
    fun `oversized bodies are rejected with 413`() = testApplication {
        appWith(fixture())

        val response = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(ByteArray(MAX_COMPRESSED_BYTES + 1))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `reusing a token for another project fails loudly`() {
        val tokens = InMemoryTokenStore()
        tokens.ensureProjectWithToken("team-a", sha256Hex("shared"))

        val failure = runCatching { tokens.ensureProjectWithToken("team-b", sha256Hex("shared")) }

        assertTrue(failure.isFailure, "cross-project token reuse must never be silent")
    }

    private fun richPayload(buildId: String, startedAt: Long, outcome: String, branch: String) = """
        {
          "schemaVersion": 1,
          "buildId": "$buildId",
          "startedAt": $startedAt,
          "finishedAt": ${startedAt + 60_000},
          "outcome": "$outcome",
          "mode": "ci",
          "vcs": {"branch": "$branch"},
          "derived": {"cacheableHitRate": 0.5}
        }
    """.trimIndent()

    @Test
    fun `query api lists filters and pages builds`() = testApplication {
        val fixture = fixture()
        appWith(fixture)
        val now = System.currentTimeMillis()
        val payloads = listOf(
            richPayload("b-old", now - 3 * 86_400_000, "SUCCESS", "main"),
            richPayload("b-mid", now - 86_400_000, "FAILED", "main"),
            richPayload("b-new", now - 3_600_000, "SUCCESS", "feature/x"),
        )
        for (p in payloads) {
            client.post("/v1/builds") {
                header("Authorization", "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody(p)
            }
        }

        val unauthorized = client.get("/v1/builds")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val all = client.get("/v1/builds") { header("Authorization", "Bearer test-token") }
        assertEquals(HttpStatusCode.OK, all.status)
        val ids = Regex("\"buildId\":\"([^\"]+)\"").findAll(all.bodyAsText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("b-new", "b-mid", "b-old"), ids, "newest first")
        assertEquals("3", all.headers["X-Total-Count"], "unfiltered total in the header (plan 018)")

        val mainOnly = client.get("/v1/builds?branch=main&limit=1&offset=1") {
            header("Authorization", "Bearer test-token")
        }
        val mainIds = Regex("\"buildId\":\"([^\"]+)\"").findAll(mainOnly.bodyAsText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("b-old"), mainIds, "branch filter + paging")
        // The header is the filter-aware total (2 on main), not the returned page size (1).
        assertEquals("2", mainOnly.headers["X-Total-Count"], "total is filter-aware, independent of paging")

        val badFilter = client.get("/v1/builds?mode=bogus") { header("Authorization", "Bearer test-token") }
        assertEquals(HttpStatusCode.BadRequest, badFilter.status)
    }

    @Test
    fun `compare endpoint is tenant-scoped, read-gated, and names the changed input`() = testApplication {
        val fixture = fixture()
        appWith(fixture)
        val foreign = fixture.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"))
        assertEquals("other", foreign.key)

        fun fingerprintPayload(id: String, jdkHash: String) = """
            {
              "schemaVersion": 1, "buildId": "$id", "startedAt": 1, "finishedAt": 2,
              "outcome": "SUCCESS", "mode": "ci",
              "fingerprints": { "build": { "jdk.home": "$jdkHash" } }
            }
        """.trimIndent()
        for (body in listOf(fingerprintPayload("cmp-a", "aaaa111122223333…"), fingerprintPayload("cmp-b", "bbbb444455556666…"))) {
            client.post("/v1/builds") {
                header("Authorization", "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

        // No token → 401; self-compare → 400; unknown id → 404.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/builds/cmp-a/compare/cmp-b").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/v1/builds/cmp-a/compare/cmp-a") { header("Authorization", "Bearer test-token") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/builds/cmp-a/compare/nope") { header("Authorization", "Bearer test-token") }.status,
        )
        // Foreign tenant sees neither build → 404, never another tenant's data.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/builds/cmp-a/compare/cmp-b") { header("Authorization", "Bearer other-token") }.status,
        )

        // Happy path: the differing jdk.home is named in the ranked diff.
        val ok = client.get("/v1/builds/cmp-a/compare/cmp-b") { header("Authorization", "Bearer test-token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        val body = ok.bodyAsText()
        assertTrue(body.contains("jdk.home"), body)
        assertTrue(body.contains("\"scope\":\"BUILD\""), body)
    }

    @Test
    fun `ingest clamps an over-cap payload but stores it, and leaves a compliant one untouched`() = testApplication {
        val fixture = fixture()
        appWith(fixture)

        val bigValue = "y".repeat(400)
        val overCap = """
            {
              "schemaVersion": 1,
              "buildId": "cap-me",
              "startedAt": 1,
              "finishedAt": 2,
              "outcome": "SUCCESS",
              "mode": "ci",
              "tags": {"big": "$bigValue"}
            }
        """.trimIndent()
        val ingest = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(overCap)
        }
        assertEquals(HttpStatusCode.Accepted, ingest.status, "an over-cap payload is clamped, not rejected")

        val stored = client.get("/v1/builds/cap-me") { header("Authorization", "Bearer test-token") }
        assertEquals(HttpStatusCode.OK, stored.status)
        val clamped = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), stored.bodyAsText())
        assertEquals(300, clamped.tags["big"]?.length, "server clamps the over-long value")
        assertEquals(1, clamped.caps?.truncatedValues)

        // A compliant payload is stored byte-identical — no caps summary.
        client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson("compliant-1"))
        }
        val compliant = client.get("/v1/builds/compliant-1") { header("Authorization", "Bearer test-token") }
        val compliantPayload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), compliant.bodyAsText())
        assertNull(compliantPayload.caps, "a compliant payload carries no caps summary")
    }

    @Test
    fun `query api detail is tenant scoped`() = testApplication {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        stores.tokens.ensureProjectWithToken("team-a", sha256Hex("token-a"))
        stores.tokens.ensureProjectWithToken("team-b", sha256Hex("token-b"))
        application { buildHoundModule(stores) }
        client.post("/v1/builds") {
            header("Authorization", "Bearer token-a")
            contentType(ContentType.Application.Json)
            setBody(payloadJson(buildId = "detail-1"))
        }

        val own = client.get("/v1/builds/detail-1") { header("Authorization", "Bearer token-a") }
        assertEquals(HttpStatusCode.OK, own.status)
        assertTrue(own.bodyAsText().contains("detail-1"))

        val foreign = client.get("/v1/builds/detail-1") { header("Authorization", "Bearer token-b") }
        assertEquals(HttpStatusCode.NotFound, foreign.status, "cross-tenant reads must 404")
    }

    @Test
    fun `query api trends bucket by day`() {
        // Store-level with fixed timestamps: bucketing itself must be assertable
        // without UTC-midnight flakiness.
        val store = InMemoryBuildStore()
        val day1 = 1751328000000 // 2025-07-01T00:00:00Z
        val day2 = day1 + 86_400_000
        for ((id, at, outcome) in listOf(
            Triple("t-1", day1 + 1_000, "SUCCESS"),
            Triple("t-2", day1 + 2_000, "FAILED"),
            Triple("t-3", day2 + 1_000, "SUCCESS"),
        )) {
            store.save(
                "p1",
                BuildHoundJson.payload.decodeFromString(
                    dev.buildhound.commons.payload.BuildPayload.serializer(),
                    richPayload(id, at, outcome, "main"),
                ),
            )
        }

        val trends = store.trends("p1", BuildFilter(), days = 7, nowMs = day2 + 10_000)
        assertEquals(2, trends.size, trends.toString())
        assertEquals(listOf(2, 1), trends.map { it.builds })
        assertEquals(listOf(1, 0), trends.map { it.failures })

        // Window exclusion: a build older than the cutoff disappears.
        assertEquals(1, store.trends("p1", BuildFilter(), days = 1, nowMs = day2 + 10_000).sumOf { it.builds })
    }

    @Test
    fun `read routes reject unauthenticated and over-limit requests`() = testApplication {
        appWith(fixture())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/builds/some-id").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/trends").status)
        // limit clamps rather than erroring
        val clamped = client.get("/v1/builds?limit=99999") { header("Authorization", "Bearer test-token") }
        assertEquals(HttpStatusCode.OK, clamped.status)
    }

    @Test
    fun `token scopes separate ingest from read`() = testApplication {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        application { buildHoundModule(stores) }

        val ingestWithIngest = client.post("/v1/builds") {
            header("Authorization", "Bearer ingest-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson(buildId = "scoped-1"))
        }
        assertEquals(HttpStatusCode.Accepted, ingestWithIngest.status)

        val readWithIngest = client.get("/v1/builds") { header("Authorization", "Bearer ingest-token") }
        assertEquals(HttpStatusCode.Forbidden, readWithIngest.status, "a leaked CI token must not read history")

        val readWithRead = client.get("/v1/builds") { header("Authorization", "Bearer read-token") }
        assertEquals(HttpStatusCode.OK, readWithRead.status)

        val ingestWithRead = client.post("/v1/builds") {
            header("Authorization", "Bearer read-token")
            contentType(ContentType.Application.Json)
            setBody(payloadJson(buildId = "scoped-2"))
        }
        assertEquals(HttpStatusCode.Forbidden, ingestWithRead.status)
    }

    @Test
    fun `zip bomb protection caps decompressed size`() {
        // 100 MB of zeros compresses tiny; the bounded gunzip must refuse to inflate it.
        val bomb = gzip("0".repeat(1024 * 1024)) // 1 MB plain, but use a tiny limit to prove the guard
        assertNull(gunzipBounded(bomb, limit = 1024))
    }
}
