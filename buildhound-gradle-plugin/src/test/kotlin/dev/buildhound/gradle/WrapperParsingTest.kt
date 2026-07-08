package dev.buildhound.gradle

import dev.buildhound.commons.payload.WrapperDistributionType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for [WrapperParsing] (plan 066): variant classification, pinning, jar
 * hashing, and GUH dist-dir probing, all over a real temp dir/files — no Gradle API needed, since
 * every function here is total (missing input degrades to null, never a throw).
 */
class WrapperParsingTest {

    private val tmp: File = Files.createTempDirectory("wrapper-parsing").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test
    fun `classifyVariant maps -all and -bin filenames, else CUSTOM`() {
        assertEquals(
            WrapperDistributionType.ALL,
            WrapperParsing.classifyVariant("https://services.gradle.org/distributions/gradle-8.14-all.zip"),
        )
        assertEquals(
            WrapperDistributionType.BIN,
            WrapperParsing.classifyVariant("https://services.gradle.org/distributions/gradle-8.14-bin.zip"),
        )
        assertEquals(
            WrapperDistributionType.CUSTOM,
            WrapperParsing.classifyVariant("https://artifacts.example.internal/mirror/gradle-8.14.zip"),
        )
        // A custom-mirror rehost of the same official filename still classifies by suffix — the
        // enum only distinguishes variant, never "official vs mirror" (that's the discarded host).
        assertEquals(
            WrapperDistributionType.BIN,
            WrapperParsing.classifyVariant("https://mirror.internal.example.com/gradle-8.14-bin.zip"),
        )
    }

    @Test
    fun `loadProperties is null for a missing file, never throws`() {
        assertNull(WrapperParsing.loadProperties(File(tmp, "missing.properties")))
    }

    @Test
    fun `isPinned reflects distributionSha256Sum presence, null for unreadable properties`() {
        assertNull(WrapperParsing.isPinned(null))
        val pinned = propertiesFile(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n" +
                "distributionSha256Sum=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\n",
        )
        assertEquals(true, WrapperParsing.isPinned(WrapperParsing.loadProperties(pinned)))

        val unpinned = propertiesFile(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n",
        )
        assertEquals(false, WrapperParsing.isPinned(WrapperParsing.loadProperties(unpinned)))
    }

    @Test
    fun `distributionUrl reads the raw url, null for unreadable properties`() {
        assertNull(WrapperParsing.distributionUrl(null))
        val props = propertiesFile("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-all.zip\n")
        assertEquals(
            "https://services.gradle.org/distributions/gradle-8.14-all.zip",
            WrapperParsing.distributionUrl(WrapperParsing.loadProperties(props)),
        )
    }

    @Test
    fun `sha256Hex is a stable full-hex digest of the file's bytes, null when missing`() {
        val jar = File(tmp, "gradle-wrapper.jar").apply { writeBytes("known content".toByteArray()) }
        val hash = WrapperParsing.sha256Hex(jar)
        assertEquals(64, hash?.length, "full hex SHA-256 is 64 chars")
        assertEquals(hash, WrapperParsing.sha256Hex(jar), "hashing is deterministic")
        // SHA-256("known content") pinned so a future refactor can't silently change the algorithm.
        assertEquals("41277d8d0b0610e58f13bdc06b732c629a2fd3ff93c382f40af3f60cfe5e5c9e", hash)
        assertNull(WrapperParsing.sha256Hex(File(tmp, "missing.jar")))
    }

    @Test
    fun `distDirName is null for CUSTOM, else the version-variant convention`() {
        assertEquals("gradle-8.14-bin", WrapperParsing.distDirName("8.14", WrapperDistributionType.BIN))
        assertEquals("gradle-8.14-all", WrapperParsing.distDirName("8.14", WrapperDistributionType.ALL))
        assertNull(WrapperParsing.distDirName("8.14", WrapperDistributionType.CUSTOM))
    }

    @Test
    fun `probeDist reports absent for a missing dir, present with a max child mtime otherwise`() {
        val guh = File(tmp, "guh")
        assertEquals(false to null, WrapperParsing.probeDist(guh, "gradle-8.14-bin"))

        // Create every directory first, THEN stamp mtimes: creating a child bumps its parent's own
        // mtime as a filesystem side effect, so setting timestamps only after all mkdirs() calls
        // finish is what keeps this test deterministic (no dependence on the real wall clock).
        val distDir = File(File(guh, "wrapper/dists"), "gradle-8.14-bin").apply { mkdirs() }
        val hashDir = File(distDir, "abc123def").apply { mkdirs() }
        distDir.setLastModified(1_000)
        hashDir.setLastModified(5_000)

        val (present, mtime) = WrapperParsing.probeDist(guh, "gradle-8.14-bin")
        assertTrue(present)
        assertEquals(5_000, mtime)
    }

    private fun propertiesFile(text: String): File =
        File(tmp, "gradle-wrapper-${System.nanoTime()}.properties").apply { writeText(text) }
}
