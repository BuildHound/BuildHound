package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * Test-collection under isolated projects (plan 024, case f). The `whenReady` walk that captures
 * Test-task locations shares plan 016's `BuildFeatures` IP gate, so under IP it must degrade to an
 * empty location map — no `taskGraph.allTasks` access from settings scope (an IP violation), no
 * BuildHound warn, payload intact. Watched (`@Tag("isolated-projects")`), like the sibling IP suite.
 * Network-free: the `java` plugin gives a real `Test` task without external dependencies.
 */
@Tag("isolated-projects")
class TestCollectionIsolatedProjectsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private val ipFlag = "-Dorg.gradle.unsafe.isolated-projects=true"

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    @Test
    fun `test-location capture degrades to empty under isolated projects without a warn`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "ip-tests-fixture"
            include(":a", ":b")
            """.trimIndent(),
        )
        // :a carries a real Test task (via the java plugin) but no tests, so it stays NO_SOURCE —
        // enough to prove the location walk is gated, with no external dependency to resolve.
        File(projectDir, "a").mkdirs()
        File(projectDir, "a/build.gradle.kts").writeText("plugins { java }")
        File(projectDir, "b").mkdirs()
        File(projectDir, "b/build.gradle.kts").writeText("""tasks.register("work") { doLast { println("b") } }""")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(":a:test", ":b:work", ipFlag)
            .build()

        assertTrue(readPayload().tests.isEmpty(), "IP gate leaves the location map empty → tests empty")
        assertTrue(
            result.output.lineSequence().none { it.contains("[buildhound]") && it.contains("failed") },
            "no BuildHound failure warn under IP: ${result.output}",
        )
    }
}
