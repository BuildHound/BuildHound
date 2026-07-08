package dev.buildhound.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-level tests for `GET /v1/trends/cohorts` and `GET /v1/tags` (plan 057): auth/scope gating,
 * the happy-path split, the benchmark-excluded-by-default fleet-view convention, the honest empty
 * comparison for an unknown tag key, the `/v1/tags` picker listing, the over-cap `tag.` filter
 * rejection, and cross-tenant isolation.
 */
class TagCohortRouteTest {

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

    /** Seeds a fast "false" cohort (5 builds, ~60s) and a slower "true" cohort (3 builds, ~100s),
     * plus a benchmark build tagged R8=true that must not leak into the default (non-benchmark) view,
     * and an "env" tag for the /v1/tags multi-key listing. */
    private fun seed(fx: Fx) {
        listOf(58_000L, 59_000L, 60_000L, 61_000L, 62_000L).forEachIndexed { i, duration ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "false-$i", startedAt = recent + i * 1000, durationMs = duration,
                    tags = mapOf("R8" to "false", "env" to "prod"),
                ),
            )
        }
        listOf(98_000L, 100_000L, 102_000L).forEachIndexed { i, duration ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "true-$i", startedAt = recent + i * 1000, durationMs = duration,
                    tags = mapOf("R8" to "true", "env" to "staging"),
                ),
            )
        }
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "bench-true", startedAt = recent, durationMs = 200_000,
                mode = dev.buildhound.commons.payload.BuildMode.BENCHMARK,
                tags = mapOf("R8" to "true"),
            ),
        )
        // A build with no tags at all must never form a synthetic "null" cohort.
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "no-tags", startedAt = recent))
    }

    @Test
    fun `cohorts and tags need a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/trends/cohorts?tag=R8").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/trends/cohorts?tag=R8", token = "ingest-token").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/tags").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/tags", token = "ingest-token").status)
    }

    @Test
    fun `missing tag parameter is a 400`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/trends/cohorts").status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/trends/cohorts?tag=").status)
    }

    @Test
    fun `happy path splits into ordered cohorts with a delta, excluding benchmark by default`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/trends/cohorts?tag=R8&days=2").bodyAsText()

        assertTrue(body.contains("\"tagKey\":\"R8\""), body)
        assertTrue(body.contains("\"value\":\"false\""), body)
        assertTrue(body.contains("\"value\":\"true\""), body)
        assertTrue(body.contains("\"sampleCount\":5"), body)
        assertTrue(body.contains("\"sampleCount\":3"), body)
        // "false" (5 builds) is the reference — the more numerous, more stable cohort.
        assertTrue(body.contains("\"referenceValue\":\"false\""), body)
        // The benchmark build (200s, tag R8=true) must not pollute either cohort's median.
        assertTrue(!body.contains("200000"), "benchmark build must be excluded from cohorts by default: $body")
    }

    @Test
    fun `an unknown tag key is an empty comparison, not a 404`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val response = get("/v1/trends/cohorts?tag=doesNotExist")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"cohorts\":[]"), body)
        // BuildHoundJson omits null fields (explicitNulls = false) rather than emitting "delta":null.
        assertTrue(!body.contains("\"delta\""), body)
    }

    @Test
    fun `v1 tags lists distinct keys with capped top values`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/tags?days=2").bodyAsText()
        assertTrue(body.contains("\"key\":\"R8\""), body)
        assertTrue(body.contains("\"key\":\"env\""), body)
        assertTrue(body.contains("\"value\":\"false\",\"count\":5"), body)
        assertTrue(body.contains("\"value\":\"true\",\"count\":3"), body)
    }

    @Test
    fun `an over-cap tag filter param is a 400`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val longKey = "k".repeat(200)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/builds?tag.$longKey=x").status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/trends?tag.$longKey=x").status)
        val longValue = "v".repeat(400)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/builds?tag.R8=$longValue").status)
    }

    @Test
    fun `more than maxTags distinct tag filter params is a 400, matching ingest symmetry`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val maxTags = dev.buildhound.commons.payload.PayloadCaps.DEFAULT.maxTags
        val withinCap = (1..maxTags).joinToString("&") { "tag.k$it=v" }
        assertEquals(HttpStatusCode.OK, get("/v1/builds?$withinCap").status, "exactly maxTags filters must still be accepted")
        val overCap = (1..(maxTags + 1)).joinToString("&") { "tag.k$it=v" }
        assertEquals(HttpStatusCode.BadRequest, get("/v1/builds?$overCap").status, "more than maxTags filters must be rejected")
        assertEquals(HttpStatusCode.BadRequest, get("/v1/trends?$overCap").status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/trends/cohorts?tag=R8&$overCap").status)
    }

    @Test
    fun `a tag equality filter narrows v1 builds and v1 trends`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val filtered = get("/v1/builds?tag.R8=true&limit=50").bodyAsText()
        assertTrue(filtered.contains("\"true-0\""), filtered)
        assertTrue(!filtered.contains("\"false-0\""), filtered)
        assertTrue(!filtered.contains("\"no-tags\""), filtered)
    }

    @Test
    fun `cohorts and tags are tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        val cohorts = get("/v1/trends/cohorts?tag=R8", token = "other-token").bodyAsText()
        assertTrue(cohorts.contains("\"cohorts\":[]"), "a foreign token must see no cohorts: $cohorts")
        assertEquals("[]", get("/v1/tags", token = "other-token").bodyAsText().trim())
    }
}
