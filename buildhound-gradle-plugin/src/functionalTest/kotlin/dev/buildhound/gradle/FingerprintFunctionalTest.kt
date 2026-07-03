package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/** Input-fingerprint capture (plan 022): salted hashes of build inputs, opt-in per-Test capture. */
class FingerprintFunctionalTest {

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

    private fun setUpSimpleProject(extraDsl: String) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "fp-fixture"
            buildhound { $extraDsl }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
    }

    @Test
    fun `build-level fingerprints capture built-in and allowlisted keys as salted hashes`() {
        setUpSimpleProject("""fingerprints { systemProperties("buildhound.test.prop") }""")

        val first = runner("hello", "-Dbuildhound.test.prop=alpha").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":hello")?.outcome)
        val build = readPayload().fingerprints?.build ?: error("expected a fingerprints.build map")

        // Built-in key present; value is a 16-hex + … salted hash, never the plaintext.
        val jdk = build["jdk.home"] ?: error("built-in jdk.home missing: ${build.keys}")
        assertTrue(jdk.matches(Regex("[0-9a-f]{16}…")), "unexpected hash shape: $jdk")
        val prop = build["sysProps-buildhound.test.prop"] ?: error("allowlisted key missing: ${build.keys}")
        assertTrue(prop.matches(Regex("[0-9a-f]{16}…")), prop)
        assertFalse(prop.contains("alpha"), "plaintext must never leak: $prop")

        // Stable for the same value; changes when the value changes.
        val second = runner("hello", "-Dbuildhound.test.prop=alpha").build()
        assertEquals(prop, readPayload().fingerprints?.build?.get("sysProps-buildhound.test.prop"), second.output)
        runner("hello", "-Dbuildhound.test.prop=beta").build()
        assertFalse(prop == readPayload().fingerprints?.build?.get("sysProps-buildhound.test.prop"))
    }

    @Test
    fun `per-Test fingerprints are not captured (deferred to the add-on)`() {
        setUpSimpleProject("""fingerprints { systemProperties("buildhound.test.prop") }""")

        runner("hello", "-Dbuildhound.test.prop=x").build()

        // Build-level fingerprints exist; the per-task map stays empty (plan 022 §8 deferral).
        val fp = readPayload().fingerprints
        assertTrue(fp?.build?.isNotEmpty() == true)
        assertTrue(fp?.tasks.isNullOrEmpty(), "per-Test map must be empty while deferred: ${fp?.tasks}")
    }

    @Test
    fun `fingerprints are omitted with a single warn when the salt cannot be created`() {
        setUpSimpleProject("""fingerprints { systemProperties("buildhound.test.prop") }""")
        // Occupy the salt path with a directory so read-or-create fails.
        File(projectDir, ".gradle/buildhound/identity.salt").mkdirs()

        val result = runner("hello", "-Dbuildhound.test.prop=x").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "salt failure must never fail the build")
        assertNull(readPayload().fingerprints, "no salt → fingerprints omitted, never plaintext")
        assertTrue(
            result.output.lineSequence().any { it.contains("[buildhound] input fingerprints skipped") },
            result.output,
        )
    }
}
