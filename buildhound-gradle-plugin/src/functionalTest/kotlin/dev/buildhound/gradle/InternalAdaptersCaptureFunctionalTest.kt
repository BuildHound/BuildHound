package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir

/**
 * Internal-adapters capture, driven from the **single** core plugin + the **single** config block
 * (plan 051). Since the module is bundled onto the core plugin's classpath (`implementation`), a real
 * `logger.warn`, a real Gradle deprecation, and real cache operations flow through the actual daemon
 * listeners when a `buildhound { internalAdapters { } }` toggle is on — and through **nothing** when the
 * block is absent. The test JVM only reads the resulting payload JSON (parsed generically), so it
 * couples to no Gradle-internal or plugin class.
 *
 * The keystone case is [`applying the plugin with no internalAdapters block captures nothing`]: it is
 * the proof that *applying the plugin is not consent to use internal Gradle APIs* — the reversal this
 * plan makes defensible.
 */
class InternalAdaptersCaptureFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args)
    // No freshDaemon(): a warm daemon is safe for most cases because the wiring resets the daemon-static
    // toggles on every CC-miss build and the collector reads-and-clears the accumulator each build.

    /**
     * A runner pinned to [dir] so multiple builds of ONE test share a single, freshly-minted daemon.
     * The master-switch test below needs that: it asserts one build's would-be capture doesn't leak into
     * the next *in the same daemon*, but must not inherit `collectLogWarnings=true` bled from another test
     * via the known plan-052 CC-hit toggle-bleed (which would otherwise make it flaky).
     */
    private fun runnerIn(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args)
            .withTestKitDir(dir)

    /** A fresh, per-test-instance TestKit dir (⇒ a fresh uncontaminated daemon), shared across its builds. */
    private val freshSharedTestKitDir: File by lazy {
        val root = File(System.getProperty("buildhound.testkit.root") ?: System.getProperty("java.io.tmpdir"))
        root.mkdirs()
        Files.createTempDirectory(root.toPath(), "ia-master-").toFile()
    }

    /**
     * @param internalAdapters the body of the `internalAdapters { }` block, or null to omit the block
     *   entirely (the default no-opt-in shape).
     * @param enabled the master switch; false exercises the "telemetry off ⇒ no capture, no stale leak" path.
     */
    private fun setUp(internalAdapters: String?, enabled: Boolean = true) {
        val block = internalAdapters?.let { "internalAdapters {\n$it\n}" } ?: ""
        val enabledLine = if (!enabled) "enabled = false" else ""
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "ia-fixture"
            buildhound {
                $enabledLine
                $block
            }
            """.trimIndent(),
        )
        // Fixture tasks: `warn` emits a real WARN log line (with a secret + out-of-project path, to
        // prove scrubbing) and is configuration-cache-safe (only touches its own `logger`); `deprecate`
        // triggers a real Gradle deprecation by reading project state at execution time — which is itself
        // a CC violation, so it is a separate task the CC cases never invoke. `compileJava` (the java
        // plugin) is the cacheable task for cache-origin capture under --build-cache.
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { java }
            tasks.register("warn") {
                doLast {
                    logger.warn("custom build warning secret=abc123XYZ456 outside /home/secret/creds.txt")
                }
            }
            tasks.register("deprecate") {
                doLast {
                    @Suppress("DEPRECATION")
                    logger.lifecycle("buildDir=" + project.buildDir)
                }
            }
            """.trimIndent(),
        )
        File(projectDir, "src/main/java/com/example/Foo.java").apply {
            parentFile.mkdirs()
            writeText("package com.example; public class Foo { public int x() { return 1; } }")
        }
    }

    private data class Block(val logWarnings: List<String>, val deprecations: List<String>, val tasks: Int)

    /** Reads `extensions.internalAdapters` generically, or null when the key is absent. */
    private fun block(): Block? {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected a BuildHound payload at $file")
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
        val obj = payload.extensions["internalAdapters"]?.jsonObject ?: return null
        fun arr(key: String) = obj[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return Block(
            logWarnings = arr("logWarnings"),
            deprecations = arr("deprecations"),
            tasks = obj["tasks"]?.jsonArray?.size ?: 0,
        )
    }

    @Test
    fun `applying the plugin with no internalAdapters block captures nothing and touches no internal API`() {
        setUp(internalAdapters = null)
        val result = runner("warn", "compileJava", "--build-cache", "--warning-mode=all").build()

        // No extensions.internalAdapters key at all — the collector's accumulator stayed empty.
        assertTrue(block() == null, "no internalAdapters block must be produced without an opt-in")
        // And no internal-API risk notice was printed — nothing consented, nothing warned.
        assertTrue(
            !result.output.contains("reads internal Gradle APIs"),
            "no internal-API risk notice without an opt-in: ${result.output}",
        )
    }

    @Test
    fun `both warning catchers capture scrubbed entries and print the internal-API notice`() {
        setUp(
            internalAdapters = """
                collectDeprecations = true
                collectLogWarnings = true
            """.trimIndent(),
        )
        val result = runner("warn", "deprecate", "--warning-mode=all").build()

        assertTrue(
            result.output.contains("reads internal Gradle APIs") && result.output.contains("collectLogWarnings"),
            "expected the internal-API risk notice: ${result.output}",
        )
        val b = block() ?: error("expected an internalAdapters block")
        // logger.warn captured (proves the LoggingOutputInternal listener is wired) and scrubbed.
        assertTrue(b.logWarnings.any { it.contains("custom build warning") }, "logger.warn captured: ${b.logWarnings}")
        assertTrue(b.logWarnings.none { it.contains("abc123XYZ456") }, "secret scrubbed: ${b.logWarnings}")
        assertTrue(b.logWarnings.none { it.contains("/home/secret") }, "out-of-project path scrubbed: ${b.logWarnings}")
        // A real Gradle deprecation on its own stream (proves progress() extraction works).
        assertTrue(b.deprecations.isNotEmpty(), "a real deprecation was captured: ${b.deprecations}")
    }

    @Test
    fun `a disabled build captures nothing and leaves no stale rows for a later enabled build`() {
        // Build 1: master switch OFF but a toggle ON in the DSL. Telemetry off ⇒ the finalizer
        // short-circuits before the read-and-clear collector, so if the wiring captured here the rows
        // would survive in the daemon-static accumulator. The master gate must force the toggle off so
        // nothing is registered or captured. Disabled builds write no payload, so nothing to read yet.
        setUp(internalAdapters = "collectCacheOrigins = true", enabled = false)
        runnerIn(freshSharedTestKitDir, "compileJava", "--build-cache").build()

        // Build 2 (same daemon, same projectDir): master ON, every toggle OFF. Must not emit an
        // internalAdapters block from build 1's would-be capture (the master-switch stale-leak guard).
        setUp(internalAdapters = null)
        runnerIn(freshSharedTestKitDir, "compileJava", "--build-cache").build()
        assertTrue(block() == null, "a disabled prior build must leave no stale internalAdapters rows")
    }

    @Test
    fun `collectCacheOrigins captures task cache rows`() {
        setUp(internalAdapters = "collectCacheOrigins = true")
        runner("compileJava", "--build-cache").build()

        val b = block() ?: error("expected an internalAdapters block with cache origins on")
        assertTrue(b.tasks >= 1, "a cacheable task's cache row is captured: ${b.tasks}")
    }

    @Test
    fun `enabling only a warning catcher captures no cache telemetry`() {
        setUp(internalAdapters = "collectDeprecations = true")
        runner("deprecate", "compileJava", "--build-cache", "--warning-mode=all").build()

        val b = block() ?: error("expected an internalAdapters block with deprecations on")
        assertTrue(b.deprecations.isNotEmpty(), "the deprecation is captured: ${b.deprecations}")
        // Trap-2 guard: the cache data paths must stay gated on collectCacheOrigins, so no task rows leak.
        assertTrue(b.tasks == 0, "no cache rows when only a warning catcher is on: ${b.tasks}")
    }

    @Disabled(
        "Known limitation, deferred to plan 052: CC-hit warm-daemon toggle-bleed. On a configuration-" +
            "cache HIT the plugin's whenReady (and thus the toggle-resetting configure()) does not run, so " +
            "a daemon-static listener registered by an earlier toggle-on build keeps capturing on a later " +
            "all-off build that reuses a pre-toggle CC entry. A proper fix must re-establish the current " +
            "build's toggle intent at execution time (CC-surviving), which is its own change (plan 052). " +
            "This test pins the exact scenario and is the acceptance criterion for that fix.",
    )
    @Test
    fun `an all-off CC-hit build after a toggle-on build in the same daemon must not capture (plan 052)`() {
        setUp(internalAdapters = null) // stable settings; the toggle is varied via -P so CC keys differ
        // Build 1: all off → store an all-off CC entry.
        runner("warn", "--configuration-cache", "--warning-mode=all").build()
        assertTrue(block() == null, "build 1 (all off) captures nothing")
        // Build 2 (same daemon): toggle ON via a property override (a distinct CC key) → registers the
        // daemon-static WARN listener that persists for the daemon's life.
        runner(
            "warn", "--configuration-cache", "--warning-mode=all",
            "-Pbuildhound.internalAdapters.collectLogWarnings=true",
        ).build()
        assertTrue(block()?.logWarnings?.isNotEmpty() == true, "build 2 (toggle on) captures")
        // Build 3: all off again → CC HIT on build 1's entry → whenReady skipped → the daemon-static
        // toggle is never reset to off, so the lingering listener captures. DESIRED: no capture.
        val reuse = runner("warn", "--configuration-cache", "--warning-mode=all").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(block() == null, "an all-off CC-hit build must not capture from a prior toggle-on build")
    }

    @Test
    fun `capture survives a configuration-cache store then reuse (same toggle state)`() {
        setUp(internalAdapters = "collectLogWarnings = true")
        val store = runner("warn", "--configuration-cache", "--warning-mode=all").build()
        assertTrue(block()?.logWarnings?.isNotEmpty() == true, "captured on the store run")

        // The reuse run is a CC hit (whenReady skipped) with the SAME toggle state — capture rides the
        // daemon-static listener registered on the store run (the plan-044 "survives a CC hit" design).
        val reuse = runner("warn", "--configuration-cache", "--warning-mode=all").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
        assertTrue(block()?.logWarnings?.isNotEmpty() == true, "capture continues on the CC-hit reuse run")
    }
}
