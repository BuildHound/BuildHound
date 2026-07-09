package dev.buildhound.internaladapters

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.ExtensionContributionContext
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement

/** Gradle-free logic of the internal-adapters module (plan 038): classifier, gate, caps, salt, model. */
class InternalAdaptersUnitTest {

    @Test
    fun `origin classification follows local greater than remote greater than store greater than miss`() {
        assertEquals(CacheOrigin.LOCAL_HIT, OriginClassifier.classify(localLoadHit = true, remoteLoadHit = true, stored = true, executed = true))
        assertEquals(CacheOrigin.REMOTE_HIT, OriginClassifier.classify(localLoadHit = false, remoteLoadHit = true, stored = true, executed = true))
        assertEquals(CacheOrigin.STORED, OriginClassifier.classify(localLoadHit = false, remoteLoadHit = false, stored = true, executed = true))
        assertEquals(CacheOrigin.MISS, OriginClassifier.classify(localLoadHit = false, remoteLoadHit = false, stored = false, executed = true))
        assertEquals(CacheOrigin.UNKNOWN, OriginClassifier.classify(localLoadHit = false, remoteLoadHit = false, stored = false, executed = false))
    }

    @Test
    fun `version gate buckets 8 and 9 and degrades everything else to unknown`() {
        assertEquals(GradleBucket.V8, VersionGate.bucket("8.14.3"))
        assertEquals(GradleBucket.V9, VersionGate.bucket("9.0"))
        assertEquals(GradleBucket.UNKNOWN, VersionGate.bucket("10.0"))
        assertEquals(GradleBucket.UNKNOWN, VersionGate.bucket("weird"))
    }

    @Test
    fun `caps drop the overflow and count it`() {
        val map = (1..250).associate { "k$it" to it }
        val (kept, dropped) = Caps.capMap(map, Caps.MAX_PROPERTY_HASHES)
        assertEquals(200, kept.size)
        assertEquals(50, dropped)
        val (list, listDropped) = Caps.capList((1..10).toList(), 4)
        assertEquals(listOf(1, 2, 3, 4), list)
        assertEquals(6, listDropped)
    }

    @Test
    fun `salt hashing is deterministic, salted, and null-safe`() {
        val dir = createTempDirectory("bh-salt").toFile()
        val salt = SaltHasher.readOrCreateSalt(File(dir, ".gradle/buildhound/internal-adapters.salt"))
        assertNotNull(salt)
        val value = byteArrayOf(1, 2, 3, 4)
        val h1 = SaltHasher.hash(salt, value)
        val h2 = SaltHasher.hash(salt, value)
        assertEquals(h1, h2, "same salt + value → same hash")
        assertTrue(h1!!.endsWith("…") && h1.length == 17, "16 hex + ellipsis: $h1")
        // A different salt yields a different digest — proves it is actually salted.
        val otherSalt = ByteArray(32) { 7 }
        assertNotEquals(h1, SaltHasher.hash(otherSalt, value))
        // No salt / no value ⇒ null (never a raw key).
        assertNull(SaltHasher.hash(null, value))
        assertNull(SaltHasher.hash(salt, null))
    }

    @Test
    fun `salt file is reused across reads`() {
        val dir = createTempDirectory("bh-salt2").toFile()
        val file = File(dir, ".gradle/buildhound/internal-adapters.salt")
        val a = SaltHasher.readOrCreateSalt(file)
        val b = SaltHasher.readOrCreateSalt(file)
        assertNotNull(a)
        assertTrue(a.contentEquals(b), "the salt file is stable across builds")
    }

    @Test
    fun `payload round-trips losslessly through the shared json`() {
        val payload = InternalAdaptersPayload(
            gradleVersion = "8.14.3",
            tasks = listOf(InternalTaskDetail(path = ":a:compileJava", cacheKey = "abcd…", origin = CacheOrigin.LOCAL_HIT)),
            avoidedMs = 1234,
            dependencyEdges = mapOf(":a:test" to listOf(":a:compileJava")),
        )
        val element: JsonElement = BuildHoundJson.payload.encodeToJsonElement(InternalAdaptersPayload.serializer(), payload)
        val decoded = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), element)
        assertEquals(payload, decoded)
        // Core (buildhound-gradle-plugin) reads avoidedMs + dependencyEdges out of this shape; that
        // extractor is unit-tested there (InternalAdaptersDerivedTest) against these exact field names.
    }

    @Test
    fun `payload round-trips the additive cache-transfer fields`() {
        val payload = InternalAdaptersPayload(
            gradleVersion = "9.0.0",
            tasks = listOf(
                InternalTaskDetail(
                    path = ":a:compileJava",
                    origin = CacheOrigin.REMOTE_HIT,
                    transferBytes = 4096,
                    loadMs = 12,
                    storeMs = null,
                ),
            ),
        )
        val element: JsonElement = BuildHoundJson.payload.encodeToJsonElement(InternalAdaptersPayload.serializer(), payload)
        val decoded = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), element)
        assertEquals(payload, decoded)
        val task = decoded.tasks.single()
        assertEquals(4096, task.transferBytes)
        assertEquals(12, task.loadMs)
        assertNull(task.storeMs, "an unmeasured store stays an honest null, never a fabricated 0")
    }

    @Test
    fun `TaskAccum transfer accumulation sums non-nulls and leaves an all-null field null`() {
        val t = TaskAccum()
        assertNull(t.transferBytes)
        assertNull(t.loadMs)
        // A load then a store on the same task accumulate bytes; each duration lands in its own field.
        t.addLoadMs(10); t.addTransferBytes(2048)
        t.addStoreMs(7); t.addTransferBytes(2048)
        assertEquals(4096, t.transferBytes, "bytes sum across the load + store ops")
        assertEquals(10, t.loadMs)
        assertEquals(7, t.storeMs)
        // A null read (getter unavailable / no bytes) never fabricates a zero.
        val untouched = TaskAccum()
        untouched.addTransferBytes(null); untouched.addLoadMs(null); untouched.addStoreMs(null)
        assertNull(untouched.transferBytes)
        assertNull(untouched.loadMs)
        assertNull(untouched.storeMs)
    }

    @Test
    fun `TaskAccum drops a negative transfer-bytes sentinel rather than corrupting the total`() {
        // Gradle's own local-load-miss result returns getArchiveSize() == -1 (a sentinel, not a thrown
        // exception — verified via javap), so a plain += would silently subtract 1 byte per miss.
        val t = TaskAccum()
        t.addTransferBytes(2048)
        t.addTransferBytes(-1)
        assertEquals(2048, t.transferBytes, "a negative reading must be dropped, not accumulated")
        // A negative-only history stays an honest null, never a fabricated/negative total.
        val negativeOnly = TaskAccum()
        negativeOnly.addTransferBytes(-1)
        assertNull(negativeOnly.transferBytes)
    }

    @Test
    fun `the collector maps accumulated transfer timings onto the task detail`() {
        InternalAdaptersState.resetForTest()
        InternalAdaptersState.accumulator().forPath(":app:compileJava").apply {
            remoteLoadHit = true
            addLoadMs(15)
            addTransferBytes(8192)
        }
        InternalAdaptersState.configure(perFile = false, gradle = "9.0.0", root = null, edges = emptyMap())

        val ctx = ExtensionContributionContext(projectKey = "p", mode = BuildMode.CI, tasks = emptyList())
        val json = InternalAdaptersCollector().contribute(ctx) ?: error("expected a contribution")
        val payload = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), json)

        val compile = payload.tasks.single { it.path == ":app:compileJava" }
        assertEquals(CacheOrigin.REMOTE_HIT, compile.origin)
        assertEquals(8192, compile.transferBytes)
        assertEquals(15, compile.loadMs)
        assertNull(compile.storeMs, "no store op ran → an honest null")
    }

    @Test
    fun `the collector assembles the extension from captured state and then clears it`() {
        InternalAdaptersState.resetForTest()
        InternalAdaptersState.accumulator().forPath(":app:compileJava").apply {
            cacheKeyRaw = byteArrayOf(1, 2, 3, 4)
            localLoadHit = true
            originExecutionTimeMs = 500
        }
        InternalAdaptersState.accumulator().forPath(":app:test").apply { executed = true }
        InternalAdaptersState.setSalt(ByteArray(32) { 9 })
        InternalAdaptersState.configure(perFile = false, gradle = "8.14.3", root = null, edges = mapOf(":app:test" to listOf(":app:compileJava")))

        val ctx = ExtensionContributionContext(projectKey = "p", mode = BuildMode.CI, tasks = emptyList())
        val json = InternalAdaptersCollector().contribute(ctx) ?: error("expected a contribution")
        val payload = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), json)

        assertEquals("8.14.3", payload.gradleVersion)
        val compile = payload.tasks.single { it.path == ":app:compileJava" }
        assertEquals(CacheOrigin.LOCAL_HIT, compile.origin)
        assertNotNull(compile.cacheKey, "the raw cache key was salted into the payload")
        assertEquals(CacheOrigin.MISS, payload.tasks.single { it.path == ":app:test" }.origin)
        assertEquals(500, payload.avoidedMs, "avoidedMs = origin execution time of the cache-hit task")
        assertEquals(mapOf(":app:test" to listOf(":app:compileJava")), payload.dependencyEdges)

        // takeAccumulator installed a fresh accumulator: both per-task capture AND edges are cleared
        // for the next build (edges are build-specific; salt persists per daemon).
        assertTrue(InternalAdaptersState.accumulator().byPath.isEmpty())
        assertTrue(InternalAdaptersState.accumulator().edges.isEmpty())
    }

    @Test
    fun `a CC-hit build (tasks captured but no whenReady) emits empty edges so criticalPath degrades to null`() {
        InternalAdaptersState.resetForTest()
        // Simulate a config-cache hit: the listener captured this build's tasks, but the whenReady
        // edge walk never ran (config skipped) — so the accumulator's edges stay empty.
        InternalAdaptersState.accumulator().forPath(":app:compileJava").apply { executed = true }
        InternalAdaptersState.setSalt(ByteArray(32) { 1 })

        val ctx = ExtensionContributionContext(projectKey = "p", mode = BuildMode.CI, tasks = emptyList())
        val json = InternalAdaptersCollector().contribute(ctx) ?: error("expected a contribution")
        val payload = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), json)

        // No stale graph from a prior invocation — edges empty ⇒ core computes null criticalPath.
        assertTrue(payload.dependencyEdges.isEmpty(), "a CC-hit build must not emit another invocation's edges")
        assertTrue(payload.tasks.isNotEmpty(), "task capture still works on a CC hit (listener is daemon-scoped)")
    }

    @Test
    fun `collectCacheOrigins toggle is set by configure and cleared by reset`() {
        InternalAdaptersState.resetForTest()
        assertFalse(InternalAdaptersState.collectCacheOrigins(), "off by default")
        InternalAdaptersState.configure(
            perFile = false, gradle = "9.6.1", root = "/proj", edges = emptyMap(),
            collectCacheOrigins = true,
        )
        assertTrue(InternalAdaptersState.collectCacheOrigins(), "configure() turns it on")
        // Independent of the warning toggles — this gates only the cache data paths (plan 074).
        assertFalse(InternalAdaptersState.collectDeprecations())
        assertFalse(InternalAdaptersState.collectLogWarnings())
        InternalAdaptersState.resetForTest()
        assertFalse(InternalAdaptersState.collectCacheOrigins(), "reset clears it")
    }

    @Test
    fun `the collector scrubs and emits captured warnings`() {
        InternalAdaptersState.resetForTest()
        val root = "/home/ci/agent/work/project"
        InternalAdaptersState.accumulator().addDeprecation("The foo API is deprecated at $root/src/Build.kt token=abc123XYZ456")
        InternalAdaptersState.accumulator().addLogWarning("something odd at /home/secret/creds.txt")
        InternalAdaptersState.configure(perFile = false, gradle = "9.6.1", root = root, edges = emptyMap())

        val ctx = ExtensionContributionContext(projectKey = "p", mode = BuildMode.CI, tasks = emptyList())
        val json = InternalAdaptersCollector().contribute(ctx) ?: error("expected a contribution")
        val payload = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), json)

        val dep = payload.deprecations.single()
        assertTrue(dep.contains("src/Build.kt"), "in-project path relativized: $dep")
        assertFalse(dep.contains("/home/ci/agent"), "absolute root stripped: $dep")
        assertFalse(dep.contains("abc123XYZ456"), "secret-shaped token redacted: $dep")
        assertFalse(payload.logWarnings.single().contains("/home/secret"), "out-of-project path redacted")

        // takeAccumulator cleared the warnings for the next build.
        assertTrue(InternalAdaptersState.accumulator().deprecations.isEmpty())
        assertTrue(InternalAdaptersState.accumulator().logWarnings.isEmpty())
    }

    @Test
    fun `warning toggles are daemon-static and survive a CC-hit build (no re-configure)`() {
        InternalAdaptersState.resetForTest()
        // Store build: whenReady runs, configure() sets the toggles.
        InternalAdaptersState.configure(
            perFile = false, gradle = "9.6.1", root = "/proj", edges = emptyMap(),
            collectDeprecations = true, collectLogWarnings = true,
        )
        // Build boundary: the collector reads-and-clears the accumulator each build.
        InternalAdaptersState.takeAccumulator()
        // CC-hit build: whenReady does NOT run (no configure()) — the daemon-static toggles persist,
        // so the listeners stay gated ON and capture continues (the perFileHashes precedent).
        assertTrue(InternalAdaptersState.collectDeprecations(), "deprecation toggle persists across the build boundary")
        assertTrue(InternalAdaptersState.collectLogWarnings(), "log-warning toggle persists across the build boundary")
        InternalAdaptersState.accumulator().addLogWarning("captured on the CC-hit build")
        val ctx = ExtensionContributionContext(projectKey = "p", mode = BuildMode.CI, tasks = emptyList())
        val json = InternalAdaptersCollector().contribute(ctx) ?: error("expected a contribution")
        val payload = BuildHoundJson.payload.decodeFromJsonElement(InternalAdaptersPayload.serializer(), json)
        assertEquals(listOf("captured on the CC-hit build"), payload.logWarnings)
    }

    @Test
    fun `the accumulator dedups warnings and bounds each stream, counting overflow`() {
        val acc = Accumulator()
        acc.addDeprecation("same")
        acc.addDeprecation("same") // dedup: not a new distinct warning, not an overflow
        assertEquals(1, acc.deprecations.size)
        assertEquals(0, acc.droppedWarnings.get())
        // Fill the log-warning stream past its cap with distinct messages.
        for (i in 1..Caps.MAX_WARNINGS + 5) acc.addLogWarning("w$i")
        assertEquals(Caps.MAX_WARNINGS, acc.logWarnings.size)
        assertEquals(5, acc.droppedWarnings.get(), "distinct warnings past the cap are dropped and counted")
    }

    @Test
    fun `accumulator correlates an op to its enclosing task via the parent chain`() {
        val acc = Accumulator()
        acc.taskPathOf[10L] = ":app:compileJava"
        acc.parentOf[20L] = 10L // snapshot op under the task op
        acc.parentOf[30L] = 20L // cache-load op under the snapshot op
        assertEquals(":app:compileJava", acc.taskPathFor(30L))
        assertNull(acc.taskPathFor(999L), "an unparented op resolves to no task")
    }
}
