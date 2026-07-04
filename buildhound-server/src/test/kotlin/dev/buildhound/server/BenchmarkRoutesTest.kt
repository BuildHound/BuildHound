package dev.buildhound.server

import dev.buildhound.commons.payload.BenchmarkInfo
import dev.buildhound.commons.payload.BuildMode
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

/** Benchmark series endpoint + fleet-view exclusion (plan 030), no socket. */
class BenchmarkRoutesTest {

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

    /** Three `clean` benchmark iterations (durations 3s/1s/2s) + one normal CI build. */
    private fun seed(fx: Fx) {
        listOf(3_000L to 1, 1_000L to 2, 2_000L to 3).forEach { (dur, iter) ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "bench-$iter", durationMs = dur, startedAt = recent + iter * 1000,
                    mode = BuildMode.BENCHMARK,
                    benchmark = BenchmarkInfo(scenario = "clean", iteration = iter, isolationMode = "no_build_cache"),
                ),
            )
        }
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "ci-1", durationMs = 9_000, startedAt = recent, mode = BuildMode.CI),
        )
    }

    @Test
    fun `benchmark series needs a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/benchmark/series").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/benchmark/series", token = "ingest-token").status)
    }

    @Test
    fun `benchmark series groups by scenario with percentiles matching the commons calculator`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/benchmark/series").bodyAsText()
        assertTrue(body.contains("\"scenario\":\"clean\""), body)
        assertTrue(body.contains("\"isolationMode\":\"no_build_cache\""), body)
        // durations 1s/2s/3s → p50 2000, p90 3000, min 1000, count 3 (nearest-rank).
        assertTrue(body.contains("\"p50\":2000"), body)
        assertTrue(body.contains("\"p90\":3000"), body)
        assertTrue(body.contains("\"min\":1000"), body)
        assertTrue(body.contains("\"count\":3"), body)
        // The normal CI build is not a benchmark point.
        assertFalse(body.contains("ci-1"), body)
    }

    @Test
    fun `trends exclude benchmark builds by default and include them with the flag`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        // Default: only the CI build counts (1 build that day); benchmark builds excluded.
        val defaultTrends = get("/v1/trends?days=2").bodyAsText()
        assertTrue(defaultTrends.contains("\"builds\":1"), "benchmark builds must be excluded by default: $defaultTrends")
        // includeBenchmark=true: all four builds count.
        val withBenchmark = get("/v1/trends?days=2&includeBenchmark=true").bodyAsText()
        assertTrue(withBenchmark.contains("\"builds\":4"), withBenchmark)
    }

    @Test
    fun `the builds list excludes benchmark builds by default`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/builds").bodyAsText()
        assertTrue(body.contains("ci-1"), body)
        assertFalse(body.contains("bench-"), "benchmark builds must not appear in the fleet list: $body")
        // Opting in surfaces them.
        assertTrue(get("/v1/builds?mode=benchmark").bodyAsText().contains("bench-"))
    }

    @Test
    fun `benchmark series is tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertEquals("[]", get("/v1/benchmark/series", token = "other-token").bodyAsText().trim())
    }
}
