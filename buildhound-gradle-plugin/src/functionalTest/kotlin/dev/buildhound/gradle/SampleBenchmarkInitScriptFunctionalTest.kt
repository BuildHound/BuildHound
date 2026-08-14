package dev.buildhound.gradle

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Guards the sample-pilot CI injection path (plan 105): the REAL
 * `.github/buildhound-sample-benchmark.init.gradle.kts` (resolved via the
 * `buildhound.sample.init-script` system property, never a copy) is applied with `-I` to a fixture
 * that configures `buildhound { server { url = ... } }` ITSELF — the samples' shape, and the reason
 * the dogfood script's `beforeSettings` hook cannot do this job.
 *
 * Every failure path in that script is a `runCatching`-swallowed reflection error that degrades to a
 * warn, so a rename of `BuildHoundExtension.server` / `ServerSpec.url` / `.token` would silently turn
 * it into a no-op: green CI, and the regression only visible as missing rows on the production
 * dashboard after a scheduled run. These tests make shape drift fail at PR time instead — the same
 * protection [DogfoodInitScriptFunctionalTest] gives the root-build path.
 */
class SampleBenchmarkInitScriptFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var server: HttpServer

    /** (Authorization header, decompressed body) per received upload. */
    private val received = CopyOnWriteArrayList<Pair<String?, String>>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/builds") { exchange ->
            received += exchange.requestHeaders.getFirst("Authorization") to
                GZIPInputStream(exchange.requestBody).readBytes().decodeToString()
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun injectedUrl() = "http://127.0.0.1:${server.address.port}"

    private fun initScript(): File {
        val script = File(requireNotNull(System.getProperty("buildhound.sample.init-script")))
        assertTrue(script.isFile, "the real sample benchmark init script must exist: $script")
        return script
    }

    /**
     * The samples' shape: the plugin is applied by the project itself and its `server { }` block
     * names a localhost demo target. The init script has to beat that literal, which is exactly what
     * a `beforeSettings` hook cannot do.
     */
    private fun setUpProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("dev.buildhound")
            }

            rootProject.name = "sample-init-fixture"

            buildhound {
                server {
                    url = "http://127.0.0.1:1"
                    token = providers.environmentVariable("BUILDHOUND_TOKEN")
                        .orElse("committed-local-dev-token")
                }
                // The samples' demo deviation, mirrored: without it this fixture is a LOCAL build
                // and UploadGate skips on the local-opt-in rule before the URL override matters.
                // (The nightly itself runs under CI markers, where that rule does not apply.)
                localBuilds {
                    enabled = true
                    requireOptInFile = false
                }
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            // `base` for the sake of the scaffolding-gate tests: it supplies the real `clean` task a
            // gradle-profiler cleanup invocation runs, which is one of the two task names the init
            // script treats as scaffolding. The samples all have it via their own plugins.
            plugins { base }

            tasks.register("hello") {
                doLast { println("hello from sample init fixture") }
            }
            """.trimIndent(),
        )
    }

    private fun runner(env: Map<String, String>, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            // Fresh daemon so the injected environment is really read, not inherited from a
            // previous test's daemon (the same reason the nightly runs one scenario per job).
            .freshDaemon()
            .withEnvironment(neutralCiEnv() + env)
            .withArguments(*arguments, "-I", initScript().absolutePath, testkitCcFlag())

    @Test
    fun `init script redirects the project's own server config to the injected target`() {
        setUpProject()

        val result = runner(
            mapOf(
                "BUILDHOUND_SAMPLE_SERVER_URL" to injectedUrl(),
                "BUILDHOUND_SAMPLE_TOKEN" to "sample-init-token",
            ),
            "hello",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(
            result.output.contains("telemetry redirected to the BUILDHOUND_SAMPLE_* ingest target"),
            "expected the redirect log line:\n${result.output}",
        )
        assertEquals(1, received.size, "expected exactly one upload at the injected URL, got ${received.size}")
        val (auth, body) = received.single()
        // The token override landed too: the fixture's committed fallback must NOT be what is sent.
        assertEquals("Bearer sample-init-token", auth)
        assertTrue(body.contains("\"projectKey\""), "uploaded body is not a payload: $body")
    }

    /**
     * The URL is gated on the token because `UploadGate` keys on the URL alone and `PayloadUploader`
     * simply omits the `Authorization` header when the token is absent — so a URL-only override
     * would POST the build's telemetry unauthenticated. Setting one env var and forgetting the other
     * is a one-line slip in the documented manual invocation.
     */
    @Test
    fun `url without token disables the upload instead of posting unauthenticated`() {
        setUpProject()

        val result = runner(
            mapOf("BUILDHOUND_SAMPLE_SERVER_URL" to injectedUrl()),
            "hello",
            // --info surfaces the gate's skip reason.
            "--info",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(received.isEmpty(), "no request may be sent without a token, got: $received")
        assertTrue(
            result.output.contains("upload skipped: no server configured"),
            "expected the gate to skip:\n${result.output}",
        )
    }

    /**
     * The stray-`init.d`-copy case: a developer mirrors the CI step locally and forgets to remove
     * the script. Telemetry is disabled (the script overrides the project's own config, and that is
     * intended), but it must say so at `warn` rather than looking like a successful redirect.
     */
    @Test
    fun `no env warns loudly and never fails the build`() {
        setUpProject()

        val result = runner(emptyMap(), "hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(received.isEmpty(), "no request may be sent without an injected target, got: $received")
        assertTrue(
            result.output.contains("are not both set — this build's telemetry upload is DISABLED"),
            "expected the loud warn:\n${result.output}",
        )
    }

    /**
     * gradle-profiler drives more invocations than it measures: a `:help` build to inspect the
     * project, and one `cleanup-tasks` build before each measured build. All of them inherit the
     * nightly's `BUILDHOUND_BENCHMARK_*` env, so all of them would publish as benchmark rows the
     * server cannot distinguish from a measurement — 5 of the 9 payloads in a `clean` cell. The
     * requested task names are what separates them (measured against gradle-profiler 0.24.0:
     * `[:help]`, `[clean]`, and the scenario's own tasks respectively).
     *
     * The upload must be *disabled*, not merely left un-redirected: falling back to the fixture's
     * own URL would arm a POST, and in CI a failed POST is what the workflow's publication check
     * reads as a broken cell.
     */
    @Test
    fun `profiler scaffolding invocations publish nothing`() {
        setUpProject()

        for (scaffolding in listOf("help", "clean")) {
            received.clear()

            val result = runner(
                mapOf(
                    "BUILDHOUND_SAMPLE_SERVER_URL" to injectedUrl(),
                    "BUILDHOUND_SAMPLE_TOKEN" to "sample-init-token",
                ),
                scaffolding,
            ).build()

            assertTrue(
                result.output.contains("is gradle-profiler scaffolding, not a measured build"),
                "expected the scaffolding gate for '$scaffolding':\n${result.output}",
            )
            assertFalse(
                result.output.contains("telemetry redirected to the BUILDHOUND_SAMPLE_* ingest target"),
                "'$scaffolding' must not be redirected:\n${result.output}",
            )
            assertTrue(
                received.isEmpty(),
                "'$scaffolding' is not a measurement and must publish nothing, got: $received",
            )
        }
    }

    /**
     * The other half of the gate: a real measured build must still publish. Without this, a gate
     * that skipped everything would pass the test above and silently reproduce the very defect
     * plan 109 exists to fix.
     */
    @Test
    fun `a measured build is still published when a scaffolding task is only part of the request`() {
        setUpProject()

        val result = runner(
            mapOf(
                "BUILDHOUND_SAMPLE_SERVER_URL" to injectedUrl(),
                "BUILDHOUND_SAMPLE_TOKEN" to "sample-init-token",
            ),
            "clean",
            "hello",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(
            result.output.contains("telemetry redirected to the BUILDHOUND_SAMPLE_* ingest target"),
            "a request that does real work must still be redirected:\n${result.output}",
        )
        assertEquals(1, received.size, "expected one upload, got ${received.size}")
    }

    /** A build that does not apply BuildHound at all: the hook must no-op, never throw. */
    @Test
    fun `build without the plugin is an unguarded no-op`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "no-plugin-fixture"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            tasks.register("hello") {
                doLast { println("hello without buildhound") }
            }
            """.trimIndent(),
        )

        val result = runner(
            mapOf(
                "BUILDHOUND_SAMPLE_SERVER_URL" to injectedUrl(),
                "BUILDHOUND_SAMPLE_TOKEN" to "sample-init-token",
            ),
            "hello",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(
            result.output.contains("sample benchmark init failed"),
            "the no-extension path must not hit the failure branch:\n${result.output}",
        )
        assertTrue(received.isEmpty(), "nothing may be uploaded without the plugin, got: $received")
    }
}
