package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Toolchain-dimension collection (plan 044). Driving a real AGP/KGP/KSP build in TestKit is heavy
 * and version-coupled (the same reason [KotlinReportFunctionalTest] seeds a fake KGP report rather
 * than compiling Kotlin), so these tests use the `buildhound.internal.toolchain.*` seam to inject
 * versions and assert the whole configuration-time → service → payload channel — including its
 * config-cache replay — carries them through. Real version *extraction* is covered by the unit test
 * ([ToolchainDetectionTest]) and validated end-to-end against the `samples/nowinandroid` harness.
 */
class ToolchainFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--configuration-cache")

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun setUpProject(gradleProperties: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "toolchain-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
        if (gradleProperties.isNotBlank()) {
            File(projectDir, "gradle.properties").writeText(gradleProperties)
        }
    }

    @Test
    fun `detected toolchain versions reach the payload and survive cc reuse`() {
        setUpProject(
            gradleProperties = "buildhound.internal.toolchain.agp=8.9.0\n" +
                "buildhound.internal.toolchain.kgp=2.2.20\n" +
                "buildhound.internal.toolchain.ksp=2.2.20-2.0.2\n",
        )

        val first = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":hello")?.outcome)
        readPayload().toolchain.let { toolchain ->
            assertEquals("8.9.0", toolchain?.agp)
            assertEquals("2.2.20", toolchain?.kgp)
            assertEquals("2.2.20-2.0.2", toolchain?.ksp)
        }

        // The versions are captured at configuration time and replayed from the service parameter,
        // so a config-cache hit (where the whenReady callback never runs) still carries them.
        val second = runner("hello").build()
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertEquals("8.9.0", readPayload().toolchain?.agp)
    }

    @Test
    fun `only the seeded dimensions are populated`() {
        setUpProject(gradleProperties = "buildhound.internal.toolchain.kgp=2.4.0\n")

        runner("hello").build()

        val toolchain = readPayload().toolchain
        assertEquals("2.4.0", toolchain?.kgp)
        assertNull(toolchain?.agp, "AGP was not seeded → stays null")
        assertNull(toolchain?.ksp, "KSP was not seeded → stays null")
    }

    @Test
    fun `a build applying none of the tools reports null AGP KGP KSP and still succeeds`() {
        setUpProject()

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "must never fail the build")
        val toolchain = readPayload().toolchain
        assertNull(toolchain?.agp)
        assertNull(toolchain?.kgp)
        assertNull(toolchain?.ksp)
    }
}
