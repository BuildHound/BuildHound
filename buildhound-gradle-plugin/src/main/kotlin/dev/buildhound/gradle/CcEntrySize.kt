package dev.buildhound.gradle

import java.io.File

/**
 * Best-effort byte size of the active configuration-cache entry (plan 064, research F14). Sums the file
 * sizes under the **newest-modified** `<rootDir>/.gradle/configuration-cache/<hash>` subdir — the entry
 * this build stored (`MISS_STORED`) or loaded (`HIT`) — rather than the whole directory, so Gradle's
 * 7-day retention of stale sibling entries never inflates the count (F14 narrowing). Read at
 * **finalizer/execution** time only (the caller has `rootDir` there); a configuration-phase read would
 * make the directory a CC fingerprint input (architecture §2 rule 9). Every step is guarded — a
 * missing/unrecognized layout, an unreadable tree, or a file count past [MAX_FILES] degrades to null
 * (honest-null, plan 005), never a thrown finalizer. A byte count only; no path ever leaves this object
 * (spec §3.7).
 */
internal object CcEntrySize {

    /**
     * Defensive ceiling on files walked for one probe. A configuration-cache entry is a handful of
     * `.bin` files; a tree exceeding this is an unrecognized layout, so degrade to null rather than
     * walk an unbounded directory inside the finalizer (never-fail/never-hang, architecture §2 rule 3).
     */
    const val MAX_FILES: Int = 50_000

    /** Byte size of the newest-modified CC entry dir under [rootDir], or null on any degraded case. */
    fun newestEntryBytes(rootDir: File): Long? = newestEntryBytes(rootDir, MAX_FILES)

    /** [MAX_FILES] override seam for the boundary test — production always uses the const. */
    internal fun newestEntryBytes(rootDir: File, maxFiles: Int): Long? = runCatching {
        val ccRoot = File(rootDir, ".gradle/configuration-cache")
        val entries = ccRoot.listFiles { file -> file.isDirectory } ?: return null
        val newest = entries.maxByOrNull { it.lastModified() } ?: return null
        sumBounded(newest, maxFiles)
    }.getOrNull()

    /**
     * Sum of `length()` over every regular file under [dir], bounded to [maxFiles] (null once exceeded).
     * An explicit stack rather than [File.walkTopDown] so the file-count guard short-circuits before an
     * unbounded sequence is materialized.
     */
    private fun sumBounded(dir: File, maxFiles: Int): Long? {
        var total = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    if (++count > maxFiles) return null
                    total += child.length()
                }
            }
        }
        return total
    }
}
