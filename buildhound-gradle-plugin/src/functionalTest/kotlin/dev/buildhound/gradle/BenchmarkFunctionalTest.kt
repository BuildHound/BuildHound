package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/** Benchmark-mode env activation (plan 030): `BUILDHOUND_BENCHMARK_*` → `mode=benchmark`, fail-closed. */
class BenchmarkFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*arguments, "--configuration-cache")

    // withEnvironment replaces the whole env; keep everything except BUILDHOUND_* so the injected
    // benchmark vars alone steer activation (PATH/JAVA_HOME etc. are preserved).
    private fun neutralEnv(): Map<String, String> = System.getenv().filterKeys { !it.startsWith("BUILDHOUND_") }

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun setUpProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "benchmark-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText("""tasks.register("hello") { doLast { println("hello") } }""")
    }

    @Test
    fun `benchmark env activates mode benchmark with the block and mirrored tags`() {
        setUpProject()
        runner("hello").freshDaemon().withEnvironment(
            neutralEnv() + mapOf(
                "BUILDHOUND_BENCHMARK_SCENARIO" to "clean",
                "BUILDHOUND_BENCHMARK_ITERATION" to "2",
                "BUILDHOUND_BENCHMARK_ISOLATION" to "no_build_cache",
                "BUILDHOUND_BENCHMARK_SEED_REF" to "seed-42",
            ),
        ).build()

        val payload = readPayload()
        assertEquals(BuildMode.BENCHMARK, payload.mode)
        assertEquals("clean", payload.benchmark?.scenario)
        assertEquals(2, payload.benchmark?.iteration)
        assertEquals("no_build_cache", payload.benchmark?.isolationMode)
        assertEquals("seed-42", payload.benchmark?.seedRef)
        // Mirrored into tags (the spec's tag contract).
        assertEquals("clean", payload.tags["scenario"])
        assertEquals("2", payload.tags["iteration"])
        assertEquals("no_build_cache", payload.tags["isolationMode"])
    }

    @Test
    fun `a bogus scenario falls back to the normal mode without failing the build`() {
        setUpProject()
        val result = runner("hello").freshDaemon()
            .withEnvironment(neutralEnv() + mapOf("BUILDHOUND_BENCHMARK_SCENARIO" to "clena"))
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, "a bogus scenario must never fail the build")
        val payload = readPayload()
        assertNotEquals(BuildMode.BENCHMARK, payload.mode)
        assertNull(payload.benchmark)
        assertTrue(result.output.contains("is not a known scenario"), result.output)
    }

    @Test
    fun `a non-numeric iteration falls back and warns`() {
        setUpProject()
        val result = runner("hello").freshDaemon()
            .withEnvironment(
                neutralEnv() + mapOf(
                    "BUILDHOUND_BENCHMARK_SCENARIO" to "clean",
                    "BUILDHOUND_BENCHMARK_ITERATION" to "four",
                ),
            )
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertNotEquals(BuildMode.BENCHMARK, readPayload().mode)
        assertNull(readPayload().benchmark)
        assertTrue(result.output.contains("is not an integer"), result.output)
    }
}
