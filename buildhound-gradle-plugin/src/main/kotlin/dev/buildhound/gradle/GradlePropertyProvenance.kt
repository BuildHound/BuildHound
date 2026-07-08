package dev.buildhound.gradle

import dev.buildhound.commons.payload.PropertyOrigin

/**
 * The fixed, non-secret `gradle.properties` allowlist (plan 051, spec §4): three `org.gradle.*`
 * performance flags plus two `android.*` flags the corpus repeatedly cites. A constant, not a
 * `buildhound {}` DSL knob — widening it is a follow-up plan's call, not a build's.
 */
internal val INVOCATION_PROPERTY_ALLOWLIST: List<String> = listOf(
    "org.gradle.caching",
    "org.gradle.parallel",
    "org.gradle.vfs.watch",
    "android.enableJetifier",
    "android.nonTransitiveRClass",
)

/**
 * Attributes each allowlisted `gradle.properties` key to the layer that effectively declares it
 * (plan 051, spec §3.2). A pure function over plain maps — no Gradle type in sight — so it unit
 * tests without `gradleApi()` on the classpath (the `VcsParsing`/`FingerprintHashing` pattern).
 *
 * **Presence-by-precedence, not value-matching**: project and Gradle User Home can both declare
 * `true`, and only "which layer declares it" reveals a silent GUH win — the finding's whole
 * point. Per key, precedence mirrors Gradle's own *Configuring the build environment* table:
 * a `-D<key>` system property outranks the Gradle User Home `gradle.properties`, which outranks
 * the project's own `gradle.properties`.
 *
 * [env] (`ORG_GRADLE_PROJECT_<key>`) is a genuine effective-value source for some keys — notably
 * `android.*`, which AGP may also read from project properties — but a value resolved only from
 * env is never confidently attributable to one of the three named layers, so it is emitted as
 * [PropertyOrigin.UNKNOWN] rather than guessed. A key declared **nowhere** (absent from every
 * input map) is omitted entirely: there is no value to report, so no entry is emitted (e.g.
 * `org.gradle.vfs.watch`'s effective default-on state when the key is unset stays invisible —
 * stated, not worked around, see the plan's Risks).
 */
internal object GradlePropertyProvenance {

    fun resolve(
        allowlist: List<String>,
        projectProps: Map<String, String>,
        guhProps: Map<String, String>,
        sysProps: Map<String, String>,
        env: Map<String, String> = emptyMap(),
    ): List<CollectedPropertyPosture> = allowlist.mapNotNull { key ->
        val override = sysProps[key]
        val guh = guhProps[key]
        val project = projectProps[key]
        val fromEnv = env["ORG_GRADLE_PROJECT_$key"]
        when {
            override != null -> CollectedPropertyPosture(key, override, PropertyOrigin.OVERRIDE)
            guh != null -> CollectedPropertyPosture(key, guh, PropertyOrigin.GRADLE_USER_HOME)
            project != null -> CollectedPropertyPosture(key, project, PropertyOrigin.PROJECT)
            fromEnv != null -> CollectedPropertyPosture(key, fromEnv, PropertyOrigin.UNKNOWN)
            else -> null
        }
    }
}
