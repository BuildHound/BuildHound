package dev.buildhound.gradle

/** Result of mapping a changed-file set to Gradle modules (plan 063): the module set + the honest degraded flag. */
data class MappedChanges(val modules: List<String>, val unattributedChanges: Boolean)

/**
 * Pure path→module mapper (plan 063, research F13) — kept free of Gradle types so the unit `test`
 * source set pins it without the Gradle API on its classpath (the [VcsParsing]/[GitExec] rationale: on
 * Gradle 9+ the test source set no longer gets `gradleApi()` implicitly).
 *
 * Maps each changed file to the **deepest owning Gradle project** using a `relDir → gradlePath` index
 * (project dir relative to the build root, forward-slash; `""` for the root project → `":"`).
 *
 * **Root legitimately owns every non-subproject path** (Gradle's ownership model): a root build file
 * (`build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts`) has no deeper subproject
 * prefix, so it attributes to `":"` — the whole-build blast radius, deliberately kept, not discarded.
 * A file under a subproject dir attributes to that subproject. [MappedChanges.unattributedChanges] is
 * the honest degraded flag (plan 005): it fires only when a changed file matches **no** index entry —
 * i.e. the descriptor walk produced an empty/partial index (root `""` absent) while `git diff` still
 * succeeded ("saw changes, couldn't attribute them"). The raw path is **never** returned or logged;
 * only the derived module set + the boolean leave here (spec §3.7).
 *
 * Longest-prefix matching is **segment-safe** (`f == key || f.startsWith("$key/")`): `"app-core/…"`
 * must never attribute to the `"app"` module. Each relDir is distinct (no ties); the result is sorted
 * for determinism and the cardinality cap (`PayloadCapper`) is a stable subset.
 */
internal object ChangedModuleMapper {

    fun map(moduleDirIndex: Map<String, String>, changedFiles: List<String>): MappedChanges {
        // Longest relDir first so the deepest owning project wins; root ("") is the length-0 fallback.
        val entries = moduleDirIndex.entries.sortedByDescending { it.key.length }
        val modules = sortedSetOf<String>()
        var unattributed = false
        for (raw in changedFiles) {
            val file = raw.trim()
            if (file.isEmpty()) continue
            val owner = entries.firstOrNull { (relDir, _) -> ownsPath(relDir, file) }
            if (owner == null) {
                // No index entry (not even root ":") — an empty/partial descriptor index. Honest flag;
                // the raw path is never emitted.
                unattributed = true
            } else {
                modules.add(owner.value)
            }
        }
        return MappedChanges(modules.toList(), unattributed)
    }

    /** [relDir] owns [file] when it is the root (`""`) or a segment-safe path prefix of [file]. */
    private fun ownsPath(relDir: String, file: String): Boolean =
        relDir.isEmpty() || file == relDir || file.startsWith("$relDir/")
}
