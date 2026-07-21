package dev.buildhound.gradle

import dev.buildhound.commons.payload.ChangeDiffBase
import java.io.File
import java.io.Serializable
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * Change blast-radius attribution (plan 063, research F13), plugin-side DTO transported CC-safely into
 * the finalizer. Null-on-the-payload is expressed by the ValueSource returning `null` (no resolvable
 * base); when present, [modules] is the distinct, sorted set of Gradle module paths that changed since
 * [base]. File paths are never carried — they die inside [ChangedModuleMapper] (spec §3.7).
 */
data class CollectedChangedModules(
    val base: ChangeDiffBase,
    val modules: List<String> = emptyList(),
    val unattributedChanges: Boolean = false,
) : Serializable

/**
 * Builds the `gradlePath → relDir` module-dir index (plan 063) from the settings descriptor tree, at
 * `settingsEvaluated` — the descriptors are populated once the settings script's `include(...)` calls
 * have run (never at `apply()`). Reading only `settings.rootProject` (path + `projectDir`) touches no
 * `Gradle.includedBuilds` (the call that forced [BuildStructureWalker] off `settingsEvaluated`, plan
 * 069), so this hook is safe here. `projectDir` is relativized to the build [Settings.rootDir] with
 * forward slashes (git's separator, matching `--relative` output — never a raw absolute path); the root
 * project maps to `""`. Every access is in-memory (no `.exists()`), so the walk adds no CC fingerprint
 * input (architecture §2 rule 9); the resulting map is baked into the CC entry and replayed on a hit
 * (module structure is CC-keyed — a `settings.gradle` edit invalidates the entry).
 */
internal object ModuleDirIndexWalker {

    fun walk(settings: Settings): Map<String, String> {
        val rootDir = settings.rootDir
        val index = LinkedHashMap<String, String>()
        fun visit(descriptor: ProjectDescriptor) {
            index[descriptor.path] = relativize(rootDir, descriptor.projectDir)
            for (child in descriptor.children) visit(child)
        }
        visit(settings.rootProject)
        return index
    }

    /** [projectDir] relative to [rootDir], forward-slash; `""` for the root project. Never an absolute path. */
    private fun relativize(rootDir: File, projectDir: File): String =
        rootDir.toPath().relativize(projectDir.toPath()).joinToString("/")
}

/**
 * Collects the change blast-radius module set (plan 063, research F13) by running one bounded
 * `git diff --name-only --relative` against a resolvable base, then mapping the changed files to Gradle
 * modules — never emitting the file paths (spec §3.7). Reuses the [GitExec]/[BoundedExec] infrastructure
 * (bounded wait, `destroyForcibly`, warn-degrade — plans 004/015) and the plan-050 subdirectory
 * discovery / `--relative` confinement; it never invents a new subprocess path.
 *
 * Same CC discipline as [VcsValueSource]: obtained only through a `FlowAction` parameter, so it
 * re-executes on CC reuse (the diff stays fresh) — inheriting the same CC-*hit*-replay caveat, where a
 * hit skips configuration so a base baked at store time is replayed rather than re-resolved. The
 * [moduleDirIndex] is baked into the CC entry (module structure is CC-keyed — a `settings.gradle` edit
 * invalidates it) while the git diff itself stays execution-time-fresh.
 *
 * Base resolution, in order (the whole block degrades to `null` — absent — if none resolves):
 * 1. [targetBranch] non-null (a CI PR base ref) → `git diff --name-only --relative --end-of-options
 *    origin/<target>...HEAD` (three-dot = merge-base, the PR's own changes); base
 *    [ChangeDiffBase.CI_PR_BASE]. A failure here (the base ref not fetched — the dominant CI-sparsity
 *    case) degrades to null; it does **not** fall back to the last-sha file (the plan's "in order": a
 *    present target branch commits to CI_PR_BASE).
 * 2. else the recorded [lastShaPath] file exists and parses to a sha → `git diff --name-only --relative
 *    --end-of-options <sha>` (cumulative since that recorded HEAD); base [ChangeDiffBase.LAST_BUILT_SHA].
 * 3. else → null.
 *
 * `--end-of-options` (git ≥ 2.24) sits immediately before the revspec in both cases: a shell-free
 * `ProcessBuilder` already rules out shell-metacharacter injection, but a revspec that itself begins
 * with `-` (a corrupted [lastShaPath] file, or `targetBranch` ever losing its `origin/` prefix) would
 * otherwise still be open to *git option* injection — e.g. a crafted `--output=<path>` revspec makes
 * git overwrite an arbitrary file. `--end-of-options` forces everything after it to be parsed as a
 * revision, never an option, without reclassifying it as a pathspec the way a bare `--` would.
 *
 * Never fails the build: git absent/timeout/non-zero, a detached HEAD with no base, a descriptor-index
 * failure — all degrade to null (or an honest `unattributedChanges` flag). No changed-file path or diff
 * output ever reaches a log line (only static args + the timeout, mirroring [VcsValueSource]).
 */
abstract class ChangedModulesValueSource : ValueSource<CollectedChangedModules, ChangedModulesValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Mirrors `buildhound { enabled }`: when false nothing is probed. */
        val enabled: Property<Boolean>

        /** Absolute path of the root project directory (the git working dir + `--relative` confinement root). */
        val rootDir: Property<String>

        /** Per-probe bound, wired from `buildhound.vcs.timeout.ms` (shared with [VcsValueSource]). */
        val timeoutMillis: Property<Long>

        /**
         * Discover the enclosing repo from a nested Gradle root (plan 050); default true, `false`
         * confines to [rootDir].
         */
        val searchParents: Property<Boolean>

        /** Absolute path of the previous-build HEAD sha file (`.gradle/buildhound/last-built-sha`). */
        val lastShaPath: Property<String>

        /** CI PR base branch (`ci.targetBranch`); null on a non-PR/local build. */
        val targetBranch: Property<String>

        /** Gradle path → project-dir-relative-to-root (`""` for root), baked from the `settingsEvaluated` walk. */
        val moduleDirIndex: MapProperty<String, String>
    }

    override fun obtain(): CollectedChangedModules? {
        if (!parameters.enabled.getOrElse(true)) return null
        val workDir = File(parameters.rootDir.orNull ?: return null)
        val timeoutMillis = parameters.timeoutMillis.getOrElse(GitExec.DEFAULT_TIMEOUT_MS)
        val searchParents = parameters.searchParents.getOrElse(true)
        // The index is keyed gradlePath → relDir in the walk; invert to relDir → gradlePath for the
        // mapper (relDir is the match key). Both stay Gradle paths / relative dirs — no absolute path.
        val moduleDirIndex = parameters.moduleDirIndex.getOrElse(emptyMap())
            .entries.associate { (gradlePath, relDir) -> relDir to gradlePath }

        val (base, diff) = resolveDiff(workDir, timeoutMillis, searchParents) ?: return null
        val changedFiles = diff.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val mapped = ChangedModuleMapper.map(moduleDirIndex, changedFiles)
        return CollectedChangedModules(
            base = base,
            modules = mapped.modules,
            unattributedChanges = mapped.unattributedChanges,
        )
    }

    /**
     * Runs the base-appropriate `git diff` and returns (base, stdout), or null when no base resolved /
     * the chosen diff failed. A resolved base with empty stdout (nothing changed) is a valid success —
     * returns an empty diff string, so the block ships with `modules = []` (honest "base resolved, no
     * changes"), never null.
     */
    @Suppress("ReturnCount") // Each exit represents a distinct unavailable VCS input.
    private fun resolveDiff(workDir: File, timeoutMillis: Long, searchParents: Boolean): Pair<ChangeDiffBase, String>? {
        val targetBranch = parameters.targetBranch.orNull?.trim()?.takeIf { it.isNotEmpty() }
        if (targetBranch != null) {
            // Three-dot = merge-base of origin/<target> and HEAD → the PR's own changes. The ref name is
            // spec'd VCS data (a branch name), never a raw path; ProcessBuilder is shell-free (no shell
            // metacharacter surface) and runGit's `--end-of-options` guards the git-option-injection
            // surface. A missing/un-fetched base ref → NonZeroExit → null (CI sparsity).
            val diff = runGit(workDir, timeoutMillis, searchParents, "origin/$targetBranch...HEAD") ?: return null
            return ChangeDiffBase.CI_PR_BASE to diff
        }
        val lastSha = readLastSha() ?: return null
        val diff = runGit(workDir, timeoutMillis, searchParents, lastSha) ?: return null
        return ChangeDiffBase.LAST_BUILT_SHA to diff
    }

    /**
     * `git diff --name-only --relative --end-of-options <revspec>`; null on a non-zero exit, a timeout,
     * or a missing binary. `--end-of-options` (git ≥ 2.24) sits directly before [revspec] so a
     * `-`-prefixed value is always parsed as a revision, never as a git option (belt-and-suspenders
     * against option/flag injection — see the class doc).
     */
    private fun runGit(workDir: File, timeoutMillis: Long, searchParents: Boolean, revspec: String): String? =
        when (val result = GitExec.run(
            workDir,
            timeoutMillis,
            listOf("diff", "--name-only", "--relative", "--end-of-options", revspec),
            searchParents = searchParents,
        )) {
            is BoundedExec.Result.Success -> result.stdout
            is BoundedExec.Result.NonZeroExit -> null
            is BoundedExec.Result.TimedOut -> {
                // Static message only — the diff output can embed paths, so it is never logged.
                logger.warn(
                    "[buildhound] git diff timed out after {} ms; changed-modules skipped (build unaffected)",
                    timeoutMillis,
                )
                null
            }
            is BoundedExec.Result.Failed -> {
                logger.info("[buildhound] git diff unavailable: {}", result.exceptionClass)
                null
            }
        }

    /** Reads + validates the previous-build HEAD sha; null when the file is absent or unparseable (never throws). */
    private fun readLastSha(): String? = runCatching {
        val path = parameters.lastShaPath.orNull ?: return null
        val file = File(path)
        if (!file.isFile) return null
        VcsParsing.parseSha(file.readText())
    }.getOrNull()

    private companion object {
        val logger = Logging.getLogger(ChangedModulesValueSource::class.java)
    }
}
