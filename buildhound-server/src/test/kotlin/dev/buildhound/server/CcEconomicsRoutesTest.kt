package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.FingerprintInfo
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

/** `/v1/rollups/cc-economics` + the `/trends` CC counters (plan 064), no socket. */
class CcEconomicsRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

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

    private suspend fun ApplicationTestBuilder.ccEconomics(query: String = "", token: String = "read-token"): CcEconomicsReport =
        json.decodeFromString(CcEconomicsReport.serializer(), get("/v1/rollups/cc-economics$query", token).bodyAsText())

    private suspend fun ApplicationTestBuilder.trends(query: String = "", token: String = "read-token"): List<TrendPoint> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TrendPoint.serializer()), get("/v1/trends$query", token).bodyAsText())

    private val now = System.currentTimeMillis()
    private val recent = now - 3_600_000

    @Test
    fun `cc-economics needs a read token and rejects an ingest-scope token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rollups/cc-economics").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/rollups/cc-economics", token = "ingest-token").status)
    }

    @Test
    fun `an empty tenant returns an INSUFFICIENT_DATA report, never another tenant's data`() = testApplication {
        val fx = fx(); appWith(fx)
        // Seed pilot with real CC data, then read as a fresh cross-tenant token — must see nothing.
        repeat(6) { i ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(buildId = "p-$i", startedAt = recent + i, configurationCache = ConfigurationCacheState.HIT),
            )
        }
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        val report = ccEconomics(token = "other-token")
        assertEquals(CiReuseClass.INSUFFICIENT_DATA, report.ciReuseClass)
        assertEquals(0, report.ccObservedBuilds)
        assertTrue(report.flipFlops.isEmpty())
    }

    @Test
    fun `a healthy CI window classifies and surfaces a flip-flop finding`() = testApplication {
        val fx = fx(); appWith(fx)
        // 6 CI builds on one machine: 4 HIT, 2 MISS_STORED → reuse 4/6 ≥ 0.5 → REUSE_HEALTHY.
        // The two MISS_STORED share the earlier HITs' fingerprint map → flip-flop findings.
        val fp = FingerprintInfo(build = mapOf("jdk.home" to "aaaa1111", "env-CI" to "bbbb2222"))
        listOf(
            ConfigurationCacheState.HIT, ConfigurationCacheState.HIT, ConfigurationCacheState.HIT,
            ConfigurationCacheState.HIT, ConfigurationCacheState.MISS_STORED, ConfigurationCacheState.MISS_STORED,
        ).forEachIndexed { i, cc ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "b-$i", startedAt = recent + i, mode = BuildMode.CI,
                    configurationCache = cc, hostnameHash = "h1", userId = "u1", fingerprints = fp,
                    configurationMs = if (cc == ConfigurationCacheState.MISS_STORED) 1200 else null,
                    ccEntrySizeBytes = 65_000_000,
                ),
            )
        }
        val report = ccEconomics()
        assertEquals(CiReuseClass.REUSE_HEALTHY, report.ciReuseClass)
        assertEquals(6, report.ccRequestedBuilds)
        assertEquals(4, report.ccHitBuilds)
        assertEquals(0.666667, report.reuseRate)
        assertEquals(1200, report.storeCostMsP50, "store-cost p50 over the MISS_STORED configurationMs")
        assertEquals(65_000_000, report.entrySizeBytesP50)
        assertEquals(2, report.flipFlops.size, "both MISS_STORED builds match the earlier HITs' fingerprints")
        assertTrue(report.flipFlops.all { it.priorBuildId == "b-0" }, "the earliest matching build is the prior")
    }

    @Test
    fun `trends carries per-day CC counters, honest-null on a day with no CC data`() = testApplication {
        val fx = fx(); appWith(fx)
        // Two CC builds (1 HIT, 1 MISS_STORED) + one pre-064-style build with no CC state, all same day.
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "h", startedAt = recent, configurationCache = ConfigurationCacheState.HIT))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "m", startedAt = recent + 1, configurationCache = ConfigurationCacheState.MISS_STORED))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "n", startedAt = recent + 2))
        val point = trends("?days=7").single()
        assertEquals(1, point.ccHit)
        assertEquals(1, point.ccMissStored)
        assertEquals(2, point.ccRequested, "HIT + MISS_STORED; the no-CC build is not requested")
    }

    @Test
    fun `the days window is clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/cc-economics?days=0").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/cc-economics?days=99999").status)
    }
}
