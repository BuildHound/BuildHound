package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelocatabilityDetectorTest {

    private fun row(
        taskPath: String = ":app:compileDebugKotlin",
        module: String? = ":app",
        hostnameHash: String? = "h1",
        origin: String,
        cacheable: Boolean? = true,
        durationMs: Long = 1000,
    ) = RelocatabilityRow(taskPath = taskPath, module = module, hostnameHash = hostnameHash, origin = origin, cacheable = cacheable, durationMs = durationMs)

    @Test
    fun `no REMOTE_HIT anywhere in the window stays silent — the fleet gate`() {
        val rows = listOf(
            row(hostnameHash = "h1", origin = "STORED"),
            row(hostnameHash = "h2", origin = "STORED"),
        )
        assertEquals(false, RelocatabilityDetector.remoteCacheObserved(rows))
        assertEquals(emptyList(), RelocatabilityDetector.detect(rows), "no REMOTE_HIT anywhere must never produce a candidate")
    }

    @Test
    fun `STORED on 2 hosts with zero REMOTE_HIT ranks as a candidate once a sibling task REMOTE_HITs`() {
        val rows = listOf(
            row(taskPath = ":app:compileDebugKotlin", hostnameHash = "h1", origin = "STORED", durationMs = 400),
            row(taskPath = ":app:compileDebugKotlin", hostnameHash = "h2", origin = "STORED", durationMs = 600),
            // A sibling task REMOTE_HITs somewhere in the fleet — satisfies the fleet gate.
            row(taskPath = ":lib:compileDebugKotlin", module = ":lib", hostnameHash = "h1", origin = "REMOTE_HIT", durationMs = 50),
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
            row(taskPath = ":app:x", hostnameHash = "h1", origin = "STORED"),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = "REMOTE_HIT"),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = "REMOTE_HIT"),
        )
        assertTrue(RelocatabilityDetector.detect(rows).none { it.taskPath == ":app:x" })
    }

    @Test
    fun `literal MISS also counts as a non-hit execution`() {
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = "MISS"),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = "MISS"),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = "REMOTE_HIT"),
        )
        assertTrue(RelocatabilityDetector.detect(rows).any { it.taskPath == ":app:x" })
    }

    @Test
    fun `a task executed repeatedly on a single host is not flagged — needs 2 distinct hosts, not 2 occurrences`() {
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = "STORED"),
            row(taskPath = ":app:x", hostnameHash = "h1", origin = "MISS"), // same host, a second build
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = "REMOTE_HIT"),
        )
        assertTrue(RelocatabilityDetector.detect(rows).none { it.taskPath == ":app:x" })
    }

    @Test
    fun `cacheable null (isolated-projects gap) still classifies from origin alone`() {
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = "STORED", cacheable = null),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = "STORED", cacheable = null),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = "REMOTE_HIT"),
        )
        assertTrue(RelocatabilityDetector.detect(rows).any { it.taskPath == ":app:x" })
    }

    @Test
    fun `an explicitly non-cacheable task is excluded even with the same cross-host origin shape`() {
        // Adversarial near-miss: OriginClassifier also emits MISS for a plain non-cacheable executed
        // task, so without this exclusion every fleet's non-cacheable tasks would false-positive here.
        val rows = listOf(
            row(taskPath = ":app:x", hostnameHash = "h1", origin = "MISS", cacheable = false),
            row(taskPath = ":app:x", hostnameHash = "h2", origin = "MISS", cacheable = false),
            row(taskPath = ":lib:y", module = ":lib", hostnameHash = "h1", origin = "REMOTE_HIT"),
        )
        assertTrue(
            RelocatabilityDetector.detect(rows).none { it.taskPath == ":app:x" },
            "a task known non-cacheable is not a relocatability candidate",
        )
    }
}
