package dev.buildhound.server

import dev.buildhound.commons.payload.BuildCacheConfigInfo
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.TaskOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Route/auth-matrix + two-tier + tenant-scoping coverage for `/v1/rollups/cache-roi` (plan 067). */
class CacheRoiRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) =
        application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String = "read-token") =
        client.get(path) { header("Authorization", "Bearer $token") }

    private suspend fun ApplicationTestBuilder.roi(days: Int = 30, token: String = "read-token"): CacheRoiRollup {
        val body = get("/v1/rollups/cache-roi?days=$days", token).bodyAsText()
        return BuildHoundJson.payload.decodeFromString(CacheRoiRollup.serializer(), body)
    }

    private val recent = System.currentTimeMillis() - 3_600_000
    private val remoteConfigured = BuildCacheConfigInfo(localEnabled = true, remoteEnabled = true, remotePush = true, remoteType = "HttpBuildCache")
    private val noRemote = BuildCacheConfigInfo(localEnabled = true)

    @Test
    fun `the route requires a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rollups/cache-roi").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/rollups/cache-roi", token = "ingest-token").status)
    }

    @Test
    fun `an empty project reads as an honest empty state`() = testApplication {
        val fx = fx(); appWith(fx)
        val roi = roi()
        assertEquals(false, roi.remoteHitRateAvailable)
        assertEquals(0, roi.buildsWithConfig)
        assertEquals(0.0, roi.remoteConfiguredShare)
        assertTrue(roi.perMode.isEmpty())
        assertNull(roi.ciReuseCandidate)
    }

    @Test
    fun `config snapshots without origin data degrade to the config summary (remoteHitRateAvailable false)`() {
        testApplication {
            val fx = fx(); appWith(fx)
            // Two builds carry the plan-067 snapshot but NO internalAdapters origin block (opt-in off).
            fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "b1", startedAt = recent, buildCache = remoteConfigured))
            fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "b2", startedAt = recent + 1000, buildCache = noRemote))

            val roi = roi()
            assertEquals(false, roi.remoteHitRateAvailable, "no origin data → no remote-hit rate")
            assertEquals(2, roi.buildsWithConfig)
            assertEquals(0.5, roi.remoteConfiguredShare, "1 of 2 builds configured an enabled remote")
            assertTrue(roi.perMode.isEmpty())
            assertNull(roi.ciReuseCandidate)
        }
    }

    @Test
    fun `origin data yields a per-mode remote-hit rate and a near-zero CI candidate`() = testApplication {
        val fx = fx(); appWith(fx)
        // A CI window with a configured remote that almost never reuses it: 60 misses + 1 remote hit.
        (0 until 60).forEach { i ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "ci-miss-$i", startedAt = recent + i, mode = BuildMode.CI, buildCache = remoteConfigured,
                    tasks = listOf(TestPayloads.task(":app:compileJava", TaskOutcome.EXECUTED, 100, cacheable = true)),
                    extensions = TestPayloads.internalAdapters(listOf(":app:compileJava" to "MISS")),
                ),
            )
        }
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "ci-hit", startedAt = recent + 100, mode = BuildMode.CI, buildCache = remoteConfigured,
                tasks = listOf(TestPayloads.task(":lib:compileJava", TaskOutcome.FROM_CACHE, 5, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":lib:compileJava" to "REMOTE_HIT")),
            ),
        )
        // A LOCAL build with a local hit — a second mode, and STORED must not count in the denominator.
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "local-1", startedAt = recent + 200, mode = BuildMode.LOCAL, buildCache = noRemote,
                tasks = listOf(
                    TestPayloads.task(":app:compileJava", TaskOutcome.FROM_CACHE, 5, cacheable = true),
                    TestPayloads.task(":app:jar", TaskOutcome.EXECUTED, 50, cacheable = true),
                ),
                extensions = TestPayloads.internalAdapters(listOf(":app:compileJava" to "LOCAL_HIT", ":app:jar" to "STORED")),
            ),
        )

        val roi = roi()
        assertEquals(true, roi.remoteHitRateAvailable)
        assertEquals(62, roi.buildsWithConfig)

        val ci = roi.perMode.single { it.mode == "CI" }
        assertEquals(61, ci.consideredExecutions, "60 MISS + 1 REMOTE_HIT")
        assertEquals(1, ci.remoteHits)
        assertTrue(ci.remoteHitRate <= CacheRoiCalculator.NEAR_ZERO_REUSE_RATE)

        val local = roi.perMode.single { it.mode == "LOCAL" }
        assertEquals(1, local.consideredExecutions, "the LOCAL build's STORED task is excluded from the denominator")
        assertEquals(1, local.localHits)

        val candidate = roi.ciReuseCandidate ?: error("expected a near-zero CI reuse candidate")
        assertEquals("CI", candidate.mode)
        assertTrue(candidate.note.contains("investigate", ignoreCase = true), candidate.note)
    }

    @Test
    fun `the rollup is tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "b1", startedAt = recent, buildCache = remoteConfigured,
                tasks = listOf(TestPayloads.task(":app:compileJava", TaskOutcome.FROM_CACHE, 5, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":app:compileJava" to "REMOTE_HIT")),
            ),
        )
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertTrue(other.id != fx.project.id)
        val roi = roi(token = "other-token")
        assertEquals(false, roi.remoteHitRateAvailable, "a fresh tenant sees none of the first tenant's ROI")
        assertEquals(0, roi.buildsWithConfig)
    }

    @Test
    fun `the days window is clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/cache-roi?days=0").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/cache-roi?days=99999").status)
    }
}
