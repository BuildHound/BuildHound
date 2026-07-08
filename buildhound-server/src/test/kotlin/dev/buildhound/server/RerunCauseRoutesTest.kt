package dev.buildhound.server

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

/** Route/auth-matrix + fleet-view (benchmark-exclusion) coverage for `/v1/rollups/rerun-causes` (plan 061). */
class RerunCauseRoutesTest {

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

    private suspend fun ApplicationTestBuilder.rerunCauses(token: String = "read-token"): RerunCauseRollup {
        val body = get("/v1/rollups/rerun-causes", token).bodyAsText()
        return BuildHoundJson.payload.decodeFromString(RerunCauseRollup.serializer(), body)
    }

    private val recent = System.currentTimeMillis() - 3_600_000

    @Test
    fun `the route requires a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rollups/rerun-causes").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/rollups/rerun-causes", token = "ingest-token").status)
    }

    @Test
    fun `an empty project reads as an honest-empty rollup`() = testApplication {
        val fx = fx(); appWith(fx)
        val rollup = rerunCauses()
        assertEquals(emptyList(), rollup.buckets)
        assertEquals(0.0, rollup.unclassifiedSharePct)
        assertEquals(0, rollup.executedTaskCount)
        assertNull(rollup.cascadeRate)
        assertNull(rollup.buildLogicStormCandidate)
    }

    @Test
    fun `bucket coverage and the storm candidate are reported for a fleet with classpath rebuilds`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "r-1", durationMs = 1000, startedAt = recent,
                tasks = listOf(
                    TestPayloads.task(
                        ":app:compileJava", TaskOutcome.EXECUTED, 400,
                        executionReasons = listOf("Class path of task ':app:compileJava' has changed from 'a' to 'b'."),
                    ),
                    TestPayloads.task(
                        ":app:compileKotlin", TaskOutcome.EXECUTED, 600,
                        executionReasons = listOf("Value of input property 'x' has changed."),
                    ),
                ),
            ),
        )
        val rollup = rerunCauses()
        assertTrue(rollup.buckets.any { it.cause == RerunCause.IMPL_CLASSPATH.name }, "$rollup")
        assertTrue(rollup.buckets.any { it.cause == RerunCause.SOURCE.name }, "$rollup")
        val candidate = rollup.buildLogicStormCandidate
        assertTrue(candidate != null, "40% IMPL_CLASSPATH coverage must surface the storm candidate: $rollup")
        assertTrue(candidate.message.contains("build-logic"), candidate.message)
    }

    @Test
    fun `an executed task with no reasons lands in UNCLASSIFIED, never silently dropped`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "r-2", durationMs = 500, startedAt = recent,
                tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.EXECUTED, 500)),
            ),
        )
        val rollup = rerunCauses()
        assertEquals(RerunCause.UNCLASSIFIED.name, rollup.buckets.single().cause)
        assertEquals(1.0, rollup.unclassifiedSharePct)
    }

    @Test
    fun `benchmark builds are excluded from the fleet rollup`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "bench-1", durationMs = 500, startedAt = recent, mode = BuildMode.BENCHMARK,
                tasks = listOf(
                    TestPayloads.task(
                        ":app:compileJava", TaskOutcome.EXECUTED, 500,
                        executionReasons = listOf("Class path of task ':app:compileJava' has changed from 'a' to 'b'."),
                    ),
                ),
            ),
        )
        val rollup = rerunCauses()
        assertEquals(emptyList(), rollup.buckets, "a benchmark-only fleet must read as empty")
    }

    @Test
    fun `the rollup is tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "r-1", durationMs = 500, startedAt = recent,
                tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.EXECUTED, 500, executionReasons = listOf("No history is available."))),
            ),
        )
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertTrue(other.id != fx.project.id)
        val rollup = rerunCauses(token = "other-token")
        assertEquals(emptyList(), rollup.buckets, "a fresh tenant has no rerun-cause data")
    }

    @Test
    fun `the days window is clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/rerun-causes?days=0").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/rerun-causes?days=99999").status)
    }
}
