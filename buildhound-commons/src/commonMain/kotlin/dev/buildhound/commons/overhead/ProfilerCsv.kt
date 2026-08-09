package dev.buildhound.commons.overhead

import kotlin.math.sqrt

/**
 * Parser for gradle-profiler's `benchmark.csv` (plan 034). The file lays scenarios out as columns and
 * keys rows by their first cell (`scenario`, `value`, `measured build #1`, …). This reads it by row
 * *name*, not position, and tolerates unknown/extra columns and rows — the format-drift discipline
 * that keeps a profiler version bump from silently breaking the verdict. Pure: it takes the file
 * contents as a `String`.
 *
 * What the file actually contains (verified against gradle-profiler 0.24.0 output, plan 106 — the
 * three assumptions below were all wrong in the original implementation and each one alone was
 * enough to make every axis unverifiable):
 *
 *  1. **No summary rows.** There is no `mean`, `median`, `min`, `max` or `stddev` row. Only
 *     `warm-up build #N` and `measured build #N` rows. Mean and spread are computed here, from the
 *     measured builds only — warm-ups are excluded, which is the whole point of running them.
 *  2. **One column per scenario *per metric*.** `--measure-config-time` emits both
 *     `total execution time` and `task start` under the same scenario name, so a column must be
 *     chosen by the `value` row, not by position. (`task start` is a cumulative timestamp, not a
 *     duration — reading it as the mean compares tens-of-seconds numbers that mean nothing.)
 *  3. **Columns are named by scenario id only when a scenario sets no `title`.** With a title, the
 *     column carries the prose instead and no axis can find its data. `overhead.scenarios` therefore
 *     sets no titles; that coupling is documented at the top of that file.
 */
object ProfilerCsv {

    /** The measured metric the overhead budget is defined on (gradle-profiler's `value` row). */
    const val TOTAL_EXECUTION_TIME: String = "total execution time"

    private const val MEASURED_PREFIX = "measured build"

    /**
     * Parse [csv] into `scenarioName → `[ScenarioStats]. Throws [IllegalArgumentException] with a
     * clear message naming what was looked for whenever the file cannot yield measurements — a
     * garbled or reshaped CSV must fail loudly, never report a false pass.
     *
     * Stats come from the `measured build #N` rows. A file that carries pre-computed `mean`/`stddev`
     * rows instead (an older or reshaped format) is still accepted, so the verdict survives a
     * profiler format change in either direction.
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
        // The per-build measurements — the only numbers gradle-profiler 0.24.0 actually writes.
        // Warm-ups are deliberately excluded: they carry the cold-daemon and first-build costs.
        val measuredRows = rows.filter { it.isNotEmpty() && it[0].startsWith(MEASURED_PREFIX, ignoreCase = true) }
        // Pre-computed summary rows, for a format that supplies them instead of per-build rows.
        val meanRow = row("mean")
        val stddevRow = row("stddev") ?: row("std dev") ?: row("standard deviation")
        // Names each column's metric when present; absent in older/reshaped files → read positionally.
        val valueRow = row("value")

        val names = scenarioRow.drop(1)
        val parsed = buildMap {
            names.forEachIndexed { i, rawName ->
                val name = rawName.trim()
                if (name.isEmpty()) return@forEachIndexed
                // Skip this scenario's other metric columns; a blank/absent cell stays eligible.
                val metric = valueRow?.getOrNull(i + 1)?.trim()
                if (!metric.isNullOrEmpty() && !metric.equals(TOTAL_EXECUTION_TIME, ignoreCase = true)) {
                    return@forEachIndexed
                }
                val column = i + 1
                val samples = measuredRows.mapNotNull { it.getOrNull(column)?.toDoubleOrNull() }
                val stats = when {
                    samples.isNotEmpty() -> ScenarioStats(
                        name = name,
                        meanMs = samples.average(),
                        stddevMs = sampleStddev(samples),
                    )
                    meanRow != null -> ScenarioStats(
                        name = name,
                        meanMs = meanRow.getOrNull(column)?.toDoubleOrNull()
                            ?: throw IllegalArgumentException("benchmark.csv scenario '$name' has no numeric mean"),
                        stddevMs = stddevRow?.getOrNull(column)?.toDoubleOrNull() ?: 0.0,
                    )
                    else -> throw IllegalArgumentException(
                        "benchmark.csv scenario '$name' has no '$MEASURED_PREFIX #N' rows and no 'mean' row " +
                            "— nothing to measure (did gradle-profiler change its row labels?)",
                    )
                }
                // Last eligible column wins on a duplicate name (defensive; the filter leaves one).
                put(name, stats)
            }
        }
        // Every column filtered away means the metric was renamed — say so, naming what was sought,
        // so a profiler upgrade is a one-look diagnosis instead of an all-axes ⚠ MISSING mystery.
        require(parsed.isNotEmpty() || names.none { it.isNotBlank() }) {
            "benchmark.csv has no '$TOTAL_EXECUTION_TIME' column for any scenario " +
                "(value row: ${valueRow?.drop(1)?.joinToString() ?: "absent"}) — did gradle-profiler rename the metric?"
        }
        return parsed
    }

    /** Sample (n−1) standard deviation — the spread of a set of measurements; 0.0 below two samples. */
    private fun sampleStddev(samples: List<Double>): Double {
        if (samples.size < 2) return 0.0
        val mean = samples.average()
        return sqrt(samples.sumOf { (it - mean) * (it - mean) } / (samples.size - 1))
    }
}
