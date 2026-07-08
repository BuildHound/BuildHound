package dev.buildhound.server

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

class RollupRoutesTest {

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

    // Recent, so the builds fall inside the rollups' real-clock window (routes use System.now()).
    private val recent = System.currentTimeMillis() - 3_600_000

    /** Two builds over :app: one executes the compile, one has a cache hit slower than that. */
    private fun seedRollupFixture(fx: Fx) {
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "r-1", durationMs = 10_000, startedAt = recent, userId = "u_1",
                tasks = listOf(
                    TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile"),
                    TestPayloads.task(":app:test", TaskOutcome.EXECUTED, 3000, type = "Test"),
                ),
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "r-2", durationMs = 9_000, startedAt = recent + 1000, userId = "u_2",
                tasks = listOf(
                    // A cache "hit" that ran 9s — slower than the 5s executed median → negative avoidance.
                    TestPayloads.task(":app:compileKotlin", TaskOutcome.FROM_CACHE, 9000, type = "KotlinCompile"),
                ),
            ),
        )
    }

    @Test
    fun `each rollup route needs a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        for (path in listOf("/v1/rollups/project-cost", "/v1/rollups/task-duration", "/v1/rollups/negative-avoidance", "/v1/rollups/plugin-cost")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, path)
            assertEquals(HttpStatusCode.Forbidden, get(path, token = "ingest-token").status, "$path with ingest scope")
        }
    }

    @Test
    fun `project cost reports per-module builds, distinct users, and a cost scalar`() = testApplication {
        val fx = fx(); appWith(fx)
        seedRollupFixture(fx)
        val body = get("/v1/rollups/project-cost").bodyAsText()
        assertTrue(body.contains("\":app\""), body)
        assertTrue(body.contains("\"builds\":2"), body)
        assertTrue(body.contains("\"executedBuilds\":1"), body)
        assertTrue(body.contains("\"buildImpactedUsers\":2"), body)
    }

    @Test
    fun `task duration ranks by name and marks by-type available when types exist`() = testApplication {
        val fx = fx(); appWith(fx)
        seedRollupFixture(fx)
        val body = get("/v1/rollups/task-duration").bodyAsText()
        assertTrue(body.contains("\"byTypeAvailable\":true"), body)
        assertTrue(body.contains("compileKotlin"), body)
        assertTrue(body.contains("KotlinCompile"), body)
    }

    @Test
    fun `by-type is marked unavailable when no ingested task carries a type`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "notype", startedAt = recent,
                tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.EXECUTED, 100)),
            ),
        )
        val body = get("/v1/rollups/task-duration").bodyAsText()
        assertTrue(body.contains("\"byTypeAvailable\":false"), body)
        assertTrue(body.contains("\"x\""), "the by-name ranking still lists the task: $body")
    }

    @Test
    fun `negative avoidance flags a cache hit slower than the executed median`() = testApplication {
        val fx = fx(); appWith(fx)
        seedRollupFixture(fx)
        val body = get("/v1/rollups/negative-avoidance").bodyAsText()
        assertTrue(body.contains("KotlinCompile"), body)
        assertTrue(body.contains("\"totalExcessMs\":4000"), body) // 9000 − 5000
    }

    @Test
    fun `plugin cost folds tasks by owning-plugin FQCN prefix, with an honest unattributed bucket`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "pc-1", startedAt = recent,
                tasks = listOf(
                    TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 6000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile"),
                    TestPayloads.task(":app:javac", TaskOutcome.EXECUTED, 2000, type = "org.gradle.api.tasks.compile.JavaCompile"),
                    TestPayloads.task(":app:custom", TaskOutcome.EXECUTED, 2000, type = "com.example.MyTask"),
                ),
            ),
        )
        val body = get("/v1/rollups/plugin-cost").bodyAsText()
        assertTrue(body.contains("\"available\":true"), body)
        assertTrue(body.contains("\"plugin\":\"Kotlin Gradle Plugin\""), body)
        assertTrue(body.contains("\"totalMs\":6000"), body)
        assertTrue(body.contains("\"plugin\":\"Gradle core\""), body)
        assertTrue(body.contains("\"plugin\":\"(unattributed)\""), "a build-script-defined type must not be silently dropped: $body")
        assertTrue(body.contains("\"sharePct\":0.6"), "6000 / 10000 total: $body")
    }

    @Test
    fun `plugin cost is unavailable when no ingested task carries a type, but still folds an honest row`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "notype", startedAt = recent,
                tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.EXECUTED, 100)),
            ),
        )
        val body = get("/v1/rollups/plugin-cost").bodyAsText()
        assertTrue(body.contains("\"available\":false"), body)
        assertTrue(body.contains("\"plugin\":\"(unattributed)\""), body)
    }

    @Test
    fun `plugin cost is tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "pc-2", startedAt = recent,
                tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 1000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile")),
            ),
        )
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertTrue(other.id != fx.project.id)
        val body = get("/v1/rollups/plugin-cost", token = "other-token").bodyAsText()
        assertTrue(body.contains("\"available\":false"), "a fresh tenant has no plugin-cost data: $body")
        assertTrue(body.contains("\"plugins\":[]"), body)
    }

    @Test
    fun `rollups are tenant-scoped — another project's builds never appear`() = testApplication {
        val fx = fx(); appWith(fx)
        seedRollupFixture(fx)
        // A second tenant with its own token sees nothing of pilot's data.
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertTrue(other.id != fx.project.id)
        val body = get("/v1/rollups/project-cost", token = "other-token").bodyAsText()
        assertEquals("[]", body.trim(), "a fresh tenant has no rollup rows")
    }

    @Test
    fun `the days window is clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        seedRollupFixture(fx)
        // days=0 clamps to 1; days far in the past still returns 200 with a valid body.
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/task-duration?days=0").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/task-duration?days=99999").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/plugin-cost?days=0").status)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/plugin-cost?days=99999").status)
    }
}
