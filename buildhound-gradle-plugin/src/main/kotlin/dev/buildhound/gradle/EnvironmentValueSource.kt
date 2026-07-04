package dev.buildhound.gradle

import dev.buildhound.commons.ci.CiEnvironment
import dev.buildhound.commons.ci.EnvironmentDetection
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.net.InetAddress
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
    val os: String? = null,
    val arch: String? = null,
    val cores: Int? = null,
    val ramMb: Long? = null,
    val hostnameHash: String? = null,
    val userId: String? = null,
    val gradleVersion: String? = null,
    val jdkVersion: String? = null,
    val ide: String? = null,
    val ideVersion: String? = null,
    val ideSync: Boolean? = null,
    val aiAgent: String? = null,
) : Serializable

/**
 * Every lookup is individually guarded: a machine where one probe fails (no reverse
 * DNS, exotic JVM without com.sun.management) still reports the rest, and the plugin
 * never fails the build (hard rule). Probe failures log the exception *class* only —
 * messages can embed the plaintext hostname (`UnknownHostException`).
 *
 * Identity fields are decided by [IdentityHashing.identityFields] (spec §3.7). The
 * interim per-project salt (plan 003) is read/created *here*, at execution time: file
 * access during the configuration phase is tracked as a CC fingerprint input, so
 * creating the salt at apply time would invalidate the very next build's cache entry.
 */
abstract class EnvironmentValueSource : ValueSource<CollectedEnvironment, EnvironmentValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Mirrors `buildhound { enabled }`: when false nothing is probed, no salt is created. */
        val enabled: Property<Boolean>
        val pseudonymize: Property<Boolean>
        /** Absolute path of the salt file, e.g. `<rootDir>/.gradle/buildhound/identity.salt`. */
        val identitySaltFile: Property<String>
    }

    override fun obtain(): CollectedEnvironment {
        if (!parameters.enabled.getOrElse(true)) return CollectedEnvironment()
        val hostname = guarded("hostname") { InetAddress.getLocalHost().hostName }
        val username = guarded("user") { System.getProperty("user.name") }
        val pseudonymize = parameters.pseudonymize.getOrElse(true)
        val salt = if (pseudonymize) guarded("salt") { IdentitySalt.readOrCreate(parameters.identitySaltFile.orNull) } else null
        val identity = IdentityHashing.identityFields(pseudonymize, salt, username, hostname)
        // IDE + AI-agent detection (plan 027), pure over env + sysprop snapshots. IDE is skipped
        // when a CI context is present (an IDE never runs CI); agent detection runs regardless.
        val env = guarded("env") { System.getenv().toMap() } ?: emptyMap()
        val sysProps = guarded("sysprops") {
            System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) ?: "" }
        } ?: emptyMap()
        val onCi = guarded("ci-detect") { CiEnvironment.detect(env) != null } ?: false
        val ide = if (onCi) {
            EnvironmentDetection.IdeInfo(null, null, null)
        } else {
            guarded("ide") { EnvironmentDetection.detectIde(sysProps, env) }
                ?: EnvironmentDetection.IdeInfo(null, null, null)
        }
        val aiAgent = guarded("agent") { EnvironmentDetection.detectAgent(env, sysProps) }
        return CollectedEnvironment(
            os = guarded("os") { System.getProperty("os.name") },
            arch = guarded("arch") { System.getProperty("os.arch") },
            cores = guarded("cores") { Runtime.getRuntime().availableProcessors() },
            ramMb = guarded("ram") { totalRamMb() },
            hostnameHash = identity.hostnameHash,
            userId = identity.userId,
            gradleVersion = guarded("gradle") { GradleVersion.current().version },
            jdkVersion = guarded("jdk") { System.getProperty("java.version") },
            ide = ide.ide,
            ideVersion = ide.version,
            ideSync = ide.sync,
            aiAgent = aiAgent,
        )
    }

    private fun totalRamMb(): Long? {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        return (bean as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize?.let { it / (1024 * 1024) }
    }

    private fun <T> guarded(what: String, block: () -> T?): T? =
        runCatching(block).onFailure {
            // Class name only: exception messages can carry identity (hostname).
            logger.info("[buildhound] environment probe '{}' unavailable: {}", what, it::class.java.simpleName)
        }.getOrNull()

    private companion object {
        val logger = Logging.getLogger(EnvironmentValueSource::class.java)
    }
}
