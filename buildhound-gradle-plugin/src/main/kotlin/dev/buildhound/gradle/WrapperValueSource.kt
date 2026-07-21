package dev.buildhound.gradle

import dev.buildhound.commons.payload.WrapperDistributionType
import java.io.File
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.util.GradleVersion

/**
 * Wrapper & startup-phase telemetry (plan 066, research F16), plugin-side DTO mirroring the
 * commons `WrapperInfo` schema type. [distMtimeMs]/[jarMtimeMs]/[distPresent]/[jvmStartMs] are
 * transport-only — raw filesystem mtimes and the JVM start time never reach the payload (spec
 * §3.7); [PayloadAssembler] consumes them to compute `guhWarmth` and discards them.
 */
data class CollectedWrapper(
    val variant: WrapperDistributionType? = null,
    val distributionSha256Pinned: Boolean? = null,
    val wrapperJarSha256: String? = null,
    /** mtime (epoch ms) of this build's resolved `<GUH>/wrapper/dists/gradle-<version>-<variant>` dir. */
    val distMtimeMs: Long? = null,
    /**
     * mtime (epoch ms) of the project's own `gradle-wrapper.jar`. Recorded (design intent: both
     * mtimes ride the DTO) but **not** part of the `guhWarmth` decision — see [GuhWarmth.classify]'s
     * KDoc for why an in-build timestamp of any kind (task or checkout) cannot anchor the
     * cold/warm comparison; [jvmStartMs] is the one that can.
     */
    val jarMtimeMs: Long? = null,
    /** Whether the GUH dist dir was found at all; false/null both mean "nothing to compare". */
    val distPresent: Boolean? = null,
    /**
     * This daemon's own JVM start time (`ManagementFactory.getRuntimeMXBean().startTime`) — the
     * `guhWarmth` anchor (plan 066 review correction): the wrapper bootstrap unpacks the
     * distribution moments *before* launching this very JVM, so comparing [distMtimeMs] against
     * this is the tightest available proxy for "was this daemon's own bootstrap what unpacked it".
     */
    val jvmStartMs: Long? = null,
) : Serializable

/**
 * Pure parsing/hashing/probing helpers (plan 066), factored out of [WrapperValueSource] so they
 * unit-test directly over temp files/dirs, without needing Gradle's `ValueSource` decoration
 * (mirrors [dev.buildhound.gradle] pure-helper precedents like `VcsParsing`/`GradlePropertyProvenance`).
 * A missing/unreadable file degrades to `null` in every function here — but that's a best-effort
 * design intent, not a hard guarantee for [loadProperties] specifically (`Properties.load` can
 * itself throw on malformed input; see its own KDoc) — the net-safe contract for the whole probe is
 * the `runCatching` guard in the caller ([WrapperValueSource.obtain]), one per probe.
 */
internal object WrapperParsing {

    /** `-all.zip` → [WrapperDistributionType.ALL], `-bin.zip` → [WrapperDistributionType.BIN], else CUSTOM. */
    fun classifyVariant(distributionUrl: String): WrapperDistributionType = when {
        distributionUrl.endsWith("-all.zip") -> WrapperDistributionType.ALL
        distributionUrl.endsWith("-bin.zip") -> WrapperDistributionType.BIN
        else -> WrapperDistributionType.CUSTOM
    }

    /**
     * Loads `gradle-wrapper.properties`; null when the file is missing. Not itself a total
     * function — `Properties.load` can throw on a malformed `\u` escape in a hand-edited file — the
     * net-safe guarantee comes from the caller ([WrapperValueSource.obtain]'s `guarded("wrapper-properties")`
     * wrapper), not from a try/catch in here.
     */
    fun loadProperties(file: File): Properties? {
        if (!file.isFile) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props
    }

    /**
     * `distributionSha256Sum=` present with a **non-empty** value → pinned; an empty value
     * (`distributionSha256Sum=`) counts as unpinned, matching the CI-side wrapper-integrity step's
     * pinning-presence grep (plan 066 infra review, 529c0f0). null [properties] (unreadable file)
     * → null (unknown).
     */
    fun isPinned(properties: Properties?): Boolean? =
        properties?.let { !it.getProperty("distributionSha256Sum").isNullOrEmpty() }

    fun distributionUrl(properties: Properties?): String? = properties?.getProperty("distributionUrl")

    /** Full lower-hex SHA-256 of [file]'s bytes, streamed (never loads the whole file into memory). */
    fun sha256Hex(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toLowerHex()
    }

    /**
     * The dist directory name Gradle's wrapper unpacks under `<GUH>/wrapper/dists/…` for a stock
     * `-bin`/`-all` distribution. Null for [WrapperDistributionType.CUSTOM]: the real directory name
     * derives from the distribution filename, which lives only in the discarded raw URL (spec §3.7)
     * — a custom mirror's dist presence is honestly left unprobed, never guessed.
     */
    fun distDirName(gradleVersion: String, variant: WrapperDistributionType): String? = when (variant) {
        WrapperDistributionType.BIN -> "gradle-$gradleVersion-bin"
        WrapperDistributionType.ALL -> "gradle-$gradleVersion-all"
        WrapperDistributionType.CUSTOM -> null
    }

    /**
     * (present, latest mtime) for `<gradleUserHome>/wrapper/dists/<dirName>`: absent → `(false,
     * null)`. The outer per-version/variant directory's own mtime bumps whenever the wrapper adds a
     * new hash-keyed subdirectory inside it (a fresh download); an unpack that already existed
     * before this daemon's own bootstrap leaves it untouched — [GuhWarmth.classify] compares this
     * mtime against the daemon's own JVM start time. Its direct children are also considered
     * (belt-and-braces across filesystems where a child's own write doesn't bump the parent's mtime).
     */
    fun probeDist(gradleUserHome: File, dirName: String): Pair<Boolean, Long?> {
        val distDir = File(File(gradleUserHome, "wrapper/dists"), dirName)
        if (!distDir.isDirectory) return false to null
        val mtimes = buildList {
            add(distDir.lastModified())
            distDir.listFiles()?.forEach { add(it.lastModified()) }
        }
        return true to mtimes.maxOrNull()
    }
}

/**
 * Execution-time wrapper & startup-phase probe (plan 066, research F16): config captures
 * *locations only* (properties/jar paths, the Gradle User Home dir — all plain strings); every
 * file read + hash happens here, in [obtain], so nothing becomes a configuration-cache fingerprint
 * input (architecture §2 rule 9) and the probes re-freshen on a CC hit (same rationale as
 * [FingerprintValueSource]/[InvocationValueSource]). No subprocess (pure file I/O + hashing), so
 * `BoundedExec` does not apply — each probe is individually `runCatching`-guarded instead, and a
 * guard failure degrades only that field, never the whole build (architecture §2 rule 3).
 */
abstract class WrapperValueSource : ValueSource<CollectedWrapper, WrapperValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Mirrors `buildhound { enabled }`: when false nothing is probed. */
        val enabled: Property<Boolean>

        /** Absolute path of `<rootDir>/gradle/wrapper/gradle-wrapper.properties` (location only). */
        val propertiesFile: Property<String>

        /** Absolute path of `<rootDir>/gradle/wrapper/gradle-wrapper.jar` (location only). */
        val jarFile: Property<String>

        /** Absolute path of the resolved Gradle User Home dir (location only). */
        val gradleUserHomeDir: Property<String>
    }

    override fun obtain(): CollectedWrapper {
        if (!parameters.enabled.getOrElse(true)) return CollectedWrapper()

        val properties = guarded("wrapper-properties") {
            parameters.propertiesFile.orNull?.let { WrapperParsing.loadProperties(File(it)) }
        }
        val variant = properties?.let(WrapperParsing::distributionUrl)?.let(WrapperParsing::classifyVariant)
        val pinned = guarded("wrapper-pinned") { WrapperParsing.isPinned(properties) }

        val jarFile = parameters.jarFile.orNull?.let(::File)
        val jarSha256 = jarFile?.let { file ->
            guarded("wrapper-jar-sha") { WrapperParsing.sha256Hex(file) }
        }
        val jarMtimeMs = jarFile?.let { file ->
            guarded("wrapper-jar-mtime") { file.takeIf { it.isFile }?.lastModified() }
        }

        val gradleVersion = guarded("gradle-version") { GradleVersion.current().version }
        val distProbe = guarded("guh-dist-probe") {
            val guh = parameters.gradleUserHomeDir.orNull
            val dirName = variant?.let { v -> gradleVersion?.let { gv -> WrapperParsing.distDirName(gv, v) } }
            if (guh == null || dirName == null) null else WrapperParsing.probeDist(File(guh), dirName)
        }
        // guhWarmth anchor (plan 066 review correction): this daemon's own JVM start, not any
        // in-build task timestamp — see GuhWarmth.classify's KDoc for why.
        val jvmStartMs = guarded("jvm-start") { ManagementFactory.getRuntimeMXBean().startTime }

        return CollectedWrapper(
            variant = variant,
            distributionSha256Pinned = pinned,
            wrapperJarSha256 = jarSha256,
            distMtimeMs = distProbe?.second,
            jarMtimeMs = jarMtimeMs,
            distPresent = distProbe?.first,
            jvmStartMs = jvmStartMs,
        )
    }

    private fun <T> guarded(what: String, block: () -> T?): T? =
        runCatching(block).onFailure {
            // Class name only: a wrapper/GUH path can embed a home dir (spec §3.7).
            logger.info("[buildhound] wrapper probe '{}' unavailable: {}", what, it::class.java.simpleName)
        }.getOrNull()

    private companion object {
        val logger = Logging.getLogger(WrapperValueSource::class.java)
    }
}
