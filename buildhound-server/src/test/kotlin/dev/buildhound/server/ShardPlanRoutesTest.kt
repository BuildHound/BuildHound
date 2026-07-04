package dev.buildhound.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** POST /v1/addons/test-sharding/plan auth/validation/idempotency/coverage (plan 040), no socket. */
class ShardPlanRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-token"), TokenScope.INGEST)
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) = application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.plan(body: String, token: String = "ingest-token") =
        client.post("/v1/addons/test-sharding/plan") { header("Authorization", "Bearer $token"); setBody(body) }

    private fun req(index: Int, total: Int = 2, reference: String = "run-1", suites: String = """["a","b","c","d"]""") =
        """{"reference":"$reference","index":$index,"total":$total,"suites":$suites}"""

    @Test
    fun `the plan endpoint requires an ingest token`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/addons/test-sharding/plan") { setBody(req(1)) }.status)
        assertEquals(HttpStatusCode.Forbidden, plan(req(1), token = "read-token").status)
    }

    @Test
    fun `shards partition the suites and their union covers the full set`() = testApplication {
        val fx = fx(); appWith(fx)
        val s1 = plan(req(index = 1, total = 2)).bodyAsText()
        val s2 = plan(req(index = 2, total = 2)).bodyAsText()
        // Each response is this shard's class list; together they cover all 4 suites, none twice.
        val classesRegex = Regex("\"classes\":\\[(.*?)]")
        fun classesOf(json: String) = classesRegex.find(json)!!.groupValues[1].split(",").filter { it.isNotBlank() }.map { it.trim('"') }
        val union = (classesOf(s1) + classesOf(s2)).toSet()
        assertEquals(setOf("a", "b", "c", "d"), union)
        assertEquals(4, classesOf(s1).size + classesOf(s2).size, "no suite assigned to two shards")
    }

    @Test
    fun `the plan is idempotent per reference and total`() = testApplication {
        val fx = fx(); appWith(fx)
        val first = plan(req(index = 1, total = 2, reference = "ref-x")).bodyAsText()
        // A later caller with a DIFFERENT suite list still gets the memoized plan for shard 1.
        val again = plan(req(index = 1, total = 2, reference = "ref-x", suites = """["z","y","x"]""")).bodyAsText()
        assertEquals(first, again, "the first caller fixes the plan; later callers read it")
    }

    @Test
    fun `invalid reference or index or total is a 400`() = testApplication {
        val fx = fx(); appWith(fx)
        assertEquals(HttpStatusCode.BadRequest, plan(req(index = 3, total = 2)).status) // index > total
        assertEquals(HttpStatusCode.BadRequest, plan(req(index = 1, total = 0)).status) // total < 1
        assertEquals(HttpStatusCode.BadRequest, plan(req(index = 1, reference = "")).status) // blank reference
        assertEquals(HttpStatusCode.BadRequest, plan(req(index = 1, total = 2_000)).status) // total > MAX_SHARDS (DoS guard)
        assertEquals(HttpStatusCode.BadRequest, plan("not json").status)
    }

    @Test
    fun `no timing history still returns a floor round-robin plan, not an error`() = testApplication {
        val fx = fx(); appWith(fx)
        // No builds ingested → no class timings → 5s floor → even distribution, HTTP 200.
        val resp = plan(req(index = 1, total = 2))
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"classes\""))
    }

    @Test
    fun `plans are tenant-isolated`() = testApplication {
        val fx = fx(); appWith(fx)
        plan(req(index = 1, total = 2, reference = "shared-ref"))
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-ingest"), TokenScope.INGEST)
        // Same reference/total under another tenant computes its OWN plan (different shardPlanId).
        val a = plan(req(index = 1, total = 2, reference = "shared-ref")).bodyAsText()
        val b = plan(req(index = 1, total = 2, reference = "shared-ref"), token = "other-ingest").bodyAsText()
        val idRegex = Regex("\"shardPlanId\":\"(.*?)\"")
        assertTrue(idRegex.find(a)!!.groupValues[1] != idRegex.find(b)!!.groupValues[1], "different tenants → different plan ids")
    }
}
