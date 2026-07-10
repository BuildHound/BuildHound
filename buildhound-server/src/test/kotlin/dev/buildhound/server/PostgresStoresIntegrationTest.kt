package dev.buildhound.server

import dev.buildhound.commons.payload.ArtifactSize
import dev.buildhound.commons.payload.ArtifactSizes
import dev.buildhound.commons.payload.ArtifactType
import dev.buildhound.commons.payload.BuildCacheConfigInfo
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.ChangeDiffBase
import dev.buildhound.commons.payload.ChangedModulesInfo
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-database round trip (architecture §5: Testcontainers). Skipped where Docker is
 * unavailable (dev sandboxes); CI runners have it. Runs against plain Postgres, which
 * also exercises the migration's guarded TimescaleDB block.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStoresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
    }

    private lateinit var dataSource: DataSource
    private lateinit var builds: PostgresBuildStore
    private lateinit var tokens: PostgresTokenStore

    @BeforeAll
    fun setUp() {
        dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        migrate(dataSource)
        builds = PostgresBuildStore(dataSource)
        tokens = PostgresTokenStore(dataSource)
    }

    private fun payload(buildId: String, startedAt: Long = 1751450000000, outcome: String = "SUCCESS", branch: String = "main") =
        BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            """
            {
              "schemaVersion": 1,
              "buildId": "$buildId",
              "startedAt": $startedAt,
              "finishedAt": ${startedAt + 60000},
              "outcome": "$outcome",
              "mode": "ci",
              "vcs": {"branch": "$branch"},
              "derived": {"cacheableHitRate": 0.5}
            }
            """.trimIndent(),
        )

    @Test
    fun `bootstrap is idempotent and tokens resolve by hash`() {
        val first = tokens.ensureProjectWithToken("pilot", sha256Hex("secret-1"))
        val second = tokens.ensureProjectWithToken("pilot", sha256Hex("secret-1"))

        assertEquals(first.id, second.id, "bootstrap must be idempotent")
        assertEquals("pilot", tokens.resolve(sha256Hex("secret-1"))?.project?.key)
        assertNull(tokens.resolve(sha256Hex("wrong")), "unknown hash resolves nothing")
        // Reusing the same token for a different project must fail boot, not misroute.
        val reuse = runCatching { tokens.ensureProjectWithToken("other-project", sha256Hex("secret-1")) }
        assertTrue(reuse.isFailure, "cross-project token reuse must never be silent")

        // Scopes persist and resolve (spec §5).
        tokens.ensureProjectWithToken("pilot", sha256Hex("ingest-only"), TokenScope.INGEST)
        assertEquals(TokenScope.INGEST, tokens.resolve(sha256Hex("ingest-only"))?.scope)
        assertEquals(TokenScope.ALL, tokens.resolve(sha256Hex("secret-1"))?.scope)
    }

    @Test
    fun `failure detail and opaque warning extensions survive the jsonb round-trip`() {
        val project = tokens.ensureProjectWithToken("failure-project", sha256Hex("fp"))
        val stored = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            """
            {
              "schemaVersion": 1,
              "buildId": "f-1",
              "startedAt": 1751450000000,
              "finishedAt": 1751450005000,
              "outcome": "FAILED",
              "mode": "ci",
              "failure": {
                "exceptionClass": "org.gradle.api.GradleException",
                "message": "Execution failed for task ':app:compileKotlin'",
                "stackTrace": "org.gradle.api.GradleException: boom\n\tat org.example.Widget.build(Widget.java:42)"
              },
              "extensions": {
                "internalAdapters": {
                  "schemaVersion": 1,
                  "gradleVersion": "9.6.1",
                  "deprecations": ["The Foo API has been deprecated."],
                  "logWarnings": ["warning: bar() in Baz has been deprecated"],
                  "droppedWarnings": 3
                }
              }
            }
            """.trimIndent(),
        )
        assertTrue(builds.save(project.id, stored), "first save inserts")

        // The dashboard/report render these fields off findById, so they must survive the REAL jsonb
        // encode-on-save / decode-on-read — not just the in-memory by-reference path ApplicationTest
        // covers (plan 045; the two stores use one BuildHoundJson, but "same config" ≠ "tested").
        val readBack = builds.findById(project.id, "f-1")
        assertEquals("org.gradle.api.GradleException", readBack?.failure?.exceptionClass)
        assertEquals(
            "org.gradle.api.GradleException: boom\n\tat org.example.Widget.build(Widget.java:42)",
            readBack?.failure?.stackTrace,
            "the multi-line stacktrace must survive the jsonb round-trip intact",
        )
        val internalAdapters = readBack?.extensions?.get("internalAdapters")
        assertNotNull(internalAdapters, "internalAdapters extension dropped in the jsonb round-trip")
        val extensionJson = internalAdapters.toString()
        assertTrue(extensionJson.contains("The Foo API has been deprecated."), extensionJson)
        assertTrue(extensionJson.contains("bar() in Baz has been deprecated"), extensionJson)
    }

    @Test
    fun `an INTERRUPTED build round-trips, re-ingests idempotently, and stays out of duration trends`() {
        val project = tokens.ensureProjectWithToken("interrupted-project", sha256Hex("ip"))
        val now = System.currentTimeMillis()
        // A deliberately huge (900s) duration so that failing to EXCLUDE it from the average is visible.
        val interrupted = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            """{"schemaVersion":1,"buildId":"i-1","startedAt":${now - 3_600_000},"finishedAt":${now - 3_600_000 + 900_000},"outcome":"INTERRUPTED","mode":"ci"}""",
        )
        assertTrue(builds.save(project.id, interrupted), "first save inserts")
        assertFalse(builds.save(project.id, interrupted), "re-ingest of the same buildId is a no-op")

        val stored = builds.findById(project.id, "i-1") ?: error("interrupted build must round-trip")
        assertEquals(dev.buildhound.commons.payload.BuildOutcome.INTERRUPTED, stored.outcome)

        // A 60s SUCCESS + the 900s INTERRUPTED on the same day: interrupted is counted but excluded
        // from the duration/failure aggregates (mirrors the in-memory store).
        builds.save(project.id, payload("s-1", startedAt = now - 3_500_000, outcome = "SUCCESS"))
        val point = builds.trends(project.id, BuildFilter(), days = 1, nowMs = now).single()
        assertEquals(2, point.builds)
        assertEquals(1, point.interrupted)
        assertEquals(0, point.failures)
        assertEquals(60_000, point.avgDurationMs, "only the 60s SUCCESS counts; the 900s interrupted is excluded")
    }

    @Test
    fun `list and trends aggregate over real sql`() {
        val project = tokens.ensureProjectWithToken("query-project", sha256Hex("q"))
        val now = System.currentTimeMillis()
        builds.save(project.id, payload("q-old", startedAt = now - 3 * 86_400_000))
        builds.save(project.id, payload("q-fail", startedAt = now - 86_400_000, outcome = "FAILED"))
        builds.save(project.id, payload("q-new", startedAt = now - 3_600_000, branch = "feature/x"))

        val all = builds.list(project.id, BuildFilter(), limit = 50, offset = 0)
        assertEquals(listOf("q-new", "q-fail", "q-old"), all.map { it.buildId }, "newest first")
        assertEquals(60_000, all.first().durationMs)

        val mainOnly = builds.list(project.id, BuildFilter(branch = "main"), limit = 50, offset = 0)
        assertEquals(listOf("q-fail", "q-old"), mainOnly.map { it.buildId })

        val failed = builds.list(project.id, BuildFilter(outcome = "FAILED"), limit = 50, offset = 0)
        assertEquals(listOf("q-fail"), failed.map { it.buildId })

        // Filtered count agrees with the filtered list length, including the no-match case (plan 018).
        assertEquals(3L, builds.count(project.id, BuildFilter()))
        assertEquals(2L, builds.count(project.id, BuildFilter(branch = "main")))
        assertEquals(1L, builds.count(project.id, BuildFilter(outcome = "FAILED")))
        assertEquals(0L, builds.count(project.id, BuildFilter(branch = "no-such-branch")), "no match → 0")

        val trends = builds.trends(project.id, BuildFilter(), days = 7, nowMs = now)
        assertEquals(3, trends.sumOf { it.builds })
        assertEquals(1, trends.sumOf { it.failures })
        assertTrue(trends.all { it.avgDurationMs == 60_000L }, trends.toString())
        assertTrue(trends.zipWithNext().all { (a, b) -> a.day < b.day }, "oldest first")

        // Window exclusion + filtered trends (the param-index arithmetic under test).
        builds.save(project.id, payload("q-ancient", startedAt = now - 10 * 86_400_000))
        assertEquals(3, builds.trends(project.id, BuildFilter(), days = 7, nowMs = now).sumOf { it.builds })
        val filtered = builds.trends(project.id, BuildFilter(branch = "main", outcome = "FAILED"), days = 7, nowMs = now)
        assertEquals(1, filtered.sumOf { it.builds })
        assertEquals(1, filtered.sumOf { it.failures })
    }

    @Test
    fun `builds round trip with tenant isolation and dedupe`() {
        val projectA = tokens.ensureProjectWithToken("team-a", sha256Hex("a"))
        val projectB = tokens.ensureProjectWithToken("team-b", sha256Hex("b"))
        val build = payload("round-trip-1")

        assertTrue(builds.save(projectA.id, build))
        assertFalse(builds.save(projectA.id, build), "same tenant + buildId dedupes")
        assertTrue(builds.save(projectB.id, build), "other tenant is independent")

        val loaded = builds.findById(projectA.id, "round-trip-1")
        assertEquals(build, loaded, "jsonb round trip must be lossless")
        assertNull(builds.findById(projectA.id, "missing"))
        assertEquals(1L, builds.count(projectA.id, BuildFilter()))
    }

    /** Reads the `project_key` hot column directly — the ingest-writes-the-column contract (plan 077). */
    private fun projectKeyColumn(projectId: String, buildId: String): String? =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT project_key FROM builds WHERE project_id = ?::uuid AND build_id = ?").use { s ->
                s.setString(1, projectId)
                s.setString(2, buildId)
                s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
            }
        }

    @Test
    fun `ingest writes the project_key hot column from the payload`() {
        val project = tokens.ensureProjectWithToken("hotcol-project", sha256Hex("hc"))
        builds.save(project.id, TestPayloads.build(buildId = "hc-1", projectKey = "repo-x"))
        builds.save(project.id, TestPayloads.build(buildId = "hc-2", projectKey = null))
        assertEquals("repo-x", projectKeyColumn(project.id, "hc-1"), "projectKey is extracted to the hot column")
        assertNull(projectKeyColumn(project.id, "hc-2"), "a null payload projectKey stays null in the column")

        // Store-boundary clamp (plan 077): an oversized key must not reach the btree index (SQLSTATE
        // 54000 poison pill) — save succeeds (no SQLException = the index accepted the tuple) and the
        // hot column holds the clamped key. Both stores clamp identically, so enumeration stays parity.
        val oversized = TestPayloads.build(buildId = "hc-3", projectKey = "k".repeat(3000))
        builds.save(project.id, oversized)
        assertEquals("k".repeat(256), projectKeyColumn(project.id, "hc-3"), "the hot column holds the clamped key")
        val inMemory = InMemoryBuildStore()
        inMemory.save(project.id, TestPayloads.build(buildId = "hc-1", projectKey = "repo-x"))
        inMemory.save(project.id, TestPayloads.build(buildId = "hc-2", projectKey = null))
        inMemory.save(project.id, oversized)
        assertEquals(inMemory.projectKeys(project.id), builds.projectKeys(project.id), "clamped-key enumeration parity")
    }

    @Test
    fun `projectKey selector filters and aggregates in lockstep with the in-memory store`() {
        val project = tokens.ensureProjectWithToken("selector-project", sha256Hex("sp"))
        val inMemory = InMemoryBuildStore()
        val now = System.currentTimeMillis()
        val recent = now - 3_600_000
        val fixtures = listOf(
            TestPayloads.build(
                buildId = "s-a1", startedAt = recent + 2000, projectKey = "repo-a", userId = "u_1",
                tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 5000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(className = "com.a.FooTest", passed = 5, failed = 0)), sha = "sha-a",
                artifacts = ArtifactSizes(android = listOf(ArtifactSize("release", ":app", ArtifactType.APK, 8_000))),
            ),
            TestPayloads.build(
                buildId = "s-a2", startedAt = recent + 3000, projectKey = "repo-a", userId = "u_2",
                tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 6000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(className = "com.a.FooTest", passed = 4, failed = 1)), sha = "sha-a",
            ),
            // A third same-sha repo-a build so FooTest reaches FlakyDetector.MIN_SAMPLES → a real cross-run flake.
            TestPayloads.build(
                buildId = "s-a3", startedAt = recent + 4000, projectKey = "repo-a", userId = "u_1",
                tasks = listOf(TestPayloads.task(":app:compileKotlin", TaskOutcome.EXECUTED, 7000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(className = "com.a.FooTest", passed = 5, failed = 0)), sha = "sha-a",
            ),
            TestPayloads.build(
                buildId = "s-b1", startedAt = recent + 1000, projectKey = "repo-b", userId = "u_1",
                tasks = listOf(TestPayloads.task(":lib:compileKotlin", TaskOutcome.EXECUTED, 4000, type = "KotlinCompile")),
                tests = listOf(TestPayloads.testTask(module = ":lib", className = "com.b.BarTest", passed = 3, retriedCases = listOf("flaky()"))),
                sha = "sha-b",
                artifacts = ArtifactSizes(android = listOf(ArtifactSize("debug", ":lib", ArtifactType.AAR, 300))),
            ),
            // A pre-077 build (null projectKey): counted under "all projects", never a selectable key.
            TestPayloads.build(buildId = "s-n1", startedAt = recent, projectKey = null, userId = "u_1"),
        )
        for (b in fixtures) {
            builds.save(project.id, b)
            inMemory.save(project.id, b)
        }

        // Enumeration: distinct non-null keys, newest-activity first, with counts + last-build ms.
        assertEquals(inMemory.projectKeys(project.id), builds.projectKeys(project.id), "project-keys aggregation parity")
        assertEquals(
            listOf(ProjectKeyRow("repo-a", 3, recent + 4000), ProjectKeyRow("repo-b", 1, recent + 1000)),
            builds.projectKeys(project.id),
        )

        // list + count: filter-aware and parity-safe; BuildSummary carries the projectKey.
        val filter = BuildFilter(projectKey = "repo-a")
        assertEquals(inMemory.list(project.id, filter, 50, 0), builds.list(project.id, filter, 50, 0), "list parity")
        assertEquals(3L, builds.count(project.id, filter))
        assertEquals(inMemory.count(project.id, filter), builds.count(project.id, filter), "count parity")
        assertEquals("repo-a", builds.list(project.id, filter, 1, 0).first().projectKey)
        // The unfiltered path still counts every build (including the null-key one) — bit-for-bit as before.
        assertEquals(5L, builds.count(project.id, BuildFilter()))

        // trends + every rollup + flaky agree byte-for-byte, filtered.
        assertEquals(inMemory.trends(project.id, filter, 30, now), builds.trends(project.id, filter, 30, now), "trends")
        assertEquals(inMemory.projectCost(project.id, 30, now, "repo-a"), builds.projectCost(project.id, 30, now, "repo-a"), "projectCost")
        assertEquals(inMemory.taskDuration(project.id, 30, now, "repo-a"), builds.taskDuration(project.id, 30, now, "repo-a"), "taskDuration")
        assertEquals(inMemory.negativeAvoidance(project.id, 30, now, "repo-a"), builds.negativeAvoidance(project.id, 30, now, "repo-a"), "negativeAvoidance")
        assertEquals(inMemory.bottlenecks(project.id, 7, now, "repo-a"), builds.bottlenecks(project.id, 7, now, "repo-a"), "bottlenecks")
        assertEquals(inMemory.toolchainAdoption(project.id, 30, now, "repo-a"), builds.toolchainAdoption(project.id, 30, now, "repo-a"), "toolchain")
        assertEquals(inMemory.flaky(project.id, 30, now, "repo-a"), builds.flaky(project.id, 30, now, "repo-a"), "flaky")
        // The filter genuinely excludes repo-b's data (else the parity asserts could pass on empties).
        assertTrue(builds.projectCost(project.id, 30, now, "repo-a").all { it.module == ":app" }, "repo-b's :lib is excluded")
        assertTrue(builds.flaky(project.id, 30, now, "repo-a").all { it.className == "com.a.FooTest" }, "repo-b's flake is excluded")

        // artifactTrends: filtered parity, non-vacuous, and repo-b's series genuinely excluded.
        val artifactTrends = builds.artifactTrends(project.id, filter, 30, now)
        assertEquals(inMemory.artifactTrends(project.id, filter, 30, now), artifactTrends, "artifactTrends parity")
        assertTrue(artifactTrends.isNotEmpty(), "non-vacuous: repo-a's APK series is present")
        assertTrue(artifactTrends.all { it.module == ":app" }, "repo-b's :lib AAR series is excluded when filtered")
    }

    /** length() of a hot column for one build row — the clamped-storage probe (plan 077). */
    private fun columnLength(table: String, column: String, projectId: String, buildId: String): Int =
        dataSource.connection.use { c ->
            // table/column are test-fixed literals, never fixture data.
            c.prepareStatement("SELECT max(length($column)) FROM $table WHERE project_id = ?::uuid AND build_id = ?").use { s ->
                s.setString(1, projectId)
                s.setString(2, buildId)
                s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }

    @Test
    fun `a payload with oversized strings in every indexed field stores clamped — no 54000 — and stays parity-safe`() {
        val project = tokens.ensureProjectWithToken("bounds-project", sha256Hex("bnd"))
        val inMemory = InMemoryBuildStore()
        val now = System.currentTimeMillis()
        val recent = now - 3_600_000
        // Same oversized sha + className across three builds (green/red/green) so the clamped values
        // still join into a cross-run flake; every other indexed field oversized on the first build.
        fun hostile(buildId: String, i: Int, failed: Int) = TestPayloads.build(
            buildId = buildId, startedAt = recent + i * 1000L,
            branch = "B".repeat(3000), sha = "s".repeat(3000),
            provider = "P".repeat(3000), runId = "r".repeat(3000), pipelineName = "L".repeat(3000),
            tasks = listOf(
                TaskExecution(
                    path = ":app:x", module = "m".repeat(3000), type = "T".repeat(3000),
                    startMs = 0, durationMs = 1000, outcome = TaskOutcome.EXECUTED,
                ),
            ),
            tests = listOf(TestPayloads.testTask(module = "M".repeat(3000), className = "C".repeat(3000), passed = 5 - failed, failed = failed)),
        )
        val fixtures = listOf(
            hostile("h".repeat(3000), 0, failed = 0),
            hostile("hb-2", 1, failed = 1),
            hostile("hb-3", 2, failed = 0),
        )
        for (b in fixtures) {
            // No SQLException here = every index (incl. the V7 test_class_outcomes PK) accepted the tuple.
            assertTrue(builds.save(project.id, b), "save must succeed, clamped — never 54000")
            inMemory.save(project.id, b)
        }

        // Hot columns hold the clamped values.
        val clampedId = "h".repeat(256)
        assertEquals(256, columnLength("builds", "branch", project.id, clampedId))
        assertEquals(256, columnLength("builds", "ci_provider", project.id, clampedId))
        assertEquals(256, columnLength("builds", "ci_run_id", project.id, clampedId))
        assertEquals(256, columnLength("builds", "pipeline_name", project.id, clampedId))
        assertEquals(256, columnLength("task_executions", "module", project.id, clampedId))
        assertEquals(512, columnLength("task_executions", "type", project.id, clampedId))
        assertEquals(512, columnLength("test_class_outcomes", "class_fqcn", project.id, clampedId))
        assertEquals(64, columnLength("test_class_outcomes", "sha", project.id, clampedId))

        // Aggregations over the clamped fields agree byte-for-byte across stores and are non-vacuous.
        assertEquals(inMemory.flaky(project.id, 30, now), builds.flaky(project.id, 30, now), "flaky parity")
        assertEquals(inMemory.taskDuration(project.id, 30, now), builds.taskDuration(project.id, 30, now), "taskDuration parity")
        val flake = builds.flaky(project.id, 30, now).single()
        assertEquals(512, flake.className.length, "the cross-run flake joined on the clamped className")
        assertEquals("T".repeat(512), builds.taskDuration(project.id, 30, now).byType.single().key)
    }

    @Test
    fun `projectKey filters the post-077 rollup surfaces in lockstep with the in-memory store`() {
        val project = tokens.ensureProjectWithToken("completion-project", sha256Hex("cp"))
        val inMemory = InMemoryBuildStore()
        val now = System.currentTimeMillis()
        val recent = now - 3_600_000
        val remote = BuildCacheConfigInfo(localEnabled = true, remoteEnabled = true, remotePush = true, remoteType = "HttpBuildCache")
        // repo-a: older, rich (every plan-079 surface has data); repo-b: NEWER sibling noise with
        // disjoint plugins/tags/modules so filtered reads have an unambiguous exclusion marker.
        val fixtures = listOf(
            TestPayloads.build(
                buildId = "pa-1", startedAt = recent + 1000, projectKey = "repo-a", sha = "sha-pa",
                tags = mapOf("team" to "alpha"),
                changedModules = ChangedModulesInfo(base = ChangeDiffBase.LAST_BUILT_SHA, modules = listOf(":app")),
                buildCache = remote, configurationCache = ConfigurationCacheState.HIT, ccLoadMs = 100,
                extensions = TestPayloads.internalAdapters(listOf(":app:compileKotlin" to "REMOTE_HIT")),
                tasks = listOf(
                    TestPayloads.task(
                        ":app:compileKotlin", TaskOutcome.EXECUTED, 5000,
                        type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile", cacheable = true,
                        executionReasons = listOf("Task has failed previously."),
                    ),
                ),
            ),
            TestPayloads.build(
                buildId = "pa-2", startedAt = recent + 2000, projectKey = "repo-a",
                outcome = dev.buildhound.commons.payload.BuildOutcome.FAILED, sha = "sha-pa2",
            ),
            TestPayloads.build(
                buildId = "pb-1", startedAt = recent + 10_000, projectKey = "repo-b", sha = "sha-pb",
                tags = mapOf("team" to "beta"),
                changedModules = ChangedModulesInfo(base = ChangeDiffBase.LAST_BUILT_SHA, modules = listOf(":lib")),
                buildCache = remote, configurationCache = ConfigurationCacheState.MISS_STORED, configurationMs = 900,
                extensions = TestPayloads.internalAdapters(listOf(":lib:compileJava" to "MISS")),
                tasks = listOf(
                    TestPayloads.task(
                        ":lib:compileJava", TaskOutcome.EXECUTED, 4000,
                        type = "org.gradle.api.tasks.compile.JavaCompile", cacheable = true,
                        executionReasons = listOf("No history is available."),
                    ),
                ),
            ),
            TestPayloads.build(buildId = "pb-2", startedAt = recent + 11_000, projectKey = "repo-b"),
            TestPayloads.build(buildId = "pb-3", startedAt = recent + 12_000, projectKey = "repo-b"),
        )
        for (b in fixtures) {
            builds.save(project.id, b)
            inMemory.save(project.id, b)
        }

        // Every re-signed method agrees byte-for-byte, filtered AND unfiltered.
        for (key in listOf<String?>(null, "repo-a")) {
            val label = key ?: "fleet"
            assertEquals(inMemory.pluginCost(project.id, 30, now, key), builds.pluginCost(project.id, 30, now, key), "pluginCost/$label")
            assertEquals(inMemory.changeBlastRadius(project.id, 30, now, key), builds.changeBlastRadius(project.id, 30, now, key), "changeBlastRadius/$label")
            assertEquals(inMemory.rerunCauses(project.id, 30, now, key), builds.rerunCauses(project.id, 30, now, key), "rerunCauses/$label")
            assertEquals(inMemory.warnings(project.id, 30, now, key), builds.warnings(project.id, 30, now, key), "warnings/$label")
            assertEquals(inMemory.cacheMissDiagnostics(project.id, 30, now, key), builds.cacheMissDiagnostics(project.id, 30, now, key), "cacheMissDiagnostics/$label")
            assertEquals(inMemory.cacheRoi(project.id, 30, now, key), builds.cacheRoi(project.id, 30, now, key), "cacheRoi/$label")
            assertEquals(inMemory.ccEconomics(project.id, 30, now, key), builds.ccEconomics(project.id, 30, now, key), "ccEconomics/$label")
            assertEquals(inMemory.deliveryHealth(project.id, 30, now, key), builds.deliveryHealth(project.id, 30, now, key), "deliveryHealth/$label")
            assertEquals(inMemory.windowPayloads(project.id, 30, 50, now, key), builds.windowPayloads(project.id, 30, 50, now, key), "windowPayloads/$label")
            assertEquals(inMemory.tagKeys(project.id, 30, now, key), builds.tagKeys(project.id, 30, now, key), "tagKeys/$label")
        }

        // Non-vacuous: sibling-repo data exists and is genuinely excluded when filtered.
        val filteredPlugins = builds.pluginCost(project.id, 30, now, "repo-a").plugins.map { it.plugin }
        assertTrue(filteredPlugins.contains("Kotlin Gradle Plugin") && !filteredPlugins.contains("Gradle core"), "$filteredPlugins")
        assertEquals(listOf(":app"), builds.changeBlastRadius(project.id, 30, now, "repo-a").map { it.module }, "repo-b's :lib excluded")
        assertEquals(listOf("alpha"), builds.tagKeys(project.id, 30, now, "repo-a").single().values.map { it.value }, "repo-b's beta excluded")
        assertEquals(1, builds.cacheRoi(project.id, 30, now, "repo-a").buildsWithConfig, "repo-b's snapshot excluded")
        assertTrue(builds.windowPayloads(project.id, 30, 50, now, "repo-a").all { it.projectKey == "repo-a" })

        // Cap starvation (the plan-079 trap): the 3 repo-b builds are NEWER, so a post-LIMIT filter
        // at cap=2 would return nothing for repo-a; the pre-LIMIT clause keeps repo-a's builds.
        assertEquals(
            listOf("pa-2", "pa-1"),
            builds.windowPayloads(project.id, 30, 2, now, "repo-a").map { it.buildId },
            "the selected repo fills the cap despite newer siblings (filter is pre-LIMIT)",
        )
        assertEquals(listOf("pb-3", "pb-2"), builds.windowPayloads(project.id, 30, 2, now).map { it.buildId }, "fleet view unchanged")
    }

    @Test
    fun `the three constant-capped rollups filter before their LIMIT against real Postgres`() {
        // Per-method cap starvation (plan 079 review): the caps are constructor-injectable so each
        // hand-written `ORDER BY … LIMIT ?` query's pre-LIMIT projectKey placement is testable at
        // cap=2 — a refactor to fetch-then-post-filter fails all three. The sibling repo's builds
        // pass every gate too (buildCache + internalAdapters), so in the broken shape they alone
        // would fill the capped window.
        val project = tokens.ensureProjectWithToken("cap-starve-project", sha256Hex("cs"))
        val capped = PostgresBuildStore(dataSource, diagnosticRowCap = 2, cacheRoiRowCap = 2, ccEconomicsRowCap = 2)
        val inMemory = InMemoryBuildStore(diagnosticRowCap = 2, cacheRoiRowCap = 2, ccEconomicsRowCap = 2)
        val now = System.currentTimeMillis()
        val recent = now - 3_600_000
        val remote = BuildCacheConfigInfo(localEnabled = true, remoteEnabled = true, remotePush = true, remoteType = "HttpBuildCache")
        val fixtures = buildList {
            for (i in 1..2) {
                add(
                    TestPayloads.build(
                        buildId = "gated-a-$i", startedAt = recent + i, projectKey = "repo-a",
                        buildCache = remote, configurationCache = ConfigurationCacheState.HIT, ccLoadMs = 100,
                        extensions = TestPayloads.internalAdapters(listOf(":app:x" to "REMOTE_HIT")),
                        tasks = listOf(TestPayloads.task(":app:x", TaskOutcome.FROM_CACHE, 10, cacheable = true)),
                    ),
                )
            }
            for (i in 1..3) {
                add(
                    TestPayloads.build(
                        buildId = "gated-b-$i", startedAt = recent + 10_000 + i, projectKey = "repo-b",
                        buildCache = remote, configurationCache = ConfigurationCacheState.DISABLED,
                        extensions = TestPayloads.internalAdapters(listOf(":lib:y" to "MISS")),
                        tasks = listOf(TestPayloads.task(":lib:y", TaskOutcome.EXECUTED, 10, cacheable = true)),
                    ),
                )
            }
        }
        for (b in fixtures) {
            capped.save(project.id, b)
            inMemory.save(project.id, b)
        }

        assertTrue(
            capped.cacheMissDiagnostics(project.id, 30, now, "repo-a").remoteCacheObserved,
            "repo-a's REMOTE_HIT rows fill the capped window despite newer gated siblings",
        )
        assertEquals(
            2, capped.cacheRoi(project.id, 30, now, "repo-a").buildsWithConfig,
            "repo-a's config snapshots fill the capped window despite newer gated siblings",
        )
        assertEquals(
            2, capped.ccEconomics(project.id, 30, now, "repo-a").ccObservedBuilds,
            "repo-a's CC posture fills the capped window despite newer siblings",
        )
        // Both stores agree at the injected cap, filtered and unfiltered — the same parity discipline.
        for (key in listOf<String?>(null, "repo-a")) {
            val label = key ?: "fleet"
            assertEquals(inMemory.cacheMissDiagnostics(project.id, 30, now, key), capped.cacheMissDiagnostics(project.id, 30, now, key), "cacheMissDiagnostics/$label")
            assertEquals(inMemory.cacheRoi(project.id, 30, now, key), capped.cacheRoi(project.id, 30, now, key), "cacheRoi/$label")
            assertEquals(inMemory.ccEconomics(project.id, 30, now, key), capped.ccEconomics(project.id, 30, now, key), "ccEconomics/$label")
        }
    }
}
