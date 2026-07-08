package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.JvmArtifactKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * JVM archive-size capture (plan 072, research F22): measures `bootJar`/`bootWar`/`jar`/`war` sizes via
 * the core-Gradle `AbstractArchiveTask` API — no external classloader, unlike the plan-031 AGP path, so
 * a plain `java-library` fixture (a real `.java` source → a real `jar`) exercises the whole
 * whenReady→finalizer channel with no heavy toolchain. Only-what-ran, CC-safe, never-fail.
 */
class JvmArtifactSizeFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*arguments, "--configuration-cache")

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    /** A root `java-library` with a real source file, so its `jar` task is unambiguously EXECUTED with content. */
    private fun setUpJvmProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "jvm-artifact-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""plugins { `java-library` }""")
        File(projectDir, "src/main/java/com/example").apply { mkdirs() }
        File(projectDir, "src/main/java/com/example/Widget.java")
            .writeText("package com.example;\npublic class Widget { public int answer() { return 42; } }\n")
    }

    @Test
    fun `a jvm build records a non-zero jar size and survives cc reuse`() {
        setUpJvmProject()

        val store = runner("jar").build()
        assertEquals(TaskOutcome.SUCCESS, store.task(":jar")?.outcome)
        val jar = readPayload().artifacts?.jvm?.singleOrNull { it.kind == JvmArtifactKind.JAR }
            ?: error("expected a JAR artifact size on the store run: ${store.output}")
        assertTrue(jar.sizeBytes > 0, "jar size must be non-zero: $jar")
        assertEquals(":", jar.module, "the root project's jar reports the root module path")

        // Second run reuses the configuration cache (whenReady never runs); the finalizer param replays
        // the baked location and re-reads File.length() at execution — the jar is UP_TO_DATE (produced
        // output), so the size still ships.
        val reuse = runner("jar").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(
            readPayload().artifacts?.jvm?.any { it.kind == JvmArtifactKind.JAR && it.sizeBytes > 0 } == true,
            "jvm sizes must survive a configuration-cache hit",
        )
    }

    @Test
    fun `a build whose archive task did not run carries no jvm record`() {
        setUpJvmProject()
        // The java-library fixture registers a `jar` task, but requesting a non-archive task leaves it
        // out of graph.allTasks (only-what-ran), so nothing is captured — artifacts collapses to null.
        File(projectDir, "build.gradle.kts").appendText(
            "\ntasks.register(\"hello\") { doLast { println(\"hello\") } }\n",
        )

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertNull(readPayload().artifacts, "no archive task ran → no jvm record (and no android) → artifacts null")
    }

    @Test
    fun `a non-jvm build applies cleanly and emits null artifacts`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "plain-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertNull(readPayload().artifacts, "a build with no archive task produces no jvm artifacts")
    }

    @Tag("isolated-projects")
    @Test
    fun `jvm archive capture is isolated-projects safe`() {
        setUpJvmProject()
        val ipFlag = "-Dorg.gradle.unsafe.isolated-projects=true"
        // The whenReady graph walk is illegal under IP, so JVM capture degrades to empty (like the task
        // dictionary and toolchain triple) — the build must succeed on store and reuse, artifacts null.
        val store = runner("jar", ipFlag).build()
        assertEquals(TaskOutcome.SUCCESS, store.task(":jar")?.outcome)
        assertNull(readPayload().artifacts, "IP leaves jvm artifacts empty → artifacts null")
        // Reuse: the jar's inputs are unchanged, so it goes UP_TO_DATE — the build (a `.build()`, so it
        // must have succeeded) still degrades JVM capture to empty under IP + a replayed config cache.
        val reuse = runner("jar", ipFlag).build()
        assertEquals(TaskOutcome.UP_TO_DATE, reuse.task(":jar")?.outcome)
        assertNull(readPayload().artifacts)
    }
}
