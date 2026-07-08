package dev.buildhound.gradle

import dev.buildhound.commons.payload.JvmArtifactKind
import java.io.Serializable

/**
 * Where one JVM archive task writes its output, captured at configuration time (plan 072, research
 * F22). Location only — never a file read — so it adds no configuration-cache fingerprint input; the
 * finalizer reads `File.length()` at execution time, gated on the task's produced-output outcome +
 * `File.exists()` (only-what-ran). Serializable so it rides the finalizer's Flow-action
 * `jvmArtifacts` parameter (the plan-046 mailbox→finalizer-param channel), replayed verbatim on a
 * configuration-cache hit when the `whenReady` callback that built it never runs.
 *
 * [module] is a project-internal Gradle path (`:app`, `:` for the root); [taskPath] joins against the
 * finalizer's task snapshot to read the outcome. [archivePath] is the resolved absolute output path —
 * used only to `File.length()`, and **never** enters the payload (spec §3.7); it is baked into the
 * **local** CC entry only, never egressed.
 */
data class JvmArtifactLocation(
    val module: String?,
    val kind: JvmArtifactKind,
    val taskPath: String,
    val archivePath: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
