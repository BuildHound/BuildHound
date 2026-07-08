package dev.buildhound.gradle

import dev.buildhound.commons.payload.PropertyOrigin
import java.io.File
import java.io.Serializable
import java.util.Properties
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/** One allowlisted `gradle.properties` key's effective value + declaring layer, plugin-side DTO. */
data class CollectedPropertyPosture(
    val key: String,
    val value: String,
    val origin: PropertyOrigin,
) : Serializable

/**
 * Invocation-switch & performance-flag posture (plan 051, spec §3.2/§4): the seven public
 * `StartParameter` scalars, plus a plaintext/attributed `gradle.properties` allowlist and
 * genuinely-new `fileEncoding`/`locale` — standing *alongside* the salted `FingerprintInfo`
 * hashes, never replacing them, so absolute-value rules can fire.
 */
data class CollectedInvocation(
    val buildCacheEnabled: Boolean? = null,
    val offline: Boolean? = null,
    val rerunTasks: Boolean? = null,
    val refreshDependencies: Boolean? = null,
    val configureOnDemand: Boolean? = null,
    val maxWorkerCount: Int? = null,
    val parallel: Boolean? = null,
    val fileEncoding: String? = null,
    val locale: String? = null,
    val properties: List<CollectedPropertyPosture> = emptyList(),
) : Serializable

/**
 * Resolves the `gradle.properties` allowlist + `fileEncoding`/`locale` at **execution** time only
 * (same CC rationale as [EnvironmentValueSource]/[FingerprintValueSource]): file/sysprop/env IO
 * during the configuration phase is a CC fingerprint input, so only the two file *locations* are
 * captured in `apply()` — reading them here means the allowlist block re-freshens on a CC hit.
 *
 * The seven `StartParameter` scalars are the opposite shape on purpose: they are read directly in
 * `apply()` (same as the existing `parallel`/`maxWorkerCount` fingerprint scalars) and ride in as
 * baked [Parameters], so on a CC hit they replay the *store-time* command line rather than the
 * hit's actual one — a stated limitation (plan 051 Risks), not a bug: `--offline` is part of the
 * CC key itself, and `--rerun-tasks`/`--refresh-dependencies` force a miss, so the three flags the
 * baseline-hygiene filter reads can never actually be stale.
 */
abstract class InvocationValueSource : ValueSource<CollectedInvocation, InvocationValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Mirrors `buildhound { enabled }`: when false nothing is probed. */
        val enabled: Property<Boolean>

        // Baked StartParameter scalars (plan 022 fingerprints narrowing #3 applies here too).
        val buildCacheEnabled: Property<Boolean>
        val offline: Property<Boolean>
        val rerunTasks: Property<Boolean>
        val refreshDependencies: Property<Boolean>
        val configureOnDemand: Property<Boolean>
        val maxWorkerCount: Property<Int>
        val parallel: Property<Boolean>

        /** Absolute path of the project's `gradle.properties` (location only — never read at apply). */
        val projectPropertiesPath: Property<String>

        /** Absolute path of the Gradle User Home `gradle.properties` (location only). */
        val gradleUserHomePropertiesPath: Property<String>
    }

    override fun obtain(): CollectedInvocation {
        if (!parameters.enabled.getOrElse(true)) return CollectedInvocation()

        val fileEncoding = guarded("file.encoding") { System.getProperty("file.encoding") }
        val locale = guarded("locale") { composeLocale() }
        val projectProps = guarded("project-properties") { loadProperties(parameters.projectPropertiesPath.orNull) }
            ?: emptyMap()
        val guhProps = guarded("guh-properties") { loadProperties(parameters.gradleUserHomePropertiesPath.orNull) }
            ?: emptyMap()
        val sysProps = guarded("sysprops") {
            INVOCATION_PROPERTY_ALLOWLIST.mapNotNull { key -> System.getProperty(key)?.let { key to it } }.toMap()
        } ?: emptyMap()
        // ORG_GRADLE_PROJECT_<key> is the effective-value source for a key that AGP honors as a
        // project property (android.*) but that shows up in none of the three named layers; its
        // origin resolves to UNKNOWN (GradlePropertyProvenance), never guessed as PROJECT/GUH.
        val env = guarded("env") { System.getenv().toMap() } ?: emptyMap()

        val properties = GradlePropertyProvenance.resolve(
            allowlist = INVOCATION_PROPERTY_ALLOWLIST,
            projectProps = projectProps,
            guhProps = guhProps,
            sysProps = sysProps,
            env = env,
        )

        return CollectedInvocation(
            buildCacheEnabled = parameters.buildCacheEnabled.orNull,
            offline = parameters.offline.orNull,
            rerunTasks = parameters.rerunTasks.orNull,
            refreshDependencies = parameters.refreshDependencies.orNull,
            configureOnDemand = parameters.configureOnDemand.orNull,
            maxWorkerCount = parameters.maxWorkerCount.orNull,
            parallel = parameters.parallel.orNull,
            fileEncoding = fileEncoding,
            locale = locale,
            properties = properties,
        )
    }

    private fun composeLocale(): String? {
        val language = System.getProperty("user.language")?.takeIf { it.isNotBlank() } ?: return null
        val country = System.getProperty("user.country")?.takeIf { it.isNotBlank() }
        return if (country.isNullOrBlank()) language else "$language-$country"
    }

    /** Never throws: a missing/unreadable/directory path is an empty map, not a build failure. */
    private fun loadProperties(path: String?): Map<String, String> {
        val file = File(path ?: return emptyMap())
        if (!file.isFile) return emptyMap()
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    private fun <T> guarded(what: String, block: () -> T?): T? =
        runCatching(block).onFailure {
            // Class name only: a gradle.properties path can embed a home dir (spec §3.7).
            logger.info("[buildhound] invocation probe '{}' unavailable: {}", what, it::class.java.simpleName)
        }.getOrNull()

    private companion object {
        val logger = Logging.getLogger(InvocationValueSource::class.java)
    }
}
