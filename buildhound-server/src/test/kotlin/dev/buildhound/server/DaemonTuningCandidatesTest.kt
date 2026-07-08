package dev.buildhound.server

import dev.buildhound.commons.payload.EnvironmentInfo
import dev.buildhound.commons.payload.GcCollector
import dev.buildhound.commons.payload.ProcessInfo
import dev.buildhound.commons.payload.ProcessRole
import dev.buildhound.commons.payload.ToolchainInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan-065 daemon-tuning rules: each fires/stays silent on fixtures, the GC pid-delta
 * refinement falls back to the lifetime fraction on a negative delta or `daemonReused=false`, and
 * candidates are advisory (pure input → output, no mutation, "investigate/consider" copy only).
 */
class DaemonTuningCandidatesTest {

    private val reused = EnvironmentInfo(daemonReused = true)
    private val fresh = EnvironmentInfo(daemonReused = false)

    private fun daemon(
        pid: Int? = 100,
        gcTimeMs: Long? = null,
        uptimeS: Long? = null,
        heapUsedMb: Long? = null,
        configuredXmxMb: Long? = null,
        rssMb: Long? = null,
        gcCollector: GcCollector? = null,
        compactObjectHeaders: Boolean? = null,
        role: ProcessRole = ProcessRole.GRADLE_DAEMON,
    ) = ProcessInfo(
        role = role, pid = pid, gcTimeMs = gcTimeMs, uptimeS = uptimeS, heapUsedMb = heapUsedMb,
        configuredXmxMb = configuredXmxMb, rssMb = rssMb, gcCollector = gcCollector,
        compactObjectHeaders = compactObjectHeaders,
    )

    private fun kinds(candidates: List<TuningCandidate>) = candidates.map { it.kind }

    // ---- GC pressure ----

    @Test
    fun `gc pressure fires above the 15 percent lifetime fraction and names the role knob`() {
        // 160s GC over 800s uptime = 0.20 lifetime fraction.
        val gradle = DaemonTuningCandidates.evaluate(
            listOf(daemon(gcTimeMs = 160_000, uptimeS = 800)), reused, null,
        ).single { it.kind == TuningKind.GC_PRESSURE.name }
        assertEquals(ProcessRole.GRADLE_DAEMON.name, gradle.role)
        assertTrue("org.gradle.jvmargs" in gradle.advisory, gradle.advisory)
        assertTrue("Investigate" in gradle.advisory, "advisory, never a confirmed fix: ${gradle.advisory}")

        val kotlin = DaemonTuningCandidates.evaluate(
            listOf(daemon(gcTimeMs = 160_000, uptimeS = 800, role = ProcessRole.KOTLIN_DAEMON)), reused, null,
        ).single { it.kind == TuningKind.GC_PRESSURE.name }
        assertTrue("kotlin.daemon.jvmargs" in kotlin.advisory, kotlin.advisory)
    }

    @Test
    fun `gc pressure stays silent below the threshold, on missing inputs, and for workers`() {
        // 3.1s GC over 812s uptime ≈ 0.004 — the healthy plan-029 golden shape.
        assertTrue(DaemonTuningCandidates.evaluate(listOf(daemon(gcTimeMs = 3_120, uptimeS = 812)), reused, null).isEmpty())
        // Missing gcTimeMs / uptimeS → no fraction → no candidate (nulls emit nothing).
        assertTrue(DaemonTuningCandidates.evaluate(listOf(daemon(gcTimeMs = null, uptimeS = 800)), reused, null).isEmpty())
        assertTrue(DaemonTuningCandidates.evaluate(listOf(daemon(gcTimeMs = 160_000, uptimeS = null)), reused, null).isEmpty())
        assertTrue(DaemonTuningCandidates.evaluate(listOf(daemon(gcTimeMs = 160_000, uptimeS = 0)), reused, null).isEmpty())
        // A worker's heap knob is task-level, not a named property — no confident advisory, no card.
        assertTrue(
            DaemonTuningCandidates.evaluate(
                listOf(daemon(gcTimeMs = 160_000, uptimeS = 800, role = ProcessRole.GRADLE_WORKER)), reused, null,
            ).none { it.kind == TuningKind.GC_PRESSURE.name },
        )
    }

    // ---- GC pid-delta refinement ----

    @Test
    fun `the pid-delta sharpens the fraction when the daemon was reused and GCT is monotonic`() {
        // Lifetime fraction is a calm 150s/3600s ≈ 0.042, but THIS build added 30s GC in a 60s
        // build — delta fraction 0.5 → the refinement (not the lifetime input) fires the card.
        val current = daemon(pid = 100, gcTimeMs = 150_000, uptimeS = 3_600)
        val prior = daemon(pid = 100, gcTimeMs = 120_000, uptimeS = 3_500)
        val candidates = DaemonTuningCandidates.evaluate(
            listOf(current), reused, null, buildDurationMs = 60_000, priorProcesses = listOf(prior),
        )
        assertTrue(TuningKind.GC_PRESSURE.name in kinds(candidates), "$candidates")
    }

    @Test
    fun `a negative delta means pid reuse and falls back to the lifetime fraction`() {
        // GCT went BACKWARDS for the same pid (daemon restart / pid reuse): the 0.5-looking delta
        // must be discarded; the calm lifetime fraction (0.042) keeps the rule silent.
        val current = daemon(pid = 100, gcTimeMs = 150_000, uptimeS = 3_600)
        val prior = daemon(pid = 100, gcTimeMs = 999_000, uptimeS = 9_000)
        val candidates = DaemonTuningCandidates.evaluate(
            listOf(current), reused, null, buildDurationMs = 60_000, priorProcesses = listOf(prior),
        )
        assertTrue(candidates.isEmpty(), "$candidates")
    }

    @Test
    fun `daemonReused false ignores the prior snapshot entirely`() {
        val current = daemon(pid = 100, gcTimeMs = 150_000, uptimeS = 3_600)
        val prior = daemon(pid = 100, gcTimeMs = 120_000, uptimeS = 3_500)
        // Same monotonic prior as the sharpening case, but a fresh daemon → lifetime fraction only.
        val candidates = DaemonTuningCandidates.evaluate(
            listOf(current), fresh, null, buildDurationMs = 60_000, priorProcesses = listOf(prior),
        )
        assertTrue(candidates.isEmpty(), "$candidates")
        // And a null environment is treated like not-reused (never a guess).
        assertTrue(
            DaemonTuningCandidates.evaluate(
                listOf(current), null, null, buildDurationMs = 60_000, priorProcesses = listOf(prior),
            ).isEmpty(),
        )
    }

    @Test
    fun `a prior snapshot with a different pid does not refine`() {
        val current = daemon(pid = 100, gcTimeMs = 150_000, uptimeS = 3_600)
        val prior = daemon(pid = 200, gcTimeMs = 120_000, uptimeS = 3_500)
        assertNull(
            DaemonTuningCandidates.gcFraction(current, reused, 60_000, listOf(prior))
                ?.takeIf { it >= DaemonTuningCandidates.GC_FRACTION_THRESHOLD },
        )
    }

    // ---- Kotlin pinned-Xmx ----

    @Test
    fun `kotlin pinned-Xmx fires at 90 percent used-vs-configured on the kotlin daemon only`() {
        val pinned = daemon(role = ProcessRole.KOTLIN_DAEMON, heapUsedMb = 1900, configuredXmxMb = 2048)
        val candidate = DaemonTuningCandidates.evaluate(listOf(pinned), reused, null)
            .single { it.kind == TuningKind.KOTLIN_PINNED_XMX.name }
        assertTrue("kotlin.daemon.jvmargs" in candidate.advisory, candidate.advisory)

        // The same pinned shape on the GRADLE daemon is a different problem (GC pressure covers it).
        val gradlePinned = daemon(heapUsedMb = 1900, configuredXmxMb = 2048)
        assertTrue(
            DaemonTuningCandidates.evaluate(listOf(gradlePinned), reused, null)
                .none { it.kind == TuningKind.KOTLIN_PINNED_XMX.name },
        )
        // Below the ratio → silent.
        val roomy = daemon(role = ProcessRole.KOTLIN_DAEMON, heapUsedMb = 640, configuredXmxMb = 2048)
        assertTrue(DaemonTuningCandidates.evaluate(listOf(roomy), reused, null).isEmpty())
    }

    // ---- ParallelGC-vs-G1 ----

    @Test
    fun `parallel gc trial is suggested only for G1 with a high fraction`() {
        val g1Hot = daemon(gcTimeMs = 160_000, uptimeS = 800, gcCollector = GcCollector.G1)
        val candidates = DaemonTuningCandidates.evaluate(listOf(g1Hot), reused, null)
        val trial = candidates.single { it.kind == TuningKind.PARALLEL_GC_TRIAL.name }
        assertTrue("trial" in trial.advisory, "a suggestion, not an instruction: ${trial.advisory}")
        assertTrue("?" in trial.advisory, trial.advisory)

        // Already on ParallelGC → no trial suggestion (GC pressure may still fire).
        val parallelHot = daemon(gcTimeMs = 160_000, uptimeS = 800, gcCollector = GcCollector.PARALLEL)
        assertTrue(
            DaemonTuningCandidates.evaluate(listOf(parallelHot), reused, null)
                .none { it.kind == TuningKind.PARALLEL_GC_TRIAL.name },
        )
        // G1 but calm → silent.
        val g1Calm = daemon(gcTimeMs = 3_000, uptimeS = 800, gcCollector = GcCollector.G1)
        assertTrue(DaemonTuningCandidates.evaluate(listOf(g1Calm), reused, null).isEmpty())
    }

    // ---- Compact Object Headers ----

    @Test
    fun `compact object headers fires on jdk 24 plus with high rss and headers not already on`() {
        val jdk24 = ToolchainInfo(jdk = "24.0.1")
        val big = daemon(rssMb = 4_880, compactObjectHeaders = false)
        val candidate = DaemonTuningCandidates.evaluate(listOf(big), reused, jdk24)
            .single { it.kind == TuningKind.COMPACT_OBJECT_HEADERS.name }
        assertTrue("UseCompactObjectHeaders" in candidate.advisory, candidate.advisory)
        assertTrue("JEP 519" in candidate.advisory, candidate.advisory)

        // An unprinted flag (null) still counts as not-enabled — the common JDK-24 default-off case.
        assertTrue(
            DaemonTuningCandidates.evaluate(listOf(daemon(rssMb = 4_880)), reused, jdk24)
                .any { it.kind == TuningKind.COMPACT_OBJECT_HEADERS.name },
        )
        // Already enabled → silent.
        assertTrue(
            DaemonTuningCandidates.evaluate(listOf(daemon(rssMb = 4_880, compactObjectHeaders = true)), reused, jdk24).isEmpty(),
        )
        // JDK 21 → silent (the flag does not exist there); unknown JDK → silent (never a guess).
        assertTrue(DaemonTuningCandidates.evaluate(listOf(big), reused, ToolchainInfo(jdk = "21.0.10")).isEmpty())
        assertTrue(DaemonTuningCandidates.evaluate(listOf(big), reused, null).isEmpty())
        assertTrue(DaemonTuningCandidates.evaluate(listOf(big), reused, ToolchainInfo(jdk = "not-a-version")).isEmpty())
        // Small rss → the ~22 % saving is absolute-noise → silent.
        assertTrue(DaemonTuningCandidates.evaluate(listOf(daemon(rssMb = 512, compactObjectHeaders = false)), reused, jdk24).isEmpty())
    }

    // ---- Ranking + advisory-only contract ----

    @Test
    fun `candidates rank by rule priority then severity with deterministic tie-breaks`() {
        val hotG1Kotlin = daemon(
            role = ProcessRole.KOTLIN_DAEMON, pid = 200, gcTimeMs = 200_000, uptimeS = 800,
            heapUsedMb = 1900, configuredXmxMb = 2048, gcCollector = GcCollector.G1, rssMb = 4_880,
        )
        val hotGradle = daemon(pid = 100, gcTimeMs = 160_000, uptimeS = 800, rssMb = 4_880)
        val candidates = DaemonTuningCandidates.evaluate(
            listOf(hotG1Kotlin, hotGradle), reused, ToolchainInfo(jdk = "24.0.1"),
        )
        // GC_PRESSURE first (both roles, higher fraction first: kotlin 0.25 > gradle 0.20), then
        // the pinned-Xmx, then the G1 trial, then the two compact-headers cards.
        assertEquals(
            listOf(
                TuningKind.GC_PRESSURE.name to ProcessRole.KOTLIN_DAEMON.name,
                TuningKind.GC_PRESSURE.name to ProcessRole.GRADLE_DAEMON.name,
                TuningKind.KOTLIN_PINNED_XMX.name to ProcessRole.KOTLIN_DAEMON.name,
                TuningKind.PARALLEL_GC_TRIAL.name to ProcessRole.KOTLIN_DAEMON.name,
                TuningKind.COMPACT_OBJECT_HEADERS.name to ProcessRole.GRADLE_DAEMON.name,
                TuningKind.COMPACT_OBJECT_HEADERS.name to ProcessRole.KOTLIN_DAEMON.name,
            ),
            candidates.map { it.kind to it.role },
        )
        // Input order must not matter (the two stores' parity discipline, applied here).
        assertEquals(
            candidates,
            DaemonTuningCandidates.evaluate(listOf(hotGradle, hotG1Kotlin), reused, ToolchainInfo(jdk = "24.0.1")),
        )
    }

    @Test
    fun `an empty probe or all-null processes emit no candidates`() {
        assertTrue(DaemonTuningCandidates.evaluate(emptyList(), reused, null).isEmpty())
        assertTrue(DaemonTuningCandidates.evaluate(listOf(ProcessInfo(role = ProcessRole.GRADLE_DAEMON)), null, null).isEmpty())
    }
}
