package dev.buildhound.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * Unit coverage for the plan-064 plugin-side pure pieces: [CcEntrySize] (newest-entry byte probe) and
 * the finalizer's [ccLoadMs] proxy helper. Both are load-bearing gates for the CC-economics fields, so
 * boundary + adversarial cases are pinned here (never-fail: every degraded input maps to an honest null).
 */
class CcEconomicsPluginTest {

    @field:TempDir
    lateinit var rootDir: File

    private fun ccEntry(name: String, mtimeMs: Long, files: Map<String, Int>): File {
        val dir = File(rootDir, ".gradle/configuration-cache/$name").apply { mkdirs() }
        files.forEach { (relPath, size) ->
            File(dir, relPath).apply { parentFile.mkdirs() }.writeBytes(ByteArray(size))
        }
        dir.setLastModified(mtimeMs)
        return dir
    }

    // --- CcEntrySize ---

    @Test
    fun `sums only the newest-modified entry, ignoring stale retained siblings`() {
        // A stale sibling (older mtime) with a huge file must not inflate the count — the plan's
        // 7-day-retention pollution guard (only the newest entry is this build's active one).
        ccEntry("stale", mtimeMs = 1_000_000_000_000, files = mapOf("store.bin" to 9_000))
        ccEntry("fresh", mtimeMs = 1_000_000_100_000, files = mapOf("store.bin" to 250, "work/graph.bin" to 150))

        assertEquals(400L, CcEntrySize.newestEntryBytes(rootDir), "only the newest entry's files (250+150) are summed")
    }

    @Test
    fun `a missing configuration-cache directory degrades to null`() {
        assertNull(CcEntrySize.newestEntryBytes(rootDir), "no .gradle/configuration-cache dir → null")
    }

    @Test
    fun `a configuration-cache directory with no entry subdirs degrades to null`() {
        File(rootDir, ".gradle/configuration-cache").mkdirs()
        assertNull(CcEntrySize.newestEntryBytes(rootDir), "no <hash> entry subdir → null")
    }

    @Test
    fun `a file count past the cap degrades to null rather than walking an unbounded tree`() {
        ccEntry("fresh", mtimeMs = 1_000_000_100_000, files = (1..4).associate { "f$it.bin" to 10 })
        // Below the cap it sums; a maxFiles of 3 against 4 files trips the guard → null.
        assertEquals(40L, CcEntrySize.newestEntryBytes(rootDir, maxFiles = 10))
        assertNull(CcEntrySize.newestEntryBytes(rootDir, maxFiles = 3), "over-cap file count → null")
    }

    // --- ccLoadMs proxy ---

    @Test
    fun `ccLoadMs is the interval from the anchor to the earliest task start`() {
        assertEquals(42L, ccLoadMs(anchorMs = 1_000, earliestTaskStartMs = 1_042))
        // A same-instant start is a valid zero interval, not a degraded null.
        assertEquals(0L, ccLoadMs(anchorMs = 1_000, earliestTaskStartMs = 1_000))
    }

    @Test
    fun `ccLoadMs degrades to null on a missing endpoint or an out-of-order anchor`() {
        assertNull(ccLoadMs(anchorMs = null, earliestTaskStartMs = 1_042), "no anchor stamped → null")
        assertNull(ccLoadMs(anchorMs = 1_000, earliestTaskStartMs = null), "no task ran → null")
        // An anchor observed AFTER the first task start (clock skew / late instantiation) is degraded,
        // never emitted as a negative nonsense value.
        assertNull(ccLoadMs(anchorMs = 1_100, earliestTaskStartMs = 1_000), "negative interval → null")
    }
}
