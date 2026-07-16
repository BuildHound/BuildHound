package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Guards the CI dogfooding injection path (plan 093): the REAL
 * `.github/buildhound-dogfood.init.gradle.kts` (resolved via the `buildhound.dogfood.init-script`
 * system property, never a copy) is applied with `-I` to a synthetic project that does NOT apply
 * the plugin itself — exactly the ci.yml `build` job shape.
 *
 * The init script resolves `dev.buildhound:buildhound-gradle-plugin` at the root build's
 * default development version (parsed from the root build script, see [rootDefaultVersion])
 * from the local Maven repository; the tests redirect that repository with `-Dmaven.repo.local` (which
 * both `mavenLocal()` and the script's stage-1 presence check honor) to a fixture repo built
 * from the already-published release-test-repository JAR — so the dev machine's real `~/.m2`
 * is never read nor required.
 */
class DogfoodInitScriptFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun initScript(): File {
        val script = File(requireNotNull(System.getProperty("buildhound.dogfood.init-script")))
        assertTrue(script.isFile, "the real dogfood init script must exist: $script")
        return script
    }

    /**
     * The default development version, parsed from the root build script's actual fallback
     * (`.getOrElse("0.1.0-SNAPSHOT")` on the buildhoundVersion provider chain) rather than
     * hardcoded here: the init script must carry its own self-contained copy of that literal,
     * and a copy asserted against another copy would stay self-consistently green forever. By
     * deriving the expectation from the source of truth, a version bump that forgets the init
     * script breaks these tests loudly instead of silently no-op-ing the dogfood telemetry
     * through the script's graceful-degrade path (093 review finding).
     */
    private fun rootDefaultVersion(): String {
        val rootBuildScript = initScript().parentFile.parentFile.resolve("build.gradle.kts")
        assertTrue(rootBuildScript.isFile, "root build script not found next to .github: $rootBuildScript")
        val match = Regex("""\.getOrElse\("([^"]+)"\)""").find(rootBuildScript.readText())
        return requireNotNull(match?.groupValues?.get(1)) {
            "could not parse the default development version from $rootBuildScript"
        }
    }

    /**
     * A fresh local-Maven-repo dir under the reclaimed-by-`clean` TestKit root (plan 049/092
     * rationale): the lingering TestKit daemon keeps the init-script classpath JAR open, so the
     * repo must never live inside the JUnit `@TempDir`, whose post-test deletion would hit the
     * locked file on Windows.
     */
    private fun newLocalRepo(): File {
        val root = File(System.getProperty("buildhound.testkit.root") ?: System.getProperty("java.io.tmpdir"))
        root.mkdirs()
        return Files.createTempDirectory(root.toPath(), "dogfood-m2-").toFile()
    }

    /**
     * Lay out the published plugin JAR as `mavenLocal()` content at the exact coordinates the
     * init script resolves. The JAR is the real shadow publication from the release-test
     * repository (same artifact PortalPublicationFunctionalTest validates); the POM is a minimal
     * synthetic one because the published POM/module pair references timestamped snapshot file
     * names that do not exist in a local-repo layout. Publication *shape* is not under test here
     * — the injection path is.
     */
    private fun installPluginInto(localRepo: File) {
        val repository = File(requireNotNull(System.getProperty("buildhound.release-test-repository")))
        val releaseVersion = requireNotNull(System.getProperty("buildhound.release-version"))
        val moduleDirectory = repository.resolve("dev/buildhound/buildhound-gradle-plugin/$releaseVersion")
        // cleanReleaseTestRepository runs before every publish, so exactly one unclassified JAR exists.
        val publishedJar = moduleDirectory.listFiles().orEmpty().single {
            it.name.endsWith(".jar") && !it.name.contains("-sources") && !it.name.contains("-javadoc")
        }

        // Laid out at the ROOT default version (the coordinates the init script must resolve),
        // regardless of the version the release-test publication happened to be built as.
        val version = rootDefaultVersion()
        val target = File(localRepo, "dev/buildhound/buildhound-gradle-plugin/$version")
        check(target.mkdirs()) { "could not create $target" }
        publishedJar.copyTo(File(target, "buildhound-gradle-plugin-$version.jar"))
        File(target, "buildhound-gradle-plugin-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.buildhound</groupId>
              <artifactId>buildhound-gradle-plugin</artifactId>
              <version>$version</version>
            </project>
            """.trimIndent(),
        )
    }

    private fun setUpProject() {
        // Deliberately NO plugins block: only the init script may bring the plugin in.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "dogfood-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            tasks.register("hello") {
                doLast { println("hello from dogfood fixture") }
            }
            """.trimIndent(),
        )
    }

    private fun runner(localRepo: File, extraEnv: Map<String, String>, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            // No withPluginClasspath(): resolution must come from the init script's mavenLocal
            // lookup, exactly as in CI. Fresh daemon so the injected environment is really read.
            .freshDaemon()
            .withEnvironment(neutralCiEnv() + extraEnv)
            .withArguments(
                *arguments,
                "-I",
                initScript().absolutePath,
                // Redirects mavenLocal() AND the init script's stage-1 presence check. A -D CLI
                // arg is Windows-safe (never written into a gradle.properties file).
                "-Dmaven.repo.local=${localRepo.absolutePath}",
                testkitCcFlag(),
            )

    @Test
    fun `init script applies the plugin from the local repo and writes a payload`() {
        setUpProject()
        val localRepo = newLocalRepo()
        installPluginInto(localRepo)

        val result = runner(
            localRepo,
            // Not CI markers (CI detection stays off) — just the two tag sources the script reads.
            mapOf("GITHUB_JOB" to "dogfood-test-job", "GITHUB_EVENT_NAME" to "dogfood-test-event"),
            "hello",
            // --info surfaces the UploadGate skip reason, proving no upload path was armed.
            "--info",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(result.output.contains("[buildhound] build "), "plugin summary missing:\n${result.output}")
        assertTrue(
            result.output.contains("upload skipped: no server configured"),
            "credential-free run must skip upload via the gate:\n${result.output}",
        )

        val payloadFile = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(payloadFile.isFile, "expected payload at $payloadFile")
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), payloadFile.readText())
        assertEquals("dogfood-fixture", payload.projectKey)
        assertEquals("dogfood-test-job", payload.tags["ci.job"], "tags: ${payload.tags}")
        assertEquals("dogfood-test-event", payload.tags["ci.trigger"], "tags: ${payload.tags}")
    }

    @Test
    fun `init script version literal matches the root build default`() {
        // The init script's stage-1 block must stay self-contained, so it carries its own copy
        // of the default development version. This pins that copy to the root build script's
        // fallback: bumping one without the other fails here with a direct message instead of
        // the indirect "plugin summary missing" failure of the activation test above.
        val version = rootDefaultVersion()
        assertTrue(
            initScript().readText().contains("val version = \"$version\""),
            "the init script's version literal drifted from the root default ($version) — " +
                "update .github/buildhound-dogfood.init.gradle.kts",
        )
    }

    @Test
    fun `missing local publication degrades to a warn and never fails the build`() {
        setUpProject()
        // An empty repo: the stage-1 presence check must skip the classpath, the body must warn.
        val localRepo = newLocalRepo()

        val result = runner(localRepo, emptyMap(), "hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(
            result.output.contains("dogfood init script: dev.buildhound is not in the local Maven repository"),
            "expected the graceful-skip warn:\n${result.output}",
        )
        assertFalse(result.output.contains("[buildhound] build "), "no telemetry may run without the plugin")
        assertFalse(
            File(projectDir, "build/buildhound/build-payload.json").exists(),
            "no payload may be written without the plugin",
        )
    }
}
