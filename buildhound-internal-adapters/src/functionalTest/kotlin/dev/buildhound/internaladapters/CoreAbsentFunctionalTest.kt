package dev.buildhound.internaladapters

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * The internal-adapters plugin applied **without** the core plugin (plan 038): it must warn and
 * no-op — never fail the build, never capture. This is verifiable with only this module's classpath.
 */
class CoreAbsentFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*args)

    private fun setUp() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound.internal-adapters") }
            rootProject.name = "core-absent-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""plugins { java }""")
        File(projectDir, "src/main/java/com/example/Foo.java").apply {
            parentFile.mkdirs()
            writeText("package com.example; public class Foo { public int x() { return 1; } }")
        }
        File(projectDir, "gradle.properties").writeText("org.gradle.caching=true\n")
    }

    @Test
    fun `applied without core the plugin warns and no-ops without failing`() {
        setUp()
        val result = runner("compileJava", "--build-cache").build() // .build() already asserts success
        assertTrue(
            result.task(":compileJava")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE, TaskOutcome.UP_TO_DATE),
            "compileJava must not fail without core",
        )
        assertTrue(result.output.contains("core plugin 'dev.buildhound' not applied"), result.output)
        // No BuildHound payload is produced (core is what writes it).
        assertTrue(!File(projectDir, "build/buildhound/build-payload.json").exists())
    }

    @Test
    fun `configuration cache is reused with the plugin applied alone`() {
        setUp()
        runner("compileJava", "--build-cache", "--configuration-cache").build()
        val reuse = runner("compileJava", "--build-cache", "--configuration-cache").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
    }
}
