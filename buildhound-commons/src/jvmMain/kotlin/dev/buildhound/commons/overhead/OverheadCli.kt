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
    // A missing stddev row degrades to zero spread → the noise-separation guard is disabled (a % wobble
    // could then mint a false breach). Surface it loudly so a reviewer knows the guard was toothless.
    if (!hasStddevRow(text)) {
        System.err.println("warning: $label benchmark.csv has no stddev row — the noise-separation guard is disabled for its axes")
    }
    return runCatching { ProfilerCsv.parse(text) }.getOrElse {
        System.err.println("failed to parse $label benchmark.csv '$path': ${it.message}")
        exitProcess(2)
    }
}

private fun hasStddevRow(csv: String): Boolean =
    csv.lineSequence().any { line ->
        line.substringBefore(',').trim().lowercase() in setOf("stddev", "std dev", "standard deviation")
    }
