package dev.buildhound.sharding

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * The sharding addon over a real Test task (plan 040): a stub server returns a plan, and JUnit test
 * classes write marker files so we can see which actually ran — proving the filter, the no-index
 * inert path, the run-all failure fallback, and CC reuse.
 */
class TestShardingFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    /** Start a stub returning [classes] for the shard (+ [assigned] as the full plan). Returns its base URL. */
    private fun stubServer(classes: List<String>, assigned: List<String>): String {
        fun arr(xs: List<String>) = xs.joinToString(",") { "\"$it\"" }
        val json = """{"shardPlanId":"stub123","index":1,"classes":[${arr(classes)}],"assigned":[${arr(assigned)}]}"""
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/addons/test-sharding/plan") { ex ->
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun runner(env: Map<String, String>, vararg args: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withEnvironment(System.getenv().filterKeys { !it.startsWith("BUILDHOUND_") } + env)
            .withArguments(*args, "--configuration-cache")

    private fun setUpProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound.test-sharding") }
            rootProject.name = "sharding-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { java }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
            """.trimIndent(),
        )
        fun testClass(name: String) = File(projectDir, "src/test/java/com/example/$name.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import java.io.File;
                public class $name {
                    @Test public void runs() throws Exception {
                        new File("build/ran").mkdirs();
                        new File("build/ran/$name").createNewFile();
                    }
                }
                """.trimIndent(),
            )
        }
        testClass("FooTest")
        testClass("BarTest")
    }

    private fun ran(name: String) = File(projectDir, "build/ran/$name").exists()

    @Test
    fun `a shard runs only its assigned test classes`() {
        setUpProject()
        val url = stubServer(classes = listOf("com.example.FooTest"), assigned = listOf("com.example.FooTest", "com.example.BarTest"))
        runner(
            mapOf("BUILDHOUND_SHARD_INDEX" to "1", "BUILDHOUND_SHARD_TOTAL" to "2", "BUILDHOUND_SHARD_REFERENCE" to "run-1", "BUILDHOUND_SERVER_URL" to url),
            "test",
        ).build()
        assertTrue(ran("FooTest"), "the assigned class ran")
        assertFalse(ran("BarTest"), "the other shard's class was filtered out")
    }

    @Test
    fun `with no shard index the addon is inert and every test runs`() {
        setUpProject()
        // No BUILDHOUND_SHARD_INDEX → no HTTP, no filter.
        runner(emptyMap(), "test").build()
        assertTrue(ran("FooTest") && ran("BarTest"), "all tests run when sharding is inert")
    }

    @Test
    fun `an unreachable server falls back to running all tests, build stays green`() {
        setUpProject()
        val result = runner(
            mapOf(
                "BUILDHOUND_SHARD_INDEX" to "1", "BUILDHOUND_SHARD_TOTAL" to "2", "BUILDHOUND_SHARD_REFERENCE" to "run-1",
                "BUILDHOUND_SERVER_URL" to "http://127.0.0.1:1", // nothing listening
            ),
            "test",
        ).build()
        assertTrue(ran("FooTest") && ran("BarTest"), "a fetch failure runs all tests (never wrong)")
        assertTrue(result.output.contains("running all tests"), result.output)
    }

    @Test
    fun `the shard filter is applied at execution time so the configuration cache is reused`() {
        setUpProject()
        val url = stubServer(classes = listOf("com.example.FooTest"), assigned = listOf("com.example.FooTest", "com.example.BarTest"))
        val env = mapOf("BUILDHOUND_SHARD_INDEX" to "1", "BUILDHOUND_SHARD_TOTAL" to "2", "BUILDHOUND_SHARD_REFERENCE" to "run-1", "BUILDHOUND_SERVER_URL" to url)
        runner(env, "test").build()
        val reuse = runner(env, "test").build()
        assertTrue(reuse.output.contains("Reusing configuration cache"), reuse.output)
    }
}
