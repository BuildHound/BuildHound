package dev.buildhound.gradle

import com.android.build.api.AndroidPluginVersion
import com.android.build.api.variant.AndroidComponentsExtension
import java.io.Serializable
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/**
 * The build-tool versions BuildHound reports as toolchain dimensions (spec §3.2, plan 044):
 * Android Gradle Plugin, Kotlin Gradle Plugin, and KSP. Every field is nullable — a build that
 * applies none of them, or a probe that cannot resolve a version, reports `null`, which the server
 * renders as an honest "not collected yet" rather than a wrong number. Serializable because it
 * rides a [TaskEventCollector] build-service parameter (replayed verbatim on a config-cache hit).
 */
data class DetectedToolchain(
    val agp: String? = null,
    val kgp: String? = null,
    val ksp: String? = null,
) : Serializable {
    fun isEmpty(): Boolean = agp == null && kgp == null && ksp == null

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Detects the AGP/KGP/KSP versions of a build by inspecting applied plugins (plan 044).
 *
 * Called at configuration time from the settings plugin's `taskGraph.whenReady` callback (the same
 * non-isolated hook that builds the task dictionary), never from an isolated `beforeProject` action —
 * so it may walk `rootProject.allprojects`. It is gated off under isolated projects exactly like the
 * task dictionary, and never runs on a config-cache hit (the detected value is replayed from the
 * service parameter instead).
 *
 * **Never-fail + no-eager-AGP-linking contract** (mirrors [AndroidArtifactCollector]): BuildHound is
 * a *settings* plugin, so AGP is `compileOnly` and absent from a non-Android build's classpath. AGP
 * types are referenced *only* inside [agpVersion], which the loop enters *only* after a string-based
 * `hasPlugin` check confirms AGP is applied — so the JVM never verifies/links an AGP type on a build
 * that lacks AGP. Every per-project probe is individually guarded; a probe that throws (including a
 * `NoClassDefFoundError` from an unexpected classpath) degrades that one dimension to `null`.
 */
internal object ToolchainDetection {

    private val KOTLIN_PLUGIN_IDS = listOf(
        "org.jetbrains.kotlin.jvm",
        "org.jetbrains.kotlin.android",
        "org.jetbrains.kotlin.multiplatform",
    )

    /**
     * First non-null version found across [projects] per dimension (all modules in one build share
     * the same plugin versions in practice, so the first hit is representative). Stops early once all
     * three are known.
     */
    fun detect(projects: Iterable<Project>): DetectedToolchain {
        var agp: String? = null
        var kgp: String? = null
        var ksp: String? = null
        for (project in projects) {
            val plugins = project.pluginManager
            if (agp == null && (plugins.hasPlugin("com.android.application") || plugins.hasPlugin("com.android.library"))) {
                agp = guarded("agp") { agpVersion(project) }
            }
            if (kgp == null && KOTLIN_PLUGIN_IDS.any { plugins.hasPlugin(it) }) {
                kgp = guarded("kgp") { kgpVersion(project) }
            }
            if (ksp == null && plugins.hasPlugin("com.google.devtools.ksp")) {
                ksp = guarded("ksp") { kspVersion(project) }
            }
            if (agp != null && kgp != null && ksp != null) break
        }
        return DetectedToolchain(agp = agp, kgp = kgp, ksp = ksp)
    }

    /**
     * AGP version via the public Variant API ([AndroidComponentsExtension.getPluginVersion], present
     * since AGP 7.3). This is the ONLY method that references an AGP type; the caller enters it only
     * when an Android plugin is applied, so the type is on the classpath by the time it is verified.
     */
    private fun agpVersion(project: Project): String? =
        project.extensions.findByType(AndroidComponentsExtension::class.java)?.pluginVersion?.let(::formatAgpVersion)

    private fun formatAgpVersion(version: AndroidPluginVersion): String = buildString {
        append(version.major).append('.').append(version.minor).append('.').append(version.micro)
        // previewType/preview are set together on alpha/beta/rc builds; released versions omit them.
        version.previewType?.let { append('-').append(it).append(version.preview) }
    }

    /**
     * KGP version via reflection on the applied Kotlin plugin. KGP is not a BuildHound dependency
     * (the plugin must apply to non-Kotlin builds), so we never link a KGP type: we find the applied
     * plugin whose class is Kotlin's and call its no-arg `getPluginVersion()` (the
     * `KotlinBasePlugin.pluginVersion` property, stable since Kotlin 1.7) reflectively.
     */
    private fun kgpVersion(project: Project): String? =
        project.plugins.firstNotNullOfOrNull { plugin ->
            plugin.takeIf { it.javaClass.name.startsWith("org.jetbrains.kotlin") }
                ?.let { noArgStringGetter(it, "getPluginVersion") }
        }

    /**
     * KSP version — best-effort. KSP exposes no public version API, so we read the KSP plugin jar's
     * `Implementation-Version` manifest attribute (via the package). It legitimately returns `null`
     * on KSP builds whose jar omits that attribute; the dimension then stays honestly "not collected".
     */
    private fun kspVersion(project: Project): String? =
        project.plugins
            .firstOrNull { it.javaClass.name.startsWith("com.google.devtools.ksp") }
            ?.javaClass?.getPackage()?.implementationVersion

    /** Invoke a no-arg getter returning a String, if the target declares one. Pure — unit-tested. */
    fun noArgStringGetter(target: Any, name: String): String? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? String }.getOrNull()

    private fun <T> guarded(what: String, block: () -> T?): T? =
        runCatching(block).onFailure {
            // Class name only — a version string is not identity, but stay consistent with the
            // environment probes and never let a probe's message reach a log. The logger is fetched
            // here (not a field) so the object's static init stays free of the Gradle API, keeping
            // [noArgStringGetter] unit-testable in the Gradle-free test source set.
            Logging.getLogger(ToolchainDetection::class.java)
                .info("[buildhound] toolchain probe '{}' unavailable: {}", what, it::class.java.simpleName)
        }.getOrNull()
}
