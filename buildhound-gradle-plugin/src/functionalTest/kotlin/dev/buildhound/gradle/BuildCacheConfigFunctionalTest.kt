package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Build-cache configuration snapshot capture (plan 067, research F17): the committed `Settings.buildCache`
 * block read via public API after settings evaluation, replayed unchanged on a config-cache hit, and — the
 * plan's named hard constraint (spec §3.7) — carrying only booleans + a normalized backend type, never the
 * remote URL or a cache directory path.
 */
class BuildCacheConfigFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    /**
     * A stable Gradle User Home shared by every run in a test (the [InvocationFunctionalTest]
     * pattern): [freshDaemon] rotates the TestKit dir per call, and without an explicit `-g` that
     * rotating dir IS the GUH — so a store→hit pair would run under two different GUHs and the
     * second run finds no configuration-cache entry at all ("no cached configuration is available").
     */
    @field:TempDir
    lateinit var guhDir: File

    /** A distinctive, never-contacted remote URL: no `--build-cache`, no cacheable task, so nothing dials it. */
    private val remoteHost = "buildcache.internal.example"
    private val remoteUrl = "http://$remoteHost:5071/cache/"

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .freshDaemon()
            .withArguments(*arguments, "-g", guhDir.absolutePath)

    private fun payloadFile(): File = File(projectDir, "build/buildhound/build-payload.json")

    private fun readPayload(): BuildPayload {
        val file = payloadFile()
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun buildScript() {
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
    }

    private fun settingsWithRemoteCache() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            import org.gradle.caching.http.HttpBuildCache
            plugins { id("dev.buildhound") }
            rootProject.name = "buildcache-fixture"
            buildCache {
                local { isEnabled = true }
                remote<HttpBuildCache> {
                    url = uri("$remoteUrl")
                    isEnabled = true
                    isPush = true
                }
            }
            """.trimIndent(),
        )
        buildScript()
    }

    private fun settingsWithNoCacheBlock() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "buildcache-fixture"
            """.trimIndent(),
        )
        buildScript()
    }

    /** Fails if the remote URL, its host, or a cache-location accessor leaked anywhere into the payload JSON. */
    private fun assertNoUrlOrPathLeak() {
        val raw = payloadFile().readText()
        assertTrue(!raw.contains(remoteHost), "remote-cache host must never reach the payload (spec §3.7)")
        assertTrue(!raw.contains("://"), "no remote-cache URL may reach the payload (spec §3.7)")
        assertTrue(!raw.contains("5071"), "no remote-cache port may reach the payload")
        assertTrue(!raw.contains("getUrl") && !raw.contains("getDirectory"), "no cache-location accessor output may leak")
    }

    @Test
    fun `a configured remote HttpBuildCache is captured as enabled, push, and the normalized type`() {
        settingsWithRemoteCache()

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)

        val buildCache = readPayload().environment?.buildCache ?: error("expected environment.buildCache")
        assertEquals(true, buildCache.localEnabled)
        assertEquals(true, buildCache.remoteEnabled)
        assertEquals(true, buildCache.remotePush)
        assertEquals("HttpBuildCache", buildCache.remoteType, "the normalized backend simpleName, not a decorated subclass")
        assertNoUrlOrPathLeak()
    }

    @Test
    fun `a build with no buildCache block reports no configured remote and leaks nothing`() {
        settingsWithNoCacheBlock()

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)

        val buildCache = readPayload().environment?.buildCache ?: error("expected environment.buildCache")
        // No remote backend configured at all → the three remote fields stay null (the finding's
        // "0% hit: no remote cache configured" signal), while local stays Gradle's default-enabled.
        assertNull(buildCache.remoteEnabled, "no remote backend configured → remoteEnabled null")
        assertNull(buildCache.remotePush)
        assertNull(buildCache.remoteType)
        assertEquals(true, buildCache.localEnabled, "the local cache is enabled by default in Gradle")
        val raw = payloadFile().readText()
        assertTrue(!raw.contains("://"), "no URL may reach the payload even with no remote configured")
    }

    @Test
    fun `the snapshot replays unchanged on a config-cache hit`() {
        settingsWithRemoteCache()

        val first = runner("hello", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":hello")?.outcome, first.output)
        val stored = readPayload().environment?.buildCache ?: error("expected environment.buildCache on the miss")

        val second = runner("hello", "--configuration-cache").build()
        assertTrue(
            second.output.lineSequence().any { it.startsWith("[buildhound] build ") && it.contains("cc=HIT") },
            second.output,
        )
        val replayed = readPayload().environment?.buildCache ?: error("expected environment.buildCache on the hit")
        // Cache configuration is stable across builds, so the baked snapshot replays byte-for-byte —
        // enabled/push/type all identical to the store-time capture (unlike per-build timings).
        assertEquals(stored, replayed, "the build-cache snapshot must replay unchanged from the CC entry")
        assertEquals("HttpBuildCache", replayed.remoteType)
        assertNoUrlOrPathLeak()
    }
}
