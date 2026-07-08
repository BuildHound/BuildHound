package dev.buildhound.gradle

import dev.buildhound.commons.payload.GcCollector
import dev.buildhound.commons.payload.ProcessRole
import java.io.Serializable
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * One JVM's collected snapshot (plan 029); the plugin-side DTO mapped to `ProcessInfo` at assembly.
 * [pid] (already parsed from jps since plan 029, carried since plan 065 — see the decision-log row)
 * plus the typed-allowlist [gcCollector]/[compactObjectHeaders] jinfo reads (plan 065).
 */
data class CollectedProcess(
    val role: ProcessRole,
    val pid: Long? = null,
    val heapUsedMb: Long? = null,
    val heapCommittedMb: Long? = null,
    val heapMaxMb: Long? = null,
    val configuredXmxMb: Long? = null,
    val gcTimeMs: Long? = null,
    val rssMb: Long? = null,
    val uptimeS: Long? = null,
    val gcCollector: GcCollector? = null,
    val compactObjectHeaders: Boolean? = null,
) : Serializable

/**
 * End-of-build JVM process probe (plan 029, spec §3.6). Runs `jps`/`jstat`/`jinfo`/`ps` at execution
 * time (same CC rationale as [VcsValueSource]/[EnvironmentValueSource]: obtained only through
 * FlowAction parameters, re-executes on CC reuse). Touches no `Project`/`Gradle` type, so it is
 * inherently isolated-projects-safe.
 *
 * Never fails and never hangs: each JDK-tool exec is bounded ([ProcessMetrics]); one failed probe
 * drops one field (not the process), and the whole obtain is wrapped so any exception — no `jps` on
 * PATH, a killed daemon, a parse miss — degrades to `emptyList()`. A timeout on any exec stops
 * probing further PIDs (a hung tool will hang again). Only numbers/roles/typed-allowlist enums are
 * kept — never a command line (jinfo/ps args can embed secrets, spec §3.7). The pid is carried
 * since plan 065 (superseding plan 029's "no PID" — see the 2026-07-08 decision-log row): an
 * ephemeral host-local integer used only as a within-one-`hostnameHash` correlation key. Failures
 * log the exception **class** only.
 */
abstract class ProcessProbeValueSource : ValueSource<List<CollectedProcess>, ProcessProbeValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Mirrors `buildhound { enabled }` AND `processProbe { enabled }`; false → nothing runs. */
        val enabled: Property<Boolean>

        /** Per-exec bound, wired from `buildhound.processprobe.timeout.ms` (test seam + escape hatch). */
        val timeoutMillis: Property<Long>

        /**
         * Internal test seam only: an absolute path to a fake `jps` (TestKit can't PATH-shadow the
         * daemon's native `jps`, so the timeout failure-injection test overrides it here). Defaults to
         * `jps` on PATH in production.
         */
        val jpsExecutable: Property<String>
    }

    override fun obtain(): List<CollectedProcess> {
        if (!parameters.enabled.getOrElse(true)) return emptyList()
        val timeout = parameters.timeoutMillis.getOrElse(GitExec.DEFAULT_TIMEOUT_MS)
        val metrics = ProcessMetrics(timeout, jps = parameters.jpsExecutable.getOrElse("jps"))
        return runCatching {
            ProcessProbeCollector.collect(
                metrics,
                onTimeout = { tool ->
                    logger.warn("[buildhound] {} timed out after {} ms; process probe truncated (build unaffected)", tool, timeout)
                },
                onFailure = { exceptionClass ->
                    logger.info("[buildhound] process probe tool unavailable: {}", exceptionClass)
                },
            )
        }.getOrElse {
            logger.info("[buildhound] process probe unavailable: {}", it::class.java.simpleName)
            emptyList()
        }
    }

    private companion object {
        val logger = Logging.getLogger(ProcessProbeValueSource::class.java)
    }
}
