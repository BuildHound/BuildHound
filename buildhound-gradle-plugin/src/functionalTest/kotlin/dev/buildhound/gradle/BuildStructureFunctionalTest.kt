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

/**
 * Declared build-structure inventory (plan 069, research F19): the `projectsLoaded`-timed
 * descriptor-tree walk ([BuildStructureWalker]) plus the [BuildStructureValueSource] filesystem
 * probes it feeds. The fixture is intentionally a multi-module build with an empty aggregator
 * (`:libs`/`:libs:legacy`, declared via `include(":libs:legacy:foo")` alone — both ancestors are
 * real sub-projects with no build file of their own), a `buildSrc/`, a root `src/`, and one
 * composite `includeBuild`.
 */
class BuildStructureFunctionalTest {

    @field:org.junit.jupiter.api.io.TempDir
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

    private fun setUpFixture() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "build-structure-fixture"
            include(":app")
            // Declaring only the leaf implicitly creates the ":libs" and ":libs:legacy" ancestor
            // sub-projects too (Gradle's include() convention) — both are real declared projects
            // with no build file of their own, the "empty intermediate aggregator" shape.
            include(":libs:legacy:foo")
            includeBuild("included-lib")
            """.trimIndent(),
        )
        // Deliberately no root build.gradle.kts — an idiomatic settings-only repo. Root (":") must
        // never appear in emptyIntermediateCandidates regardless (asserted below and in the
        // dedicated no-root-build-file regression test), so this fixture no longer dodges the case
        // with a placeholder file (plan 069 review).

        File(projectDir, "app").mkdirs()
        File(projectDir, "app/build.gradle.kts").writeText(
            """tasks.register("work") { doLast { println("app work") } }""",
        )
        File(projectDir, "libs/legacy/foo").mkdirs()
        File(projectDir, "libs/legacy/foo/build.gradle.kts").writeText(
            """tasks.register("work") { doLast { println("foo work") } }""",
        )

        File(projectDir, "buildSrc").mkdirs()
        File(projectDir, "buildSrc/build.gradle.kts").writeText("")

        File(projectDir, "src").mkdirs()
        File(projectDir, "src/marker.txt").writeText("placeholder root source")

        File(projectDir, "included-lib").mkdirs()
        File(projectDir, "included-lib/settings.gradle.kts").writeText("""rootProject.name = "included-lib"""")
        File(projectDir, "included-lib/build.gradle.kts").writeText("")
    }

    @Test
    fun `a multi-module build reports counts, the empty-intermediate candidates, buildSrc, and root sources`() {
        setUpFixture()

        val result = runner(":app:work").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:work")?.outcome)
        val structure = readPayload().buildStructure ?: error("expected a buildStructure block")
        // root, :app, :libs, :libs:legacy, :libs:legacy:foo.
        assertEquals(5, structure.projectCount)
        assertEquals(3, structure.maxDepth, ":libs:legacy:foo has 3 path segments")
        assertEquals(1, structure.includedBuildCount)
        assertEquals(true, structure.buildSrcPresent)
        assertEquals(true, structure.sourcesInRoot)
        assertTrue(
            structure.emptyIntermediateCandidates.containsAll(listOf(":libs", ":libs:legacy")),
            "expected both empty aggregators as candidates: ${structure.emptyIntermediateCandidates}",
        )
        assertFalse(":app" in structure.emptyIntermediateCandidates, "a leaf with its own build file must not be a candidate")
        assertFalse(
            ":libs:legacy:foo" in structure.emptyIntermediateCandidates,
            "a leaf with its own build file must not be a candidate",
        )
        // Root (":") has children and, in this fixture, deliberately no build file of its own — the
        // exact shape that used to false-positive as an "empty intermediate" candidate (plan 069
        // review): root is definitionally not an intermediate project, regardless of whether it has
        // a build file.
        assertFalse(":" in structure.emptyIntermediateCandidates, "root must never be an empty-intermediate candidate")
        // Sorted for determinism.
        assertEquals(structure.emptyIntermediateCandidates.sorted(), structure.emptyIntermediateCandidates)
    }

    @Test
    fun `a flat single-project build reports zero depth, no included builds, and no candidates`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "flat-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("work") { doLast { println("root work") } }""",
        )

        val result = runner(":work").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":work")?.outcome)
        val structure = readPayload().buildStructure ?: error("expected a buildStructure block")
        assertEquals(1, structure.projectCount, "only the root project is declared")
        assertEquals(0, structure.maxDepth, "root itself is depth 0")
        assertEquals(0, structure.includedBuildCount)
        assertEquals(false, structure.buildSrcPresent)
        assertEquals(false, structure.sourcesInRoot)
        assertTrue(
            structure.emptyIntermediateCandidates.isEmpty(),
            "a single leafless project has no children, so no candidates: ${structure.emptyIntermediateCandidates}",
        )
    }

    @Test
    fun `the cc entry is reused and the inventory stays populated on both the store and the hit`() {
        setUpFixture()

        val store = runner(":app:work", "--configuration-cache").build()
        assertTrue(summaryLine(store.output).contains("cc=MISS_STORED"), summaryLine(store.output))
        val stored = readPayload().buildStructure ?: error("expected a buildStructure block on the store build")
        assertEquals(5, stored.projectCount)

        // A genuine cc=HIT is the load-bearing proof that the descriptors/counts baked into the
        // ValueSource parameters at store time survive reuse, and that the .exists() probes still
        // run at execution — unlike projectEvaluations (plan 052), this block must NOT go null here:
        // the descriptor walk is settings-level, not `taskGraph`-derived, so nothing about it
        // depends on configuration having actually re-run this build.
        val hit = runner(":app:work", "--configuration-cache").build()
        assertTrue(hit.output.contains("Reusing configuration cache"), hit.output)
        assertTrue(summaryLine(hit.output).contains("cc=HIT"), summaryLine(hit.output))
        val replayed = readPayload().buildStructure ?: error("expected buildStructure to survive a CC hit")
        assertEquals(stored.projectCount, replayed.projectCount)
        assertEquals(stored.maxDepth, replayed.maxDepth)
        assertEquals(stored.includedBuildCount, replayed.includedBuildCount)
        assertEquals(stored.buildSrcPresent, replayed.buildSrcPresent)
        assertEquals(stored.sourcesInRoot, replayed.sourcesInRoot)
        assertEquals(stored.emptyIntermediateCandidates, replayed.emptyIntermediateCandidates)
    }

    @Test
    fun `a forced descriptor-walk failure degrades to a null block without failing the build`() {
        setUpFixture()

        val result = runner(":app:work", "-Pbuildhound.internal.failBuildStructureSnapshot=true").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:work")?.outcome)
        assertEquals(
            1,
            result.output.lineSequence().count { it.contains("[buildhound] build-structure descriptor walk failed") },
            "expected exactly one capture-failure warn line:\n${result.output}",
        )
        assertNull(readPayload().buildStructure, "a forced walk failure must degrade to a null block")
    }

    @Test
    fun `the master switch off leaves the inventory uncaptured`() {
        setUpFixture()

        val result = runner(":app:work", "-Pbuildhound.enabled=false").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:work")?.outcome)
        assertFalse(result.output.contains("[buildhound] build "), result.output)
    }
}
