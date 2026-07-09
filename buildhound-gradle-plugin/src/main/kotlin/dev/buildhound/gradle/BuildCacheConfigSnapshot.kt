package dev.buildhound.gradle

import java.io.Serializable
import org.gradle.caching.configuration.BuildCacheConfiguration

/**
 * Plugin-side snapshot of the committed `Settings.buildCache` config (plan 067, research F17): whether
 * the local + remote caches are enabled, whether the remote pushes, and the remote backend's normalized
 * class `simpleName`. Serializable because it rides the Flow finalizer's `buildCache` parameter (baked
 * into and replayed verbatim from the config-cache entry — cache *configuration* is stable across builds,
 * unlike per-build timings, so a CC-hit replay is correct here).
 *
 * **Privacy (spec §3.7, named in the plan):** only booleans + a normalized type identifier are ever
 * captured. The remote backend's URL (`HttpBuildCache.getUrl()` — a hostname, possibly with embedded
 * credentials) and the local cache's directory (`DirectoryBuildCache.getDirectory()` — an absolute path)
 * are **never** read; [BuildCacheConfigReader.snapshot] touches only `isEnabled`/`isPush`/`javaClass`.
 */
data class BuildCacheConfigSnapshot(
    val localEnabled: Boolean? = null,
    val remoteEnabled: Boolean? = null,
    val remotePush: Boolean? = null,
    val remoteType: String? = null,
) : Serializable {
    fun isEmpty(): Boolean =
        localEnabled == null && remoteEnabled == null && remotePush == null && remoteType == null

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Reads the public `Settings.buildCache` DSL into a [BuildCacheConfigSnapshot] (plan 067). Public Gradle
 * API only (`BuildCacheConfiguration`/`BuildCache` — no internal type, so this stays on the always-on
 * core path, unlike the internal-adapters transfer timings). Every read is `runCatching → null`, so a
 * surprising Gradle-version shape degrades a field to `null` and never fails the build (spec §3.1).
 */
object BuildCacheConfigReader {

    /**
     * Normalizes Gradle's runtime class name for the remote backend to its stable, documented name
     * (e.g. `HttpBuildCache`). Pure — no Gradle types — so it unit-tests without a build. Gradle
     * instantiates DSL types through a decorating subclass (`…_Decorated`) that may be proxied
     * (`…$$…`); both are stripped so the wire value is the backend's own `simpleName`, never a
     * generated-subclass artifact. Only the `$$` proxy marker is stripped, not every `$` — a legitimate
     * `simpleName` that happens to contain a single dollar (review finding: an obfuscated or
     * framework-generated backend class) survives untouched. A null/blank input (or one that strips to
     * empty) returns null.
     */
    fun normalizeRemoteType(className: String?): String? {
        val raw = className?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw.substringBefore("$$").removeSuffix("_Decorated").takeIf { it.isNotEmpty() }
    }

    /**
     * Snapshots the committed build-cache configuration. Reads `local.isEnabled`, `remote?.isEnabled`,
     * `remote?.isPush`, and the remote's normalized class name — nothing that could carry a URL or a
     * filesystem path (spec §3.7). A null `remote` (no remote backend configured at all) leaves the three
     * remote fields null, which is exactly the "no remote cache configured" signal the finding wants.
     */
    fun snapshot(buildCache: BuildCacheConfiguration): BuildCacheConfigSnapshot {
        val localEnabled = runCatching { buildCache.local.isEnabled }.getOrNull()
        val remote = runCatching { buildCache.remote }.getOrNull()
        return BuildCacheConfigSnapshot(
            localEnabled = localEnabled,
            remoteEnabled = remote?.let { runCatching { it.isEnabled }.getOrNull() },
            remotePush = remote?.let { runCatching { it.isPush }.getOrNull() },
            remoteType = remote?.let { normalizeRemoteType(runCatching { it.javaClass.simpleName }.getOrNull()) },
        )
    }
}
