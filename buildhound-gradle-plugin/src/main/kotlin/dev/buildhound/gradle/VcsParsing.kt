package dev.buildhound.gradle

/**
 * Pure parsers for git output — kept free of Gradle types so unit tests never need the
 * Gradle API on their classpath (on Gradle 9+ the `test` source set no longer gets
 * `gradleApi()` implicitly, and loading a `ValueSource` subtype there fails).
 */
internal object VcsParsing {

    /** `HEAD` means detached (typical CI checkout) — no meaningful branch name. */
    fun parseBranch(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it != "HEAD" }

    fun parseSha(raw: String): String? =
        raw.trim().takeIf { it.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}")) }

    fun parseDirty(raw: String): Boolean = raw.isNotBlank()
}
