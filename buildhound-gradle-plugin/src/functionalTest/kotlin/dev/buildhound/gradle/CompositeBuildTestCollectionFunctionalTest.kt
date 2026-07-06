package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * Regression gate for plan 044: in a **composite** build the collector `BuildService` is instantiated
 * by an included build's task events (here a `build-logic` convention plugin the root applies, exactly
 * as the nowinandroid dev harness is structured) *before* the root's `taskGraph.whenReady` fills the
 * plan-016/024 mailbox — freezing the service param empty, so test telemetry was silently dropped
 * (`tests: []`). The durable sidecar file (`TestLocationSidecar`) is the fix; without it these tests
 * fail on `main`.
 */
class CompositeBuildTestCollectionFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

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

    /**
     * A root build that applies a convention plugin from an included `build-logic` build. Building that
     * plugin runs its `:jar` (etc.) during the root's *configuration*, so the collector sees task-finish
     * events and is instantiated before `whenReady` — the freeze this plan fixes. The root module has one
     * passing and one failing JUnit 5 test so we also assert a failed test is captured (never fails the
     * build via the plugin).
     */
    private fun setUpComposite() {
        // --- included build: a trivial project convention plugin ---
        val buildLogic = File(projectDir, "build-logic").apply { mkdirs() }
        File(buildLogic, "settings.gradle.kts").writeText("""rootProject.name = "build-logic"""")
        File(buildLogic, "build.gradle.kts").writeText(
            """
            plugins { `java-gradle-plugin` }
            repositories { mavenCentral() }
            gradlePlugin {
                plugins.create("conv") {
                    id = "my.conv"
                    implementationClass = "conv.ConvPlugin"
                }
            }
            """.trimIndent(),
        )
        File(buildLogic, "src/main/java/conv").apply { mkdirs() }
            .let { dir ->
                File(dir, "ConvPlugin.java").writeText(
                    """
                    package conv;
                    import org.gradle.api.Plugin;
                    import org.gradle.api.Project;
                    public class ConvPlugin implements Plugin<Project> {
                        public void apply(Project project) { }
                    }
                    """.trimIndent(),
                )
            }

        // --- root build: applies dev.buildhound (settings) + my.conv (project, from build-logic) ---
        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                includeBuild("build-logic")
                repositories { gradlePluginPortal(); mavenCentral() }
            }
            plugins { id("dev.buildhound") }
            rootProject.name = "composite-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { java; id("my.conv") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.1")
            }
            tasks.withType<Test>().configureEach { useJUnitPlatform() }
            """.trimIndent(),
        )
        File(projectDir, "src/test/java/com/example").apply { mkdirs() }
            .let { dir ->
                File(dir, "SampleTest.java").writeText(
                    """
                    package com.example;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.*;
                    class SampleTest {
                        @Test void passes() { assertTrue(true); }
                        @Test void fails() { assertEquals(1, 2, "one is not two"); }
                    }
                    """.trimIndent(),
                )
            }
    }

    @Test
    fun `test telemetry is captured in a composite build (fails on main - frozen service param)`() {
        setUpComposite()

        // The build fails (a test fails); the plugin still assembles + writes telemetry.
        val result = runner("test", "--configuration-cache").buildAndFail()

        val payload = readPayload()
        assertEquals(BuildOutcome.FAILED, payload.outcome)
        val testTask = payload.tests.single { it.taskPath.endsWith(":test") }
        val sample = testTask.classes.single { it.className == "com.example.SampleTest" }
        assertEquals(1, sample.passed, "the passing case is captured")
        assertEquals(1, sample.failed, "the failing case is captured")
        assertTrue(
            testTask.failedOrRetried.any { it.name.startsWith("fails") },
            "the failed test rides the payload: ${testTask.failedOrRetried}",
        )
        assertTrue(result.output.contains("[buildhound] build"), "the summary line still prints")
    }

    @Test
    fun `composite test telemetry survives configuration cache store then reuse`() {
        setUpComposite()

        runner("test", "--configuration-cache").buildAndFail()
        assertTrue(readPayload().tests.isNotEmpty(), "populated on the store run")

        // Reuse run: whenReady never fires, so the mailbox holder is empty — the .gradle sidecar
        // persisted from the store run is what keeps the tests present (the CC-hit gap).
        val reuse = runner("test", "--configuration-cache").buildAndFail()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(readPayload().tests.single().classes.isNotEmpty(), "populated again on the reuse run")
    }
}
