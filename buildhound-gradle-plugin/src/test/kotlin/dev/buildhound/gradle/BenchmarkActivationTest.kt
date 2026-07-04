package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Env-activation allowlist + fail-closed rules for benchmark mode (Gradle-free, plan 030). */
class BenchmarkActivationTest {

    private fun activate(env: Map<String, String>, warns: MutableList<String> = mutableListOf()) =
        BenchmarkActivation.fromEnv(env, warn = { warns.add(it) })

    @Test
    fun `no scenario means no benchmark, silently`() {
        val warns = mutableListOf<String>()
        assertNull(activate(emptyMap(), warns))
        assertTrue(warns.isEmpty(), "an ordinary build must not warn")
    }

    @Test
    fun `a valid scenario activates with parsed iteration and isolation`() {
        val result = activate(
            mapOf(
                "BUILDHOUND_BENCHMARK_SCENARIO" to "clean",
                "BUILDHOUND_BENCHMARK_ITERATION" to "4",
                "BUILDHOUND_BENCHMARK_ISOLATION" to "no_build_cache",
                "BUILDHOUND_BENCHMARK_SEED_REF" to "seed-1",
            ),
        )
        assertEquals(CollectedBenchmark("clean", 4, "no_build_cache", "seed-1"), result)
    }

    @Test
    fun `an unknown scenario fails closed with a warn`() {
        val warns = mutableListOf<String>()
        assertNull(activate(mapOf("BUILDHOUND_BENCHMARK_SCENARIO" to "clena"), warns))
        assertTrue(warns.single().contains("not a known scenario"), warns.toString())
    }

    @Test
    fun `a non-numeric iteration fails closed with a warn`() {
        val warns = mutableListOf<String>()
        val result = activate(
            mapOf("BUILDHOUND_BENCHMARK_SCENARIO" to "clean", "BUILDHOUND_BENCHMARK_ITERATION" to "four"),
            warns,
        )
        assertNull(result)
        assertTrue(warns.single().contains("not an integer"), warns.toString())
    }

    @Test
    fun `an unknown isolation mode fails closed with a warn`() {
        val warns = mutableListOf<String>()
        val result = activate(
            mapOf("BUILDHOUND_BENCHMARK_SCENARIO" to "clean", "BUILDHOUND_BENCHMARK_ISOLATION" to "no_bild_cache"),
            warns,
        )
        assertNull(result)
        assertTrue(warns.single().contains("not a known isolation mode"), warns.toString())
    }

    @Test
    fun `scenario alone is enough - iteration and isolation are optional`() {
        assertEquals(
            CollectedBenchmark("no_op", null, null, null),
            activate(mapOf("BUILDHOUND_BENCHMARK_SCENARIO" to "no_op")),
        )
    }

    @Test
    fun `every advertised scenario is accepted`() {
        for (scenario in BenchmarkActivation.SCENARIOS) {
            assertEquals(scenario, activate(mapOf("BUILDHOUND_BENCHMARK_SCENARIO" to scenario))?.scenario)
        }
    }
}
