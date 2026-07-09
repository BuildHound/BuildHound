package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.ConfigurationCacheState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure coverage for the plan-064 CC flip-flop detector (a MISS_STORED whose salted inputs match a
 * strictly-earlier CC-requesting build in the same single-machine salt stream). Adversarial + boundary +
 * deterministic-tie cases per the house convention.
 */
class CcFlipFlopDetectorTest {

    private fun row(
        buildId: String,
        startedAt: Long,
        ccState: ConfigurationCacheState,
        fingerprints: Map<String, String>,
        userId: String? = "u1",
        hostnameHash: String? = "h1",
    ) = CcBuildRow(
        buildId = buildId, startedAt = startedAt, mode = BuildMode.LOCAL, ccState = ccState,
        configurationMs = null, ccLoadMs = null, ccEntrySizeBytes = null,
        userId = userId, hostnameHash = hostnameHash, fingerprints = fingerprints,
    )

    private val fpX = mapOf("jdk.home" to "aaaa1111", "env-CI" to "bbbb2222")
    private val fpY = mapOf("jdk.home" to "cccc3333", "env-CI" to "bbbb2222")

    @Test
    fun `flags a MISS_STORED matching an earlier CC-requesting build in the same stream`() {
        val findings = CcFlipFlopDetector.detect(
            listOf(
                row("a", 1000, ConfigurationCacheState.HIT, fpX),
                row("b", 2000, ConfigurationCacheState.MISS_STORED, fpX),
            ),
        )
        val finding = findings.single()
        assertEquals("b", finding.buildId)
        assertEquals("a", finding.priorBuildId)
        assertEquals(2000, finding.startedAt)
    }

    @Test
    fun `does not flag when the fingerprints differ`() {
        assertTrue(
            CcFlipFlopDetector.detect(
                listOf(
                    row("a", 1000, ConfigurationCacheState.HIT, fpX),
                    row("b", 2000, ConfigurationCacheState.MISS_STORED, fpY),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `only a MISS_STORED is flagged — a HIT on matching inputs is the healthy case`() {
        assertTrue(
            CcFlipFlopDetector.detect(
                listOf(
                    row("a", 1000, ConfigurationCacheState.MISS_STORED, fpX),
                    row("b", 2000, ConfigurationCacheState.HIT, fpX),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `a DISABLED prior is not evidence of a flip-flop (no entry was ever stored)`() {
        assertTrue(
            CcFlipFlopDetector.detect(
                listOf(
                    row("a", 1000, ConfigurationCacheState.DISABLED, fpX),
                    row("b", 2000, ConfigurationCacheState.MISS_STORED, fpX),
                ),
            ).isEmpty(),
            "a MISS_STORED after a DISABLED build with identical inputs is the first store, not a flip-flop",
        )
    }

    @Test
    fun `salt streams never compare across machines or users`() {
        // Same fingerprints, different (userId, hostnameHash) → different salt streams → no finding.
        assertTrue(
            CcFlipFlopDetector.detect(
                listOf(
                    row("a", 1000, ConfigurationCacheState.HIT, fpX, hostnameHash = "hA"),
                    row("b", 2000, ConfigurationCacheState.MISS_STORED, fpX, hostnameHash = "hB"),
                ),
            ).isEmpty(),
        )
        assertTrue(
            CcFlipFlopDetector.detect(
                listOf(
                    row("a", 1000, ConfigurationCacheState.HIT, fpX, userId = "uA"),
                    row("b", 2000, ConfigurationCacheState.MISS_STORED, fpX, userId = "uB"),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `a row without a full salt-stream identity or with empty fingerprints never participates`() {
        // Null hostnameHash / userId, or an empty fingerprint map: no stream / nothing to match.
        assertTrue(
            CcFlipFlopDetector.detect(
                listOf(
                    row("a", 1000, ConfigurationCacheState.HIT, fpX, hostnameHash = null),
                    row("b", 2000, ConfigurationCacheState.MISS_STORED, fpX, hostnameHash = null),
                ),
            ).isEmpty(),
        )
        assertTrue(
            CcFlipFlopDetector.detect(
                listOf(
                    row("a", 1000, ConfigurationCacheState.HIT, emptyMap()),
                    row("b", 2000, ConfigurationCacheState.MISS_STORED, emptyMap()),
                ),
            ).isEmpty(),
            "two builds with no captured fingerprints must not read as identical inputs",
        )
    }

    @Test
    fun `the first build in a stream has no prior, so it is never flagged`() {
        assertTrue(
            CcFlipFlopDetector.detect(listOf(row("a", 1000, ConfigurationCacheState.MISS_STORED, fpX))).isEmpty(),
        )
    }

    @Test
    fun `priorBuildId is the earliest matching CC-requesting build`() {
        val finding = CcFlipFlopDetector.detect(
            listOf(
                row("a", 1000, ConfigurationCacheState.HIT, fpX),
                row("b", 2000, ConfigurationCacheState.MISS_STORED, fpX),
                row("c", 3000, ConfigurationCacheState.MISS_STORED, fpX),
            ),
        ).single { it.buildId == "c" }
        assertEquals("a", finding.priorBuildId, "the earliest matching prior, not the immediately preceding one")
    }

    @Test
    fun `findings are ordered by (startedAt, buildId) deterministically across streams`() {
        val findings = CcFlipFlopDetector.detect(
            listOf(
                // stream (u1,h1): a(HIT) then b(MISS) → b flagged at t=2000
                row("a", 1000, ConfigurationCacheState.HIT, fpX),
                row("b", 2000, ConfigurationCacheState.MISS_STORED, fpX),
                // stream (u2,h2): c(MISS) then d(MISS) same fp → d flagged at t=1500
                row("c", 500, ConfigurationCacheState.MISS_STORED, fpY, userId = "u2", hostnameHash = "h2"),
                row("d", 1500, ConfigurationCacheState.MISS_STORED, fpY, userId = "u2", hostnameHash = "h2"),
            ),
        )
        assertEquals(listOf("d", "b"), findings.map { it.buildId }, "sorted by startedAt then buildId (1500 < 2000)")
    }
}
