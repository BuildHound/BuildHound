package dev.buildhound.server

import dev.buildhound.commons.payload.BuildCacheConfigInfo
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.ConfigurationCacheState
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
 * projectKey filter completion (plan 079): the post-077 surfaces honor the selector. Two repos under
 * one tenant; filtered reads must show only the selected repo's data, param-absent reads stay
 * fleet-wide. Store-level parity lives in PostgresStoresIntegrationTest; this covers the route wiring
 * (validation, plumbing into the re-signed store methods, and the delivery-health flaky enrichment).
 */
class ProjectKeyFilterRoutesTest {

    private class Fx(val stores: ServerStores, val project: ProjectRef)

    private fun fx(): Fx {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        val project = stores.tokens.ensureProjectWithToken("pilot", sha256Hex("read-token"), TokenScope.READ)
        return Fx(stores, project)
    }

    private fun ApplicationTestBuilder.appWith(fx: Fx) =
        application { buildHoundModule(fx.stores, RateLimits(0, 0, 0)) }

    private suspend fun ApplicationTestBuilder.get(path: String) =
        client.get(path) { header("Authorization", "Bearer read-token") }

    private val recent = System.currentTimeMillis() - 3_600_000
    private val remoteConfigured = BuildCacheConfigInfo(localEnabled = true, remoteEnabled = true, remotePush = true, remoteType = "HttpBuildCache")

    /**
     * Two repos with disjoint plugins/tags/test classes so every filtered read has an unambiguous
     * "mine" vs "sibling" marker. Each repo carries the plan-059 same-key rerun trio (a FAILED build,
     * a same-sha rerun with an intra-run retry, and a third build to clear FlakyDetector.MIN_SAMPLES),
     * so delivery-health's retry tax AND its plan-036 flakyRerunTax panel are non-vacuous per repo.
     */
    private fun seed(fx: Fx) {
        fun repo(key: String, cls: String, taskType: String, sha: String, at: Long) {
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "$key-skfail", outcome = BuildOutcome.FAILED, startedAt = at, durationMs = 60_000,
                    sha = sha, projectKey = key, tags = mapOf("team" to if (key == "repo-a") "alpha" else "beta"),
                    buildCache = remoteConfigured,
                    tasks = listOf(TestPayloads.task(":app:work", TaskOutcome.EXECUTED, 5000, type = taskType)),
                    tests = listOf(TestPayloads.testTask(className = cls)),
                ),
            )
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "$key-skrerun", startedAt = at + 100_000, durationMs = 120_000, sha = sha, projectKey = key,
                    tests = listOf(TestPayloads.testTask(className = cls, retriedCases = listOf("flakyCase()"))),
                ),
            )
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "$key-s3", startedAt = at + 200_000, durationMs = 60_000, sha = "$key-green", projectKey = key,
                    tests = listOf(TestPayloads.testTask(className = cls)),
                ),
            )
        }
        repo("repo-a", "com.a.CartTest", "org.jetbrains.kotlin.gradle.tasks.KotlinCompile", "rerun-a", recent)
        repo("repo-b", "com.b.OrderTest", "org.gradle.api.tasks.compile.JavaCompile", "rerun-b", recent + 1000)
    }

    @Test
    fun `plugin-cost is filtered by projectKey`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val unfiltered = get("/v1/rollups/plugin-cost").bodyAsText()
        assertTrue(unfiltered.contains("Kotlin Gradle Plugin") && unfiltered.contains("Gradle core"), unfiltered)
        val filtered = get("/v1/rollups/plugin-cost?projectKey=repo-a").bodyAsText()
        assertTrue(filtered.contains("Kotlin Gradle Plugin"), filtered)
        assertFalse(filtered.contains("Gradle core"), "repo-b's JavaCompile time is excluded: $filtered")
    }

    @Test
    fun `cache-roi is filtered by projectKey`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        fun buildsWithConfig(body: String) =
            BuildHoundJson.payload.decodeFromString(CacheRoiRollup.serializer(), body).buildsWithConfig
        assertEquals(2, buildsWithConfig(get("/v1/rollups/cache-roi").bodyAsText()), "both repos' snapshots fleet-wide")
        assertEquals(1, buildsWithConfig(get("/v1/rollups/cache-roi?projectKey=repo-a").bodyAsText()), "repo-a's only")
    }

    @Test
    fun `delivery-health is filtered by projectKey, including the flakyRerunTax panel`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val unfiltered = get("/v1/rollups/delivery-health").bodyAsText()
        assertTrue(unfiltered.contains("\"sameKeyCandidates\":2"), unfiltered)
        assertTrue(unfiltered.contains("com.a.CartTest") && unfiltered.contains("com.b.OrderTest"), unfiltered)

        val filtered = get("/v1/rollups/delivery-health?projectKey=repo-a").bodyAsText()
        assertTrue(filtered.contains("\"sameKeyCandidates\":1"), filtered)
        assertFalse(filtered.contains("repo-b-skrerun"), "repo-b's rerun build never appears: $filtered")
        // The enrichment's internal flaky window inherits the key — a sibling repo's flaky class
        // must not claim this repo's rerun builds.
        assertTrue(filtered.contains("com.a.CartTest"), filtered)
        assertFalse(filtered.contains("com.b.OrderTest"), "repo-b's flaky class is excluded: $filtered")
    }

    @Test
    fun `tags and trends-cohorts are filtered by projectKey`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val tagsUnfiltered = get("/v1/tags").bodyAsText()
        assertTrue(tagsUnfiltered.contains("alpha") && tagsUnfiltered.contains("beta"), tagsUnfiltered)
        val tagsFiltered = get("/v1/tags?projectKey=repo-a").bodyAsText()
        assertTrue(tagsFiltered.contains("alpha"), tagsFiltered)
        assertFalse(tagsFiltered.contains("beta"), "repo-b's tag value is excluded: $tagsFiltered")

        val cohortsUnfiltered = get("/v1/trends/cohorts?tag=team").bodyAsText()
        assertTrue(cohortsUnfiltered.contains("alpha") && cohortsUnfiltered.contains("beta"), cohortsUnfiltered)
        val cohortsFiltered = get("/v1/trends/cohorts?tag=team&projectKey=repo-a").bodyAsText()
        assertTrue(cohortsFiltered.contains("alpha"), cohortsFiltered)
        assertFalse(cohortsFiltered.contains("beta"), "repo-b's cohort is excluded: $cohortsFiltered")
    }

    @Test
    fun `an over-long projectKey is a 400 on every newly wired route`() = testApplication {
        val fx = fx(); appWith(fx)
        val tooLong = "x".repeat(257)
        val paths = listOf(
            "/v1/rollups/plugin-cost", "/v1/rollups/change-blast-radius", "/v1/rollups/rerun-causes",
            "/v1/rollups/warnings", "/v1/rollups/cache-miss-diagnostics", "/v1/rollups/cache-roi",
            "/v1/rollups/cc-economics", "/v1/rollups/recommendations", "/v1/rollups/delivery-health",
            "/v1/tags", "/v1/trends/cohorts?tag=team",
        )
        for (path in paths) {
            val sep = if (path.contains("?")) "&" else "?"
            assertEquals(HttpStatusCode.BadRequest, get("$path${sep}projectKey=$tooLong").status, path)
        }
    }

    @Test
    fun `a capped window filters before the cap - a busy sibling repo cannot starve the selection`() {
        // The trap (plan 079): with the newest builds all from repo-b, a post-LIMIT filter would slice
        // the cap-sized newest set first and then find no repo-a rows in it. windowPayloads has a
        // per-call cap, so it proves the placement directly at cap=2.
        val store = InMemoryBuildStore()
        store.save("p", TestPayloads.build(buildId = "a-1", startedAt = recent, projectKey = "repo-a"))
        store.save("p", TestPayloads.build(buildId = "a-2", startedAt = recent + 1000, projectKey = "repo-a"))
        for (i in 1..3) {
            store.save("p", TestPayloads.build(buildId = "b-$i", startedAt = recent + 10_000 + i, projectKey = "repo-b"))
        }
        val now = System.currentTimeMillis()
        val filtered = store.windowPayloads("p", 30, 2, now, "repo-a")
        assertEquals(listOf("a-2", "a-1"), filtered.map { it.buildId }, "the selected repo fills the cap despite newer siblings")
        // Unchanged fleet view: the cap takes the newest builds regardless of repo.
        assertEquals(listOf("b-3", "b-2"), store.windowPayloads("p", 30, 2, now).map { it.buildId })
    }

    @Test
    fun `the three constant-capped rollups also filter before their caps`() {
        // Same trap, per method (plan 079 review): the caps are constructor-injectable so each
        // hand-written LIMIT query's pre-LIMIT placement is testable at cap=2 — a refactor to
        // fetch-then-post-filter fails all three. The sibling repo's builds pass every gate too
        // (buildCache + internalAdapters), so in the broken shape they'd fill the whole capped window.
        val store = InMemoryBuildStore(diagnosticRowCap = 2, cacheRoiRowCap = 2, ccEconomicsRowCap = 2)
        seedCapStarvation(store)
        val now = System.currentTimeMillis()
        assertTrue(
            store.cacheMissDiagnostics("p", 30, now, "repo-a").remoteCacheObserved,
            "repo-a's REMOTE_HIT rows fill the capped window despite newer gated siblings",
        )
        assertEquals(
            2, store.cacheRoi("p", 30, now, "repo-a").buildsWithConfig,
            "repo-a's config snapshots fill the capped window despite newer gated siblings",
        )
        assertEquals(
            2, store.ccEconomics("p", 30, now, "repo-a").ccObservedBuilds,
            "repo-a's CC posture fills the capped window despite newer siblings",
        )
    }

    /**
     * Two OLD repo-a builds that satisfy every capped read's gate (buildCache config + an
     * internalAdapters REMOTE_HIT origin + a CC HIT posture) under three NEWER repo-b builds that
     * satisfy the same gates — so a post-LIMIT filter at cap=2 would fetch only repo-b rows and
     * return an empty/false filtered result for repo-a.
     */
    private fun seedCapStarvation(store: BuildStore) {
        for (i in 1..2) {
            store.save(
                "p",
                TestPayloads.build(
                    buildId = "gated-a-$i", startedAt = recent + i, projectKey = "repo-a",
                    buildCache = remoteConfigured, configurationCache = ConfigurationCacheState.HIT, ccLoadMs = 100,
                    extensions = TestPayloads.internalAdapters(listOf(":app:x" to "REMOTE_HIT")),
                    tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.FROM_CACHE, 10, cacheable = true)),
                ),
            )
        }
        for (i in 1..3) {
            store.save(
                "p",
                TestPayloads.build(
                    buildId = "gated-b-$i", startedAt = recent + 10_000 + i, projectKey = "repo-b",
                    buildCache = remoteConfigured, configurationCache = ConfigurationCacheState.DISABLED,
                    extensions = TestPayloads.internalAdapters(listOf(":lib:y" to "MISS")),
                    tasks = listOf(TestPayloads.task(":lib:y", TaskOutcome.EXECUTED, 10, cacheable = true)),
                ),
            )
        }
    }
}
