package dev.buildhound.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import javax.inject.Inject
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/** Git snapshot of the build's working copy (spec §3.2, payload `vcs` block). */
data class CollectedVcs(
    val branch: String? = null,
    val sha: String? = null,
    val dirty: Boolean? = null,
) : Serializable

/**
 * Runs `git` in the root project directory at execution time (same CC rationale as
 * [EnvironmentValueSource]: obtained only through FlowAction parameters, re-executes on
 * CC reuse so sha/dirty stay fresh). Every probe degrades to null — no git binary, not
 * a repository, empty repository — and the build is never failed.
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
    }

    @get:Inject
    protected abstract val execOperations: ExecOperations

    override fun obtain(): CollectedVcs {
        if (!parameters.enabled.getOrElse(true)) return CollectedVcs()
        val workDir = File(parameters.rootDir.orNull ?: return CollectedVcs())
        return CollectedVcs(
            branch = git(workDir, "rev-parse", "--abbrev-ref", "HEAD")?.let(VcsParsing::parseBranch),
            sha = git(workDir, "rev-parse", "HEAD")?.let(VcsParsing::parseSha),
            dirty = git(workDir, "status", "--porcelain")?.let(VcsParsing::parseDirty),
        )
    }

    private fun git(workDir: File, vararg args: String): String? = runCatching {
        val stdout = ByteArrayOutputStream()
        val result = execOperations.exec { spec ->
            spec.commandLine("git", *args)
            spec.workingDir = workDir
            spec.standardOutput = stdout
            spec.errorOutput = ByteArrayOutputStream() // discard; may contain paths
            spec.environment("GIT_TERMINAL_PROMPT", "0")
            // Never take optional .git/index locks from a telemetry read.
            spec.environment("GIT_OPTIONAL_LOCKS", "0")
            // Never discover an enclosing, unrelated repository above the project.
            workDir.parentFile?.let { spec.environment("GIT_CEILING_DIRECTORIES", it.absolutePath) }
            spec.isIgnoreExitValue = true
        }
        if (result.exitValue == 0) stdout.toString(Charsets.UTF_8) else null
    }.onFailure {
        // Class name only — keep messages (which can embed paths) out of the log.
        logger.info("[buildhound] git probe unavailable: {}", it::class.java.simpleName)
    }.getOrNull()

    private companion object {
        val logger = Logging.getLogger(VcsValueSource::class.java)
    }
}
