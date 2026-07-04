package dev.buildhound.server

import dev.buildhound.commons.payload.ArtifactSize
import dev.buildhound.commons.payload.ArtifactSizes
import dev.buildhound.commons.payload.ArtifactType
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Artifact-size trend endpoint (plan 031), no socket. */
class ArtifactRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String = "read-token") =
        client.get(path) { header("Authorization", "Bearer $token") }

    private val recent = System.currentTimeMillis() - 3_600_000

    private fun seed(fx: Fx) {
        // Two builds the same day: :app release APK 8000 then 9000 → avg 8500, max 9000, builds 2.
        listOf(8_000L, 9_000L).forEachIndexed { i, size ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "a-$i", startedAt = recent + i * 1000,
                    artifacts = ArtifactSizes(android = listOf(ArtifactSize("release", ":app", ArtifactType.APK, size))),
                ),
            )
        }
        // A build with no artifacts must not appear.
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "no-art", startedAt = recent))
    }

    @Test
    fun `artifact trends need a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/artifacts/trends").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/artifacts/trends", token = "ingest-token").status)
    }

    @Test
    fun `artifact trends group by module, variant, type with avg and max`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/artifacts/trends?days=2").bodyAsText()
        assertTrue(body.contains("\"variant\":\"release\""), body)
        assertTrue(body.contains("\"type\":\"APK\""), body)
        assertTrue(body.contains("\"module\":\":app\""), body)
        assertTrue(body.contains("\"avgSizeBytes\":8500"), body)
        assertTrue(body.contains("\"maxSizeBytes\":9000"), body)
        assertTrue(body.contains("\"builds\":2"), body)
    }

    @Test
    fun `artifact trends are tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertEquals("[]", get("/v1/artifacts/trends", token = "other-token").bodyAsText().trim())
    }

    @Test
    fun `re-ingesting the same build does not double-count artifacts`() = testApplication {
        val fx = fx(); appWith(fx)
        val payload = TestPayloads.build(
            buildId = "dup", startedAt = recent,
            artifacts = ArtifactSizes(android = listOf(ArtifactSize("release", ":app", ArtifactType.APK, 5000))),
        )
        assertTrue(fx.stores.builds.save(fx.project.id, payload))
        assertFalse(fx.stores.builds.save(fx.project.id, payload)) // duplicate
        val body = get("/v1/artifacts/trends?days=1").bodyAsText()
        assertTrue(body.contains("\"builds\":1"), "a duplicate build must not double-count: $body")
    }
}
