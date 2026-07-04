package dev.buildhound.gradle

import dev.buildhound.commons.payload.ArtifactSize
import dev.buildhound.commons.payload.ArtifactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtifactRecordIoTest {

    @Test
    fun `encode then parse round-trips`() {
        val line = ArtifactRecordIo.encode(module = ":app", variant = "release", type = ArtifactType.APK, sizeBytes = 8_421_376)
        assertEquals(
            ArtifactSize(variant = "release", module = ":app", type = ArtifactType.APK, sizeBytes = 8_421_376),
            ArtifactRecordIo.parse(line),
        )
    }

    @Test
    fun `a null module round-trips as null`() {
        val line = ArtifactRecordIo.encode(module = null, variant = "debug", type = ArtifactType.AAR, sizeBytes = 100)
        val parsed = ArtifactRecordIo.parse(line)!!
        assertNull(parsed.module)
        assertEquals(ArtifactType.AAR, parsed.type)
    }

    @Test
    fun `malformed, blank, and unknown-type lines are dropped`() {
        assertNull(ArtifactRecordIo.parse(""))
        assertNull(ArtifactRecordIo.parse("not json"))
        assertNull(ArtifactRecordIo.parse("""{"variant":"release","type":"XAPK","sizeBytes":1}""")) // unknown enum
        assertNull(ArtifactRecordIo.parse("""{"type":"APK","sizeBytes":1}""")) // missing variant
        assertNull(ArtifactRecordIo.parse("""{"variant":"r","type":"APK","sizeBytes":"big"}""")) // non-numeric size
    }

    @Test
    fun `parseAll flattens multi-line files and skips bad lines`() {
        val fileA = listOf(
            ArtifactRecordIo.encode(":app", "release", ArtifactType.APK, 10),
            "garbage",
            ArtifactRecordIo.encode(":app", "release", ArtifactType.AAB, 20),
        ).joinToString("\n")
        val fileB = ArtifactRecordIo.encode(":lib", "release", ArtifactType.AAR, 30)

        val all = ArtifactRecordIo.parseAll(listOf(fileA, fileB))
        assertEquals(3, all.size)
        assertEquals(setOf(ArtifactType.APK, ArtifactType.AAB, ArtifactType.AAR), all.map { it.type }.toSet())
    }
}
