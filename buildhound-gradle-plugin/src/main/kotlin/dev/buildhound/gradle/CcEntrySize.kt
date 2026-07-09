package dev.buildhound.gradle

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Best-effort byte size of the active configuration-cache entry (plan 064, research F14). Sums the file
 * sizes under the **newest-modified, `entry.bin`-marked** `<rootDir>/.gradle/configuration-cache/<id>`
 * subdir — the entry this build stored (`MISS_STORED`) or loaded (`HIT`) — rather than the whole
 * directory, so Gradle's 7-day retention of stale sibling entries never inflates the count (F14
 * narrowing). The `entry.bin` marker is required, not just newest-mtime: a concurrent build touching the
 * same project (an IDE tooling-API sync is the mainstream case BuildHound explicitly supports) leaves
 * behind near-empty or `candidates.bin`-only sibling dirs that can be *newer* than the real entry;
 * trusting mtime alone would silently pick one of those and ship a confident `0L` instead of an honest
 * null (F14 narrowing #2 — verified against this repo's own `.gradle/configuration-cache` layout). Read
 * at **finalizer/execution** time only (the caller has `rootDir` there); a configuration-phase read
 * would make the directory a CC fingerprint input (architecture §2 rule 9). Every step is guarded — a
 * missing/unrecognized layout, an unreadable tree, or a node count past [MAX_FILES] degrades to null
 * (honest-null, plan 005), never a thrown finalizer. A byte count only; no path ever leaves this object
 * (spec §3.7).
 */
internal object CcEntrySize {

    /**
     * Defensive ceiling on filesystem nodes (directories + files) walked for one probe. A
     * configuration-cache entry is a handful of `.bin` files with no meaningful subdirectory depth; a
     * tree exceeding this is an unrecognized layout, so degrade to null rather than walk an unbounded
     * directory inside the finalizer (never-fail/never-hang, architecture §2 rule 3).
     */
    const val MAX_FILES: Int = 50_000

    /** Byte size of the newest-modified, `entry.bin`-marked CC entry dir under [rootDir], or null on any degraded case. */
    fun newestEntryBytes(rootDir: File): Long? = newestEntryBytes(rootDir, MAX_FILES)

    /** [MAX_FILES] override seam for the boundary test — production always uses the const. */
    internal fun newestEntryBytes(rootDir: File, maxFiles: Int): Long? = runCatching {
        val ccRoot = File(rootDir, ".gradle/configuration-cache")
        val candidates = ccRoot.listFiles { file -> file.isDirectory } ?: return null
        // Only a dir carrying entry.bin is a genuine stored/loaded entry — see class doc (F14 narrowing
        // #2). Rank by mtime *within* that trusted set only, never across it.
        val entries = candidates.filter { File(it, "entry.bin").isFile }
        val newest = entries.maxByOrNull { it.lastModified() } ?: return null
        sumBounded(newest, maxFiles)
    }.getOrNull()

    /**
     * Sum of file sizes under [dir], bounded to [maxFiles] filesystem nodes total — directories and
     * files alike, not just files (null once exceeded). Symlinks are never followed
     * ([LinkOption.NOFOLLOW_LINKS]): a link back into an ancestor would otherwise cycle without bound,
     * and a link out of the tree (e.g. to `/etc`) would fold an arbitrary external path's size into the
     * count — neither is acceptable under the never-hang guarantee or the byte-count-only contract
     * (spec §3.7). An explicit stack rather than [File.walkTopDown]/`Files.walk` so the node-count guard
     * short-circuits before an unbounded sequence is materialized.
     */
    private fun sumBounded(dir: File, maxFiles: Int): Long? {
        var total = 0L
        var nodes = 0
        val stack = ArrayDeque<Path>()
        stack.addLast(dir.toPath())
        while (stack.isNotEmpty()) {
            val children = runCatching { Files.newDirectoryStream(stack.removeLast()).use { it.toList() } }
                .getOrNull() ?: continue
            for (child in children) {
                val attrs = runCatching {
                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }.getOrNull() ?: continue
                if (attrs.isSymbolicLink) continue
                if (++nodes > maxFiles) return null
                when {
                    attrs.isDirectory -> stack.addLast(child)
                    attrs.isRegularFile -> total += attrs.size()
                }
            }
        }
        return total
    }
}
