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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dashboard project selector (plan 077): the `/v1/project-keys` enumeration endpoint and the optional
 * `projectKey` filter on the query surface. In-memory routes; parity with Postgres is covered by
 * PostgresStoresIntegrationTest. Terminology guard: `projectKey` is the *payload* axis, never the tenant.
 */
class ProjectKeyRoutesTest {

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

    // Recent, so builds fall inside the rollup/trend real-clock windows (routes use System.now()).
    private val recent = System.currentTimeMillis() - 3_600_000

    /** Two repos under one tenant: repo-a (3 builds, newer) + repo-b (1 build, older) + a pre-077 null. */
    private fun seed(fx: Fx) {
        // repo-a's FooTest diverges across ≥3 same-sha builds → a CROSS_RUN flake (FlakyDetector.MIN_SAMPLES).
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "a-1", startedAt = recent + 2000, projectKey = "repo-a", userId = "u_1",
                tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(className = "com.a.FooTest", passed = 5, failed = 0)),
                sha = "sha-a",
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "a-2", startedAt = recent + 3000, projectKey = "repo-a", userId = "u_2",
                tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 6000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(className = "com.a.FooTest", passed = 4, failed = 1)),
                sha = "sha-a",
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "a-3", startedAt = recent + 4000, projectKey = "repo-a", userId = "u_1",
                tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 7000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(className = "com.a.FooTest", passed = 5, failed = 0)),
                sha = "sha-a",
            ),
        )
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "b-1", startedAt = recent + 1000, projectKey = "repo-b", userId = "u_1",
                tasks = listOf(TestPayloads.task(":lib:compileKotlin", TaskOutcome.EXECUTED, 4000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(module = ":lib", className = "com.b.BarTest", passed = 3, retriedCases = listOf("flaky()"))),
                sha = "sha-b",
            ),
        )
        // A pre-077 build (null projectKey) — appears only under "all projects", never as a selectable key.
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(buildId = "n-1", startedAt = recent, projectKey = null, userId = "u_1"),
        )
    }

    @Test
    fun `project-keys needs a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/project-keys").status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/project-keys", token = "ingest-token").status)
    }

    @Test
    fun `project-keys lists distinct non-null keys, newest-activity first, with counts`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val body = get("/v1/project-keys").bodyAsText()
        // Both repos present; the null-key build contributes no row.
        assertTrue(body.contains("repo-a"), body)
        assertTrue(body.contains("repo-b"), body)
        assertFalse(body.contains("\"projectKey\":null"), "null projectKey is never a selectable key: $body")
        // repo-a's last build (recent+4000) is newer than repo-b's (recent+1000) → repo-a first.
        assertTrue(body.indexOf("repo-a") < body.indexOf("repo-b"), "newest-activity first: $body")
        // repo-a has 3 builds, repo-b has 1.
        assertTrue(body.contains("\"projectKey\":\"repo-a\",\"builds\":3"), body)
        assertTrue(body.contains("\"projectKey\":\"repo-b\",\"builds\":1"), body)
    }

    @Test
    fun `project-keys is empty when no build carries a non-null key`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "only-null", projectKey = null))
        assertEquals("[]", get("/v1/project-keys").bodyAsText().trim())
    }

    @Test
    fun `project-keys is tenant-scoped — another tenant's keys never leak`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val other = fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        fx.stores.builds.save(other.id, TestPayloads.build(buildId = "o-1", projectKey = "secret-repo"))
        val body = get("/v1/project-keys", token = "other-token").bodyAsText()
        assertTrue(body.contains("secret-repo"), body)
        assertFalse(body.contains("repo-a"), "pilot's keys must not leak to another tenant: $body")
    }

    @Test
    fun `projectKey filter narrows the builds list and its X-Total-Count together`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val all = get("/v1/builds")
        assertEquals("5", all.headers["X-Total-Count"], "unfiltered total counts every build")

        val filtered = get("/v1/builds?projectKey=repo-a")
        val ids = Regex("\"buildId\":\"([^\"]+)\"").findAll(filtered.bodyAsText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("a-3", "a-2", "a-1"), ids, "only repo-a builds, newest first")
        // The header is filter-aware and must not drift from the returned page.
        assertEquals("3", filtered.headers["X-Total-Count"], "count and list share the one filter")
    }

    @Test
    fun `projectKey filters trends`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val unfiltered = get("/v1/trends").bodyAsText()
        val filtered = get("/v1/trends?projectKey=repo-a").bodyAsText()
        // 5 builds unfiltered on the (single recent) day, 3 for repo-a.
        assertTrue(unfiltered.contains("\"builds\":5"), unfiltered)
        assertTrue(filtered.contains("\"builds\":3"), filtered)
    }

    @Test
    fun `projectKey filters a rollup`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val filtered = get("/v1/rollups/project-cost?projectKey=repo-a").bodyAsText()
        assertTrue(filtered.contains("\":app\""), "repo-a's :app module is present: $filtered")
        assertFalse(filtered.contains("\":lib\""), "repo-b's :lib module is filtered out: $filtered")
    }

    @Test
    fun `projectKey filters flaky`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val filtered = get("/v1/flaky?projectKey=repo-a").bodyAsText()
        assertTrue(filtered.contains("com.a.FooTest"), "repo-a's cross-run flake is present: $filtered")
        assertFalse(filtered.contains("com.b.BarTest"), "repo-b's flake is filtered out: $filtered")
    }

    @Test
    fun `an over-long projectKey param is a 400`() = testApplication {
        val fx = fx(); appWith(fx)
        val tooLong = "x".repeat(257)
        for (path in listOf("/v1/builds", "/v1/trends", "/v1/rollups/project-cost", "/v1/flaky", "/v1/benchmark/series")) {
            assertEquals(HttpStatusCode.BadRequest, get("$path?projectKey=$tooLong").status, path)
        }
        // 256 is the boundary and stays valid.
        assertEquals(HttpStatusCode.OK, get("/v1/builds?projectKey=${"x".repeat(256)}").status)
    }

    @Test
    fun `an over-long payload projectKey is clamped at save and stays reachable`() = testApplication {
        val fx = fx(); appWith(fx)
        // The store clamps to MAX_HOT_STRING_CHARS (btree-index poison-pill guard, plans 077/078) — the
        // clamped key must equal the query-param cap, so the build stays reachable by filter.
        fx.stores.builds.save(fx.project.id, TestPayloads.build(buildId = "long-1", projectKey = "k".repeat(3000)))
        val keys = get("/v1/project-keys").bodyAsText()
        assertTrue(keys.contains("k".repeat(256)), "clamped key is enumerated: ${keys.take(400)}")
        assertFalse(keys.contains("k".repeat(257)), "no key longer than the cap survives save")
        val filtered = get("/v1/builds?projectKey=${"k".repeat(256)}")
        assertEquals(HttpStatusCode.OK, filtered.status)
        assertTrue(filtered.bodyAsText().contains("\"buildId\":\"long-1\""), "the clamped key filters to the build")
        assertEquals("1", filtered.headers["X-Total-Count"])
    }

    @Test
    fun `project-keys enumeration is capped, dropping the longest-idle keys`() = testApplication {
        val fx = fx(); appWith(fx)
        // MAX_PROJECT_KEYS + 5 distinct keys with ascending activity: the 5 oldest fall off the end.
        for (i in 1..MAX_PROJECT_KEYS + 5) {
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(buildId = "cap-$i", startedAt = recent + i * 1000L, projectKey = "repo-%04d".format(i)),
            )
        }
        val body = get("/v1/project-keys").bodyAsText()
        assertEquals(MAX_PROJECT_KEYS, Regex("\"projectKey\":").findAll(body).count(), "exactly the cap")
        for (i in 1..5) assertFalse(body.contains("repo-%04d".format(i)), "oldest-activity key repo-%04d dropped".format(i))
        assertTrue(body.contains("repo-%04d".format(MAX_PROJECT_KEYS + 5)), "newest-activity key kept")
    }

    @Test
    fun `an empty projectKey param is a literal value, not unset`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        // "" is a literal filter value like branch= — no build has projectKey "" — do not normalize.
        val response = get("/v1/builds?projectKey=")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("0", response.headers["X-Total-Count"], "empty string matches nothing")
    }
}
