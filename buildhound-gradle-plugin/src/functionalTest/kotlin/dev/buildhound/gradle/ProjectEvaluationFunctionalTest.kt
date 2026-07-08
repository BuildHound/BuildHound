package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * Per-project configuration-time attribution (plan 052): the `beforeProject`/`afterProject`-timed
 * `.gradle/buildhound/config-timings/` sidecar the Flow finalizer reads (then clears) at build end.
 * These TestKit tests are the arbiter of the CC story (plan's own framing) — this is the first
 * collector that writes a file *directly* from an `IsolatedAction`, at configuration time.
 */
class ProjectEvaluationFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*arguments)

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun summaryLine(output: String): String =
        output.lineSequence().single { it.startsWith("[buildhound] build ") }

    /** `:a` sleeps at configuration time so it reliably evaluates slower than `:b` (and the root). */
    private fun setUpMultiProject(settingsDsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "project-eval-fixture"
            include(":a", ":b")
            $settingsDsl
            """.trimIndent(),
        )
        File(projectDir, "a").mkdirs()
        File(projectDir, "a/build.gradle.kts").writeText(
            """
            Thread.sleep(150)
            tasks.register("work") { doLast { println("a work") } }
            """.trimIndent(),
        )
        File(projectDir, "b").mkdirs()
        File(projectDir, "b/build.gradle.kts").writeText(
            """tasks.register("work") { doLast { println("b work") } }""",
        )
    }

    @Test
    fun `a multi-project build emits per-project evaluations ranked slowest-first`() {
        setUpMultiProject()

        runner(":a:work", ":b:work").build()

        val evaluations = readPayload().projectEvaluations ?: error("expected a projectEvaluations block")
        val aIndex = evaluations.indexOfFirst { it.path == ":a" }
        val bIndex = evaluations.indexOfFirst { it.path == ":b" }
        assertTrue(aIndex >= 0 && bIndex >= 0, "both subprojects must be captured: $evaluations")
        assertTrue(aIndex < bIndex, "the slower project (:a, which sleeps) must rank before :b: $evaluations")
    }

    @Test
    fun `the cc entry is reused across store then hit, present on the miss and null on the hit`() {
        setUpMultiProject()

        val store = runner(":a:work", ":b:work", "--configuration-cache").build()
        assertTrue(summaryLine(store.output).contains("cc=MISS_STORED"), summaryLine(store.output))
        val stored = readPayload().projectEvaluations
        assertTrue(stored != null && stored.any { it.path == ":a" } && stored.any { it.path == ":b" }, "expected both on the store build: $stored")

        // The CC entry must be REUSED (not invalidated by the config-time mkdirs+writeText) — a genuine
        // cc=HIT on the second, identical invocation is the load-bearing proof of the plan's named CC
        // input hazard being avoided.
        val hit = runner(":a:work", ":b:work", "--configuration-cache").build()
        assertTrue(hit.output.contains("Reusing configuration cache"), hit.output)
        assertTrue(summaryLine(hit.output).contains("cc=HIT"), summaryLine(hit.output))
        assertNull(readPayload().projectEvaluations, "must be null on a configuration-cache hit — configuration did not run")
    }

    @Tag("isolated-projects")
    @Test
    fun `the block is populated under isolated projects, unlike the empty whenReady task dictionary`() {
        setUpMultiProject()
        val ipFlag = "-Dorg.gradle.unsafe.isolated-projects=true"

        val result = runner(":a:work", ":b:work", "--configuration-cache", ipFlag).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":a:work")?.outcome, "IP build must succeed")
        assertFalse(
            result.output.lineSequence().any { it.startsWith("[buildhound]") && it.contains("failed") },
            "no BuildHound warn/failure under isolated projects:\n${result.output}",
        )
        val evaluations = readPayload().projectEvaluations ?: error("expected a populated projectEvaluations block under IP")
        assertTrue(evaluations.any { it.path == ":a" } && evaluations.any { it.path == ":b" }, "expected both subprojects: $evaluations")
    }

    @Test
    fun `a narrower invocation after a broad one never inherits the wider build's stale project entries`() {
        setUpMultiProject()
        // configureOnDemand so the narrower second invocation configures ONLY :a's subtree — without it
        // Gradle fully configures every project on every build regardless of the requested task set, and
        // this test would prove nothing about the finalizer's read-then-clear.
        File(projectDir, "gradle.properties").writeText("org.gradle.configureondemand=true\n")

        val broad = runner(":a:work", ":b:work").build()
        assertEquals(TaskOutcome.SUCCESS, broad.task(":a:work")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, broad.task(":b:work")?.outcome)
        val broadEvaluations = readPayload().projectEvaluations ?: error("expected both projects on the broad build")
        assertTrue(broadEvaluations.any { it.path == ":b" }, "expected :b on the broad build: $broadEvaluations")

        val narrow = runner(":a:work").build()
        assertEquals(TaskOutcome.SUCCESS, narrow.task(":a:work")?.outcome)
        val narrowEvaluations = readPayload().projectEvaluations ?: error("expected :a on the narrow build")
        assertTrue(narrowEvaluations.any { it.path == ":a" }, "expected :a itself: $narrowEvaluations")
        assertFalse(
            narrowEvaluations.any { it.path == ":b" },
            "the narrow build must not inherit :b's leftover file from the broad build: $narrowEvaluations",
        )
    }

    @Test
    fun `a dsl-disabled broad build leaves no stale entries for a later enabled narrow build`() {
        // configureOnDemand so the enabled second invocation configures ONLY :a's subtree (see the
        // narrower-invocation test above for why the :b assertion is vacuous without it).
        File(projectDir, "gradle.properties").writeText("org.gradle.configureondemand=true\n")

        // Broad build, disabled via the settings-script DSL: the apply-time master switch is still on,
        // so the beforeProject/afterProject collector writes :a and :b timing files regardless — a
        // `buildhound { enabled = false }` DSL value cannot reach the already-registered
        // IsolatedActions (plan 052's named limitation). Before the 052 review fix the finalizer's
        // disabled early-return also skipped read-then-clear, so the enabled narrow build below picked
        // up :b's stale file and misattributed the disabled build's timing to itself.
        setUpMultiProject(settingsDsl = "buildhound { enabled = false }")
        val broad = runner(":a:work", ":b:work").build()
        assertEquals(TaskOutcome.SUCCESS, broad.task(":b:work")?.outcome)
        assertFalse(broad.output.contains("[buildhound] build "), "a DSL-disabled build must not finalize a payload")
        assertEquals(
            emptyList(),
            File(projectDir, ".gradle/buildhound/config-timings").listFiles()?.toList().orEmpty(),
            "the disabled build's finalizer must still clear the sidecar its collector could not be stopped from writing",
        )

        // Enabled narrow build touching only :a — :b must never appear in its payload.
        setUpMultiProject()
        val narrow = runner(":a:work").build()
        assertEquals(TaskOutcome.SUCCESS, narrow.task(":a:work")?.outcome)
        val evaluations = readPayload().projectEvaluations ?: error("expected :a on the enabled narrow build")
        assertTrue(evaluations.any { it.path == ":a" }, "expected :a itself: $evaluations")
        assertFalse(
            evaluations.any { it.path == ":b" },
            "the enabled narrow build must not inherit :b from the DSL-disabled broad build: $evaluations",
        )
    }

    @Test
    fun `the master switch off leaves the config-timings sidecar untouched`() {
        setUpMultiProject()

        val result = runner(":a:work", ":b:work", "-Pbuildhound.enabled=false").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":a:work")?.outcome)
        assertFalse(result.output.contains("[buildhound] build "), result.output)
        assertFalse(
            File(projectDir, ".gradle/buildhound/config-timings").exists(),
            "the master switch off must leave no config-timings sidecar",
        )
    }
}
