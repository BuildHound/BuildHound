package dev.buildhound.gradle

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
 * One declared project's config-time-only facts (plan 069): a build-file *path* — never read here,
 * only its location (a config-phase file read is a CC fingerprint input, architecture §2 rule 9) —
 * and whether it has child projects (the aggregator-candidate signal).
 */
data class ProjectDescriptorInfo(
    val buildFilePath: String,
    val hasChildren: Boolean,
) : Serializable

/**
 * The descriptor-tree walk's config-time output (plan 069), mailboxed from `projectsLoaded` into
 * an `AtomicReference` (the toolchain/task-dictionary pattern, plans 046/016) and delivered to
 * [BuildStructureValueSource] as baked parameters — the counts and the path map are frozen at
 * configuration time; only the filesystem `.exists()` probes happen later, at execution.
 */
data class CapturedBuildStructure(
    val projectCount: Int? = null,
    val maxDepth: Int? = null,
    val includedBuildCount: Int? = null,
    val descriptors: Map<String, ProjectDescriptorInfo> = emptyMap(),
) : Serializable

/** Build-structure inventory (plan 069, research F19), plugin-side DTO mirroring the commons `BuildStructureInfo` schema type. */
data class CollectedBuildStructure(
    val projectCount: Int? = null,
    val maxDepth: Int? = null,
    val includedBuildCount: Int? = null,
    val buildSrcPresent: Boolean? = null,
    val sourcesInRoot: Boolean? = null,
    val emptyIntermediateCandidates: List<String> = emptyList(),
) : Serializable

/**
 * Walks the declared project-descriptor tree at `projectsLoaded` (plan 069, research F19):
 * `Settings.rootProject` is a [ProjectDescriptor], populated only once the settings script's
 * `include(...)` calls have run — earlier than that (`apply()`) the tree is still empty (the F19
 * narrowing that rules out capturing this in `apply()` directly).
 *
 * **Not `settingsEvaluated`** (the plan's original choice): `Gradle.includedBuilds` throws
 * `"Included builds are not yet available for this build"` when read from inside
 * `settingsEvaluated` (verified empirically against the project's own Gradle distribution) —
 * `includeBuild(...)` registrations exist by then, but the composite isn't wired up until the next
 * lifecycle step. `projectsLoaded` is that next step: the descriptor tree is unchanged and
 * `includedBuilds` is populated, and it still fires before any project's build script evaluates
 * (still configuration time, still before the task graph) — also verified empirically to populate
 * correctly under `-Dorg.gradle.unsafe.isolated-projects=true`, alongside the reasoning below.
 *
 * Descriptors are settings-level metadata, not `Project` — the walk stays legal under isolated
 * projects (no cross-project access), unlike `taskGraph.allTasks` (plan 016's task dictionary),
 * which is why this inventory does not need an isolated-projects degradation gate. Every access here
 * (`path`/`buildFile`/`children`) is in-memory only; no `.exists()` call, so the walk itself never
 * becomes a configuration-cache fingerprint input (architecture §2 rule 9).
 */
internal object BuildStructureWalker {

    fun walk(settings: Settings): CapturedBuildStructure {
        val descriptors = LinkedHashMap<String, ProjectDescriptorInfo>()
        var count = 0
        var maxDepth = 0
        fun visit(descriptor: ProjectDescriptor) {
            count++
            val depth = depthOf(descriptor.path)
            if (depth > maxDepth) maxDepth = depth
            descriptors[descriptor.path] = ProjectDescriptorInfo(
                buildFilePath = descriptor.buildFile.absolutePath,
                hasChildren = descriptor.children.isNotEmpty(),
            )
            for (child in descriptor.children) visit(child)
        }
        visit(settings.rootProject)
        return CapturedBuildStructure(
            projectCount = count,
            maxDepth = maxDepth,
            includedBuildCount = settings.gradle.includedBuilds.size,
            descriptors = descriptors,
        )
    }

    /** Path-segment count: root (`":"`) is depth 0; each further colon is one nesting level. */
    private fun depthOf(path: String): Int = if (path == ":") 0 else path.count { it == ':' }
}

/**
 * Execution-time half of the build-structure inventory (plan 069, research F19): the config-time
 * walk ([BuildStructureWalker], driven from `projectsLoaded`) captures the declared descriptor
 * tree with build-file *paths* only; every `.exists()` check happens here, in [obtain], at execution
 * time — the same CC rationale as [VcsValueSource]/[FingerprintValueSource]: a `ValueSource` read
 * only through a `FlowAction` parameter re-executes on CC reuse, so the filesystem probes stay fresh
 * without re-fingerprinting configuration.
 *
 * [CollectedBuildStructure.emptyIntermediateCandidates] is a **ranked heuristic candidate list, not a
 * verdict** — an `allprojects{}`-configured aggregator with no build file of its own is a
 * *recommended* Gradle pattern, not necessarily dead weight, so the plugin ships only the structural
 * fact (spec §3.7: Gradle paths, never a judgment or a filesystem path). Sorted for determinism and
 * capped so a pathological monorepo can't blow the payload budget — no `PayloadCapper`/`PayloadScrubber`
 * change (there is no free text here to bound; the cap lives in the collecting `ValueSource` itself,
 * per the plan's design).
 */
abstract class BuildStructureValueSource : ValueSource<CollectedBuildStructure, BuildStructureValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Mirrors `buildhound { enabled }`: when false nothing is probed. */
        val enabled: Property<Boolean>

        /** Absolute path of the root project directory. */
        val rootDir: Property<String>

        val projectCount: Property<Int>
        val maxDepth: Property<Int>
        val includedBuildCount: Property<Int>

        /** Gradle path -> (buildFilePath, hasChildren), baked from the `projectsLoaded` walk. */
        val descriptors: MapProperty<String, ProjectDescriptorInfo>
    }

    override fun obtain(): CollectedBuildStructure {
        if (!parameters.enabled.getOrElse(true)) return CollectedBuildStructure()
        val rootDir = parameters.rootDir.orNull ?: return CollectedBuildStructure()
        // projectCount is the walk's own signal that projectsLoaded actually populated the mailbox
        // (master switch off, or a guarded walk failure, both leave it null) — treat the whole block
        // as one atomic capture rather than shipping buildSrc/sourcesInRoot alone: they are cheap to
        // probe independently of the walk, but a partially populated block is worse than an honest
        // null one here (never-fail contract; plan 069 exit criteria: the block degrades to null).
        parameters.projectCount.orNull ?: return CollectedBuildStructure()
        val descriptors = parameters.descriptors.getOrElse(emptyMap())

        val candidates = descriptors.entries
            .asSequence()
            .filter { (_, info) -> info.hasChildren && !File(info.buildFilePath).exists() }
            .map { it.key }
            .sorted()
            .toList()
        val cappedCandidates = if (candidates.size > MAX_EMPTY_INTERMEDIATE_CANDIDATES) {
            logger.info(
                "[buildhound] {} empty-intermediate candidates found; capped to {}",
                candidates.size,
                MAX_EMPTY_INTERMEDIATE_CANDIDATES,
            )
            candidates.take(MAX_EMPTY_INTERMEDIATE_CANDIDATES)
        } else {
            candidates
        }

        return CollectedBuildStructure(
            projectCount = parameters.projectCount.orNull,
            maxDepth = parameters.maxDepth.orNull,
            includedBuildCount = parameters.includedBuildCount.orNull,
            buildSrcPresent = File(rootDir, "buildSrc").exists(),
            sourcesInRoot = File(rootDir, "src").exists(),
            emptyIntermediateCandidates = cappedCandidates,
        )
    }

    private companion object {
        /** Cardinality guardrail (plan 069, spec §3.9 discipline): bounds a pathological monorepo;
         * ranked (sorted) first so the surviving subset is deterministic. */
        const val MAX_EMPTY_INTERMEDIATE_CANDIDATES = 500
        val logger = Logging.getLogger(BuildStructureValueSource::class.java)
    }
}
