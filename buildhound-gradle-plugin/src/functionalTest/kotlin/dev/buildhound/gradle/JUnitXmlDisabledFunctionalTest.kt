package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * Honest degraded state when JUnit XML is disabled (plan 053, research F3). Gradle's own
 * performance guide recommends `reports.junitXml.required = false` on `Test` tasks "if you use a
 * Build Scan" — a team following it would otherwise lose all plan-024 test telemetry with zero
 * signal. The flag is authoritative over whatever XML happens to sit on disk (a stale run from
 * before the flag flipped, the inverse of plan 024 §6's stale-output race).
 */
class JUnitXmlDisabledFunctionalTest {

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

    /** A real JVM module with one passing JUnit 5 test and JUnit XML disabled on `Test`. */
    private fun setUpDisabledXmlProject(dsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "junit-xml-disabled-fixture"
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
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                reports.junitXml.required.set(false)
            }
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
            }
            """.trimIndent(),
        )
    }

    /**
     * Same fixture, but with a failing case too: a Test task whose last run failed is never
     * considered up-to-date, so it genuinely re-executes on a second invocation — needed for the
     * CC-hit variant below, where the disabled note must only ever appear for a **this-build**
     * EXECUTED/FAILED outcome (mirrors [TestCollectionFunctionalTest]'s own CC-reuse fixture).
     */
    private fun setUpDisabledXmlProjectWithFailingTest() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "junit-xml-disabled-cc-fixture"
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
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                reports.junitXml.required.set(false)
            }
            """.trimIndent(),
        )
        val testDir = File(projectDir, "src/test/java/com/example").apply { mkdirs() }
        File(testDir, "SampleTest.java").writeText(
            """
            package com.example;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class SampleTest {
                @Test void fails() { assertEquals(1, 2, "one is not two"); }
            }
            """.trimIndent(),
        )
    }

    /** Stale XML from a prior `required = true` run — the flag must override it, not this file. */
    private fun seedStaleXml() {
        val resultsDir = File(projectDir, "build/test-results/test").apply { mkdirs() }
        File(resultsDir, "TEST-com.example.SampleTest.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.SampleTest" tests="1" failures="0" time="0.01">
              <testcase name="stale()" classname="com.example.SampleTest" time="0.01"/>
            </testsuite>
            """.trimIndent(),
        )
    }

    @Test
    fun `an executed test task with xml disabled produces an honest degraded note and no phantom results`() {
        setUpDisabledXmlProject()
        seedStaleXml()

        runner("test").build()

        val payload = readPayload()
        assertEquals(BuildOutcome.SUCCESS, payload.outcome)
        assertTrue(payload.tests.none { it.taskPath.endsWith(":test") }, "the flag overrides whatever XML sits on disk")
        val telemetry = payload.testTelemetry ?: error("expected a testTelemetry block")
        assertEquals(listOf(":test"), telemetry.xmlDisabledTasks)
    }

    @Test
    fun `the disabled note survives a configuration cache store then a hit`() {
        // A failing test never counts as up-to-date, so :test genuinely re-executes both runs —
        // otherwise the second run would be UP_TO_DATE and correctly emit no note (its own case below).
        setUpDisabledXmlProjectWithFailingTest()

        runner("test").buildAndFail()
        assertEquals(listOf(":test"), readPayload().testTelemetry?.xmlDisabledTasks, "populated on the store run")

        val reuse = runner("test").buildAndFail()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertEquals(
            listOf(":test"),
            readPayload().testTelemetry?.xmlDisabledTasks,
            "the durable sidecar replays the disabled flag on a CC hit, when whenReady never runs",
        )
    }

    @Test
    fun `an up-to-date test task on a second run produces no note (this-build EXECUTED-FAILED only)`() {
        setUpDisabledXmlProject()

        runner("test").build()
        assertEquals(listOf(":test"), readPayload().testTelemetry?.xmlDisabledTasks, "populated on the store run")

        val reuse = runner("test").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(reuse.output.contains("UP-TO-DATE") || reuse.task(":test")?.outcome?.name == "UP_TO_DATE", reuse.output)
        assertNull(readPayload().testTelemetry, "a task that did not execute this build produces neither a result nor a note")
    }

    @Test
    fun `collection disabled via the dsl produces no note`() {
        setUpDisabledXmlProject(dsl = "tests { collect = false }")

        runner("test").build()

        assertNull(readPayload().testTelemetry, "the testsCollect guard wraps the whole flag-authoritative path")
        assertTrue(readPayload().tests.isEmpty())
    }

    @Test
    fun `xml required (the default) still parses results, no degraded note`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "junit-xml-enabled-fixture"
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
            }
            """.trimIndent(),
        )

        runner("test").build()

        val payload = readPayload()
        assertTrue(payload.tests.any { it.taskPath.endsWith(":test") }, "xml enabled (the default) still parses")
        assertNull(payload.testTelemetry)
    }
}
