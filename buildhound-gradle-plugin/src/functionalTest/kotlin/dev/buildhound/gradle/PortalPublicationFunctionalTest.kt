package dev.buildhound.gradle

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element

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
                // The fixture version is stable in development. Isolate dependency caches so the
                // just-published marker and plugin JAR cannot be replaced by an older SNAPSHOT hit.
                .freshDaemon()
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
        val pluginJar = resolvePublishedArtifact(moduleDirectory, releaseVersion, "jar")

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

        val pom = resolvePublishedArtifact(moduleDirectory, releaseVersion, "pom")
        assertFalse(pom.readText().contains("<dependencies>"), "shadow publication leaked runtime dependencies")
        return pluginJar
    }

    private fun resolvePublishedArtifact(
        moduleDirectory: File,
        releaseVersion: String,
        extension: String,
    ): File {
        val artifactId = "buildhound-gradle-plugin"
        val exactArtifact = moduleDirectory.resolve("$artifactId-$releaseVersion.$extension")
        if (exactArtifact.isFile) return exactArtifact

        val metadata = moduleDirectory.resolve("maven-metadata.xml")
        assertTrue(metadata.isFile, "missing Maven metadata: $metadata")
        val documentBuilderFactory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setXIncludeAware(false)
                setExpandEntityReferences(false)
            }
        val document = metadata.inputStream().use { documentBuilderFactory.newDocumentBuilder().parse(it) }
        val snapshotVersions = document.getElementsByTagName("snapshotVersion")
        val publishedVersion =
            (0 until snapshotVersions.length)
                .asSequence()
                .mapNotNull { snapshotVersions.item(it) as? Element }
                .filter { it.childText("extension") == extension }
                .filter { it.childText("classifier").isNullOrBlank() }
                .mapNotNull { it.childText("value") }
                .singleOrNull()
                ?: error("no unclassified .$extension artifact in $metadata")
        val artifact = moduleDirectory.resolve("$artifactId-$publishedVersion.$extension")
        assertTrue(artifact.isFile, "Maven metadata points to missing artifact: $artifact")
        return artifact
    }

    private fun Element.childText(tagName: String): String? =
        getElementsByTagName(tagName).item(0)?.textContent?.trim()

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
