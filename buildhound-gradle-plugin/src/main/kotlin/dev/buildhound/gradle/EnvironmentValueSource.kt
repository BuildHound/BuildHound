package dev.buildhound.gradle

import java.io.File
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.security.SecureRandom
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.util.GradleVersion

/**
 * Snapshot of the machine/toolchain environment (spec §3.2), collected outside the
 * configuration model so it stays configuration-cache safe and re-executes on CC reuse.
 * Plain `Serializable` DTO — the FlowAction maps it onto the payload schema (chunk 4).
 */
data class CollectedEnvironment(
    val os: String?,
    val arch: String?,
    val cores: Int?,
    val ramMb: Long?,
    val hostnameHash: String?,
    val userId: String?,
    val gradleVersion: String?,
    val jdkVersion: String?,
) : Serializable

/**
 * Every lookup is individually guarded: a machine where one probe fails (no reverse
 * DNS, exotic JVM without com.sun.management) still reports the rest, and the plugin
 * never fails the build (hard rule).
 *
 * Identity fields (spec §3.7): `pseudonymize=true` (default) requires a salt — when the
 * salt is unavailable the fields are omitted, never silently sent in plaintext.
 * `pseudonymize=false` sends plaintext username/hostname (explicit team choice).
 *
 * The interim per-project salt (plan 003) is read/created *here*, at execution time:
 * file access during the configuration phase is tracked as a CC fingerprint input, so
 * creating the salt at apply time would invalidate the very next build's cache entry.
 */
abstract class EnvironmentValueSource : ValueSource<CollectedEnvironment, EnvironmentValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val pseudonymize: Property<Boolean>
        /** Absolute path of the salt file, e.g. `<rootDir>/.gradle/buildhound/identity.salt`. */
        val identitySaltFile: Property<String>
    }

    override fun obtain(): CollectedEnvironment {
        val hostname = guarded("hostname") { InetAddress.getLocalHost().hostName }
        val username = guarded("user") { System.getProperty("user.name") }
        val pseudonymize = parameters.pseudonymize.getOrElse(true)
        val salt = if (pseudonymize) guarded("salt") { readOrCreateSalt() } else null
        return CollectedEnvironment(
            os = guarded("os") { System.getProperty("os.name") },
            arch = guarded("arch") { System.getProperty("os.arch") },
            cores = guarded("cores") { Runtime.getRuntime().availableProcessors() },
            ramMb = guarded("ram") { totalRamMb() },
            hostnameHash = hostname?.let {
                when {
                    !pseudonymize -> it
                    salt != null -> IdentityHashing.hostnameHash(salt, it)
                    else -> null
                }
            },
            userId = if (username != null && hostname != null) {
                when {
                    !pseudonymize -> username
                    salt != null -> IdentityHashing.userId(salt, username, hostname)
                    else -> null
                }
            } else {
                null
            },
            gradleVersion = guarded("gradle") { GradleVersion.current().version },
            jdkVersion = guarded("jdk") { System.getProperty("java.version") },
        )
    }

    private fun readOrCreateSalt(): ByteArray? {
        val saltFile = File(parameters.identitySaltFile.orNull ?: return null)
        if (saltFile.isFile) {
            saltFile.readBytes().takeIf { it.size == SALT_BYTES }?.let { return it }
            // unexpected size: regenerate rather than trust it
        }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        saltFile.parentFile.mkdirs()
        saltFile.writeBytes(salt)
        return salt
    }

    private fun totalRamMb(): Long? {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        return (bean as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize?.let { it / (1024 * 1024) }
    }

    private fun <T> guarded(what: String, block: () -> T?): T? =
        runCatching(block).onFailure {
            logger.info("[buildhound] environment probe '{}' unavailable: {}", what, it.message)
        }.getOrNull()

    private companion object {
        const val SALT_BYTES = 32
        val logger = Logging.getLogger(EnvironmentValueSource::class.java)
    }
}
