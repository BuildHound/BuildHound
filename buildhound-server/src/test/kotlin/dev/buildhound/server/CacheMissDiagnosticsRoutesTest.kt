package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.TaskOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Route/auth-matrix + fleet-view coverage for `/v1/rollups/cache-miss-diagnostics` (plan 068). */
class CacheMissDiagnosticsRoutesTest {

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

    private suspend fun ApplicationTestBuilder.diagnostics(days: Int = 30, token: String = "read-token"): CacheMissDiagnostics {
        val body = get("/v1/rollups/cache-miss-diagnostics?days=$days", token).bodyAsText()
        return BuildHoundJson.payload.decodeFromString(CacheMissDiagnostics.serializer(), body)
    }

    private val recent = System.currentTimeMillis() - 3_600_000

    @Test
    fun `the route requires a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rollups/cache-miss-diagnostics").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/rollups/cache-miss-diagnostics", token = "ingest-token").status)
    }

    @Test
    fun `an empty project reads as an honest empty state — remoteCacheObserved is false`() = testApplication {
        val fx = fx(); appWith(fx)
        val result = diagnostics()
        assertEquals(false, result.remoteCacheObserved)
        assertEquals(emptyList(), result.nonRelocatable)
        assertEquals(emptyList(), result.volatileInputs)
    }

    @Test
    fun `the happy path names the non-relocatable task and the volatile credential key`() = testApplication {
        val fx = fx(); appWith(fx)
        // b1/b3 share host h1 (a real 2-build salt stream for the credential key); b2 is a second host,
        // giving :app:compileDebugKotlin the 2 distinct cross-host STORED executions the detector needs.
        // b3's :lib:test REMOTE_HITs, satisfying detector 1's fleet gate.
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "b1", durationMs = 400, startedAt = recent, hostnameHash = "h1",
                tasks = listOf(TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 400, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":app:compileDebugKotlin" to "STORED")),
                fingerprints = TestPayloads.fingerprints(mapOf("env-GITHUB_TOKEN" to "aaaa1111")),
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "b2", durationMs = 600, startedAt = recent + 1000, hostnameHash = "h2",
                tasks = listOf(TestPayloads.task(":app:compileDebugKotlin", TaskOutcome.EXECUTED, 600, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":app:compileDebugKotlin" to "STORED")),
                fingerprints = TestPayloads.fingerprints(mapOf("env-GITHUB_TOKEN" to "bbbb2222")),
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "b3", durationMs = 50, startedAt = recent + 2000, hostnameHash = "h1",
                tasks = listOf(TestPayloads.task(":lib:test", TaskOutcome.EXECUTED, 50, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":lib:test" to "REMOTE_HIT")),
                fingerprints = TestPayloads.fingerprints(mapOf("env-GITHUB_TOKEN" to "cccc3333")),
            ),
        )

        val result = diagnostics()
        assertEquals(true, result.remoteCacheObserved)

        val candidate = result.nonRelocatable.single()
        assertEquals(":app:compileDebugKotlin", candidate.taskPath)
        assertEquals(2, candidate.crossHostCount)
        assertEquals(1000L, candidate.wastedMs)

        val volatileKey = result.volatileInputs.single { it.key == "env-GITHUB_TOKEN" }
        assertEquals(1.0, volatileKey.volatility)
        assertTrue(volatileKey.note.contains("credential", ignoreCase = true), volatileKey.note)
    }

    @Test
    fun `remoteCacheObserved stays false and no candidates surface when the fleet never observed a REMOTE_HIT`() = testApplication {
        val fx = fx(); appWith(fx)
        for ((i, host) in listOf("h1", "h2").withIndex()) {
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "b$i", durationMs = 400, startedAt = recent + i * 1000, hostnameHash = host,
                    tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.EXECUTED, 400, cacheable = true)),
                    extensions = TestPayloads.internalAdapters(listOf(":app:x" to "STORED")),
                ),
            )
        }
        val result = diagnostics()
        assertEquals(false, result.remoteCacheObserved, "no REMOTE_HIT anywhere must read as an honest empty state")
        assertEquals(emptyList(), result.nonRelocatable)
    }

    @Test
    fun `the rollup is tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "b1", durationMs = 400, startedAt = recent, hostnameHash = "h1",
                tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.EXECUTED, 400, cacheable = true)),
                extensions = TestPayloads.internalAdapters(listOf(":app:x" to "REMOTE_HIT")),
            ),
        )
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertTrue(other.id != fx.project.id)
        val result = diagnostics(token = "other-token")
        assertEquals(false, result.remoteCacheObserved, "a fresh tenant has no cache-miss diagnostics data")
    }

    @Test
    fun `the days window is clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/cache-miss-diagnostics?days=0").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/cache-miss-diagnostics?days=99999").status)
    }
}
