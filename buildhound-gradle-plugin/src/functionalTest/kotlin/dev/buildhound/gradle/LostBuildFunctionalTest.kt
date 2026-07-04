package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/** Lost-build accounting (plan 033): start-marker write/reconcile, CC-safe, never-fail. */
class LostBuildFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*arguments, "--configuration-cache")

    private fun setUpPlainProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "lost-build-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
    }

    private fun startedDir() = File(projectDir, "build/buildhound/started")
    private fun interruptedDir() = File(projectDir, "build/buildhound/interrupted")

    /** Seed a stale marker as if a prior build died before finalizing; startedAt is recent (within TTL). */
    private fun seedMarker(buildId: String, startedAtMs: Long = System.currentTimeMillis() - 60_000) {
        startedDir().mkdirs()
        File(startedDir(), "$buildId.json").writeText(
            """{"buildId":"$buildId","startedAtMs":$startedAtMs,"mode":"local","projectKey":"prior","requestedTasks":["build"]}""",
        )
    }

    @Test
    fun `a normal build leaves no start-marker behind`() {
        setUpPlainProject()
        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        // The collector writes this build's marker mid-build; the finalizer deletes its own at the end.
        val leftover = startedDir().listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        assertTrue(leftover.isEmpty(), "a finalized build must leave no marker: ${leftover.map { it.name }}")
    }

    @Test
    fun `a stale marker is reconciled into an INTERRUPTED build and then removed`() {
        setUpPlainProject()
        seedMarker("seeded-dead")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(File(startedDir(), "seeded-dead.json").exists(), "the reconciled marker must be deleted")
        val interrupted = File(interruptedDir(), "seeded-dead.json")
        assertTrue(interrupted.isFile, "a local INTERRUPTED payload must be written for the lost build")
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), interrupted.readText())
        assertEquals(BuildOutcome.INTERRUPTED, payload.outcome)
        assertEquals("seeded-dead", payload.buildId)
        assertTrue(payload.tasks.isEmpty())
    }

    @Test
    fun `marker IO does not invalidate the configuration cache`() {
        setUpPlainProject()
        val store = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, store.task(":hello")?.outcome)
        val reuse = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, reuse.task(":hello")?.outcome)
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
    }

    @Test
    fun `a corrupt marker never fails the build and is dropped`() {
        setUpPlainProject()
        startedDir().mkdirs()
        File(startedDir(), "corrupt.json").writeText("this is not valid json {{{")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "a corrupt marker must never fail the build")
        assertFalse(File(startedDir(), "corrupt.json").exists(), "an unparseable marker is dropped, not retried forever")
    }
}
