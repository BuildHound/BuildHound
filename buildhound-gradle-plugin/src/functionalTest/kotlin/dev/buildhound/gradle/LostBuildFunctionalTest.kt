package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
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

/** Lost-build accounting (plan 033): start-marker write/reconcile, CC-safe, never-fail. */
class LostBuildFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var server: HttpServer

    /** Decompressed bodies of the payloads the stub ingest server received. */
    private val received = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/builds") { exchange ->
            received += GZIPInputStream(exchange.requestBody).readBytes().decodeToString()
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun serverUrl() = "http://127.0.0.1:${server.address.port}"

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*arguments, "--configuration-cache")

    private fun setUpPlainProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "lost-build-fixture"
            buildhound {
                // seedMarker writes "mode":"local", and `requireOptInFile` defaults to true — so
                // without this the seeded markers are blocked by CONSENT, not by the missing server,
                // and the retention cases below would silently stop testing retention. The
                // consent path has its own cases that set this deliberately.
                localBuilds { requireOptInFile = false }
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
    }

    /**
     * Same fixture, but able to publish: an ingest target plus the local-build opt-out the samples
     * use. The seeded markers carry `"mode":"local"`, so without `requireOptInFile = false` the gate
     * would skip on consent rather than on configuration and the retention path would never run.
     */
    private fun setUpPublishingProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "lost-build-fixture"
            buildhound {
                server { url = "${serverUrl()}" }
                localBuilds { requireOptInFile = false }
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
    }

    private fun startedDir() = File(projectDir, "build/buildhound/started")
    private fun interruptedDir() = File(projectDir, "build/buildhound/interrupted")

    /** Seed a stale marker as if a prior build died before finalizing; startedAt is recent (within TTL). */
    private fun seedMarker(buildId: String, startedAtMs: Long = System.currentTimeMillis() - 60_000) {
        startedDir().mkdirs()
        File(startedDir(), "$buildId.json").writeText(
            """{"buildId":"$buildId","startedAtMs":$startedAtMs,"mode":"local","projectKey":"prior","requestedTasks":["build"]}""",
        )
    }

    @Test
    fun `a normal build leaves no start-marker behind`() {
        setUpPlainProject()
        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        // The collector writes this build's marker mid-build; the finalizer deletes its own at the end.
        val leftover = startedDir().listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        assertTrue(leftover.isEmpty(), "a finalized build must leave no marker: ${leftover.map { it.name }}")
    }

    /**
     * Plan 110 changed this contract deliberately. It used to assert the marker was **deleted** here.
     * With no server the payload could not be published, so the marker now survives to drive a retry
     * on a later build that can — see [a retained marker is published by a later build that has a
     * server]. The local mirror is still written on this first visit, so "a lost build is visible
     * even with no server" is unchanged.
     */
    @Test
    fun `a stale marker is reconciled locally and kept when there is no server`() {
        setUpPlainProject()
        seedMarker("seeded-dead")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(
            File(startedDir(), "seeded-dead.json").exists(),
            "a marker that could not be published must survive for a later build",
        )
        val interrupted = File(interruptedDir(), "seeded-dead.json")
        assertTrue(interrupted.isFile, "a local INTERRUPTED payload must be written for the lost build")
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), interrupted.readText())
        assertEquals(BuildOutcome.INTERRUPTED, payload.outcome)
        assertEquals("seeded-dead", payload.buildId)
        assertTrue(payload.tasks.isEmpty())
    }

    /** The whole point of the retention: the lost build reaches the server on a later build. */
    @Test
    fun `a retained marker is published by a later build that has a server`() {
        setUpPlainProject()
        seedMarker("seeded-dead")
        runner("hello").build()
        assertTrue(File(startedDir(), "seeded-dead.json").exists(), "precondition: the marker was retained")
        assertTrue(received.isEmpty(), "precondition: nothing was published without a server")

        setUpPublishingProject()
        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(
            File(startedDir(), "seeded-dead.json").exists(),
            "once published, the marker is deleted",
        )
        val interrupted = received.filter { it.contains("\"buildId\":\"seeded-dead\"") }
        assertEquals(1, interrupted.size, "the lost build must be uploaded exactly once, got: $received")
        assertTrue(interrupted.single().contains("INTERRUPTED"), interrupted.single())
    }

    /**
     * The consent path is untouched. A `LOCAL` build with the opt-in marker required and absent is a
     * decision not to publish, not a configuration gap: the marker is dropped as before, so creating
     * `~/.buildhound/optin` later can never retroactively publish a build recorded before it
     * (spec §3.7).
     */
    @Test
    fun `a marker skipped for a missing local opt-in is still deleted`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "lost-build-fixture"
            buildhound {
                server { url = "${serverUrl()}" }
                localBuilds { requireOptInFile = true }
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
        seedMarker("no-consent")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(
            File(startedDir(), "no-consent.json").exists(),
            "a consent skip must still consume the marker",
        )
        assertTrue(received.isEmpty(), "nothing may be published without the opt-in marker, got: $received")
    }

    /**
     * The privacy invariant plan 110 §4.2 claims: a build the consent rule would have blocked must
     * never become publishable later.
     *
     * The trap is that `UploadGate.decide` reports the FIRST blocker. Offline — the plugin's default
     * — a `LOCAL` build with no `~/.buildhound/optin` is blocked by *both* the missing server and the
     * missing consent, and if the missing server is the one reported, retention keeps the marker. A
     * developer who later configures a server and opts in would then publish builds recorded before
     * they ever consented.
     */
    @Test
    fun `an offline build the consent rule would block is not published after a later opt-in`() {
        // Run 1: no server, and `requireOptInFile` at its DEFAULT of true — not setUpPlainProject(),
        // which opts out of it precisely so the retention cases stay about the server. This fixture
        // is the plugin's stock configuration, which is the whole point of the scenario.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "lost-build-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
        seedMarker("pre-consent")
        runner("hello").build()
        assertFalse(
            File(startedDir(), "pre-consent.json").exists(),
            "a marker that consent would also have blocked must not be retained",
        )

        // Run 2: the developer onboards — a server appears AND they create the opt-in marker.
        val optIn = File(projectDir, "optin-marker").apply { writeText("") }
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "lost-build-fixture"
            buildhound {
                server { url = "${serverUrl()}" }
                localBuilds { requireOptInFile = true }
            }
            """.trimIndent(),
        )
        // -P rather than gradle.properties: an absolute path in a properties file is
        // escape-mangled on Windows.
        val result = runner("hello", "-Pbuildhound.optin.file=${optIn.invariantSeparatorsPath}").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(
            received.none { it.contains(""""buildId":"pre-consent"""") },
            "a build recorded before consent existed must never be published, got: $received",
        )
    }

    /**
     * A retained marker is re-visited by every later build, so the mirror must not be re-synthesized
     * and rewritten each time — that cost would land on the always-on finalizer path in the plugin's
     * default (offline) configuration (plan 110 §4.3).
     */
    @Test
    fun `a retained marker does not rewrite its local mirror on later builds`() {
        setUpPlainProject()
        seedMarker("seeded-dead")
        runner("hello").build()
        val mirror = File(interruptedDir(), "seeded-dead.json")
        assertTrue(mirror.isFile, "precondition: the mirror was written")
        // A sentinel rather than mtime: filesystem timestamp granularity makes mtime flaky, and this
        // proves the exact property — the file is not written again.
        mirror.writeText(mirror.readText() + "\n")
        val sentinel = mirror.readText()

        runner("hello").build()

        assertTrue(File(startedDir(), "seeded-dead.json").exists(), "still retained")
        assertEquals(sentinel, mirror.readText(), "the mirror must be reused, not rewritten")
    }

    @Test
    fun `marker IO does not invalidate the configuration cache`() {
        setUpPlainProject()
        val store = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, store.task(":hello")?.outcome)
        val reuse = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, reuse.task(":hello")?.outcome)
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
    }

    @Test
    fun `a corrupt marker never fails the build and is dropped`() {
        setUpPlainProject()
        startedDir().mkdirs()
        File(startedDir(), "corrupt.json").writeText("this is not valid json {{{")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "a corrupt marker must never fail the build")
        assertFalse(File(startedDir(), "corrupt.json").exists(), "an unparseable marker is dropped, not retried forever")
    }
}
