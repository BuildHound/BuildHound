package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Plan-065 widening of the plan-032 toolchain math: p50 duration per version row, and the jdk
 * dimension's grouping by JDK **major** ("your JDK 17 daemons are p50 X % slower than your JDK 21
 * daemons") via [ToolchainCalculator.jdkMajor]. Pure-unit, no store.
 */
class ToolchainCalculatorTest {

    private fun sample(version: String?, durationMs: Long? = null, userId: String? = "u_1", startedAt: Long = 1_000) =
        ToolchainSample(version = version, userId = userId, startedAt = startedAt, durationMs = durationMs)

    @Test
    fun `p50 by jdk major over a 17-vs-21 fixture`() {
        val samples = listOf(
            // Three JDK 21 patch releases collapse into major "21": durations 40s/50s/60s → p50 50s.
            sample("21.0.9", 40_000),
            sample("21.0.10", 50_000),
            sample("21.0.10", 60_000),
            // Two JDK 17 builds → major "17": durations 80s/90s → nearest-rank p50 80s.
            sample("17.0.2", 80_000),
            sample("17", 90_000),
        )
        val dimension = ToolchainCalculator.dimension(samples, ToolchainCalculator::jdkMajor)

        assertEquals(listOf("21", "17"), dimension.versions.map { it.version }, "majors, most builds first")
        val jdk21 = dimension.versions.single { it.version == "21" }
        val jdk17 = dimension.versions.single { it.version == "17" }
        assertEquals(3, jdk21.builds)
        assertEquals(50_000, jdk21.durationP50Ms)
        assertEquals(80_000, jdk17.durationP50Ms)
        // The comparison the plan names: JDK 17 daemons are p50 60 % slower than JDK 21 daemons.
        assertEquals(0.6, (jdk17.durationP50Ms!! - jdk21.durationP50Ms!!).toDouble() / jdk21.durationP50Ms!!)
        // "Behind" works on majors too.
        assertEquals(listOf("17"), dimension.behind.map { it.version })
    }

    @Test
    fun `p50 stays null when no sample carries a duration — the other dimensions' shape`() {
        val dimension = ToolchainCalculator.dimension(listOf(sample("8.14.3"), sample("8.14.3"), sample("9.0.0")))
        assertNull(dimension.versions.first { it.version == "8.14.3" }.durationP50Ms)
        assertNull(dimension.versions.first { it.version == "9.0.0" }.durationP50Ms)
    }

    @Test
    fun `p50 is order-invariant — the two stores feed rows in different orders`() {
        val forward = listOf(sample("21", 10_000), sample("21", 30_000), sample("21", 20_000))
        assertEquals(
            ToolchainCalculator.dimension(forward, ToolchainCalculator::jdkMajor),
            ToolchainCalculator.dimension(forward.reversed(), ToolchainCalculator::jdkMajor),
        )
    }

    @Test
    fun `jdkMajor takes the leading numeric segment and falls back to the whole string`() {
        assertEquals("21", ToolchainCalculator.jdkMajor("21.0.10"))
        assertEquals("17", ToolchainCalculator.jdkMajor("17"))
        assertEquals("24", ToolchainCalculator.jdkMajor("24-ea"))
        assertEquals("21", ToolchainCalculator.jdkMajor("21+35"))
        // A legacy 1.8-style string honestly reports its leading segment — stated, not special-cased
        // (the plugin's JDK-21 floor makes this shape unreachable from our own collector).
        assertEquals("1", ToolchainCalculator.jdkMajor("1.8.0_392"))
        // Non-numeric leading segment → the whole string, unchanged (never a guessed major).
        assertEquals("graal-ce", ToolchainCalculator.jdkMajor("graal-ce"))
    }
}
