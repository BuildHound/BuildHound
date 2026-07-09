package dev.buildhound.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions
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
        // 7-day-retention pollution guard (only the newest entry is this build's active one). Both are
        // genuine entries (entry.bin present) so the mtime tie-break between them is what's under test.
        ccEntry("stale", mtimeMs = 1_000_000_000_000, files = mapOf("entry.bin" to 4, "store.bin" to 9_000))
        ccEntry(
            "fresh",
            mtimeMs = 1_000_000_100_000,
            files = mapOf("entry.bin" to 0, "store.bin" to 250, "work/graph.bin" to 150),
        )

        assertEquals(400L, CcEntrySize.newestEntryBytes(rootDir), "only the newest entry's files (250+150+0) are summed")
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
    fun `a newer non-entry sibling never wins over an older entry-bin-marked dir`() {
        // Empirically the real layout (verified against this repo's own .gradle/configuration-cache): a
        // genuine entry always carries entry.bin; an in-flight/orphaned candidate dir can be completely
        // empty, and a short hash-like dir holds only candidates.bin — never entry.bin. Both decoys here
        // are newer than the real entry (the mainstream trigger is a concurrent IDE tooling-API sync), so
        // a pure newest-mtime pick would silently choose one of them and sum to 0 instead of the real 50.
        ccEntry("real-entry", mtimeMs = 1_000_000_000_000, files = mapOf("entry.bin" to 20, "work.bin" to 30))
        ccEntry("empty-decoy", mtimeMs = 1_000_000_200_000, files = emptyMap())
        ccEntry("candidates-decoy", mtimeMs = 1_000_000_300_000, files = mapOf("candidates.bin" to 38))

        assertEquals(50L, CcEntrySize.newestEntryBytes(rootDir), "the entry.bin-marked dir wins even though both decoys are newer")
    }

    @Test
    fun `no entry-bin-marked dir anywhere degrades to null, never a decoy's size`() {
        ccEntry("empty-decoy", mtimeMs = 1_000_000_000_000, files = emptyMap())
        ccEntry("candidates-decoy", mtimeMs = 1_000_000_100_000, files = mapOf("candidates.bin" to 38))

        assertNull(CcEntrySize.newestEntryBytes(rootDir), "no dir carries entry.bin → honest null, not 0 or a decoy's bytes")
    }

    @Test
    fun `a node count past the cap degrades to null rather than walking an unbounded tree`() {
        ccEntry("fresh", mtimeMs = 1_000_000_100_000, files = mapOf("entry.bin" to 0) + (1..4).associate { "f$it.bin" to 10 })
        // 5 files total (entry.bin + f1..f4): below the cap it sums; a maxFiles of 3 trips the guard → null.
        assertEquals(40L, CcEntrySize.newestEntryBytes(rootDir, maxFiles = 10))
        assertNull(CcEntrySize.newestEntryBytes(rootDir, maxFiles = 3), "over-cap node count → null")
    }

    @Test
    fun `deeply nested directories are bounded by total node count, not just file count`() {
        // Only 2 real files (entry.bin, leaf.bin) but 5 nested directories in between. A file-only guard
        // would let this through at maxFiles=3 (2 files ≤ 3); the fix counts directory pushes too, so the
        // walk is still bounded even when the tree is deep rather than wide.
        var dir = ccEntry("deep", mtimeMs = 1_000_000_000_000, files = mapOf("entry.bin" to 5))
        repeat(5) { i -> dir = File(dir, "d$i").apply { mkdirs() } }
        File(dir, "leaf.bin").writeBytes(ByteArray(10))

        assertNull(CcEntrySize.newestEntryBytes(rootDir, maxFiles = 3), "directory pushes alone exceed a cap of 3")
    }

    @Test
    fun `a symlink is never followed, so a cycle back into the tree cannot hang the walk`() {
        val entryDir = ccEntry("cyclic", mtimeMs = 1_000_000_000_000, files = mapOf("entry.bin" to 5))
        val supportsSymlinks = try {
            Files.createSymbolicLink(File(entryDir, "loop").toPath(), entryDir.toPath())
            true
        } catch (_: Exception) {
            false
        }
        Assumptions.assumeTrue(supportsSymlinks, "platform/filesystem does not support symlinks")

        // The symlink points back at its own parent dir — an unbounded cycle if followed. NOFOLLOW_LINKS
        // means it is skipped entirely, so the walk terminates and the count is exactly entry.bin's size.
        assertEquals(5L, CcEntrySize.newestEntryBytes(rootDir), "the symlink cycle is skipped, not descended into")
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
