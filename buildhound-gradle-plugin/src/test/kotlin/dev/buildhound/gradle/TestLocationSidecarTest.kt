package dev.buildhound.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestLocationSidecarTest {

    private val tmp: File = Files.createTempDirectory("sidecar").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test
    fun `encode then decode round-trips, including a null module`() {
        val map = mapOf(
            ":core:common:test" to TestResultLocations("/a/build/test-results/test", ":core:common"),
            ":app:testDebugUnitTest" to TestResultLocations("/b/test-results", module = null),
        )
        assertEquals(map, TestLocationSidecar.decode(TestLocationSidecar.encode(map)))
    }

    @Test
    fun `write then read round-trips through the dot-gradle sidecar file`() {
        val map = mapOf(":m:test" to TestResultLocations("/x/test-results/test", ":m"))
        TestLocationSidecar.write(tmp, map)

        assertTrue(File(tmp, ".gradle/buildhound/test-locations.jsonl").isFile, "file created under .gradle")
        assertEquals(map, TestLocationSidecar.read(tmp))
    }

    @Test
    fun `read of a missing file is empty, never throws`() {
        assertEquals(emptyMap(), TestLocationSidecar.read(tmp))
    }

    @Test
    fun `malformed lines are skipped, well-formed ones survive`() {
        val good = TestLocationSidecar.encode(mapOf(":m:test" to TestResultLocations("/x", ":m")))
        val decoded = TestLocationSidecar.decode("not json\n\n{\"taskPath\":\"only-path\"}\n$good")
        assertEquals(mapOf(":m:test" to TestResultLocations("/x", ":m")), decoded)
    }

    @Test
    fun `write overwrites rather than appends`() {
        TestLocationSidecar.write(tmp, mapOf(":a:test" to TestResultLocations("/a", ":a")))
        TestLocationSidecar.write(tmp, mapOf(":b:test" to TestResultLocations("/b", ":b")))
        assertEquals(mapOf(":b:test" to TestResultLocations("/b", ":b")), TestLocationSidecar.read(tmp))
    }

    // --- plan 053: junitXmlRequired round-trip ---

    @Test
    fun `junitXmlRequired false round-trips through encode-decode`() {
        val map = mapOf(":app:test" to TestResultLocations("/x/test-results/test", ":app", junitXmlRequired = false))
        assertEquals(map, TestLocationSidecar.decode(TestLocationSidecar.encode(map)))
    }

    @Test
    fun `junitXmlRequired true is omitted from the encoded line but still round-trips`() {
        val map = mapOf(":app:test" to TestResultLocations("/x/test-results/test", ":app", junitXmlRequired = true))
        val encoded = TestLocationSidecar.encode(map)
        assertTrue(!encoded.contains("junitXmlRequired"), "the common case (true) is omitted to keep legacy lines compatible")
        assertEquals(map, TestLocationSidecar.decode(encoded))
    }

    @Test
    fun `a legacy line without junitXmlRequired decodes to true`() {
        val legacyLine = """{"taskPath":":app:test","junitXmlDir":"/x/test-results/test","module":":app"}"""
        assertEquals(
            mapOf(":app:test" to TestResultLocations("/x/test-results/test", ":app", junitXmlRequired = true)),
            TestLocationSidecar.decode(legacyLine),
        )
    }
}
