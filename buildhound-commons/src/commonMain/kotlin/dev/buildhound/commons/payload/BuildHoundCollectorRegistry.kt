package dev.buildhound.commons.payload

import kotlinx.serialization.json.JsonElement

/**
 * Read-only facts a [BuildHoundExtensionContributor] may consult when building its `extensions`
 * entry (plan 039). Pure commons data — no `Project`/`Gradle`/`Settings` types — so contributors are
 * unit-testable without Gradle and the collection step stays configuration-cache safe. The task list
 * is the immutable snapshot the core collector already produced; addons that need per-project or
 * live-task reach are responsible for their own (public-API) mechanism, not this context.
 */
data class ExtensionContributionContext(
    val projectKey: String?,
    val mode: BuildMode,
    val tasks: List<TaskExecution>,
    /** CI-derived correlation (provider/runId/…), or null on a local build. */
    val ci: CiInfo? = null,
)

/**
 * The contract an addon implements to add one section to `BuildPayload.extensions` without forking
 * core (plan 039) — the single coupling point between core and an addon plugin. Mirrors
 * [dev.buildhound.commons.ci.CiEnvironmentProvider]: a pure interface in commons, `ServiceLoader`
 * discovered on the settings classpath by the core plugin's Flow finalizer.
 *
 * Implementations **must not throw** ([BuildHoundCollectorRegistry] guards anyway, but the contract
 * keeps addons honest) and **must respect spec §3.7** — the returned JSON is *not* deep-scrubbed by
 * core (core cannot know an addon's shape), so an addon must itself carry no absolute paths, env
 * dumps, PII, or secrets. Keep the payload small: the plan-019 size budget still applies and the
 * largest offending entries are dropped.
 */
interface BuildHoundExtensionContributor {
    /** Stable addon id, used as the `extensions` map key, e.g. `"testQuarantine"`, `"testSharding"`. */
    val addonId: String

    /** The addon's JSON section for this build, or null to contribute nothing. */
    fun contribute(context: ExtensionContributionContext): JsonElement?
}

/**
 * Ordered, dedup-by-[addonId][BuildHoundExtensionContributor.addonId], never-throw façade over the
 * discovered contributors (plan 039). Evaluates each in order; a contributor that throws is skipped
 * (one bad addon can never suppress a sibling — same posture as `CiEnvironment.detect`); a
 * null return contributes no key; a duplicate id keeps the **last** value and warns. Commons stays
 * logger-free — the plugin passes an [onWarn] sink (its slf4j logger); tests capture it.
 */
object BuildHoundCollectorRegistry {
    fun collect(
        contributors: List<BuildHoundExtensionContributor>,
        context: ExtensionContributionContext,
        onWarn: (String) -> Unit = {},
    ): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (contributor in contributors) {
            val id = contributor.addonId
            val value = try {
                contributor.contribute(context)
            } catch (t: Throwable) {
                onWarn("buildhound addon '$id' contributor threw ${t::class.simpleName}; skipping its extensions entry")
                null
            } ?: continue
            if (out.put(id, value) != null) {
                onWarn("buildhound addon id '$id' contributed more than once; keeping the last value")
            }
        }
        return out
    }
}
