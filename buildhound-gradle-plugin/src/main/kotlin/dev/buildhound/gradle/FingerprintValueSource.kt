package dev.buildhound.gradle

import java.io.Serializable
import java.util.TimeZone
import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * Build-level input fingerprints (plan 022), salted hashes, for CC-safe transport into the
 * FlowAction.
 */
data class CollectedFingerprints(val build: Map<String, String> = emptyMap()) : Serializable

/**
 * Build-level input fingerprinting (plan 022, spec §4) at execution time, so probes never become
 * configuration-cache inputs and the values re-hash on CC reuse. Built-in keys plus the
 * `fingerprints {}` allowlists are hashed with the shared per-project salt ([FingerprintHashing]);
 * without a salt the whole block is omitted (never plaintext), matching the identity rule.
 *
 * Gradle-property *values* arrive pre-resolved in [Parameters.gradleProperties] (they are CC inputs
 * already); system properties and env vars are read here in `obtain()`.
 */
abstract class FingerprintValueSource :
    ValueSource<CollectedFingerprints, FingerprintValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val enabled: Property<Boolean>
        val identitySaltFile: Property<String>
        val systemProperties: SetProperty<String>
        val envVars: SetProperty<String>
        /** name -> value, resolved from `providers.gradleProperty` at configuration time. */
        val gradleProperties: MapProperty<String, String>
        val parallel: Property<Boolean>
        val maxWorkers: Property<Int>
        /** Max allowlisted names honored per family (cardinality cap, plan 022/019). */
        val maxNames: Property<Int>
        /** Max key-name length; longer keys are truncated. */
        val maxKeyChars: Property<Int>
    }

    override fun obtain(): CollectedFingerprints {
        if (!parameters.enabled.getOrElse(true)) return CollectedFingerprints()
        val salt = runCatching {
            IdentitySalt.readOrCreate(parameters.identitySaltFile.orNull)
        }.getOrNull()
        if (salt == null) {
            // Requested but unsaltable (e.g. salt path is a directory): omit, warn once, never
            // fail.
            logger.warn("[buildhound] input fingerprints skipped: no identity salt available")
            return CollectedFingerprints()
        }

        val maxNames = parameters.maxNames.getOrElse(DEFAULT_MAX_NAMES)
        val maxKeyChars = parameters.maxKeyChars.getOrElse(DEFAULT_MAX_KEY_CHARS)
        val raw = LinkedHashMap<String, String>()

        // Built-in keys (research §4/§5 miss causes). jdk.version is the FULL runtime version —
        // Gradle keys the cache on the major only, a documented miss cause.
        fun sysProp(key: String, prop: String) = System.getProperty(prop)?.let { raw[key] = it }
        runCatching {
            sysProp("jdk.home", "java.home")
            sysProp("jdk.vendor", "java.vendor")
            sysProp("jdk.version", "java.runtime.version")
            sysProp("file.encoding", "file.encoding")
            sysProp("user.language", "user.language")
            sysProp("user.country", "user.country")
            sysProp("os.name", "os.name")
            sysProp("os.arch", "os.arch")
            raw["timezone"] = TimeZone.getDefault().id
            raw["gradle.parallel"] = parameters.parallel.getOrElse(false).toString()
            parameters.maxWorkers.orNull?.let { raw["gradle.maxWorkers"] = it.toString() }
        }

        // Allowlisted extras: prefixed keys, read at execution. Sort before the cap so the
        // surviving subset is deterministic, and warn once if any family is over cap.
        val sysNames = parameters.systemProperties.getOrElse(emptySet())
        val envNames = parameters.envVars.getOrElse(emptySet())
        val gradleProps = parameters.gradleProperties.getOrElse(emptyMap())
        if (sysNames.size > maxNames || envNames.size > maxNames || gradleProps.size > maxNames) {
            logger.warn(
                "[buildhound] fingerprint allowlist over cap ({} names/family); extra names dropped",
                maxNames,
            )
        }
        sysNames.sorted().take(maxNames).forEach { name ->
            System.getProperty(name)?.let { raw["sysProps-$name"] = it }
        }
        envNames.sorted().take(maxNames).forEach { name ->
            System.getenv(name)?.let { raw["env-$name"] = it }
        }
        gradleProps.entries
            .sortedBy { it.key }
            .take(maxNames)
            .forEach { (name, value) -> raw["gradleProp-$name"] = value }

        // Hash every value with the salt; truncate over-long key names.
        val hashed = LinkedHashMap<String, String>()
        for ((key, value) in raw) {
            hashed[key.take(maxKeyChars)] = FingerprintHashing.hash(salt, value)
        }
        return CollectedFingerprints(build = hashed)
    }

    private companion object {
        const val DEFAULT_MAX_NAMES = 32
        const val DEFAULT_MAX_KEY_CHARS = 64
        val logger = Logging.getLogger(FingerprintValueSource::class.java)
    }
}
