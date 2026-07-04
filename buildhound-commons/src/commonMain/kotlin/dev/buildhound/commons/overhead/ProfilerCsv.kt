package dev.buildhound.commons.overhead

/**
 * Parser for gradle-profiler's `benchmark.csv` (plan 034). The file lays scenarios out as columns and
 * keys metadata/stat rows by their first cell (`scenario`, `mean`, `stddev`, …). This reads it by row
 * *name*, not position, and tolerates unknown/extra columns and rows — the format-drift discipline
 * that keeps a profiler version bump from silently breaking the verdict. Pure: it takes the file
 * contents as a `String`.
 */
object ProfilerCsv {

    /**
     * Parse [csv] into `scenarioName → `[ScenarioStats]. Throws [IllegalArgumentException] with a
     * clear message when the required `scenario` or `mean` row is absent (a garbled CSV must fail
     * loudly, never report a false pass). A missing `stddev` row degrades to `0.0` spread.
     */
    fun parse(csv: String): Map<String, ScenarioStats> {
        val rows = csv.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .map { line -> line.split(',').map { it.trim() } }
            .toList()
        require(rows.isNotEmpty()) { "empty benchmark.csv" }

        fun row(label: String): List<String>? =
            rows.firstOrNull { it.isNotEmpty() && it[0].equals(label, ignoreCase = true) }

        val scenarioRow = row("scenario")
            ?: throw IllegalArgumentException("benchmark.csv has no 'scenario' row (columns can't be named)")
        val meanRow = row("mean")
            ?: throw IllegalArgumentException("benchmark.csv has no 'mean' row (no measurements to read)")
        // gradle-profiler labels the spread "stddev"; accept a couple of aliases, default to 0 spread.
        val stddevRow = row("stddev") ?: row("std dev") ?: row("standard deviation")

        val names = scenarioRow.drop(1)
        return buildMap {
            names.forEachIndexed { i, rawName ->
                val name = rawName.trim()
                if (name.isEmpty()) return@forEachIndexed
                val mean = meanRow.getOrNull(i + 1)?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("benchmark.csv scenario '$name' has no numeric mean")
                val stddev = stddevRow?.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
                // Last column wins on a duplicate name (gradle-profiler never emits dupes; defensive).
                put(name, ScenarioStats(name = name, meanMs = mean, stddevMs = stddev))
            }
        }
    }
}
