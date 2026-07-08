package dev.buildhound.gradle

import dev.buildhound.commons.payload.PropertyOrigin

/**
 * The fixed, non-secret `gradle.properties` allowlist (plan 051, spec §4): three `org.gradle.*`
 * performance flags plus two `android.*` flags the corpus repeatedly cites. A constant, not a
 * `buildhound {}` DSL knob — widening it is a follow-up plan's call, not a build's.
 *
 * All five keys are boolean flags, which is what makes emitting [CollectedPropertyPosture.value]
 * as plaintext safe without a dedicated scrubber rule (spec §3.7 — the existing whole-payload
 * scrubber has nothing free-form to catch here). **Widening this list to a non-boolean or
 * free-form key is not a one-line change**: it also needs scrubber coverage for
 * `GradlePropertyPosture.value`, since that value would then be exactly the kind of arbitrary
 * text the scrubber is meant to catch.
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
 * point.
 *
 * **Precedence is per key family, not uniform** (plan 051 Design — the namespaces are read by
 * different consumers over different channels, confirmed per-key rather than assumed uniform):
 * - `org.gradle.*` keys are genuine *Gradle properties*: their command-line/override channel is a
 *   `-D<key>` system property. Only `-D`, the Gradle User Home `gradle.properties`, and the
 *   project's own `gradle.properties` are valid sources; [env] (`ORG_GRADLE_PROJECT_<key>`) is
 *   *not* a channel AGP/Gradle honors for these keys, so a value resolvable only from env is
 *   emitted as [PropertyOrigin.UNKNOWN] rather than guessed as an override.
 * - `android.*` keys are *project properties* read by AGP (e.g. `project.findProperty(...)`):
 *   their command-line override channel is `-P` (`StartParameter.projectProperties`), not `-D` —
 *   a plain JVM system property never reaches AGP's project-property lookup. `-P` and the
 *   `ORG_GRADLE_PROJECT_<key>` env convention are both genuine override channels for this family
 *   and outrank the two `gradle.properties` files, exactly like `-D` does for `org.gradle.*`. A
 *   bare `-D` on an `android.*` key is **not** a confirmed channel, so it is never consulted —
 *   ignoring it rather than guessing keeps the "confirmed per key" rule honest.
 *
 * A key declared **nowhere** valid for its family (absent from every applicable input map) is
 * omitted entirely: there is no value to report, so no entry is emitted (e.g.
 * `org.gradle.vfs.watch`'s effective default-on state when the key is unset stays invisible —
 * stated, not worked around, see the plan's Risks).
 */
internal object GradlePropertyProvenance {

    private const val PROJECT_PROPERTY_PREFIX = "android."

    fun resolve(
        allowlist: List<String>,
        projectProps: Map<String, String>,
        guhProps: Map<String, String>,
        sysProps: Map<String, String>,
        env: Map<String, String> = emptyMap(),
        cliProjectProperties: Map<String, String> = emptyMap(),
    ): List<CollectedPropertyPosture> = allowlist.mapNotNull { key ->
        if (key.startsWith(PROJECT_PROPERTY_PREFIX)) {
            resolveProjectProperty(key, projectProps, guhProps, cliProjectProperties, env)
        } else {
            resolveGradleProperty(key, projectProps, guhProps, sysProps, env)
        }
    }

    /**
     * `org.gradle.*` family: `-D<key>` system property ⇒ [PropertyOrigin.OVERRIDE], else GUH file,
     * else project file, else — since [env] is not a confirmed channel for this family — a value
     * resolvable only from `ORG_GRADLE_PROJECT_<key>` is [PropertyOrigin.UNKNOWN] rather than
     * omitted (it *is* a real effective value some tooling could pick up, just not confidently
     * attributable to one of the three named layers), else `null`.
     */
    private fun resolveGradleProperty(
        key: String,
        projectProps: Map<String, String>,
        guhProps: Map<String, String>,
        sysProps: Map<String, String>,
        env: Map<String, String>,
    ): CollectedPropertyPosture? {
        val override = sysProps[key]
        val guh = guhProps[key]
        val project = projectProps[key]
        val fromEnv = env["ORG_GRADLE_PROJECT_$key"]
        return when {
            override != null -> CollectedPropertyPosture(key, override, PropertyOrigin.OVERRIDE)
            guh != null -> CollectedPropertyPosture(key, guh, PropertyOrigin.GRADLE_USER_HOME)
            project != null -> CollectedPropertyPosture(key, project, PropertyOrigin.PROJECT)
            fromEnv != null -> CollectedPropertyPosture(key, fromEnv, PropertyOrigin.UNKNOWN)
            else -> null
        }
    }

    /**
     * `android.*` family: `-P<key>` (`cliProjectProperties`) or `ORG_GRADLE_PROJECT_<key>` (env)
     * ⇒ [PropertyOrigin.OVERRIDE] — `-P` wins when both are present, mirroring Gradle's own
     * command-line-beats-env layering — else GUH file, else project file, else `null` (a bare
     * `-D` is never a valid channel for this family — see class doc).
     */
    private fun resolveProjectProperty(
        key: String,
        projectProps: Map<String, String>,
        guhProps: Map<String, String>,
        cliProjectProperties: Map<String, String>,
        env: Map<String, String>,
    ): CollectedPropertyPosture? {
        val override = cliProjectProperties[key] ?: env["ORG_GRADLE_PROJECT_$key"]
        val guh = guhProps[key]
        val project = projectProps[key]
        return when {
            override != null -> CollectedPropertyPosture(key, override, PropertyOrigin.OVERRIDE)
            guh != null -> CollectedPropertyPosture(key, guh, PropertyOrigin.GRADLE_USER_HOME)
            project != null -> CollectedPropertyPosture(key, project, PropertyOrigin.PROJECT)
            else -> null
        }
    }
}
