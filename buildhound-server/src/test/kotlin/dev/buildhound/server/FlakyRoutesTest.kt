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

/** GET /v1/flaky auth/tenant/window + the two signals, no socket (plan 036). */
class FlakyRoutesTest {

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

    @Test
    fun `flaky needs a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/flaky").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/flaky", token = "ingest-token").status)
    }

    @Test
    fun `a cross-run divergence at the same sha is reported`() = testApplication {
        val fx = fx(); appWith(fx)
        // 3 builds, same sha; FooTest passes in two and fails in one → cross-run flaky.
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "c1", startedAt = recent, sha = "sha-a", tests = listOf(TestPayloads.testTask(passed = 5, failed = 0))))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "c2", startedAt = recent + 1, sha = "sha-a", tests = listOf(TestPayloads.testTask(passed = 4, failed = 1))))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "c3", startedAt = recent + 2, sha = "sha-a", tests = listOf(TestPayloads.testTask(passed = 5, failed = 0))))

        val body = get("/v1/flaky").bodyAsText()
        assertTrue(body.contains("com.example.FooTest"), body)
        assertTrue(body.contains("\"signal\":\"CROSS_RUN\""), body)
    }

    @Test
    fun `a fail-then-pass retry is reported as a RETRY flake`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "r1", startedAt = recent, sha = "sha-a", tests = listOf(TestPayloads.testTask())))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "r2", startedAt = recent + 1, sha = "sha-a", tests = listOf(TestPayloads.testTask(retriedCases = listOf("flakyGateway()")))))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "r3", startedAt = recent + 2, sha = "sha-a", tests = listOf(TestPayloads.testTask())))

        val body = get("/v1/flaky").bodyAsText()
        assertTrue(body.contains("\"signal\":\"RETRY\""), body)
    }

    @Test
    fun `differing shas produce no flaky record`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "d1", startedAt = recent, sha = "sha-a", tests = listOf(TestPayloads.testTask(failed = 0))))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "d2", startedAt = recent + 1, sha = "sha-b", tests = listOf(TestPayloads.testTask(failed = 1))))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "d3", startedAt = recent + 2, sha = "sha-c", tests = listOf(TestPayloads.testTask(failed = 0))))
        assertEquals("[]", get("/v1/flaky").bodyAsText().trim(), "a cross-commit regression is not flaky")
    }

    @Test
    fun `flaky is tenant-scoped and days-clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "c1", startedAt = recent, sha = "sha-a", tests = listOf(TestPayloads.testTask(failed = 0))))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "c2", startedAt = recent + 1, sha = "sha-a", tests = listOf(TestPayloads.testTask(failed = 1))))
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "c3", startedAt = recent + 2, sha = "sha-a", tests = listOf(TestPayloads.testTask(failed = 0))))
        // days=0 clamps to 1 — a *working* window, not an empty one: the recent cross-run flake is
        // still reported (a route that read days=0 literally, or ignored days, would differ).
        val clamped = get("/v1/flaky?days=0")
        assertEquals(HttpStatusCode.OK, clamped.status)
        assertTrue(clamped.bodyAsText().contains("\"signal\":\"CROSS_RUN\""), "days=0 must clamp to a usable window")
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertEquals("[]", get("/v1/flaky", token = "other-token").bodyAsText().trim())
    }

    @Test
    fun `a class split across two Test tasks in one build is not a false cross-run flake`() = testApplication {
        val fx = fx(); appWith(fx)
        // Each build reports FooTest via two tasks: a green shard (5/0) and a red shard (0/1). Pre-fix,
        // the in-memory store kept both rows and the same-sha cross-run predicate fired from ONE build;
        // Postgres (PK per module/class) kept one. classOutcomesOf now sums to a single red row per
        // build, so both stores agree and a single build never looks divergent.
        val split = listOf(
            TestPayloads.testTask(passed = 5, failed = 0),
            TestPayloads.testTask(passed = 0, failed = 1),
        )
        for (i in 0..2) {
            fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "s$i", startedAt = recent + i, sha = "sha-a", tests = split))
        }
        assertEquals("[]", get("/v1/flaky").bodyAsText().trim(), "a within-build shard split is not cross-run flakiness")
    }
}
