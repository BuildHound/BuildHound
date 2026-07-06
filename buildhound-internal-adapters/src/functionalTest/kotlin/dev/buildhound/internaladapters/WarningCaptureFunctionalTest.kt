package dev.buildhound.internaladapters

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * Real-signal end-to-end (plan 044): applies BOTH core (`dev.buildhound`) and this module in one real
 * TestKit build, so a genuine `logger.warn` and a genuine Gradle deprecation flow through the actual
 * daemon listeners — capture the reflection-guarded wiring is easy to get silently wrong. The test JVM
 * only reads the resulting payload JSON (parsed generically, no Gradle-internal or main-module classes),
 * and asserts the warnings land in `extensions.internalAdapters`, scrubbed, when the toggles are on —
 * and are absent when off.
 */
class WarningCaptureFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    /** The internal-adapters plugin-under-test classpath (module + its deps: commons, serialization). */
    private fun basePluginClasspath(): List<File> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")) {
            "plugin-under-test-metadata.properties missing from the functionalTest classpath"
        }
        val props = Properties().apply { stream.use { load(it) } }
        return props.getProperty("implementation-classpath").split(File.pathSeparator).map { File(it) }
    }

    private fun locationOf(clazz: Class<*>): File = File(clazz.protectionDomain.codeSource.location.toURI())

    /**
     * Classpath entries for the core plugin, robust to the project dep resolving as either a jar
     * (self-contained) or classes+resources dirs. In the dir case the plugin descriptor lives in a
     * sibling resources dir, found via the descriptor resource.
     */
    private fun corePluginEntries(): List<File> {
        val entries = mutableListOf(locationOf(dev.buildhound.gradle.BuildHoundSettingsPlugin::class.java))
        val descriptor = "META-INF/gradle-plugins/dev.buildhound.properties"
        runCatching {
            val url = javaClass.classLoader.getResource(descriptor) ?: return@runCatching
            if (url.protocol == "file") {
                val path = File(url.toURI()).path
                entries += File(path.removeSuffix(descriptor.replace('/', File.separatorChar)).trimEnd(File.separatorChar))
            }
        }
        return entries
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath((basePluginClasspath() + corePluginEntries()).distinct())
            .withArguments(*args)

    private fun setUp(togglesOn: Boolean) {
        val toggles = if (togglesOn) {
            """
            internalAdapters {
                collectDeprecations = true
                collectLogWarnings = true
            }
            """
        } else {
            ""
        }
        // Core first so its shared service exists when internal-adapters checks for it.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("dev.buildhound")
                id("dev.buildhound.internal-adapters")
            }
            rootProject.name = "warn-fixture"
            $toggles
            """.trimIndent(),
        )
        // A real WARN log line (with a secret + out-of-project path, to prove scrubbing), and a real
        // Gradle deprecation (accessing project state at execution time nags for removal).
        File(projectDir, "build.gradle.kts").writeText(
            """
            tasks.register("warn") {
                doLast {
                    logger.warn("custom build warning secret=abc123XYZ456 outside /home/secret/creds.txt")
                    @Suppress("DEPRECATION")
                    logger.lifecycle("buildDir=" + project.buildDir)
                }
            }
            """.trimIndent(),
        )
    }

    private data class Captured(val logWarnings: List<String>, val deprecations: List<String>)

    /** Reads `extensions.internalAdapters` generically (no typed model needed in the test JVM). */
    private fun captured(): Captured? {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected a BuildHound payload at $file")
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
        val block = payload.extensions["internalAdapters"]?.jsonObject ?: return null
        fun arr(key: String) = block[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return Captured(logWarnings = arr("logWarnings"), deprecations = arr("deprecations"))
    }

    @Test
    fun `warnings are captured and scrubbed when both toggles are on`() {
        setUp(togglesOn = true)
        val result = runner("warn", "--warning-mode=all").build()

        // Enabling a catcher warns that it uses internal Gradle APIs and may break on upgrade.
        assertTrue(
            result.output.contains("reads internal Gradle APIs") && result.output.contains("collectLogWarnings"),
            "expected the internal-API risk warning: ${result.output}",
        )

        val c = captured() ?: error("expected an internalAdapters extension block")
        // logger.warn captured — proves the LoggingOutputInternal listener is really wired — and scrubbed.
        assertTrue(c.logWarnings.any { it.contains("custom build warning") }, "logger.warn captured: ${c.logWarnings}")
        assertTrue(c.logWarnings.none { it.contains("abc123XYZ456") }, "secret scrubbed: ${c.logWarnings}")
        assertTrue(c.logWarnings.none { it.contains("/home/secret") }, "out-of-project path scrubbed: ${c.logWarnings}")
        // A real Gradle deprecation is captured on its own stream — proves progress() extraction works.
        assertTrue(c.deprecations.isNotEmpty(), "a real deprecation was captured: ${c.deprecations}")
    }

    @Test
    fun `no warnings are captured when the toggles are off (default)`() {
        setUp(togglesOn = false)
        runner("warn", "--warning-mode=all").build()

        val c = captured()
        // The block may still exist for cache/edge data, but both warning streams must be empty.
        assertTrue(c == null || c.logWarnings.isEmpty(), "off by default: no log warnings")
        assertTrue(c == null || c.deprecations.isEmpty(), "off by default: no deprecations")
    }
}
