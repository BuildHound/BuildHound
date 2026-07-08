package dev.buildhound.server

import dev.buildhound.commons.payload.EnvironmentInfo
import dev.buildhound.commons.payload.GcCollector
import dev.buildhound.commons.payload.ProcessInfo
import dev.buildhound.commons.payload.ProcessRole
import dev.buildhound.commons.payload.ToolchainInfo
import kotlinx.serialization.Serializable

/**
 * The fixed daemon-tuning rule taxonomy (plan 065, research F15) [DaemonTuningCandidates] evaluates.
 * Server-local enum (not `@Serializable`), the [WarningCategory]/[RerunCause] convention — the wire
 * model ([TuningCandidate.kind]) carries the plain `.name` string, never the enum type. Declaration
 * order is the ranking priority (the plan's rule listing order).
 */
enum class TuningKind { GC_PRESSURE, KOTLIN_PINNED_XMX, PARALLEL_GC_TRIAL, COMPACT_OBJECT_HEADERS }

/**
 * One ranked daemon-tuning candidate (plan 065). Every candidate is **advisory** — "investigate",
 * never a confirmed fix, and nothing is ever auto-applied — mirroring plan 060's candidate framing.
 * [role] names the JVM the evidence came from; [advisory] names the role-specific knob
 * (`org.gradle.jvmargs` for the Gradle daemon, `kotlin.daemon.jvmargs` for the Kotlin daemon).
 * The strings are composed entirely server-side from enum/threshold constants — no payload text
 * is echoed, so there is nothing to scrub.
 */
@Serializable
data class TuningCandidate(
    val kind: String,
    val role: String? = null,
    val advisory: String,
)

/**
 * Pure, unit-testable daemon-tuning rules over one build's already-collected process snapshot
 * (plan 065, research F15) — no I/O, no store, no new endpoint: the server consumer is the
 * plan-071 `/v1/builds/{buildId}/diagnosis` synthesis, and the dashboard/HTML-artifact process
 * panels render the same primary-input rules client-side from the already-fetched payload (the
 * artifact is zero-network by locked decision #4, so it *cannot* call an evaluator; thresholds
 * here are the pinned source of truth the two JS surfaces mirror).
 *
 * The GC-pressure primary input is the **uptimeS-normalized lifetime fraction**
 * `gcTimeMs / (uptimeS * 1000)` — a proxy, not Google's per-build GC % (which needs start+end
 * sampling, still plan-029's deferral). A pid-delta refinement sharpens it ONLY when
 * `environment.daemonReused == true` AND the prior same-pid snapshot's GCT is monotonic
 * (a negative delta means pid reuse / daemon restart → fall back to the fraction).
 */
object DaemonTuningCandidates {

    /** Google's "GC > 15 % of build time ⇒ raise heap" threshold (research F15), applied to the proxy. */
    const val GC_FRACTION_THRESHOLD = 0.15

    /** Kotlin-daemon pinned-Xmx: `heapUsedMb ≈ configuredXmxMb` at or above this ratio. */
    const val PINNED_XMX_RATIO = 0.9

    /** JEP 519 Compact Object Headers ships in JDK 24+ (~22 % heap on object-heavy workloads). */
    const val COMPACT_HEADERS_MIN_JDK_MAJOR = 24

    /**
     * "High rss" for the Compact-Object-Headers rule: below ~2 GiB of resident set the ~22 %
     * object-header saving is small in absolute terms and the card would be noise.
     */
    const val HIGH_RSS_MB = 2048L

    /**
     * Evaluate the four advisory rules. [priorProcesses] is the previous same-`hostnameHash` build's
     * snapshot when a caller has one (enables the pid-delta refinement together with
     * [buildDurationMs]); both default to absent — the lifetime fraction is the always-on input.
     * Returns candidates ranked by [TuningKind] priority (the plan's rule order), then severity
     * descending within a kind, then role name — fully deterministic, no order-of-input dependence.
     * Rules that see nulls simply emit no candidate (never-fail inherited).
     */
    fun evaluate(
        processes: List<ProcessInfo>,
        environment: EnvironmentInfo?,
        toolchain: ToolchainInfo?,
        buildDurationMs: Long? = null,
        priorProcesses: List<ProcessInfo> = emptyList(),
    ): List<TuningCandidate> {
        val candidates = mutableListOf<Scored>()
        for (process in processes) {
            val fraction = gcFraction(process, environment, buildDurationMs, priorProcesses) ?: continue
            gcPressure(process, fraction)?.let(candidates::add)
            parallelGcTrial(process, fraction)?.let(candidates::add)
        }
        for (process in processes) {
            kotlinPinnedXmx(process)?.let(candidates::add)
            compactObjectHeaders(process, toolchain)?.let(candidates::add)
        }
        return candidates
            // One card per (kind, role): repeated GRADLE_WORKER rows collapse to the worst evidence.
            .groupBy { it.candidate.kind to it.candidate.role }
            .map { (_, group) -> group.maxWith(compareBy({ it.severity }, { it.candidate.advisory })) }
            .sortedWith(
                compareBy<Scored> { TuningKind.valueOf(it.candidate.kind).ordinal }
                    .thenByDescending { it.severity }
                    .thenBy { it.candidate.role ?: "" },
            )
            .map { it.candidate }
    }

    private data class Scored(val candidate: TuningCandidate, val severity: Double)

    /**
     * Effective GC fraction for one process. Primary: `gcTimeMs / (uptimeS*1000)` (lifetime).
     * Refinement: `(gcTimeMs - priorGcTimeMs) / buildDurationMs`, applied only when the daemon was
     * reused, a prior snapshot with the SAME pid exists, its GCT is monotonic (delta >= 0), and the
     * build duration is positive — any miss falls back to the lifetime fraction (never a guess).
     */
    internal fun gcFraction(
        process: ProcessInfo,
        environment: EnvironmentInfo?,
        buildDurationMs: Long?,
        priorProcesses: List<ProcessInfo>,
    ): Double? {
        val gcTimeMs = process.gcTimeMs ?: return null
        val uptimeS = process.uptimeS?.takeIf { it > 0 }
        val lifetime = uptimeS?.let { gcTimeMs.toDouble() / (it * 1000.0) }
        if (environment?.daemonReused != true) return lifetime
        val pid = process.pid ?: return lifetime
        val priorGc = priorProcesses.firstOrNull { it.pid == pid && it.role == process.role }?.gcTimeMs ?: return lifetime
        val delta = gcTimeMs - priorGc
        if (delta < 0) return lifetime // pid reuse / daemon restart — GCT went backwards
        val duration = buildDurationMs?.takeIf { it > 0 } ?: return lifetime
        return delta.toDouble() / duration
    }

    private fun gcPressure(process: ProcessInfo, fraction: Double): Scored? {
        if (fraction < GC_FRACTION_THRESHOLD) return null
        val knob = heapKnobFor(process.role) ?: return null
        return Scored(
            TuningCandidate(
                kind = TuningKind.GC_PRESSURE.name,
                role = process.role.name,
                advisory = "Investigate high GC time (${percent(fraction)} of ${roleLabel(process.role)} JVM time) — " +
                    "consider raising the heap via $knob.",
            ),
            severity = fraction,
        )
    }

    /** G1 + the same high GC fraction ⇒ a throughput-trade *suggestion*, never an instruction. */
    private fun parallelGcTrial(process: ProcessInfo, fraction: Double): Scored? {
        if (process.gcCollector != GcCollector.G1 || fraction < GC_FRACTION_THRESHOLD) return null
        return Scored(
            TuningCandidate(
                kind = TuningKind.PARALLEL_GC_TRIAL.name,
                role = process.role.name,
                advisory = "Throughput-bound? The ${roleLabel(process.role)} runs G1 with high GC time — " +
                    "a ParallelGC trial (-XX:+UseParallelGC) may trade pause time for throughput.",
            ),
            severity = fraction,
        )
    }

    /** `heapUsedMb ≈ configuredXmxMb` on the Kotlin daemon — no new field, both exist since plan 029. */
    private fun kotlinPinnedXmx(process: ProcessInfo): Scored? {
        if (process.role != ProcessRole.KOTLIN_DAEMON) return null
        val used = process.heapUsedMb ?: return null
        val xmx = process.configuredXmxMb?.takeIf { it > 0 } ?: return null
        val ratio = used.toDouble() / xmx
        if (ratio < PINNED_XMX_RATIO) return null
        return Scored(
            TuningCandidate(
                kind = TuningKind.KOTLIN_PINNED_XMX.name,
                role = process.role.name,
                advisory = "The Kotlin daemon heap sits at ${percent(ratio)} of its configured -Xmx (${xmx} MB) — " +
                    "consider raising kotlin.daemon.jvmargs.",
            ),
            severity = ratio,
        )
    }

    /** JDK 24+ + high rss + headers not already on (an explicit `false` OR an unprinted null). */
    private fun compactObjectHeaders(process: ProcessInfo, toolchain: ToolchainInfo?): Scored? {
        val jdkMajor = toolchain?.jdk?.let(::leadingNumericSegment) ?: return null
        if (jdkMajor < COMPACT_HEADERS_MIN_JDK_MAJOR) return null
        val rssMb = process.rssMb ?: return null
        if (rssMb < HIGH_RSS_MB || process.compactObjectHeaders == true) return null
        return Scored(
            TuningCandidate(
                kind = TuningKind.COMPACT_OBJECT_HEADERS.name,
                role = process.role.name,
                advisory = "The ${roleLabel(process.role)} uses $rssMb MB RSS on JDK $jdkMajor without compact object " +
                    "headers — consider enabling -XX:+UseCompactObjectHeaders (~22 % heap, JEP 519).",
            ),
            severity = rssMb.toDouble(),
        )
    }

    /**
     * The role-specific heap knob. Workers get no GC-pressure card: their heap is task-level
     * (`Test.maxHeapSize` / fork options), not a single named property — an advisory naming the
     * wrong knob would be a confident-but-wrong fix, so none is emitted (honest, plan §3.7 spirit).
     */
    private fun heapKnobFor(role: ProcessRole): String? = when (role) {
        ProcessRole.GRADLE_DAEMON -> "org.gradle.jvmargs"
        ProcessRole.KOTLIN_DAEMON -> "kotlin.daemon.jvmargs"
        ProcessRole.GRADLE_WORKER -> null
    }

    private fun roleLabel(role: ProcessRole): String = when (role) {
        ProcessRole.GRADLE_DAEMON -> "Gradle daemon"
        ProcessRole.KOTLIN_DAEMON -> "Kotlin daemon"
        ProcessRole.GRADLE_WORKER -> "Gradle worker"
    }

    private fun percent(fraction: Double): String = "${Math.round(fraction * 100)} %"

    /** JDK **major** = the leading numeric segment ("21.0.10" → 21); null when it is not numeric. */
    internal fun leadingNumericSegment(version: String): Int? =
        version.trim().split('.', '-', '_', '+').firstOrNull()?.toIntOrNull()
}
