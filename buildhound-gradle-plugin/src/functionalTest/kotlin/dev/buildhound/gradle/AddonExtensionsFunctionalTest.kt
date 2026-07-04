package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.gradle.fixture.FixtureExtensionContributor
import dev.buildhound.gradle.fixture.ThrowingExtensionContributor
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Addon `extensions` discovery + merge (plan 039). A fixture [BuildHoundExtensionContributor] is put
 * on the plugin-under-test classpath via a hand-written `META-INF/services` file, so the core
 * finalizer's `ServiceLoader` finds it exactly as it would a real separately-built addon.
 */
class AddonExtensionsFunctionalTest {

    private val serviceFile = "META-INF/services/dev.buildhound.commons.payload.BuildHoundExtensionContributor"

    @field:TempDir
    lateinit var projectDir: File

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    /** The default plugin-under-test classpath (from the injected metadata), so we can augment it. */
    private fun basePluginClasspath(): List<File> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")) {
            "plugin-under-test-metadata.properties missing from the functionalTest classpath"
        }
        val props = Properties().apply { stream.use { load(it) } }
        return props.getProperty("implementation-classpath").split(File.pathSeparator).map { File(it) }
    }

    /** The directory holding the compiled fixture contributors (the functionalTest classes dir). */
    private fun fixtureClassesDir(): File =
        File(FixtureExtensionContributor::class.java.protectionDomain.codeSource.location.toURI())

    /** Write a `META-INF/services` file naming [contributorFqcns] into a fresh dir and return it. */
    private fun servicesDir(vararg contributorFqcns: String): File {
        val dir = File(projectDir, "addon-services-${contributorFqcns.hashCode()}")
        File(dir, serviceFile).apply { parentFile.mkdirs(); writeText(contributorFqcns.joinToString("\n")) }
        return dir
    }

    private fun runner(servicesDir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(basePluginClasspath() + fixtureClassesDir() + servicesDir)
            .withArguments(*arguments, "--configuration-cache")

    private fun setUpProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "addon-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
    }

    @Test
    fun `a discovered contributor's block appears in the payload extensions`() {
        setUpProject()
        val services = servicesDir(FixtureExtensionContributor::class.java.name)

        val result = runner(services, "hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)

        val extension = readPayload().extensions["fixtureAddon"]
            ?: error("expected a fixtureAddon extensions block")
        val obj = extension.jsonObject
        assertEquals(1, obj.getValue("schemaVersion").jsonPrimitive.content.toInt())
        // projectKey defaults to rootProject.name — proving the context carried the collected facts.
        assertEquals("addon-fixture", obj.getValue("projectKey").jsonPrimitive.content)
        assertTrue(obj.getValue("taskCount").jsonPrimitive.content.toInt() >= 1)
    }

    @Test
    fun `a throwing contributor never fails the build and does not suppress a sibling`() {
        setUpProject()
        val services = servicesDir(
            ThrowingExtensionContributor::class.java.name,
            FixtureExtensionContributor::class.java.name,
        )

        val result = runner(services, "hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "a throwing addon must never fail the build")

        val payload = readPayload()
        assertTrue(payload.extensions.containsKey("fixtureAddon"), "the well-behaved sibling still contributes")
        assertFalse(payload.extensions.containsKey("throwingAddon"), "the throwing addon contributes nothing")
        assertTrue(result.output.contains("throwingAddon") && result.output.contains("threw"), result.output)
    }

    @Test
    fun `contributor discovery adds no configuration-cache input so the entry is reused`() {
        setUpProject()
        val services = servicesDir(FixtureExtensionContributor::class.java.name)

        val store = runner(services, "hello").build()
        assertTrue(readPayload().extensions.containsKey("fixtureAddon"), "contributed on the store run")

        val reuse = runner(services, "hello").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(readPayload().extensions.containsKey("fixtureAddon"), "extensions still contributed on the reuse run")
    }
}
