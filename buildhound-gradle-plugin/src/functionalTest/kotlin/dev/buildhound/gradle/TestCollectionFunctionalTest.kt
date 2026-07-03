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
 * Test collection (plan 024): the finalizer parses each executed `Test` task's JUnit XML into the
 * payload. The populated cases use a real `java` + JUnit 5 fixture (resolved from Maven Central,
 * the discovery-spike setup); the negative cases use the trivial no-dependency fixture.
 */
class TestCollectionFunctionalTest {

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

    /** A real JVM module with one passing and one failing JUnit 5 test. */
    private fun setUpJavaJunitProject(dsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "tests-fixture"
            buildhound { $dsl }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { java }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.1")
            }
            tasks.withType<Test>().configureEach { useJUnitPlatform() }
            """.trimIndent(),
        )
        val testDir = File(projectDir, "src/test/java/com/example").apply { mkdirs() }
        File(testDir, "SampleTest.java").writeText(
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

    private fun setUpTrivialProject(dsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "trivial-fixture"
            buildhound { $dsl }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
    }

    @Test
    fun `a failing test run is collected into the payload without the plugin failing the build`() {
        setUpJavaJunitProject()

        // The build itself fails (a test fails); telemetry is still assembled and written.
        val result = runner("test").buildAndFail()

        val payload = readPayload()
        assertEquals(BuildOutcome.FAILED, payload.outcome)
        val testTask = payload.tests.single { it.taskPath.endsWith(":test") }
        val sample = testTask.classes.single { it.className == "com.example.SampleTest" }
        assertEquals(1, sample.passed)
        assertEquals(1, sample.failed)
        assertTrue(
            testTask.failedOrRetried.any { it.name.startsWith("fails") },
            "the failing case must carry detail: ${testTask.failedOrRetried}",
        )
        // The failure message is retained (scrubbed); the raw stack-trace body is never shipped.
        val failure = testTask.failedOrRetried.single { it.name.startsWith("fails") }
        assertTrue(failure.messageHash != null, "a stable flaky-signal hash is present")
        assertTrue(result.output.contains("[buildhound] build"), "the summary line still prints")
    }

    @Test
    fun `test results survive configuration cache store then reuse`() {
        setUpJavaJunitProject()

        runner("test").buildAndFail()
        assertTrue(readPayload().tests.isNotEmpty(), "populated on the store run")

        val reuse = runner("test").buildAndFail()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(readPayload().tests.single().classes.isNotEmpty(), "populated again on the reuse run")
    }

    @Test
    fun `a build with no tests has an empty tests section and no test-results directory`() {
        setUpTrivialProject()

        val result = runner("hello").build()

        assertTrue(readPayload().tests.isEmpty())
        assertTrue(!File(projectDir, "build/test-results").exists(), "no test task ran, so nothing was read")
        assertTrue(result.output.contains("0 test(s)"), result.output)
    }

    @Test
    fun `collection can be disabled via the dsl`() {
        setUpJavaJunitProject(dsl = "tests { collect = false }")

        runner("test").buildAndFail()

        assertTrue(readPayload().tests.isEmpty(), "the executed test task's XML is not read when collection is off")
    }

    @Test
    fun `the failure-injection seam leaves the build green with a single warn and empty tests`() {
        setUpTrivialProject()

        val result = runner("hello", "-Pbuildhound.internal.failTestCollection=true").build()

        assertTrue(readPayload().tests.isEmpty())
        assertEquals(
            1,
            result.output.lineSequence().count { it.contains("[buildhound] test result collection failed") },
            "exactly one warn: ${result.output}",
        )
    }
}
