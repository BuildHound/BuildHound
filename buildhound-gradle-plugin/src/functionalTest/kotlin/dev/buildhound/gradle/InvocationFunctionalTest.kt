package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.PropertyOrigin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Invocation-switch & performance-flag posture capture (plan 051): the seven `StartParameter`
 * scalars, `fileEncoding`/`locale`, and the layer-attributed `gradle.properties` allowlist.
 */
class InvocationFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    /** A Gradle User Home distinct from the project dir and from TestKit's own working dir (below). */
    @field:TempDir
    lateinit var guhDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .freshDaemon()
            // `-g`/`--gradle-user-home` is the CLI flag StartParameter#getGradleUserHomeDir reads —
            // an explicit, documented override, safer than relying on TestKit's own testKitDir
            // semantics (which govern TestKit's registry, not necessarily the build's GUH).
            .withArguments(*arguments, "-g", guhDir.absolutePath)

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun setUpSimpleProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "invocation-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
    }

    @Test
    fun `Gradle User Home gradle-properties silently wins over the project file`() {
        setUpSimpleProject()
        File(projectDir, "gradle.properties").writeText("org.gradle.caching=false\n")
        File(guhDir, "gradle.properties").writeText("org.gradle.caching=true\n")

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)

        val invocation = readPayload().environment?.invocation ?: error("expected environment.invocation")
        val caching = invocation.properties.first { it.key == "org.gradle.caching" }
        assertEquals("true", caching.value, "GUH's value must win")
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, caching.origin, "GUH silently overrides the project file")
    }

    @Test
    fun `a project-only property attributes PROJECT`() {
        setUpSimpleProject()
        File(projectDir, "gradle.properties").writeText("org.gradle.parallel=true\n")

        runner("hello").build()

        val invocation = readPayload().environment?.invocation ?: error("expected environment.invocation")
        val parallelProp = invocation.properties.first { it.key == "org.gradle.parallel" }
        assertEquals("true", parallelProp.value)
        assertEquals(PropertyOrigin.PROJECT, parallelProp.origin)
    }

    @Test
    fun `offline and max-workers populate the StartParameter scalars`() {
        setUpSimpleProject()

        runner("hello", "--offline", "--max-workers=2").build()

        val invocation = readPayload().environment?.invocation ?: error("expected environment.invocation")
        assertEquals(true, invocation.offline)
        assertEquals(2, invocation.maxWorkerCount)
        assertNotNull(invocation.fileEncoding, "fileEncoding must be captured")
        assertNotNull(invocation.locale, "locale must be captured")
    }

    @Test
    fun `rerunTasks and refreshDependencies scalars populate from their CLI flags`() {
        setUpSimpleProject()

        runner("hello", "--rerun-tasks").build()
        val rerun = readPayload().environment?.invocation ?: error("expected environment.invocation")
        assertEquals(true, rerun.rerunTasks)

        runner("hello", "--refresh-dependencies").build()
        val refreshed = readPayload().environment?.invocation ?: error("expected environment.invocation")
        assertEquals(true, refreshed.refreshDependencies)
    }

    @Test
    fun `a config-cache hit keeps the block present and re-freshes the allowlist`() {
        setUpSimpleProject()
        File(projectDir, "gradle.properties").writeText("org.gradle.caching=true\n")

        val first = runner("hello", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":hello")?.outcome, first.output)
        val firstInvocation = readPayload().environment?.invocation ?: error("expected environment.invocation on the miss")
        assertEquals("true", firstInvocation.properties.first { it.key == "org.gradle.caching" }.value)

        // Change the project file between builds — a real CC-hit rerun must still re-read it: the
        // allowlist is resolved in obtain() (execution time), not baked like the StartParameter scalars.
        File(projectDir, "gradle.properties").writeText("org.gradle.caching=false\n")

        val second = runner("hello", "--configuration-cache").build()
        assertTrue(
            second.output.lineSequence().any { it.startsWith("[buildhound] build ") && it.contains("cc=HIT") },
            second.output,
        )
        val secondInvocation = readPayload().environment?.invocation ?: error("expected environment.invocation on the hit")
        assertEquals(
            "false",
            secondInvocation.properties.first { it.key == "org.gradle.caching" }.value,
            "the allowlist must re-freshen on a CC hit, unlike the baked StartParameter scalars",
        )
    }
}
