package dev.buildhound.gradle

import java.io.Serializable
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/**
 * The build-tool versions BuildHound reports as toolchain dimensions (spec §3.2, plan 046):
 * Android Gradle Plugin, Kotlin Gradle Plugin, and KSP. Every field is nullable — a build that
 * applies none of them, or a probe that cannot resolve a version, reports `null`, which the server
 * renders as an honest "not collected yet" rather than a wrong number. Serializable because it
 * rides the Flow finalizer's `toolchain` parameter (replayed verbatim on a config-cache hit).
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
 * Detects the AGP/KGP/KSP versions of a build by inspecting applied plugins (plan 046).
 *
 * Called at configuration time from the settings plugin's `taskGraph.whenReady` callback (the same
 * non-isolated hook that builds the task dictionary), never from an isolated `beforeProject` action —
 * so it may walk `rootProject.allprojects`. It is gated off under isolated projects exactly like the
 * task dictionary, and never runs on a config-cache hit (the detected value is replayed from the
 * service parameter instead).
 *
 * **Never-fail contract.** Every probe is *pure reflection* over the applied-plugin objects — no
 * compile-time AGP/KGP/KSP type is referenced anywhere here (AGP is only `compileOnly`, absent from a
 * non-Android build), so nothing can `NoClassDefFoundError` at link time. The string `hasPlugin` gate
 * merely avoids probing projects that don't apply the tool. Each per-project probe is individually
 * guarded; a probe that throws degrades that one dimension to `null`, never the build.
 */
internal object ToolchainDetection {

    private val KOTLIN_PLUGIN_IDS = listOf(
        "org.jetbrains.kotlin.jvm",
        "org.jetbrains.kotlin.android",
        "org.jetbrains.kotlin.multiplatform",
    )

    /**
     * Every AGP application-id whose plugin registers an `androidComponents` extension — the classic
     * app/library/test/dynamic-feature plus the newer KMP-library plugin
     * (`com.android.kotlin.multiplatform.library`). A pure KMP-library module applies none of
     * app/library, so it must be gated in explicitly or its AGP version is missed.
     */
    private val ANDROID_PLUGIN_IDS = listOf(
        "com.android.application",
        "com.android.library",
        "com.android.kotlin.multiplatform.library",
        "com.android.dynamic-feature",
        "com.android.test",
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
            if (agp == null && ANDROID_PLUGIN_IDS.any { plugins.hasPlugin(it) }) {
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
     * AGP version. Primary: the `androidComponents` extension's `pluginVersion.version` — AGP's own
     * canonical version string (`AndroidComponentsExtension.pluginVersion`, present since AGP 7.3, whose
     * `AndroidPluginVersion.getVersion()` formats releases and previews exactly as AGP prints them). We
     * look the extension up by its stable name because `findByType` with a raw generic Class does NOT
     * match AGP's parameterized extension registration (verified against a real AGP project); reading
     * `getVersion()` reflectively also keeps this file free of any compile-time AGP type. Fallback for
     * older AGP (7.0–7.2, no `pluginVersion`, or a components extension registered under another name):
     * the `com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION` constant, loaded through AGP's classloader.
     */
    private fun agpVersion(project: Project): String? {
        val pluginVersion = project.extensions.findByName("androidComponents")?.let { invokeNoArg(it, "getPluginVersion") }
        if (pluginVersion != null) noArgStringGetter(pluginVersion, "getVersion")?.let { return it }
        return agpVersionFromConstant(project)
    }

    private fun agpVersionFromConstant(project: Project): String? {
        val androidPlugin = ANDROID_PLUGIN_IDS.firstNotNullOfOrNull { project.plugins.findPlugin(it) } ?: return null
        val versionClass = androidPlugin.javaClass.classLoader?.loadClass("com.android.Version") ?: return null
        return versionClass.getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null) as? String
    }

    /**
     * KGP version via reflection on the applied Kotlin plugin: find the applied plugin whose class is
     * Kotlin's and call its no-arg `getPluginVersion()` (the `KotlinBasePlugin.pluginVersion` property,
     * stable since Kotlin 1.7).
     */
    private fun kgpVersion(project: Project): String? =
        project.plugins.firstNotNullOfOrNull { plugin ->
            plugin.takeIf { it.javaClass.name.startsWith("org.jetbrains.kotlin") }
                ?.let { noArgStringGetter(it, "getPluginVersion") }
        }

    /**
     * KSP version. Primary: `KspGradleSubplugin.getPluginArtifact().version` — the Kotlin
     * `SubpluginArtifact` carries the KSP version and needs no dependency resolution (config-cache
     * safe). Fallback: the KSP jar's `Implementation-Version` manifest attribute (often absent → null,
     * an honest "not collected").
     */
    private fun kspVersion(project: Project): String? {
        val plugin = project.plugins.firstOrNull { it.javaClass.name.startsWith("com.google.devtools.ksp") } ?: return null
        invokeNoArg(plugin, "getPluginArtifact")?.let { artifact ->
            noArgStringGetter(artifact, "getVersion")?.let { return it }
        }
        return plugin.javaClass.getPackage()?.implementationVersion
    }

    /** Invoke a no-arg getter on [target], returning its result or null on any failure. Pure — unit-tested. */
    fun invokeNoArg(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()

    /** Invoke a no-arg getter returning a String (null if absent or not a String). Pure — unit-tested. */
    fun noArgStringGetter(target: Any, name: String): String? = invokeNoArg(target, name) as? String

    private fun <T> guarded(what: String, block: () -> T?): T? =
        runCatching(block).onFailure {
            // Class name only — a version string is not identity, but stay consistent with the
            // environment probes and never let a probe's message reach a log. The logger is fetched
            // here (not a field) so the object's static init stays free of the Gradle API, keeping
            // the reflection helpers unit-testable in the Gradle-free test source set.
            Logging.getLogger(ToolchainDetection::class.java)
                .info("[buildhound] toolchain probe '{}' unavailable: {}", what, it::class.java.simpleName)
        }.getOrNull()
}
