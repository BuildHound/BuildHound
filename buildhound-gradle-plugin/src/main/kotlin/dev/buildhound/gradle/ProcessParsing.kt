package dev.buildhound.gradle

import dev.buildhound.commons.payload.GcCollector
import dev.buildhound.commons.payload.ProcessRole

/**
 * Pure parsing for the process probe (plan 029, spec §3.6) — no Gradle, no exec, so the JDK-tool
 * output shapes and the measurement math (research §4.1, non-negotiable) are unit-testable directly.
 *
 * Pinned math traps:
 * - heap **used** = jstat `EU+OU+S0U+S1U` — survivors **included** (build-process-watcher drops them);
 * - **committed** = `EC+OC+S0C+S1C`;
 * - **max** (capacity) = jstat `-gccapacity` `NGCMX+OGCMX` — *not* `-Xmx`;
 * - **GC time** = jstat `GCT` **total** (never `YGCT+FGCT`, which omits `CGCT` and undercounts G1/ZGC);
 * - jstat columns are keyed **by header name**, not position (layouts differ across JDKs).
 * jstat `-gc`/`-gccapacity` report KB; `GCT` is seconds; `ps -o rss=` is KB; jinfo MaxHeapSize is bytes.
 */
internal object ProcessParsing {

    private const val KB_PER_MB = 1024.0

    /** jps `-l` lines are `"<pid> <fully.qualified.MainClass>"`; keep only the three JVMs we probe. */
    fun parseJpsLines(output: String): List<Pair<Long, ProcessRole>> =
        output.lineSequence().mapNotNull { parseJpsLine(it) }.toList()

    fun parseJpsLine(line: String): Pair<Long, ProcessRole>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val space = trimmed.indexOf(' ')
        val pid = (if (space < 0) trimmed else trimmed.substring(0, space)).toLongOrNull() ?: return null
        val mainClass = if (space < 0) "" else trimmed.substring(space + 1).trim()
        val role = roleFor(mainClass) ?: return null
        return pid to role
    }

    private fun roleFor(mainClass: String): ProcessRole? {
        // jps prints the FQ main class (or a jar path); match the simple name.
        val simple = mainClass.substringBefore(' ').substringAfterLast('.').substringAfterLast('/')
        return when (simple) {
            "GradleDaemon" -> ProcessRole.GRADLE_DAEMON
            "KotlinCompileDaemon" -> ProcessRole.KOTLIN_DAEMON
            "GradleWorkerMain" -> ProcessRole.GRADLE_WORKER
            else -> null
        }
    }

    /** Full `jstat -gc`/`-gccapacity` stdout is a header line then a values line; empty when malformed. */
    fun parseJstat(output: String): Map<String, Double> {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
        if (lines.size < 2) return emptyMap()
        return parseJstatByHeader(lines[0], lines[1])
    }

    /**
     * Zip a jstat header row to its values row by column name. Absent/short columns yield no entry
     * (callers read null); a non-numeric cell is skipped. Keyed by name so a reordered/renamed JDK
     * layout can't silently read the wrong column.
     */
    fun parseJstatByHeader(header: String, values: String): Map<String, Double> {
        val names = header.trim().split(WHITESPACE)
        val cells = values.trim().split(WHITESPACE)
        val result = LinkedHashMap<String, Double>()
        for (i in names.indices) {
            val cell = cells.getOrNull(i)?.toDoubleOrNull() ?: continue
            result[names[i]] = cell
        }
        return result
    }

    /** Heap **used** MB from `jstat -gc` (survivors included); null if any component column is absent. */
    fun heapUsedMb(gc: Map<String, Double>): Long? = sumMb(gc, "EU", "OU", "S0U", "S1U")

    /** Heap **committed** MB from `jstat -gc`; null if any component column is absent. */
    fun heapCommittedMb(gc: Map<String, Double>): Long? = sumMb(gc, "EC", "OC", "S0C", "S1C")

    /** Total GC time (ms) from `jstat -gc` `GCT` seconds — the total column, not `YGCT+FGCT`. */
    fun gcTimeMs(gc: Map<String, Double>): Long? = gc["GCT"]?.let { Math.round(it * 1000.0) }

    /** Heap **max** (capacity) MB from `jstat -gccapacity` `NGCMX+OGCMX`; not the configured Xmx. */
    fun heapMaxMb(capacity: Map<String, Double>): Long? = sumMb(capacity, "NGCMX", "OGCMX")

    /** Configured `-Xmx` MB from a `jinfo -flags` line's `-XX:MaxHeapSize=<bytes>`; null when absent. */
    fun configuredXmxMb(jinfoFlags: String): Long? {
        val match = MAX_HEAP.find(jinfoFlags) ?: return null
        val bytes = match.groupValues[1].toLongOrNull() ?: return null
        return Math.round(bytes / KB_PER_MB / KB_PER_MB)
    }

    /**
     * The selected GC from a `jinfo -flags` line (plan 065, research F15) — a **typed allowlist**,
     * exactly like [configuredXmxMb] above and plan 051's `INVOCATION_PROPERTY_ALLOWLIST`: only the
     * fixed `-XX:+Use…GC` selection flags in [GC_COLLECTOR_FLAGS] are read; every other token the
     * jinfo line carries (absolute paths, `-D…` args, the classpath jinfo also prints) is discarded
     * by construction — the extraction never returns a string, so there is nothing to scrub.
     *
     * Checked in fixed allowlist order (a deterministic pick if a pathological line ever carried
     * two selection flags). An *enabled* `-XX:+Use…GC` flag outside the allowlist maps to
     * [GcCollector.UNKNOWN] — honest, never a guessed name (known non-collector `…SystemGC` tuning
     * flags are excluded from that fallback); no collector-selection flag at all is null.
     *
     * **Widening this allowlist to a value-carrying or free-form flag is not a one-line change**
     * (the plan-051 rule): a flag whose value is arbitrary text would need scrubber coverage, which
     * these discrete enum/bool reads deliberately avoid — that is *why* they are safe (spec §3.7).
     */
    fun parseGcCollector(jinfoFlags: String): GcCollector? {
        for ((pattern, collector) in GC_COLLECTOR_FLAGS) {
            if (pattern.containsMatchIn(jinfoFlags)) return collector
        }
        val unknownEnabled = ENABLED_GC_FLAG.findAll(jinfoFlags)
            .any { match -> !match.groupValues[1].endsWith("SystemGC") }
        return if (unknownEnabled) GcCollector.UNKNOWN else null
    }

    /**
     * JEP 519 Compact Object Headers from a `jinfo -flags` line (plan 065): `-XX:+…` → true,
     * `-XX:-…` → false, not printed (pre-JDK-24, or default-off unset) → null. Same typed-allowlist
     * discipline as [parseGcCollector] — a boolean read, never a string.
     */
    fun parseCompactObjectHeaders(jinfoFlags: String): Boolean? =
        COMPACT_OBJECT_HEADERS.find(jinfoFlags)?.let { it.groupValues[1] == "+" }

    /** RSS MB from `ps -o rss=` (KB); null when the output is not a number. */
    fun rssMb(psRss: String): Long? {
        val kb = psRss.trim().toLongOrNull() ?: return null
        return Math.round(kb / KB_PER_MB)
    }

    /** Elapsed seconds from `ps -o etime=` (`[[dd-]hh:]mm:ss`, portable across Linux/macOS). */
    fun uptimeSeconds(etime: String): Long? {
        val text = etime.trim()
        if (text.isEmpty()) return null
        val (days, hms) = if (text.contains('-')) {
            val parts = text.split('-', limit = 2)
            (parts[0].toLongOrNull() ?: return null) to parts[1]
        } else {
            0L to text
        }
        val units = hms.split(':').map { it.toLongOrNull() ?: return null }
        val (h, m, s) = when (units.size) {
            3 -> Triple(units[0], units[1], units[2])
            2 -> Triple(0L, units[0], units[1])
            else -> return null
        }
        return ((days * 24 + h) * 60 + m) * 60 + s
    }

    private fun sumMb(stats: Map<String, Double>, vararg columns: String): Long? {
        var totalKb = 0.0
        for (column in columns) totalKb += stats[column] ?: return null
        return Math.round(totalKb / KB_PER_MB)
    }

    private val WHITESPACE = Regex("\\s+")
    private val MAX_HEAP = Regex("-XX:MaxHeapSize=(\\d+)")

    /**
     * The fixed collector-selection allowlist (plan 065). A constant, not a DSL knob — widening it
     * is a follow-up plan's call, not a build's (see [parseGcCollector]'s widening warning).
     */
    private val GC_COLLECTOR_FLAGS: List<Pair<Regex, GcCollector>> = listOf(
        "UseG1GC" to GcCollector.G1,
        "UseParallelGC" to GcCollector.PARALLEL,
        "UseZGC" to GcCollector.ZGC,
        "UseSerialGC" to GcCollector.SERIAL,
        "UseShenandoahGC" to GcCollector.SHENANDOAH,
        "UseEpsilonGC" to GcCollector.EPSILON,
    ).map { (flag, collector) -> Regex("-XX:\\+$flag\\b") to collector }

    /** An enabled `Use…GC`-shaped flag — the honest-`UNKNOWN` fallback input for a future collector. */
    private val ENABLED_GC_FLAG = Regex("-XX:\\+(Use\\w+GC)\\b")

    private val COMPACT_OBJECT_HEADERS = Regex("-XX:([+-])UseCompactObjectHeaders\\b")
}
