package dev.buildhound.gradle

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class PortalPublicationFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `published marker resolves a self contained plugin`() {
        val repository =
            File(requireNotNull(System.getProperty("buildhound.release-test-repository")))
        val releaseVersion = requireNotNull(System.getProperty("buildhound.release-version"))
        val pluginJar = assertPublishedArtifact(repository, releaseVersion)
        assertSeparatelyCompiledAddonLoads(pluginJar)

        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${repository.toURI().toASCIIString()}") }
                }
            }

            plugins {
                id("dev.buildhound") version "$releaseVersion"
            }

            rootProject.name = "portal-publication-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            tasks.register("hello") {
                doLast { println("hello from published plugin fixture") }
            }
            """.trimIndent(),
        )

        // Deliberately no withPluginClasspath() or includeBuild: resolution must use the
        // generated plugin marker, POM/module metadata, and shadow JAR exactly as a user would.
        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("hello", "--stacktrace", testkitCcFlag())
                .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(result.output.contains("[buildhound] build "), result.output)
        assertTrue(
            File(projectDir, "build/buildhound/buildhound-report.html").isFile,
            "the bundled report module and resources must be available",
        )
    }

    private fun assertPublishedArtifact(repository: File, releaseVersion: String): File {
        val moduleDirectory =
            repository.resolve("dev/buildhound/buildhound-gradle-plugin/$releaseVersion")
        val exactJar = moduleDirectory.resolve("buildhound-gradle-plugin-$releaseVersion.jar")
        val pluginJar =
            exactJar.takeIf(File::isFile)
                ?: moduleDirectory.listFiles()
                    ?.filter { it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") }
                    ?.maxByOrNull(File::lastModified)
                ?: error("no published plugin JAR under $moduleDirectory")
        assertTrue(pluginJar.isFile, "missing published plugin JAR: $pluginJar")

        val entries = mutableSetOf<String>()
        val serviceBody =
            ZipFile(pluginJar).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) entries += enumeration.nextElement().name
                val service =
                    zip.getEntry("META-INF/services/dev.buildhound.commons.payload.BuildHoundExtensionContributor")
                assertTrue(service != null, "internal-adapters service descriptor is missing")
                zip.getInputStream(service).bufferedReader().use { it.readText() }
            }

        listOf(
            "META-INF/gradle-plugins/dev.buildhound.properties",
            "dev/buildhound/commons/payload/BuildPayload.class",
            "dev/buildhound/report/report-template.html",
            "dev/buildhound/internaladapters/InternalAdaptersWiring.class",
            "kotlinx/serialization/json/Json.class",
        ).forEach { expected -> assertTrue(expected in entries, "missing bundled entry: $expected") }
        assertTrue(
            serviceBody.contains("dev.buildhound.internaladapters.InternalAdaptersCollector"),
            "internal-adapters service provider is missing: $serviceBody",
        )
        listOf(
            "kotlin/",
            "org/jetbrains/annotations/",
            "dev/buildhound/internal/shaded/kotlinx/serialization/",
            "dev/buildhound/sharding/",
        ).forEach { prefix ->
            assertFalse(entries.any { it.startsWith(prefix) }, "unexpected bundled package: $prefix")
        }
        assertFalse(entries.any { it.endsWith(".kotlin_module") }, "incompatible Kotlin module index bundled")

        val exactPom = moduleDirectory.resolve("buildhound-gradle-plugin-$releaseVersion.pom")
        val pom =
            exactPom.takeIf(File::isFile)
                ?: moduleDirectory.listFiles()
                    ?.filter { it.name.endsWith(".pom") }
                    ?.maxByOrNull(File::lastModified)
                ?: error("no published POM under $moduleDirectory")
        assertTrue(pom.isFile, "missing published POM: $pom")
        assertFalse(pom.readText().contains("<dependencies>"), "shadow publication leaked runtime dependencies")
        return pluginJar
    }

    /**
     * Load the published core together with the real test-sharding addon from its separately compiled
     * output. A child-first boundary for BuildHound + serialization types models the consumer plugin
     * classloader: if Shadow ever rewrites the JsonElement return type in the public contributor SPI,
     * invoking this implementation throws AbstractMethodError instead of returning null.
     */
    private fun assertSeparatelyCompiledAddonLoads(pluginJar: File) {
        val addonClassName = "dev.buildhound.sharding.TestShardingCollector"
        val addonLocation =
            requireNotNull(Class.forName(addonClassName).protectionDomain.codeSource?.location) {
                "separately compiled test-sharding output is not on the functional-test classpath"
            }

        PluginBoundaryClassLoader(
            arrayOf(pluginJar.toURI().toURL(), addonLocation),
            PortalPublicationFunctionalTest::class.java.classLoader,
        ).use { loader ->
            val contributorType =
                loader.loadClass("dev.buildhound.commons.payload.BuildHoundExtensionContributor")
            val contextType =
                loader.loadClass("dev.buildhound.commons.payload.ExtensionContributionContext")
            val buildModeType = loader.loadClass("dev.buildhound.commons.payload.BuildMode")
            val localMode =
                buildModeType.enumConstants.single { (it as Enum<*>).name == "LOCAL" }
            val context =
                contextType.constructors.single { it.parameterCount == 4 }
                    .newInstance(null, localMode, emptyList<Any>(), null)
            val addon = loader.loadClass(addonClassName).getDeclaredConstructor().newInstance()

            assertTrue(contributorType.isInstance(addon), "addon no longer implements the published SPI")
            assertEquals(
                null,
                contributorType.getMethod("contribute", contextType).invoke(addon, context),
                "an inactive addon should dispatch through the published SPI and contribute nothing",
            )
        }
    }

    private class PluginBoundaryClassLoader(urls: Array<URL>, parent: ClassLoader) :
        URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("dev.buildhound.") || name.startsWith("kotlinx.serialization.")) {
                synchronized(getClassLoadingLock(name)) {
                    findLoadedClass(name)?.let { return it }
                    try {
                        return findClass(name).also { if (resolve) resolveClass(it) }
                    } catch (_: ClassNotFoundException) {
                        // Gradle and Kotlin host types deliberately come from the parent loader.
                    }
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}
