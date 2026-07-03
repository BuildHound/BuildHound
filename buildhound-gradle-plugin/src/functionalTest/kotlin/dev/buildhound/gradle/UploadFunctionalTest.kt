package dev.buildhound.gradle

import com.sun.net.httpserver.HttpServer
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class UploadFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<Pair<String?, BuildPayload>>()

    @Volatile
    private var respondWith: Int = 202

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/builds") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val body = GZIPInputStream(exchange.requestBody).readBytes().decodeToString()
            val code = if (body.contains("\"poison\"")) 400 else respondWith
            if (code in 200..299) {
                received += auth to BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), body)
            }
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun serverUrl() = "http://127.0.0.1:${server.address.port}"

    private fun fixtureHome(): File = File(projectDir, "home").apply { mkdirs() }

    private fun setUpProject(url: String, extraDsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("dev.buildhound")
            }

            rootProject.name = "upload-fixture"

            buildhound {
                server {
                    url = "$url"
                    token = providers.gradleProperty("bhTestToken")
                }
                $extraDsl
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            tasks.register("hello") {
                doLast { println("hello") }
            }
            """.trimIndent(),
        )
    }

    // Inner-build CC mode injected from `buildhound.testkit.cc` (plan 021); no upload test
    // pins CC semantics, so the whole suite runs under both models.
    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, testkitCcFlag())

    /**
     * CI mode is forced via the DSL (`mode = CI`) — recorded plan divergence; the
     * gradleProperty token wiring is TEST-ONLY (production wires
     * `providers.environmentVariable`, see architecture §6).
     */
    private fun ciArgs(vararg extra: String) = arrayOf(
        "hello", "-PbhTestToken=test-token-123",
        // Pin the opt-in marker to the fixture: the gate must never read the
        // developer's real ~/.buildhound/optin from a test (buildhound.optin.file
        // is the documented override, plan 008).
        "-Pbuildhound.optin.file=${File(fixtureHome(), ".buildhound/optin").absolutePath}",
    ) + extra

    private fun spoolDir() = File(projectDir, "build/buildhound/spool")

    @Test
    fun `ci mode uploads a gzip payload with the bearer token`() {
        setUpProject(serverUrl(), extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.CI")

        val result = runner(*ciArgs()).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(result.output.contains("[buildhound] payload uploaded"), result.output)
        assertEquals(1, received.size)
        val (auth, payload) = received.single()
        assertEquals("Bearer test-token-123", auth)
        assertEquals("upload-fixture", payload.projectKey)
        assertTrue(payload.tasks.any { it.path == ":hello" })
        assertTrue(!spoolDir().exists() || spoolDir().listFiles().isNullOrEmpty(), "nothing should be spooled")
    }

    @Test
    fun `unreachable server spools and the next build drains`() {
        // A port from a started-then-stopped server is very likely closed.
        val deadPort = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            .also { it.start() }.let { srv -> srv.address.port.also { srv.stop(0) } }
        setUpProject("http://127.0.0.1:$deadPort", extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.CI")

        val firstRun = runner(*ciArgs()).build()

        assertEquals(TaskOutcome.SUCCESS, firstRun.task(":hello")?.outcome, "dead server must never fail the build")
        assertTrue(firstRun.output.contains("payload spooled"), firstRun.output)
        assertEquals(1, spoolDir().listFiles()?.size ?: 0)

        // Point the fixture at the live server; the spooled payload drains, then the new one uploads.
        setUpProject(serverUrl(), extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.CI")
        val secondRun = runner(*ciArgs()).build()

        assertTrue(secondRun.output.contains("drained 1 spooled payload"), secondRun.output)
        assertEquals(2, received.size)
        assertTrue(spoolDir().listFiles().isNullOrEmpty(), "spool must be empty after draining")
    }

    @Test
    fun `local mode without opt-in never uploads`() {
        setUpProject(serverUrl(), extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.LOCAL")

        val result = runner(*ciArgs()).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertEquals(0, received.size, "local build without opt-in must not upload")
        assertTrue(File(projectDir, "build/buildhound/build-payload.json").isFile, "payload still written locally")
    }

    @Test
    fun `local mode uploads when the opt-in marker exists`() {
        File(fixtureHome(), ".buildhound").mkdirs()
        File(fixtureHome(), ".buildhound/optin").writeText("")
        setUpProject(serverUrl(), extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.LOCAL")

        runner(*ciArgs()).build()

        assertEquals(1, received.size, "opt-in marker must enable local upload")
    }

    // NOTE: the without-opt-in test relies on the pinned marker path NOT existing.

    @Test
    fun `server errors spool but rejections drop`() {
        setUpProject(serverUrl(), extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.CI")

        respondWith = 503
        val transient = runner(*ciArgs()).build()
        assertTrue(transient.output.contains("payload spooled"), transient.output)
        assertEquals(1, spoolDir().listFiles()?.size ?: 0, "5xx must spool")

        respondWith = 400
        val rejected = runner(*ciArgs()).build()
        assertTrue(rejected.output.contains("rejected"), rejected.output)
        // The 400 also rejected the drained spool file — both are gone, nothing new spooled.
        assertTrue(spoolDir().listFiles().isNullOrEmpty(), "4xx must drop, not spool")
    }

    @Test
    fun `a poisoned spool file does not block younger ones`() {
        setUpProject(serverUrl(), extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.CI")
        spoolDir().mkdirs()
        val poison = minimalPayload("poison")
        val good = minimalPayload("good-spooled-build")
        File(spoolDir(), "aaa-poison.json.gz").writeBytes(gzip(poison))
        Thread.sleep(1100) // distinct lastModified so drain order is deterministic
        File(spoolDir(), "bbb-good.json.gz").writeBytes(gzip(good))

        val result = runner(*ciArgs()).build()

        assertTrue(result.output.contains("rejected spooled payload"), result.output)
        assertTrue(spoolDir().listFiles().isNullOrEmpty(), "poison dropped, good drained")
        assertTrue(received.any { it.second.buildId == "good-spooled-build" }, "younger spool file must still drain")
        assertEquals(2, received.size, "good spool + current build")
    }

    private fun gzip(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) }
        return out.toByteArray()
    }

    private fun minimalPayload(buildId: String): String =
        "{\"buildId\":\"" + buildId + "\",\"startedAt\":0,\"finishedAt\":1,\"outcome\":\"SUCCESS\"}"

    @Test
    fun `local mode uploads when the opt-in requirement is lifted`() {
        setUpProject(
            serverUrl(),
            extraDsl = """
                mode = dev.buildhound.gradle.TelemetryMode.LOCAL
                localBuilds { requireOptInFile = false }
            """.trimIndent(),
        )

        runner(*ciArgs()).build()

        assertEquals(1, received.size)
    }
}
