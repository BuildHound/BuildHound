package dev.buildhound.gradle

import org.gradle.api.provider.ProviderFactory

/**
 * Reads `buildhound.<key>` gradle-property / `BUILDHOUND_<KEY>` env overrides for the DSL knobs
 * (plan 027, the CCUD `Overrides` pattern; `<KEY>` = `<key>.uppercase().replace('.','_')`). Applied
 * as the `convention()` fallback so precedence is **explicit DSL value → override → built-in
 * default** (an explicitly-set DSL value always wins over a convention).
 *
 * Reading the two providers eagerly at configuration time is CC-safe: `gradleProperty` and
 * `environmentVariable` providers are tracked configuration-cache inputs (the same mechanism plans
 * 022/023 rely on). Parsing is fail-safe: an unparseable boolean/enum is ignored with an info log,
 * never a build failure. **`server.token` is excluded by construction** — a token override would
 * serialize into the on-disk CC entry, so tokens stay env-provider-only (architecture §6).
 */
internal class ConfigOverrides(
    private val providers: ProviderFactory,
    // Injected so this class carries no Gradle `Logging` reference — its pure companion helpers then
    // load in a Gradle-less unit-test JVM. apply() wires this to its own logger.
    private val info: (String) -> Unit = {},
) {

    /** The raw override string for [key] (gradle property first, then env), or null when unset. */
    fun raw(key: String): String? {
        require(isOverridable(key)) { "$key is never overridable (architecture §6)" }
        return providers.gradleProperty("buildhound.$key").orNull
            ?: providers.environmentVariable("BUILDHOUND_${key.uppercase().replace('.', '_')}").orNull
    }

    fun string(key: String): String? = raw(key)?.takeIf { it.isNotEmpty() }

    fun bool(key: String): Boolean? {
        val raw = raw(key) ?: return null
        return parseBool(raw).also {
            if (it == null) info("[buildhound] ignoring non-boolean override buildhound.$key='$raw'")
        }
    }

    fun mode(key: String): TelemetryMode? {
        val raw = raw(key) ?: return null
        return parseEnum<TelemetryMode>(raw).also {
            if (it == null) info("[buildhound] ignoring unknown mode override buildhound.$key='$raw'")
        }
    }

    companion object {
        /** The token is excluded by construction — an override would serialize it into the CC entry. */
        const val EXCLUDED_KEY: String = "server.token"

        fun isOverridable(key: String): Boolean = key != EXCLUDED_KEY

        /** Lenient, fail-safe boolean parse; null when unrecognizable (ignored by the caller). */
        fun parseBool(raw: String): Boolean? = when (raw.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }

        inline fun <reified E : Enum<E>> parseEnum(raw: String): E? =
            enumValues<E>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}
