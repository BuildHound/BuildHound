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
import kotlin.test.assertTrue

/** Route/auth-matrix + fleet-view (benchmark-exclusion) coverage for `/v1/rollups/warnings` (plan 060). */
class WarningRoutesTest {

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

    private suspend fun ApplicationTestBuilder.warnings(period: Int = 7, token: String = "read-token"): WarningsRollup {
        val body = get("/v1/rollups/warnings?period=$period", token).bodyAsText()
        return BuildHoundJson.payload.decodeFromString(WarningsRollup.serializer(), body)
    }

    private val recent = System.currentTimeMillis() - 3_600_000

    @Test
    fun `the route requires a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rollups/warnings").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/rollups/warnings", token = "ingest-token").status)
    }

    @Test
    fun `an empty project reads as an honest-empty rollup`() = testApplication {
        val fx = fx(); appWith(fx)
        val rollup = warnings()
        assertEquals(7, rollup.period)
        assertEquals(emptyList(), rollup.warnings)
        assertEquals(false, rollup.typeDataAvailable)
    }

    @Test
    fun `the happy path returns a ranked ALWAYS_RUN candidate with evidence`() = testApplication {
        val fx = fx(); appWith(fx)
        for (i in 1..3) {
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "b$i", durationMs = 500, startedAt = recent + i * 1000,
                    tasks = listOf(
                        TestPayloads.task(
                            ":app:customTask", TaskOutcome.EXECUTED, 100,
                            executionReasons = listOf("Task.upToDateWhen is false."),
                        ),
                    ),
                ),
            )
        }
        val rollup = warnings()
        val row = rollup.warnings.single { it.category == WarningCategory.ALWAYS_RUN.name }
        assertEquals(3, row.buildsObserved)
        assertEquals(3, row.buildsAffected)
        assertEquals("Task.upToDateWhen is false.", row.evidenceReason)
    }

    @Test
    fun `benchmark builds are excluded from the fleet rollup`() = testApplication {
        val fx = fx(); appWith(fx)
        for (i in 1..3) {
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "bench-$i", durationMs = 500, startedAt = recent + i * 1000, mode = BuildMode.BENCHMARK,
                    tasks = listOf(
                        TestPayloads.task(
                            ":app:customTask", TaskOutcome.EXECUTED, 100,
                            executionReasons = listOf("Task.upToDateWhen is false."),
                        ),
                    ),
                ),
            )
        }
        val rollup = warnings()
        assertEquals(emptyList(), rollup.warnings, "a benchmark-only fleet must read as empty")
    }

    @Test
    fun `the rollup is tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        for (i in 1..3) {
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "b$i", durationMs = 500, startedAt = recent + i * 1000,
                    tasks = listOf(
                        TestPayloads.task(
                            ":app:customTask", TaskOutcome.EXECUTED, 100,
                            executionReasons = listOf("Task.upToDateWhen is false."),
                        ),
                    ),
                ),
            )
        }
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertTrue(other.id != fx.project.id)
        val rollup = warnings(token = "other-token")
        assertEquals(emptyList(), rollup.warnings, "a fresh tenant has no warning data")
    }

    @Test
    fun `the period window is clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/warnings?period=0").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/warnings?period=99999").status)
    }
}
