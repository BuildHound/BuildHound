package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.ConfigurationCacheState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Route coverage for plan 054's `GET /v1/rollups/recommendations` (fleet) and
 * `GET /v1/builds/{buildId}/recommendations` (per-build): auth matrix, tenant isolation, `days` clamp,
 * and empty-not-500. The rule math itself is unit-tested in [RecommendationEngineTest] — these tests
 * exercise only the routes' auth, lookup, and wiring, no socket (the [CcEconomicsRoutesTest] /
 * [DiagnosisRoutesTest] structure).
 */
class RecommendationRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String? = "read-token") =
        client.get(path) { token?.let { header("Authorization", "Bearer $it") } }

    private suspend fun ApplicationTestBuilder.fleetRecommendations(query: String = "", token: String = "read-token"): RecommendationsRollup =
        json.decodeFromString(RecommendationsRollup.serializer(), get("/v1/rollups/recommendations$query", token).bodyAsText())

    private suspend fun ApplicationTestBuilder.perBuild(buildId: String, token: String? = "read-token") =
        get("/v1/builds/$buildId/recommendations", token)

    private val now = System.currentTimeMillis()
    private val recent = now - 3_600_000

    // ---- GET /v1/rollups/recommendations (fleet) ------------------------------------------------

    @Test
    fun `fleet recommendations need a read token and reject an ingest-scope token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/rollups/recommendations", token = null).status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/rollups/recommendations", token = "ingest-token").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/recommendations", token = "read-token").status)
    }

    @Test
    fun `an empty tenant returns 200 with an empty recommendations list, never 500`() = testApplication {
        val fx = fx(); appWith(fx)
        val rollup = fleetRecommendations()
        assertTrue(rollup.recommendations.isEmpty())
        assertEquals(0, rollup.buildsAnalyzed)
    }

    @Test
    fun `tenant isolation - a second tenant sees none of the first tenant's recommendations`() = testApplication {
        val fx = fx(); appWith(fx)
        // 6 CI builds with the configuration cache disabled -> HYGIENE-CACHE-OFF/BP-CC-ENABLE must fire
        // for "pilot" (1.0 share, well above the 0.5 gate).
        repeat(6) { i ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "p-$i", startedAt = recent + i, mode = BuildMode.CI,
                    configurationCache = ConfigurationCacheState.DISABLED,
                ),
            )
        }
        val pilotRollup = fleetRecommendations()
        assertTrue(pilotRollup.recommendations.isNotEmpty(), "pilot's CC-disabled builds must trip a rule")
        assertEquals(6, pilotRollup.buildsAnalyzed)

        // A brand-new tenant token must see none of pilot's data — a fresh empty rollup, not a 500 and
        // not a cross-tenant peek.
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        val otherRollup = fleetRecommendations(token = "other-token")
        assertTrue(otherRollup.recommendations.isEmpty(), "a fresh cross-tenant token must see none of pilot's data")
        assertEquals(0, otherRollup.buildsAnalyzed)
    }

    @Test
    fun `the days window is clamped at both ends, never a 500`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/recommendations?days=0", token = "read-token").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/recommendations?days=9999", token = "read-token").status)
    }

    // ---- GET /v1/builds/{buildId}/recommendations (per-build) ------------------------------------

    @Test
    fun `per-build recommendations need a read token and reject an ingest-scope token`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "b1"))
        assertEquals(HttpStatusCode.Unauthorized, perBuild("b1", token = null).status)
        assertEquals(HttpStatusCode.Forbidden, perBuild("b1", token = "ingest-token").status)
        assertEquals(HttpStatusCode.OK, perBuild("b1", token = "read-token").status)
    }

    @Test
    fun `an unknown build 404s`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.NotFound, perBuild("no-such-build").status)
    }

    @Test
    fun `a foreign-tenant build 404s - the same status as unknown, no existence oracle`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-read-token"), TokenScope.READ)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "tenant-a-build"))

        val foreign = get("/v1/builds/tenant-a-build/recommendations", token = "other-read-token")
        val unknown = get("/v1/builds/does-not-exist/recommendations", token = "other-read-token")
        assertEquals(HttpStatusCode.NotFound, foreign.status, "a real build belonging to another tenant must read as 404, never a cross-tenant peek")
        assertEquals(unknown.status, foreign.status, "a foreign build and an unknown build must be indistinguishable")
    }

    @Test
    fun `own build 200s with a per-build rollup, empty on a clean build`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "clean-1", configurationCache = ConfigurationCacheState.HIT, hitRate = 0.95),
        )
        val response = perBuild("clean-1")
        assertEquals(HttpStatusCode.OK, response.status)
        val rollup = json.decodeFromString(RecommendationsRollup.serializer(), response.bodyAsText())
        assertTrue(rollup.recommendations.isEmpty(), "a clean build trips no rule")
        assertEquals(1, rollup.buildsAnalyzed)
        assertEquals(0, rollup.period, "no window for the single-build variant")
    }

    @Test
    fun `own build 200s and fires a rule when the build's own signal warrants it`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "noisy-1", mode = BuildMode.CI, configurationCache = ConfigurationCacheState.DISABLED),
        )
        val rollup = json.decodeFromString(RecommendationsRollup.serializer(), perBuild("noisy-1").bodyAsText())
        assertTrue(rollup.recommendations.any { it.ruleId == "HYGIENE-CACHE-OFF" }, "a single CC-disabled build still yields a card (share=1.0 over one observed build)")
    }
}
