package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Golden-file contract test (roadmap phase 0): every historical schema version must keep
 * deserializing. New schema versions add a new golden file; existing files are never edited.
 */
class GoldenPayloadTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden file $name" }
            .readBytes()
            .decodeToString()

    @Test
    fun `schema v1 golden file deserializes`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals("3f9d3c1e-8f3a-4a5e-9b2d-0c8f4a7d6e21", payload.buildId)
        assertEquals(BuildOutcome.SUCCESS, payload.outcome)
        assertEquals(BuildMode.CI, payload.mode)
        assertEquals(2, payload.tasks.size)
        assertEquals(TaskOutcome.FROM_CACHE, payload.tasks[0].outcome)
        assertEquals(ConfigurationCacheState.HIT, payload.environment?.configurationCache)
        assertEquals("azure-devops", payload.ci?.provider)
        assertEquals(0.5, payload.derived?.cacheableHitRate)
        // Toolchain dimensions (plan 046): Gradle/JDK plus the AGP/KGP/KSP versions the collector now
        // populates. The v1 golden already carries all five, pinning their wire contract.
        assertEquals("8.14.3", payload.toolchain?.gradle)
        assertEquals("21.0.10", payload.toolchain?.jdk)
        assertEquals("8.9.0", payload.toolchain?.agp)
        assertEquals("2.2.20", payload.toolchain?.kgp)
        assertEquals("2.2.20-2.0.2", payload.toolchain?.ksp)
    }

    @Test
    fun `schema v1 task-metadata golden file deserializes with populated fields`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-task-metadata.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals(3, payload.tasks.size)
        val compile = payload.tasks[0]
        assertEquals("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", compile.type)
        assertEquals(true, compile.cacheable)
        assertEquals(TaskOutcome.FROM_CACHE, compile.outcome)
        val nonCacheable = payload.tasks[2]
        assertEquals(false, nonCacheable.cacheable)
        assertEquals("Produces no cacheable output", nonCacheable.nonCacheableReason)
        assertEquals(0.5, payload.derived?.cacheableHitRate)
        assertEquals(1200, payload.derived?.configurationMs)
        // Pin the golden's hand-authored hit rate to what the live formula produces for
        // its tasks, so a future golden edit can't silently drift from the calculator.
        assertEquals(payload.derived?.cacheableHitRate, DerivedMetricsCalculator.cacheableHitRate(payload.tasks))
    }

    @Test
    fun `schema v1 caps golden file deserializes with a populated caps summary`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-caps.json"))

        assertEquals(1, payload.schemaVersion)
        val caps = payload.caps
        assertEquals(4, caps?.droppedTags)
        assertEquals(1, caps?.truncatedValues)
        assertEquals(3, caps?.droppedExecutionReasons)
        assertEquals(2, caps?.truncatedExecutionReasons)
        assertEquals(17, caps?.droppedTasks)
        assertEquals(mapOf("EXECUTED" to 15, "FROM_CACHE" to 2), caps?.droppedTaskOutcomes)
    }

    @Test
    fun `schema v1 fingerprints golden file deserializes with populated maps`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-fingerprints.json"))

        assertEquals(1, payload.schemaVersion)
        val fp = payload.fingerprints
        assertEquals("9f86d081884c7d65…", fp?.build?.get("jdk.home"))
        assertEquals(4, fp?.build?.size)
        assertEquals("fcde2b2edba56bf4…", fp?.tasks?.get(":app:testDebugUnitTest")?.get("sysProps-robolectric.offline"))
    }

    @Test
    fun `payloads without a fingerprints field default to null`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1.json"))
        assertNull(payload.fingerprints)
        assertNull(payload.kotlin)
        assertTrue(payload.tests.isEmpty())
        assertTrue(payload.processes.isEmpty())
        assertNull(payload.benchmark)
        assertNull(payload.artifacts)
        assertNull(payload.links)
        assertNull(payload.environment?.ide)
        assertNull(payload.environment?.aiAgent)
        assertNull(payload.environment?.invocation)
        assertNull(payload.vcs?.remoteUrl)
        // Additive guarantee (plan 039): a payload without an `extensions` block defaults to empty,
        // and the v1 golden — authored before the field existed — still deserializes.
        assertTrue(payload.extensions.isEmpty())
        // Additive guarantee (plan 052): a payload without a `projectEvaluations` block defaults to null.
        assertNull(payload.projectEvaluations)
        // Additive guarantee (plan 069): a payload without a `buildStructure` block, and an
        // `environment` without `isolatedProjects`, both default to null.
        assertNull(payload.buildStructure)
        assertNull(payload.environment?.isolatedProjects)
        // Additive guarantee (plan 066): a payload without a `wrapper` block defaults to null.
        assertNull(payload.wrapper)
        // Additive guarantee (plan 053): a payload without a `testTelemetry` block defaults to null.
        assertNull(payload.testTelemetry)
        // Additive guarantee (plan 065): an `environment` without `workersMax` defaults to null.
        assertNull(payload.environment?.workersMax)
        // Additive guarantee (plan 072): a payload without a `toolchain.springBoot` dimension defaults null.
        assertNull(payload.toolchain?.springBoot)
        // Additive guarantee (plan 067): an `environment` without a `buildCache` config block defaults null.
        assertNull(payload.environment?.buildCache)
        // Additive guarantee (plan 063): a payload without a `changedModules` block defaults to null.
        assertNull(payload.changedModules)
        // Additive guarantee (plan 064): an `environment` without `configurationCacheParallel` and a
        // `derived` block without `ccEntrySizeBytes`/`ccLoadMs` all default to null.
        assertNull(payload.environment?.configurationCacheParallel)
        assertNull(payload.derived?.ccEntrySizeBytes)
        assertNull(payload.derived?.ccLoadMs)
    }

    @Test
    fun `schema v1 excluded-tasks golden file deserializes with a populated excludedTaskNames list`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-excluded-tasks.json"),
        )

        assertEquals(1, payload.schemaVersion, "excludedTaskNames is additive — the envelope stays schema v1")
        // Sorted task names from StartParameter.excludedTaskNames (plan 054) — the `-x lint -x test` smell.
        assertEquals(listOf(":app:lint", ":app:test"), payload.excludedTaskNames)
        // Task-name shape only — never a filesystem path (spec §3.7), same exposure class as requestedTasks.
        assertTrue(payload.excludedTaskNames.all { it.startsWith(":") }, "excludedTaskNames carries Gradle task paths only")
        val wire = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
        assertTrue(!wire.contains("src/") && !wire.contains(".kt\""), "no filesystem path may leak via excludedTaskNames")
    }

    @Test
    fun `payloads without an excludedTaskNames field default to empty`() {
        // Additive guarantee (plan 054): a payload authored before the field existed still deserializes
        // and defaults to an empty list, exactly like requestedTasks.
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1.json"))
        assertTrue(payload.excludedTaskNames.isEmpty())
    }

    @Test
    fun `schema v1 changed-modules golden file deserializes with a populated blast-radius block`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-changed-modules.json"),
        )

        assertEquals(1, payload.schemaVersion, "change blast-radius attribution is additive — the envelope stays schema v1")
        val changed = payload.changedModules ?: error("expected a changedModules block")
        assertEquals(ChangeDiffBase.CI_PR_BASE, changed.base)
        // Gradle module paths only — never a filesystem path or a raw changed-file list (spec §3.7).
        assertEquals(listOf(":app", ":core:common"), changed.modules)
        assertEquals(false, changed.unattributedChanges)
        // Every emitted module is a Gradle path (leading ':'), never a filesystem path or a changed
        // file — the plugin's ChangedModuleMapper maps files to modules and drops the raw paths (§3.7).
        assertTrue(changed.modules.all { it.startsWith(":") }, "changedModules carries Gradle paths only")
        val wire = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
        assertTrue(!wire.contains("src/") && !wire.contains(".kt"), "no changed-file path may leak into the payload")
    }

    @Test
    fun `schema v1 buildcache golden file deserializes with a populated remote-cache config snapshot`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-buildcache.json"),
        )

        assertEquals(1, payload.schemaVersion, "the build-cache config snapshot is additive — the envelope stays schema v1")
        val buildCache = payload.environment?.buildCache ?: error("expected an environment.buildCache block")
        assertEquals(true, buildCache.localEnabled)
        assertEquals(true, buildCache.remoteEnabled)
        assertEquals(true, buildCache.remotePush)
        // The normalized backend simpleName — a code identifier, never the URL (spec §3.7).
        assertEquals("HttpBuildCache", buildCache.remoteType)
        // Privacy: no remote URL or local cache directory anywhere in the wire payload (grep the JSON).
        val wire = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
        assertTrue(!wire.contains("http://") && !wire.contains("://"), "no remote-cache URL may leak into the payload")
        assertTrue(!wire.contains("getUrl") && !wire.contains("getDirectory"), "no cache-location accessor output may leak")
    }

    @Test
    fun `schema v1 cc-economics golden file deserializes with CC parallel, entry size, and load proxy`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-cc-economics.json"),
        )

        assertEquals(1, payload.schemaVersion, "the CC-economics fields are additive — the envelope stays schema v1")
        // The org.gradle.configuration-cache.parallel flag rides at the environment level (plan 064).
        assertEquals(true, payload.environment?.configurationCacheParallel)
        // A CC HIT: configurationMs is 0 (a distinct field), the ccLoadMs proxy is populated instead,
        // and the loaded entry's byte size is carried alongside (plan 064, research F14).
        assertEquals(ConfigurationCacheState.HIT, payload.environment?.configurationCache)
        assertEquals(0, payload.derived?.configurationMs)
        assertEquals(42, payload.derived?.ccLoadMs)
        assertEquals(68157440, payload.derived?.ccEntrySizeBytes)
        // Byte count + boolean only — no path or cache directory ever ships (spec §3.7).
        val wire = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), payload)
        assertTrue(!wire.contains("configuration-cache/") && !wire.contains(".gradle/"), "no CC-entry path may leak into the payload")
    }

    @Test
    fun `pre-065 process rows default pid, collector, and headers to null`() {
        // The plan-029 processes golden predates the tuning fields — untouched, it must keep
        // deserializing with the three additions defaulted null (the additive guarantee, plan 065).
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-processes.json"))
        for (process in payload.processes) {
            assertNull(process.pid)
            assertNull(process.gcCollector)
            assertNull(process.compactObjectHeaders)
        }
    }

    @Test
    fun `schema v1 build-structure golden file deserializes with a populated inventory and isolatedProjects flag`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-build-structure.json"),
        )

        assertEquals(1, payload.schemaVersion, "build-structure inventory is additive — the envelope stays schema v1")
        assertEquals(true, payload.environment?.isolatedProjects)
        val structure = payload.buildStructure ?: error("expected a buildStructure block")
        assertEquals(42, structure.projectCount)
        assertEquals(3, structure.maxDepth)
        assertEquals(1, structure.includedBuildCount)
        assertEquals(true, structure.buildSrcPresent)
        assertEquals(false, structure.sourcesInRoot)
        // A ranked heuristic candidate list, not a verdict — Gradle paths only, never a filesystem path.
        assertEquals(listOf(":libs", ":libs:legacy"), structure.emptyIntermediateCandidates)
    }

    @Test
    fun `schema v1 wrapper golden file deserializes with a populated distribution and warmth`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-wrapper.json"),
        )

        assertEquals(1, payload.schemaVersion, "wrapper telemetry is additive — the envelope stays schema v1")
        val wrapper = payload.wrapper ?: error("expected a wrapper block")
        assertEquals(WrapperDistributionType.BIN, wrapper.distributionVariant)
        assertEquals(true, wrapper.distributionSha256Pinned)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", wrapper.wrapperJarSha256)
        assertEquals(GuhWarmth.COLD, wrapper.guhWarmth)
    }

    @Test
    fun `GuhWarmth classify covers cold, warm, and absent-dist against this daemon's own JVM start`() {
        val jvmStart = 1_000_000L
        // COLD: the dist mtime sits within the fresh window of this daemon's own JVM start —
        // unpacked at/around this daemon's own bootstrap. (Not the first task's start: the
        // wrapper's bootstrap unpacks the dist *before* this JVM launches, which is before
        // configuration, which is before any task — so an in-build task timestamp can never
        // observe COLD; this is the plan-066-review correction.)
        assertEquals(GuhWarmth.COLD, GuhWarmth.classify(distMtimeMs = jvmStart, distPresent = true, jvmStartMs = jvmStart))
        assertEquals(
            GuhWarmth.COLD,
            GuhWarmth.classify(distMtimeMs = jvmStart - GuhWarmth.FRESH_WINDOW_MS, distPresent = true, jvmStartMs = jvmStart),
        )
        // A dist mtime slightly AFTER jvmStart (clock-skew tolerance) still reads as COLD.
        assertEquals(GuhWarmth.COLD, GuhWarmth.classify(distMtimeMs = jvmStart + 1_000, distPresent = true, jvmStartMs = jvmStart))
        // WARM: meaningfully older than this daemon's own start — a persisted, reused GUH.
        assertEquals(
            GuhWarmth.WARM,
            GuhWarmth.classify(distMtimeMs = jvmStart - GuhWarmth.FRESH_WINDOW_MS - 1, distPresent = true, jvmStartMs = jvmStart),
        )
        // UNKNOWN: no dist to compare (system/IDE Gradle, or the probe never ran).
        assertEquals(
            GuhWarmth.UNKNOWN,
            GuhWarmth.classify(distMtimeMs = jvmStart - 1, distPresent = false, jvmStartMs = jvmStart),
        )
        assertEquals(
            GuhWarmth.UNKNOWN,
            GuhWarmth.classify(distMtimeMs = jvmStart - 1, distPresent = null, jvmStartMs = jvmStart),
        )
        // UNKNOWN: a guarded stat/JVM-introspection failure left one of the timestamps unavailable.
        assertEquals(GuhWarmth.UNKNOWN, GuhWarmth.classify(distMtimeMs = null, distPresent = true, jvmStartMs = jvmStart))
        assertEquals(GuhWarmth.UNKNOWN, GuhWarmth.classify(distMtimeMs = jvmStart - 1, distPresent = true, jvmStartMs = null))
    }

    @Test
    fun `schema v1 project-evaluations golden file deserializes ranked slowest-first`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-project-evaluations.json"),
        )

        assertEquals(1, payload.schemaVersion, "per-project timing is additive — the envelope stays schema v1")
        val evaluations = payload.projectEvaluations ?: error("expected a projectEvaluations block")
        assertEquals(3, evaluations.size)
        assertEquals(listOf(":app", ":core:network", ":core:common"), evaluations.map { it.path })
        assertEquals(listOf(4200L, 1800L, 300L), evaluations.map { it.evaluationMs }, "ranked slowest-first")
    }

    @Test
    fun `internal-adapters golden populates derived avoided-critical and the extensions block`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-internal-adapters.json"))

        assertEquals(1, payload.schemaVersion, "tier-b detail is additive — the envelope stays schema v1")
        // The long-null derived fields (plan 005) are now populated (plan 038).
        assertEquals(8000, payload.derived?.avoidedMs)
        assertEquals(29000, payload.derived?.criticalPathMs)
        // The internal-op detail rides in the additive extensions map with its own schemaVersion.
        val internal = payload.extensions.getValue("internalAdapters").jsonObject
        assertEquals(1, internal.getValue("schemaVersion").jsonPrimitive.int)
        val tasks = internal.getValue("tasks").let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(2, tasks.size)
        assertEquals("LOCAL_HIT", tasks[0].jsonObject.getValue("origin").jsonPrimitive.content)
    }

    @Test
    fun `internal-adapters-edges golden populates dependencyEdges and pins criticalPathMs to the live formula`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-internal-adapters-edges.json"),
        )

        assertEquals(1, payload.schemaVersion, "the edge list is additive — the envelope stays schema v1")
        val internal = payload.extensions.getValue("internalAdapters").jsonObject
        val edges = internal.getValue("dependencyEdges").jsonObject
        assertEquals(2, edges.size)
        assertEquals(
            listOf(":app:compileDebugKotlin", ":app:testDebugUnitTest"),
            edges.getValue(":app:assembleDebug").let { it as kotlinx.serialization.json.JsonArray }.map { it.jsonPrimitive.content },
        )

        // Pin the golden's hand-authored criticalPathMs to what the live formula produces from these
        // same tasks + edges, so a future golden edit can't silently drift from the calculator (plan 062
        // reads this exact `dependencyEdges` shape server-side, so it must round-trip and compute
        // consistently, not just deserialize).
        val dependencyEdges = edges.entries.associate { (path, deps) ->
            path to (deps as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
        }
        assertEquals(28000, payload.derived?.criticalPathMs)
        assertEquals(payload.derived?.criticalPathMs, DerivedMetricsCalculator.criticalPathMs(payload.tasks, dependencyEdges))
    }

    @Test
    fun `test-sharding golden populates the testSharding extension block`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-sharding.json"))

        assertEquals(1, payload.schemaVersion, "an addon block is additive — the envelope stays schema v1")
        val sharding = payload.extensions.getValue("testSharding").jsonObject
        assertEquals(1, sharding.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(2, sharding.getValue("shardIndex").jsonPrimitive.int)
        assertEquals(4, sharding.getValue("shardTotal").jsonPrimitive.int)
        assertEquals(true, sharding.getValue("appliedFilter").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `extensions golden file deserializes with two addon-owned blocks`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v2ext.json"))

        assertEquals(1, payload.schemaVersion, "the core envelope stays schema v1 — addons don't bump it")
        assertEquals(setOf("testQuarantine", "testSharding"), payload.extensions.keys)
        // Each addon owns its own nested schemaVersion, so core reads opaque JsonElement and stays
        // decoupled from addon types (plan 039).
        val quarantine = payload.extensions.getValue("testQuarantine").jsonObject
        assertEquals(1, quarantine.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("muted", quarantine.getValue("mode").jsonPrimitive.content)
        val sharding = payload.extensions.getValue("testSharding").jsonObject
        assertEquals(4, sharding.getValue("shardCount").jsonPrimitive.int)
    }

    @Test
    fun `schema v1 tests golden file deserializes with populated results`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-tests.json"))

        assertEquals(1, payload.schemaVersion)
        val task = payload.tests.single()
        assertEquals(":app:testDebugUnitTest", task.taskPath)
        assertEquals(":app", task.module)
        assertEquals(5, task.truncatedClasses)
        assertTrue(task.allCases.isEmpty(), "allCases reserved-empty in v1")
        val cart = task.classes.first { it.className == "com.example.CartTest" }
        assertEquals(11, cart.passed)
        assertEquals(1, cart.failed)
        // A retried case is one detail with an ordered multi-entry outcome sequence.
        val retried = task.failedOrRetried.first { it.name == "flakyPaymentGateway()" }
        assertEquals(listOf(TestCaseOutcome.FAILED, TestCaseOutcome.PASSED), retried.outcomes)

        // messageHash must be the SHA-256 of its own message — it is the flaky-signal key plans
        // 036/040 join on, so a golden with an inconsistent hash would enshrine a wrong contract.
        for (detail in task.failedOrRetried) {
            assertEquals(sha256(detail.message!!), detail.messageHash, "messageHash must hash its message")
        }
    }

    @Test
    fun `schema v1 test-telemetry golden file deserializes with the disabled task path and no phantom result`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-test-telemetry.json"),
        )

        assertEquals(1, payload.schemaVersion, "the degraded-state block is additive — the envelope stays schema v1")
        assertTrue(payload.tests.isEmpty(), "the flag-authoritative collector never parses a disabled task's xml")
        val telemetry = payload.testTelemetry ?: error("expected a testTelemetry block")
        assertEquals(listOf(":app:testDebugUnitTest"), telemetry.xmlDisabledTasks)
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    @Test
    fun `schema v1 invocation golden file deserializes with populated scalars and property provenance`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-invocation.json"))

        assertEquals(1, payload.schemaVersion)
        val invocation = payload.environment?.invocation ?: error("expected an environment.invocation block")
        assertEquals(true, invocation.buildCacheEnabled)
        assertEquals(false, invocation.offline)
        assertEquals(false, invocation.rerunTasks)
        assertEquals(false, invocation.refreshDependencies)
        assertEquals(false, invocation.configureOnDemand)
        assertEquals(8, invocation.maxWorkerCount)
        assertEquals(true, invocation.parallel)
        // Plaintext fileEncoding/locale ride alongside the salted FingerprintInfo hashes (never
        // replacing them) so absolute-value rules ("Cp1252 fleet -> set UTF-8") can fire.
        assertEquals("UTF-8", invocation.fileEncoding)
        assertEquals("en-US", invocation.locale)

        val caching = invocation.properties.first { it.key == "org.gradle.caching" }
        assertEquals("true", caching.value)
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, caching.origin, "GUH silently wins over the project file")
        val parallelProp = invocation.properties.first { it.key == "org.gradle.parallel" }
        assertEquals(PropertyOrigin.PROJECT, parallelProp.origin)
        // android.* keys are AGP-read project properties, so their real override channel is -P
        // (StartParameter.projectProperties) or ORG_GRADLE_PROJECT_* env — never -D (plan-051 review
        // fix, GradlePropertyProvenance). This wire fixture is representative of a real -P override.
        val nonTransitive = invocation.properties.first { it.key == "android.nonTransitiveRClass" }
        assertEquals(PropertyOrigin.OVERRIDE, nonTransitive.origin)
    }

    @Test
    fun `schema v1 ci-env golden file deserializes with populated ide, agent, remote, and links`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-ci-env.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals("Android Studio", payload.environment?.ide)
        assertEquals("2024.1", payload.environment?.ideVersion)
        assertEquals(false, payload.environment?.ideSync)
        assertEquals("Claude Code", payload.environment?.aiAgent)
        assertEquals("https://******@gitlab.com/acme/app.git", payload.vcs?.remoteUrl)
        assertEquals("gitlab", payload.ci?.provider)
        assertEquals("2", payload.ci?.attributes?.get("runAttempt"))
        assertTrue(payload.links?.commitUrl?.startsWith("https://gitlab.com/acme/app/-/commit/") == true)
        assertTrue(payload.links.pullRequestUrl?.contains("/-/merge_requests/") == true)
    }

    @Test
    fun `TestUnitKey is the single canonical module-slash-class join key`() {
        // Pinned exact form: plans 036/037/040 reference this verbatim to join, so it must never drift.
        assertEquals(":app/com.example.FooTest", TestUnitKey.of(":app", "com.example.FooTest"))
        // Null module → empty-string prefix (still a leading slash), not "null/…".
        assertEquals("/com.example.FooTest", TestUnitKey.of(null, "com.example.FooTest"))
        // TestClassResult.unitKey delegates to the canonical function — same output.
        val cls = TestClassResult(className = "com.example.FooTest")
        assertEquals(TestUnitKey.of(":app", "com.example.FooTest"), cls.unitKey(":app"))
    }

    @Test
    fun `schema v1 kotlin golden file deserializes with populated report`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-kotlin.json"))

        assertEquals(1, payload.schemaVersion)
        val kotlin = payload.kotlin
        assertEquals("KOTLIN_2_4", kotlin?.reportSchema)
        assertEquals(1, kotlin?.truncatedTasks)
        val report = kotlin?.perTask?.single()
        assertEquals(":app:compileDebugKotlin", report?.taskPath)
        assertEquals(1168, report?.durationMs)
        assertEquals(false, report?.incremental)
        assertEquals(listOf("UNKNOWN_CHANGES_IN_GRADLE_INPUTS"), report?.nonIncrementalReasons)
        assertEquals(190, report?.compilerTimesMs?.get("RUN_COMPILATION"))
        assertEquals(3, report?.linesOfCode)
    }

    @Test
    fun `schema v1 processes golden file deserializes with a configured-vs-used snapshot`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-processes.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals(2, payload.processes.size)
        val daemon = payload.processes.first { it.role == ProcessRole.GRADLE_DAEMON }
        // The exit-criterion "configured vs used": used < configured Xmx, and capacity (heapMax) is
        // pinned distinct from the configured Xmx so a refactor can't conflate the two.
        assertEquals(1462, daemon.heapUsedMb)
        assertEquals(4096, daemon.configuredXmxMb)
        assertEquals(4096, daemon.heapMaxMb)
        assertTrue(daemon.heapUsedMb!! < daemon.configuredXmxMb!!)
        assertEquals(3120, daemon.gcTimeMs)
        assertEquals(2711, daemon.rssMb)
        val kotlin = payload.processes.first { it.role == ProcessRole.KOTLIN_DAEMON }
        assertEquals(640, kotlin.heapUsedMb)
        assertEquals(2048, kotlin.configuredXmxMb)
    }

    @Test
    fun `schema v1 process-tuning golden file deserializes with collector, headers, pid, and workersMax`() {
        val payload = BuildHoundJson.payload.decodeFromString(
            BuildPayload.serializer(),
            golden("build-payload-v1-process-tuning.json"),
        )

        assertEquals(1, payload.schemaVersion, "GC/JVM tuning signals are additive — the envelope stays schema v1")
        // Plaintext workers.max rides at the environment level (the benchmark-slicing dimension,
        // plan 065); the salted gradle.maxWorkers fingerprint is a separate, unchanged channel.
        assertEquals(8, payload.environment?.workersMax)
        val daemon = payload.processes.first { it.role == ProcessRole.GRADLE_DAEMON }
        assertEquals(41214, daemon.pid)
        assertEquals(GcCollector.G1, daemon.gcCollector)
        assertEquals(false, daemon.compactObjectHeaders, "an explicit -XX:-UseCompactObjectHeaders reads as false, not null")
        val kotlinDaemon = payload.processes.first { it.role == ProcessRole.KOTLIN_DAEMON }
        assertEquals(GcCollector.G1, kotlinDaemon.gcCollector)
        assertNull(kotlinDaemon.compactObjectHeaders, "an unprinted flag stays an honest null")
        // The fixture is a realistic tuning case: pinned Xmx (used ≈ configured) + high lifetime GC.
        assertTrue(daemon.heapUsedMb!! >= Math.round(daemon.configuredXmxMb!! * 0.9))
    }

    @Test
    fun `schema v1 benchmark golden file deserializes with a populated benchmark block`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-benchmark.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals(BuildMode.BENCHMARK, payload.mode)
        val benchmark = payload.benchmark ?: error("expected a benchmark block")
        assertEquals("clean", benchmark.scenario)
        assertEquals(3, benchmark.iteration)
        assertEquals("no_build_cache", benchmark.isolationMode)
        assertEquals("seed-2026-07-04", benchmark.seedRef)
        // The typed block is also mirrored into tags (the spec's tag contract).
        assertEquals("clean", payload.tags["scenario"])
        assertEquals("no_build_cache", payload.tags["isolationMode"])
    }

    @Test
    fun `schema v1 artifacts golden file deserializes with populated APK, AAB, and AAR sizes`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-artifacts.json"))

        assertEquals(1, payload.schemaVersion)
        val android = payload.artifacts?.android ?: error("expected an artifacts.android list")
        assertEquals(3, android.size)
        val apk = android.single { it.type == ArtifactType.APK }
        assertEquals("release", apk.variant)
        assertEquals(":app", apk.module)
        assertEquals(8421376, apk.sizeBytes)
        assertEquals(setOf(ArtifactType.APK, ArtifactType.AAB, ArtifactType.AAR), android.map { it.type }.toSet())
    }

    @Test
    fun `schema v1 jvm golden file deserializes with springBoot toolchain and jvm artifact sizes`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-jvm.json"))

        assertEquals(1, payload.schemaVersion, "the springBoot dimension + jvm artifacts are additive — the envelope stays schema v1")
        // toolchain.springBoot reported like agp/kgp/ksp (plan 072, research F22).
        assertEquals("3.3.2", payload.toolchain?.springBoot)
        assertEquals("9.0.0", payload.toolchain?.gradle)
        val jvm = payload.artifacts?.jvm ?: error("expected an artifacts.jvm list")
        assertEquals(2, jvm.size)
        val bootJar = jvm.single { it.kind == JvmArtifactKind.BOOT_JAR }
        assertEquals(":app", bootJar.module)
        assertEquals(24117248, bootJar.sizeBytes)
        // JvmArtifactKind serial names are stable (older readers reject unknown enum values, so all four
        // kinds are fixed up front — plan 072 Risks).
        assertEquals(setOf(JvmArtifactKind.BOOT_JAR, JvmArtifactKind.JAR), jvm.map { it.kind }.toSet())
        // A JVM build carries no Android artifacts.
        assertTrue(payload.artifacts.android.isEmpty())
    }

    @Test
    fun `the pre-072 artifacts golden defaults springBoot null and jvm empty (backward-compat, unedited)`() {
        // The plan-031 Android golden predates the plan-072 additions — untouched, it must keep
        // deserializing with the two additions defaulted (additive guarantee).
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-artifacts.json"))

        assertNull(payload.toolchain?.springBoot, "an unedited pre-072 payload has no springBoot dimension")
        assertTrue(payload.artifacts?.jvm?.isEmpty() == true, "artifacts.jvm defaults empty on a pre-072 Android payload")
    }

    @Test
    fun `schema v1 interrupted golden file deserializes as a never-finalized build`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-interrupted-v1.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals(BuildOutcome.INTERRUPTED, payload.outcome)
        assertEquals(BuildMode.LOCAL, payload.mode)
        // A never-finalized build carries no work and no derived metrics — finishedAt == startedAt.
        assertTrue(payload.tasks.isEmpty(), "an interrupted build has no task rows")
        assertNull(payload.derived, "an interrupted build has no derived metrics")
        assertEquals(payload.startedAt, payload.finishedAt)
    }

    @Test
    fun `schema v1 failure-detail golden file deserializes with populated failure`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-failure-detail.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals(BuildOutcome.FAILED, payload.outcome)
        val failure = payload.failure
        assertTrue(failure != null, "failure must be populated on a FAILED build")
        assertEquals("org.gradle.api.tasks.TaskExecutionException", failure.exceptionClass)
        assertEquals("Execution failed for task ':app:compileDebugKotlin'.", failure.message)
        // messageHash is the SHA-256 of the raw message — pin the golden to the live formula so a
        // future edit (or a copy-paste placeholder) can't silently drift, as with cacheableHitRate.
        assertEquals(sha256(failure.message!!), failure.messageHash)
        val stack = failure.stackTrace ?: ""
        assertTrue(stack.contains("Caused by"), "the cause chain is preserved")
        assertTrue(!stack.contains("/Users/"), "no absolute path leaks in the shipped trace")
        assertNull(failure.taskPath, "v1 build-level capture leaves taskPath null")
    }

    @Test
    fun `StartMarker round-trips losslessly`() {
        val marker = StartMarker(
            buildId = "7c2a1b90-4d5e-4f6a-8b7c-1d2e3f4a5b6c",
            startedAtMs = 1751450000000,
            mode = BuildMode.CI,
            projectKey = "pilot-android",
            requestedTasks = listOf("assembleDebug", "test"),
        )
        val json = BuildHoundJson.payload.encodeToString(StartMarker.serializer(), marker)
        assertEquals(marker, BuildHoundJson.payload.decodeFromString(StartMarker.serializer(), json))
    }

    @Test
    fun `round trip is lossless`() {
        for (name in listOf(
            "build-payload-v1.json",
            "build-payload-v1-task-metadata.json",
            "build-payload-v1-caps.json",
            "build-payload-v1-fingerprints.json",
            "build-payload-v1-kotlin.json",
            "build-payload-v1-tests.json",
            "build-payload-v1-ci-env.json",
            "build-payload-v1-invocation.json",
            "build-payload-v1-processes.json",
            "build-payload-v1-benchmark.json",
            "build-payload-v1-artifacts.json",
            "build-payload-v1-jvm.json",
            "build-payload-interrupted-v1.json",
            "build-payload-v2ext.json",
            "build-payload-v1-internal-adapters.json",
            "build-payload-v1-internal-adapters-edges.json",
            "build-payload-v1-sharding.json",
            "build-payload-v1-failure-detail.json",
            "build-payload-v1-project-evaluations.json",
            "build-payload-v1-build-structure.json",
            "build-payload-v1-wrapper.json",
            "build-payload-v1-test-telemetry.json",
            "build-payload-v1-process-tuning.json",
            "build-payload-v1-buildcache.json",
            "build-payload-v1-changed-modules.json",
            "build-payload-v1-cc-economics.json",
            "build-payload-v1-excluded-tasks.json",
        )) {
            val original = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden(name))
            val reEncoded = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), original)
            val decodedAgain = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), reEncoded)

            assertEquals(original, decodedAgain, name)
        }
    }

    @Test
    fun `unknown fields from newer plugins are tolerated`() {
        val withExtraField = golden("build-payload-v1.json")
            .replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"futureField\": {\"x\": 1},")

        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), withExtraField)
        assertTrue(payload.tasks.isNotEmpty())
    }
}
