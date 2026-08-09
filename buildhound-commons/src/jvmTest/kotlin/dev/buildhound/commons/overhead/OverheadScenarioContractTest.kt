package dev.buildhound.commons.overhead

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract between `buildhound-ci-assets/overhead/overhead.scenarios` and [OverheadBudget]
 * (plan 106). gradle-profiler names each `benchmark.csv` column after a scenario's `title` when one
 * is set, and after the scenario **id** otherwise — and the budget's axes look scenarios up by id.
 *
 * Adding a `title` to any scenario therefore renames its column to prose, every axis reports
 * `⚠ MISSING`, and the `overhead-budget` job verifies nothing while looking like it ran. That is the
 * defect class that left the job inert for its whole lifetime, and the frozen `benchmark-*.csv`
 * fixtures cannot catch a reintroduction: they are captures of a title-less run, so they stay green
 * no matter what the live scenario file says. Only reading the real file catches it.
 */
class OverheadScenarioContractTest {

    /** Scenario declarations look like `no_op {`; comments are `//`-prefixed. */
    private val scenarioDeclaration = Regex("""^(\w+)\s*\{""")

    /** A real `title = "…"` assignment. The descriptive text is kept as `// "…"` comments instead. */
    private val titleAssignment = Regex("""^\s*title\s*=""")

    private fun scenariosFile(): File {
        // The Test task's working directory is this module's project dir, so the CI assets sit one
        // level up. Fails loudly with the resolved path rather than skipping — a silently skipped
        // contract test is the same disease as a silently passing budget.
        val file = File("../buildhound-ci-assets/overhead/overhead.scenarios")
        assertTrue(file.isFile, "expected the scenario file at ${file.absolutePath}")
        return file
    }

    @Test
    fun `no scenario sets a title, so csv columns carry the ids the budget looks up`() {
        val offenders = scenariosFile().readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .filter { titleAssignment.containsMatchIn(it) }
        assertEquals(
            emptyList(), offenders,
            "a scenario sets `title`, which renames its benchmark.csv column to that prose and makes " +
                "every OverheadBudget axis report ⚠ MISSING. Keep the description as a `//` comment.",
        )
    }

    @Test
    fun `every scenario the budget references exists in the scenario file`() {
        val declared = scenariosFile().readLines()
            .mapNotNull { scenarioDeclaration.find(it.trimEnd())?.groupValues?.get(1) }
            .toSet()
        val referenced = OverheadBudget.DEFAULT.axes
            .flatMap { listOf(it.treatment.scenario, it.baseline.scenario) }
            .toSet()
        assertTrue(
            declared.containsAll(referenced),
            "OverheadBudget references scenarios the harness never runs: ${referenced - declared} " +
                "(declared: $declared). Those axes would report ⚠ MISSING on every run.",
        )
    }
}
