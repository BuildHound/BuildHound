package dev.buildhound.gradle

import dev.buildhound.commons.payload.ArtifactType
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/** Android artifact-size capture (plan 031): AGP-optional, never-fail. */
class ArtifactSizeFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*arguments, "--configuration-cache")

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun setUpPlainProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "artifact-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
    }

    @Test
    fun `a non-Android build applies cleanly and emits null artifacts, with no AGP on the classpath`() {
        setUpPlainProject()
        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertNull(readPayload().artifacts, "no Android artifacts on a plain build")
    }

    @Test
    fun `a corrupt artifacts dir never fails the build and leaves the finalization marker`() {
        setUpPlainProject()
        // A `.jsonl` entry that is a directory makes the finalizer's readText throw, exercising the
        // finalizer's outer runCatching → warn + marker path (plan 031 §3), build still succeeds.
        File(projectDir, "build/buildhound/artifacts/bad.jsonl").mkdirs()

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "a corrupt artifacts dir must never fail the build")
        assertTrue(
            result.output.contains("[buildhound] telemetry finalization failed (build unaffected)"),
            result.output,
        )
        assertTrue(File(projectDir, "build/buildhound-failure.marker").isFile, "expected a failure marker")
    }

    @Tag("isolated-projects")
    @Test
    fun `the artifact collector reaction is isolated-projects safe on a non-Android build`() {
        setUpPlainProject()
        val ipFlag = "-Dorg.gradle.unsafe.isolated-projects=true"
        // The beforeProject reaction is new plugin surface — under IP it must not fail, and the
        // IP/CC entry must store then reuse with telemetry (artifacts = null on a non-Android build).
        val store = runner("hello", ipFlag).build()
        assertEquals(TaskOutcome.SUCCESS, store.task(":hello")?.outcome)
        assertNull(readPayload().artifacts)
        val reuse = runner("hello", ipFlag).build()
        assertEquals(TaskOutcome.SUCCESS, reuse.task(":hello")?.outcome)
        assertNull(readPayload().artifacts)
    }

    @Test
    fun `an Android app build records a non-zero APK size for the assembled variant`() {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        assumeTrue(sdk != null && File(sdk).isDirectory, "Android SDK not available — Android artifact capture skipped")

        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            plugins { id("dev.buildhound") }
            rootProject.name = "android-artifact-fixture"
            include(":app")
            """.trimIndent(),
        )
        File(projectDir, "gradle.properties").writeText("android.useAndroidX=true\n")
        val app = File(projectDir, "app").apply { mkdirs() }
        File(app, "build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") version "9.2.1" }
            android {
                namespace = "com.example.pilot"
                compileSdk = 34
                defaultConfig { minSdk = 24 }
            }
            """.trimIndent(),
        )
        File(app, "src/main").apply { mkdirs() }
        File(app, "src/main/AndroidManifest.xml").writeText("""<manifest/>""")

        runner("app:assembleDebug").build()
        val apk = readPayload().artifacts?.android?.firstOrNull { it.type == ArtifactType.APK }
            ?: error("expected an APK artifact size")
        assertTrue(apk.sizeBytes > 0, "APK size must be non-zero: $apk")
        assertEquals(":app", apk.module)

        // Second run reuses the configuration cache; the collector must still emit sizes (the
        // AndroidArtifactsSizeReport store-then-reuse pattern).
        val reuse = runner("app:assembleDebug").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(
            readPayload().artifacts?.android?.any { it.type == ArtifactType.APK && it.sizeBytes > 0 } == true,
            "artifact sizes must survive a configuration-cache hit",
        )
    }
}
