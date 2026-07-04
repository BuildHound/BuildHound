package dev.buildhound.internaladapters

import dev.buildhound.commons.payload.BuildHoundJson
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `payload round-trips and derivedInputs extracts avoidedMs and edges`() {
        val payload = InternalAdaptersPayload(
            gradleVersion = "8.14.3",
            tasks = listOf(InternalTaskDetail(path = ":a:compileJava", cacheKey = "abcd…", origin = CacheOrigin.LOCAL_HIT)),
            avoidedMs = 1234,
            dependencyEdges = mapOf(":a:test" to listOf(":a:compileJava")),
        )
        val element: JsonElement = BuildHoundJson.payload.encodeToJsonElement(InternalAdaptersPayload.serializer(), payload)
        val (avoided, edges) = InternalAdaptersCollector.derivedInputs(element)
        assertEquals(1234, avoided)
        assertEquals(mapOf(":a:test" to listOf(":a:compileJava")), edges)
        // A null / malformed element degrades to (null, empty), never throws.
        assertEquals(null to emptyMap(), InternalAdaptersCollector.derivedInputs(null))
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
