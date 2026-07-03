package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * Watched isolated-projects suite (plan 021). A multi-project build under
 * `-Dorg.gradle.unsafe.isolated-projects=true` — a single-project fixture cannot surface IP,
 * which is about cross-project isolation. Run only by the non-blocking `isolatedProjectsTest`
 * task/CI job (`@Tag("isolated-projects")`, excluded from the default `functionalTest`).
 *
 * These assert BuildHound's own signals (payload intact, both subprojects captured, cc=HIT on
 * reuse, no BuildHound warn) rather than Gradle console text. Per-collector IP *degradation*
 * guarantees are enforced by **blocking** tests in the default suite (first: plan 016's
 * task-metadata degradation case) per the §2 degradation contract, not here.
 */
@Tag("isolated-projects")
class IsolatedProjectsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private val ipFlag = "-Dorg.gradle.unsafe.isolated-projects=true"

    private fun setUpMultiProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("dev.buildhound")
            }

            rootProject.name = "ip-fixture"
            include(":a", ":b")
            """.trimIndent(),
        )
        for (name in listOf("a", "b")) {
            File(projectDir, name).mkdirs()
            File(projectDir, "$name/build.gradle.kts").writeText(
                """
                tasks.register("work") { doLast { println("$name work") } }
                """.trimIndent(),
            )
        }
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun summaryLine(output: String): String =
        output.lineSequence().single { it.startsWith("[buildhound] build ") }

    @Test
    fun `payload survives isolated-projects store and reuse with both subprojects captured`() {
        setUpMultiProject()

        val store = runner(":a:work", ":b:work", "--configuration-cache", ipFlag).build()

        assertEquals(TaskOutcome.SUCCESS, store.task(":a:work")?.outcome, "IP build must succeed")
        assertEquals(TaskOutcome.SUCCESS, store.task(":b:work")?.outcome)
        assertFalse(
            store.output.lineSequence().any { it.startsWith("[buildhound]") && it.contains("failed") },
            "no BuildHound warn/failure under isolated projects:\n${store.output}",
        )
        val stored = readPayload()
        assertTrue(
            stored.tasks.any { it.path == ":a:work" },
            "subproject :a task must be captured: ${stored.tasks.map { it.path }}",
        )
        assertTrue(stored.tasks.any { it.path == ":b:work" }, "subproject :b task must be captured")

        // Second run replays the IP configuration-cache entry — telemetry must survive the hit.
        val hit = runner(":a:work", ":b:work", "--configuration-cache", ipFlag).build()

        assertTrue(summaryLine(hit.output).contains("cc=HIT"), summaryLine(hit.output))
        val replayed = readPayload()
        assertTrue(replayed.tasks.any { it.path == ":a:work" }, "telemetry must survive IP reuse")
        assertTrue(replayed.tasks.any { it.path == ":b:work" })
    }
}
