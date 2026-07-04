package dev.buildhound.gradle

import java.io.File
import java.io.Serializable
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/** Git snapshot of the build's working copy (spec §3.2, payload `vcs` block). */
data class CollectedVcs(
    val branch: String? = null,
    val sha: String? = null,
    val dirty: Boolean? = null,
    /** Redacted `remote.origin.url` (plan 027); userInfo stripped for all schemes, fail-closed. */
    val remoteUrl: String? = null,
) : Serializable

/**
 * Runs `git` in the root project directory at execution time (same CC rationale as
 * [EnvironmentValueSource]: obtained only through FlowAction parameters, re-executes on
 * CC reuse so sha/dirty stay fresh). Every probe degrades to null — no git binary, not
 * a repository, empty repository, *hung git* — and the build is never failed. Each
 * subprocess gets a bounded wait ([GitExec], plan 015): on timeout the process is
 * forcibly killed, the remaining probes are skipped, and a single warn line is logged.
 *
 * Only branch name, commit sha, and the *emptiness* of `git status --porcelain` are
 * kept; status output itself (paths) is discarded (spec §3.7: no paths in payloads).
 */
abstract class VcsValueSource : ValueSource<CollectedVcs, VcsValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Mirrors `buildhound { enabled }`: when false nothing is executed. */
        val enabled: Property<Boolean>
        /** Absolute path of the root project directory. */
        val rootDir: Property<String>
        /** Per-probe bound, wired from `buildhound.vcs.timeout.ms` (test seam + escape hatch). */
        val timeoutMillis: Property<Long>
    }

    override fun obtain(): CollectedVcs {
        if (!parameters.enabled.getOrElse(true)) return CollectedVcs()
        val workDir = File(parameters.rootDir.orNull ?: return CollectedVcs())
        val probe = GitProbe(workDir, parameters.timeoutMillis.getOrElse(GitExec.DEFAULT_TIMEOUT_MS))
        return CollectedVcs(
            branch = probe.run("rev-parse", "--abbrev-ref", "HEAD")?.let(VcsParsing::parseBranch),
            sha = probe.run("rev-parse", "HEAD")?.let(VcsParsing::parseSha),
            dirty = probe.run("status", "--porcelain")?.let(VcsParsing::parseDirty),
            // Redacted before it ever leaves this ValueSource — a credentialed remote never lands.
            remoteUrl = probe.run("config", "--get", "remote.origin.url")
                ?.let { dev.buildhound.commons.ci.SourceLinks.redactRemoteUrl(it) },
        )
    }

    /**
     * Runs probes until the first timeout: a git that hung once will hang again, so one
     * timeout budget bounds the whole collection instead of 3×.
     */
    private class GitProbe(private val workDir: File, private val timeoutMillis: Long) {
        private var timedOut = false

        fun run(vararg args: String): String? {
            if (timedOut) return null
            return when (val result = GitExec.run(workDir, timeoutMillis, args.toList())) {
                is BoundedExec.Result.Success -> result.stdout
                is BoundedExec.Result.NonZeroExit -> null
                is BoundedExec.Result.TimedOut -> {
                    timedOut = true
                    // Static probe args and millis only — process output can embed paths.
                    logger.warn(
                        "[buildhound] git {} timed out after {} ms; vcs info skipped (build unaffected)",
                        args.joinToString(" "),
                        timeoutMillis,
                    )
                    null
                }
                is BoundedExec.Result.Failed -> {
                    // Class name only — keep messages (which can embed paths) out of the log.
                    logger.info("[buildhound] git probe unavailable: {}", result.exceptionClass)
                    null
                }
            }
        }
    }

    private companion object {
        val logger = Logging.getLogger(VcsValueSource::class.java)
    }
}
