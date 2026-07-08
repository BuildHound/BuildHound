package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.ToolchainInfo
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Bottlenecks landing rollup + toolchain adoption endpoints (plan 032), no socket. */
class BottleneckRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

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

    private val now = System.currentTimeMillis()
    private val current = now - 3_600_000 // 1h ago — inside the 7-day current window
    private val prior = now - 10L * 86_400_000 // 10 days ago — inside the prior 7..14-day window

    /**
     * KotlinCompile regresses 1000→5000ms; NewType appears only now (isNew); VanishedType only in the
     * prior window (isVanished). 2 current builds vs 3 prior → buildCount delta −1/3. c-1 carries a
     * cacheable EXECUTED task (a cache miss hotspot); prior builds are all SUCCESS.
     */
    private fun seed(fx: Fx) {
        fun save(payload: dev.buildhound.commons.payload.BuildPayload) = fx.stores.builds.save(fx.project.id, payload)
        // Prior window: 3 builds.
        repeat(2) { i ->
            save(
                TestPayloads.build(
                    buildId = "p-$i", durationMs = 2000, startedAt = prior + i, userId = "u_$i", hitRate = 0.5,
                    tasks = listOf(
                        TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 1000, type = "KotlinCompile"),
                        TestPayloads.task(":app:vanish", TaskOutcome.EXECUTED, 500, type = "VanishedType"),
                    ),
                ),
            )
        }
        save(TestPayloads.build(buildId = "p-2", durationMs = 2000, startedAt = prior + 5, userId = "u_9"))
        // Current window: 2 builds.
        repeat(2) { i ->
            save(
                TestPayloads.build(
                    buildId = "c-$i", durationMs = 6000, startedAt = current + i, userId = "u_$i", hitRate = 0.8,
                    tasks = listOf(
                        TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile"),
                        TestPayloads.task(":app:new", TaskOutcome.EXECUTED, 800, type = "NewType"),
                    ) + if (i == 0) {
                        listOf(TestPayloads.task(":app:res", TaskOutcome.EXECUTED, 1200, type = "CacheMissType", cacheable = true))
                    } else {
                        emptyList()
                    },
                ),
            )
        }
    }

    private suspend fun ApplicationTestBuilder.bottlenecks(query: String = "", token: String = "read-token"): BottlenecksRollup =
        json.decodeFromString(BottlenecksRollup.serializer(), get("/v1/rollups/bottlenecks$query", token).bodyAsText())

    @Test
    fun `both rollup routes need a read token`() = testApplication {
        val fx = fx(); appWith(fx)
        for (path in listOf("/v1/rollups/bottlenecks", "/v1/rollups/toolchain")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, path)
            assertEquals(HttpStatusCode.Forbidden, get(path, token = "ingest-token").status, "$path with ingest scope")
        }
    }

    @Test
    fun `headline KPIs carry this-window, prior-window, and the signed delta`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val r = bottlenecks()
        assertEquals(7, r.period)
        assertEquals(2.0, r.buildCount.current)
        assertEquals(3.0, r.buildCount.prior)
        assertEquals(-0.333333, r.buildCount.deltaPct) // (2-3)/3, rounded to 6dp
        assertEquals(1.0, r.successRate.current) // all builds SUCCESS both windows
        // avgHitRate over the two current builds (0.8, 0.8) vs prior (0.5, 0.5); p-2 has no hit rate.
        assertEquals(0.8, r.hitRate.current)
        assertEquals(0.5, r.hitRate.prior)
        assertEquals(0.6, r.hitRate.deltaPct) // (0.8-0.5)/0.5
    }

    @Test
    fun `a regressed task reports the duration delta and percent`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val row = bottlenecks().regressedTasks.single { it.key == "KotlinCompile" }
        assertEquals(5000, row.currentMs)
        assertEquals(1000, row.priorMs)
        assertEquals(4000, row.deltaMs)
        assertEquals(4.0, row.deltaPct)
        assertFalse(row.isNew || row.isVanished)
    }

    @Test
    fun `new and vanished groups are flagged, never divided`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val regressed = bottlenecks().regressedTasks
        val newRow = regressed.single { it.key == "NewType" }
        assertTrue(newRow.isNew)
        assertNull(newRow.priorMs)
        assertNull(newRow.deltaPct, "a brand-new group has no percent (never ∞)")
        val vanished = regressed.single { it.key == "VanishedType" }
        assertTrue(vanished.isVanished)
        assertEquals(0, vanished.currentMs)
        assertNull(vanished.deltaPct, "a vanished group has no percent (never −100)")
    }

    @Test
    fun `cache-miss hotspots surface cacheable executed tasks when the flag is present`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val r = bottlenecks()
        assertTrue(r.cacheDataAvailable)
        assertEquals(setOf("CacheMissType"), r.cacheMissHotspots.map { it.key }.toSet())
    }

    @Test
    fun `cache-miss degrades honestly when no task carries the cacheable flag`() = testApplication {
        val fx = fx(); appWith(fx)
        // A single current build with no cacheable flag on any task.
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "nc-1", durationMs = 2000, startedAt = current,
                tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.EXECUTED, 900, type = "Plain")),
            ),
        )
        val r = bottlenecks()
        assertFalse(r.cacheDataAvailable, "cacheable never populated → unavailable, not an empty consensus")
        assertTrue(r.cacheMissHotspots.isEmpty())
    }

    @Test
    fun `plan-025 verdict counts are null until a verdict store is wired`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        val r = bottlenecks()
        assertNull(r.budgetBreaches)
        assertNull(r.trendRegressions)
    }

    @Test
    fun `the period window is clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/bottlenecks?period=0").status)
        assertEquals(1, bottlenecks("?period=0").period, "period=0 clamps to 1")
        assertEquals(90, bottlenecks("?period=99999").period, "period is capped at 90")
    }

    @Test
    fun `bottlenecks are tenant-scoped`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        val r = bottlenecks(token = "other-token")
        assertEquals(0.0, r.buildCount.current)
        assertTrue(r.regressedTasks.isEmpty() && r.slowestWork.isEmpty())
    }

    @Test
    fun `benchmark builds are excluded from the fleet bottlenecks view`() = testApplication {
        val fx = fx(); appWith(fx)
        seed(fx)
        // A benchmark build in the current window must not inflate slowestWork.
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "bench-1", durationMs = 30_000, startedAt = current, mode = BuildMode.BENCHMARK,
                tasks = listOf(TestPayloads.task(":app:bench", TaskOutcome.EXECUTED, 30_000, type = "BenchType")),
            ),
        )
        assertTrue(bottlenecks().slowestWork.none { it.key == "BenchType" }, "benchmark work is not a fleet bottleneck")
    }

    @Test
    fun `top plugins folds current-window tasks by owning plugin and ranks by total time`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "tp-1", durationMs = 6500, startedAt = current,
                tasks = listOf(
                    TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 4000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile"),
                    TestPayloads.task(":app:mergeRes", TaskOutcome.EXECUTED, 2000, type = "com.android.build.gradle.tasks.MergeResources"),
                    TestPayloads.task(":app:custom", TaskOutcome.EXECUTED, 500, type = "com.example.MyTask"),
                ),
            ),
        )
        val plugins = bottlenecks().topPlugins
        assertEquals("Kotlin Gradle Plugin", plugins[0].key, "ranked first — largest total time: $plugins")
        assertEquals(4000, plugins[0].currentMs)
        assertTrue(plugins.any { it.key == "Android Gradle Plugin" && it.currentMs == 2000L }, "$plugins")
        assertTrue(plugins.any { it.key == "(unattributed)" && it.currentMs == 500L }, "an unrecognized FQCN prefix must not be dropped: $plugins")
    }

    @Test
    fun `top plugins inherit the fleet-view benchmark exclusion`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "bench-tp", durationMs = 9000, startedAt = current, mode = BuildMode.BENCHMARK,
                tasks = listOf(TestPayloads.task(":app:bench", TaskOutcome.EXECUTED, 9000, type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile")),
            ),
        )
        assertTrue(bottlenecks().topPlugins.isEmpty(), "a benchmark-only fleet has no plugin-cost bottleneck")
    }

    // ---- toolchain ----

    private suspend fun ApplicationTestBuilder.toolchain(query: String = "", token: String = "read-token"): ToolchainRollup =
        json.decodeFromString(ToolchainRollup.serializer(), get("/v1/rollups/toolchain$query", token).bodyAsText())

    private fun seedToolchain(fx: Fx) {
        // 3 builds on Gradle 8.10 (two users), 1 on 8.9 — 8.9 is behind. All on JDK 21.
        listOf("8.10" to "u_1", "8.10" to "u_2", "8.10" to "u_1", "8.9" to "u_3").forEachIndexed { i, (g, u) ->
            fx.stores.builds.save(
                fx.project.id,
                TestPayloads.build(
                    buildId = "t-$i", durationMs = 1000, startedAt = current + i, userId = u,
                    toolchain = ToolchainInfo(gradle = g, jdk = "21"),
                ),
            )
        }
    }

    @Test
    fun `toolchain reports a version distribution with hashed distinct users and a behind list`() = testApplication {
        val fx = fx(); appWith(fx)
        seedToolchain(fx)
        val r = toolchain()
        assertTrue(r.gradle.available)
        val v810 = r.gradle.versions.single { it.version == "8.10" }
        assertEquals(3, v810.builds)
        assertEquals(2, v810.distinctUsers) // u_1, u_2 — distinct over hashes
        assertEquals(0.75, v810.sharePct)
        assertEquals(listOf("8.9"), r.gradle.behind.map { it.version }, "the older version is behind the 8.10 majority")
        assertTrue(r.jdk.available)
    }

    @Test
    fun `agp, kgp and ksp report unavailable until the plugin collects them`() = testApplication {
        val fx = fx(); appWith(fx)
        seedToolchain(fx)
        val r = toolchain()
        for (dim in listOf(r.agp, r.kgp, r.ksp)) {
            assertFalse(dim.available, "dimension must be honest about not-collected-yet")
            assertTrue(dim.versions.isEmpty() && dim.behind.isEmpty())
        }
    }

    @Test
    fun `toolchain is tenant-scoped and days-clamped`() = testApplication {
        val fx = fx(); appWith(fx)
        seedToolchain(fx)
        assertEquals(HttpStatusCode.OK, get("/v1/rollups/toolchain?days=0").status)
        fx.stores.tokens.ensureProjectWithToken("other", sha256Hex("other-token"), TokenScope.READ)
        assertFalse(toolchain(token = "other-token").gradle.available, "a fresh tenant sees no toolchain data")
    }

    @Test
    fun `a build outside the window is excluded from toolchain adoption`() = testApplication {
        val fx = fx(); appWith(fx)
        fx.stores.builds.save(
            fx.project.id,
            TestPayloads.build(
                buildId = "old-1", durationMs = 1000, startedAt = now - 400L * 86_400_000,
                toolchain = ToolchainInfo(gradle = "7.0", jdk = "17"),
            ),
        )
        assertFalse(toolchain("?days=30").gradle.available, "a 400-day-old build is outside a 30-day window")
    }
}
