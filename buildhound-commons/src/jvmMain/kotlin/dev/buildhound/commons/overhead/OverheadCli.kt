package dev.buildhound.commons.overhead

import java.io.File
import kotlin.system.exitProcess

/**
 * Thin JVM launcher (plan 034) over [OverheadCalculator]: reads a plugin-on and a plugin-off
 * gradle-profiler `benchmark.csv`, evaluates them against [OverheadBudget.DEFAULT], prints a Markdown
 * table (the CI artifact), and exits non-zero on a breach so the `overhead-budget` job fails. All the
 * math lives in commons; this is glue. Invoked from `buildhound-ci-assets/overhead/bin/buildhound-overhead`.
 *
 * Exit codes: 0 = within budget, 1 = budget breached, 2 = bad input (unreadable/garbled CSV).
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: buildhound-overhead <plugin-on benchmark.csv> <plugin-off benchmark.csv>")
        exitProcess(2)
    }
    val on = parseOrExit(args[0], "plugin-on")
    val off = parseOrExit(args[1], "plugin-off")

    val report = OverheadCalculator.evaluate(on, off, OverheadBudget.DEFAULT)
    println("## Plugin overhead vs budget (plan 034)")
    println()
    println(OverheadCalculator.markdownTable(report))
    if (report.anyBreached) {
        // Flush the table before the stderr verdict, or an interleaved CI log prints "BREACHED"
        // above the table it refers to.
        System.out.flush()
        System.err.println()
        System.err.println("OVERHEAD BUDGET BREACHED — see the table above.")
        exitProcess(1)
    }
    println()
    println("All axes within budget.")
}

private fun parseOrExit(path: String, label: String): Map<String, ScenarioStats> {
    val text = runCatching { File(path).readText() }.getOrElse {
        System.err.println("failed to read $label benchmark.csv '$path': ${it.message}")
        exitProcess(2)
    }
    val stats = runCatching { ProfilerCsv.parse(text) }.getOrElse {
        System.err.println("failed to parse $label benchmark.csv '$path': ${it.message}")
        exitProcess(2)
    }
    // Zero spread everywhere means single-sample scenarios (or a format we could not read a spread
    // from) → the noise-separation guard is disabled and a % wobble could mint a false breach.
    // Checked on the parsed stats, not by scanning for a 'stddev' row: gradle-profiler writes no
    // summary rows at all, so the old text scan warned on every real run (plan 106).
    if (stats.isNotEmpty() && stats.values.all { it.stddevMs == 0.0 }) {
        System.err.println("warning: $label benchmark.csv yielded no spread — the noise-separation guard is disabled for its axes")
    }
    return stats
}
