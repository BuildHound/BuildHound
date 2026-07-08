package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelocatabilityDetectorTest {

    private fun row(
        taskPath: String = ":app:compileDebugKotlin",
        module: String? = ":app",
        hostnameHash: String? = "h1",
        origin: RelocatabilityOrigin,
        cacheable: Boolean? = true,
        durationMs: Long = 1000,
    ) = RelocatabilityRow(taskPath = taskPath, module = module, hostnameHash = hostnameHash, origin = origin, cacheable = cacheable, durationMs = durationMs)

    @Test
    fun `no REMOTE_HIT anywhere in the window stays silent — the fleet gate`() {
        val rows = listOf(
            row(hostnameHash = "h1", origin = RelocatabilityOrigin.STORED),
            row(hostnameHash = "h2", origin = RelocatabilityOrigin.STORED),
        )
        assertEquals(false, RelocatabilityDetector.remoteCacheObserved(rows))
        assertEquals(emptyList(), RelocatabilityDetector.detect(rows), "no REMOTE_HIT anywhere must never produce a candidate")
    }

    @Test
    fun `STORED on 2 hosts with zero REMOTE_HIT ranks as a candidate once a sibling task REMOTE_HITs`() {
        val rows = listOf(
            row(taskPath = ":app:compileDebugKotlin", hostnameHash = "h1", origin = RelocatabilityOrigin.STORED, durationMs = 400),
            row(taskPath = ":app:compileDebugKotlin", hostnameHash = "h2", origin = RelocatabilityOrigin.STORED, durationMs = 600),
            // A sibling task REMOTE_HITs somewhere in the fleet — satisfies the fleet gate.
            row(taskPath = ":lib:compileDebugKotlin", module = ":lib", hostnameHash = "h1", origin = RelocatabilityOrigin.REMOTE_HIT, durationMs = 50),
        )
        assertTrue(RelocatabilityDetector.remoteCacheObserved(rows))
        val candidate = RelocatabilityDetector.detect(rows).single { it.taskPath == ":app:compileDebugKotlin" }
        assertEquals(":app", candidate.module)
        assertEquals(2, candidate.crossHostCount)
        assertEquals(1000L, candidate.wastedMs)
        assertTrue(candidate.note.contains("investigate", ignoreCase = true), candidate.note)
    }

    @Test
    fun `a task that REMOTE_HITs on the second host is not flagged — relocated fine`() {
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = RelocatabilityOrigin.STORED),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = RelocatabilityOrigin.REMOTE_HIT),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = RelocatabilityOrigin.REMOTE_HIT),
        )
        assertTrue(RelocatabilityDetector.detect(rows).none { it.taskPath == ":app:x" })
    }

    @Test
    fun `literal MISS also counts as a non-hit execution`() {
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = RelocatabilityOrigin.MISS),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = RelocatabilityOrigin.MISS),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = RelocatabilityOrigin.REMOTE_HIT),
        )
        assertTrue(RelocatabilityDetector.detect(rows).any { it.taskPath == ":app:x" })
    }

    @Test
    fun `a task executed repeatedly on a single host is not flagged — needs 2 distinct hosts, not 2 occurrences`() {
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = RelocatabilityOrigin.STORED),
            row(taskPath = ":app:x", hostnameHash = "h1", origin = RelocatabilityOrigin.MISS), // same host, a second build
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = RelocatabilityOrigin.REMOTE_HIT),
        )
        assertTrue(RelocatabilityDetector.detect(rows).none { it.taskPath == ":app:x" })
    }

    @Test
    fun `cacheable null (isolated-projects gap) still classifies from origin alone`() {
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = RelocatabilityOrigin.STORED, cacheable = null),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = RelocatabilityOrigin.STORED, cacheable = null),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = RelocatabilityOrigin.REMOTE_HIT),
        )
        assertTrue(RelocatabilityDetector.detect(rows).any { it.taskPath == ":app:x" })
    }

    @Test
    fun `an explicitly non-cacheable task is excluded even with the same cross-host origin shape`() {
        // Adversarial near-miss: OriginClassifier also emits MISS for a plain non-cacheable executed
        // task, so without this exclusion every fleet's non-cacheable tasks would false-positive here.
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = RelocatabilityOrigin.MISS, cacheable = false),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = RelocatabilityOrigin.MISS, cacheable = false),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = RelocatabilityOrigin.REMOTE_HIT),
        )
        assertTrue(
            RelocatabilityDetector.detect(rows).none { it.taskPath == ":app:x" },
            "a task known non-cacheable is not a relocatability candidate",
        )
    }

    @Test
    fun `a row with no captured hostnameHash contributes to neither crossHostCount nor wastedMs`() {
        // Adversarial near-miss for the wastedMs-vs-crossHostCount evidence-set fix: a third execution
        // with no hostnameHash (uncaptured — strict mode, or a pre-existing payload) must not inflate
        // wastedMs beyond what crossHostCount actually counted.
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = RelocatabilityOrigin.STORED, durationMs = 400),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = RelocatabilityOrigin.STORED, durationMs = 600),
            row(taskPath = ":app:x", hostnameHash = null, origin = RelocatabilityOrigin.STORED, durationMs = 9999),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = RelocatabilityOrigin.REMOTE_HIT),
        )
        val candidate = RelocatabilityDetector.detect(rows).single { it.taskPath == ":app:x" }
        assertEquals(2, candidate.crossHostCount, "the uncaptured-host row must not count as a third distinct host")
        assertEquals(1000L, candidate.wastedMs, "the uncaptured-host row's 9999ms must not inflate wastedMs beyond the 2 hosted executions")
    }
}
