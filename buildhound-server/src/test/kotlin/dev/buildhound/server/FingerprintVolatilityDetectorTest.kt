package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FingerprintVolatilityDetectorTest {

    private fun row(hostnameHash: String, startedAt: Long, buildId: String, fingerprints: Map<String, String>) =
        FingerprintStreamRow(hostnameHash = hostnameHash, startedAt = startedAt, buildId = buildId, fingerprints = fingerprints)

    @Test
    fun `a key that changes on every consecutive build in one stream scores 1_0`() {
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("env-GITHUB_TOKEN" to "aaa")),
            row("h1", 2000, "b2", mapOf("env-GITHUB_TOKEN" to "bbb")),
            row("h1", 3000, "b3", mapOf("env-GITHUB_TOKEN" to "ccc")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single { it.key == "env-GITHUB_TOKEN" }
        assertEquals(1.0, entry.volatility)
        assertEquals(1, entry.contributingStreams)
    }

    @Test
    fun `a stable key across a stream scores 0_0`() {
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("jdk.home" to "same")),
            row("h1", 2000, "b2", mapOf("jdk.home" to "same")),
            row("h1", 3000, "b3", mapOf("jdk.home" to "same")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single { it.key == "jdk.home" }
        assertEquals(0.0, entry.volatility)
    }

    @Test
    fun `the same key stable-within-each-of-two-salt-streams-but-differing-across scores 0_0 — streams are never pooled`() {
        // The load-bearing case: h1 and h2 are different machines with different per-project salts, so
        // "jdk.home" hashes differently on each even though it never actually changes on either machine.
        // Pooling all four builds into one global timeline would see salt-a -> salt-b -> salt-a -> salt-b
        // transitions and misread this as maximally volatile.
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("jdk.home" to "salt-a-value")),
            row("h1", 2000, "b2", mapOf("jdk.home" to "salt-a-value")),
            row("h2", 1500, "b3", mapOf("jdk.home" to "salt-b-value")),
            row("h2", 2500, "b4", mapOf("jdk.home" to "salt-b-value")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single { it.key == "jdk.home" }
        assertEquals(0.0, entry.volatility, "pooling would read the cross-stream salt difference as volatility")
        assertEquals(2, entry.contributingStreams)
    }

    @Test
    fun `a stream with fewer than 2 builds is excluded — the MIN_STREAM gate`() {
        val rows = listOf(row("h1", 1000, "b1", mapOf("env-GITHUB_TOKEN" to "aaa")))
        assertEquals(emptyList(), FingerprintVolatilityDetector.detect(rows))
    }

    @Test
    fun `exactly 2 builds in a stream (the MIN_STREAM boundary) is included, not excluded`() {
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("jdk.home" to "a")),
            row("h1", 2000, "b2", mapOf("jdk.home" to "b")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single()
        assertEquals(1.0, entry.volatility)
        assertEquals(1, entry.contributingStreams)
    }

    @Test
    fun `env-GITHUB_TOKEN is matched after the prefix strip and credential-noted`() {
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("env-GITHUB_TOKEN" to "aaa")),
            row("h1", 2000, "b2", mapOf("env-GITHUB_TOKEN" to "bbb")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single()
        assertTrue(entry.note.contains("credential", ignoreCase = true), entry.note)
        assertTrue(!entry.note.contains("aaa") && !entry.note.contains("bbb"), "the note must never echo a fingerprint value")
    }

    @Test
    fun `a non-credential volatile key still scores and gets a generic note`() {
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("env-SOME_FLAG" to "aaa")),
            row("h1", 2000, "b2", mapOf("env-SOME_FLAG" to "bbb")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single()
        assertEquals(1.0, entry.volatility)
        assertTrue(entry.note.isNotBlank())
        assertTrue(!entry.note.contains("credential", ignoreCase = true), entry.note)
    }

    @Test
    fun `a CI run-id-shaped key is noted as a run identifier, not a credential`() {
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("env-GITHUB_RUN_ID" to "aaa")),
            row("h1", 2000, "b2", mapOf("env-GITHUB_RUN_ID" to "bbb")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single()
        assertTrue(entry.note.contains("run", ignoreCase = true), entry.note)
        assertTrue(!entry.note.contains("credential", ignoreCase = true), entry.note)
    }

    @Test
    fun `a built-in key with no prefix falls back to BuildComparator's exact-name catalog note`() {
        val rows = listOf(
            row("h1", 1000, "b1", mapOf("jdk.home" to "a")),
            row("h1", 2000, "b2", mapOf("jdk.home" to "b")),
        )
        val entry = FingerprintVolatilityDetector.detect(rows).single()
        assertEquals(BuildComparator.explanatoryNote("jdk.home"), entry.note)
    }
}
