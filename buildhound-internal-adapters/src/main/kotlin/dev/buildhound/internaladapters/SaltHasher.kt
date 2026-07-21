package dev.buildhound.internaladapters

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Salted re-hashing for tier-(b) fingerprints (plan 038). Cache keys and per-property value hashes are
 * already opaque Gradle digests; re-hashing them with a per-project secret salt (HMAC-SHA256, `"fp:"`
 * domain separation) makes them diff **within** a project across builds while being impossible to
 * dictionary-reverse to the raw Gradle key. Output is `16 hex chars + …` — the plan-022 fingerprint
 * shape. **No salt ⇒ null**, so a caller omits the block rather than ever emitting a raw key.
 *
 * The salt lives in its own file under the project's `.gradle/buildhound/` (survives `clean`,
 * gitignored by the sibling core plugin's salt writer / this writer's own `.gitignore`) and is
 * read-or-created at **execution time** — a config-phase file touch is a CC fingerprint input
 * ([IdentitySalt] precedent in the core plugin). This is the addon's own salt namespace: tier-(b)
 * hashes only ever diff against other tier-(b) hashes, so they need not share the core's salt.
 */
object SaltHasher {

    private const val SALT_BYTES = 32
    private const val DOMAIN = "fp:"
    private const val DIGEST_PREFIX_BYTES = 8
    private const val BYTE_MASK = 0xff
    private const val HEX_PADDING = 0x100
    private const val HEX_RADIX = 16

    fun readOrCreateSalt(saltFile: File): ByteArray? {
        val existing = validBytesOrNull(saltFile)
        if (existing != null) return existing
        val dir = (saltFile.parentFile ?: return null).apply { mkdirs() }
        runCatching { File(dir, ".gitignore").takeIf { !it.exists() }?.writeText("*\n") }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val tmp = File(dir, ".internal-adapters.salt.${ProcessHandle.current().pid()}.tmp")
        return try {
            tmp.writeBytes(salt)
            runCatching {
                Files.setPosixFilePermissions(
                    tmp.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            Files.move(tmp.toPath(), saltFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            salt
        } catch (_: Exception) {
            validBytesOrNull(saltFile) // lost a concurrent race → use whatever landed
        } finally {
            tmp.delete()
        }
    }

    /** Salted 16-hex-char digest of [value] (a raw byte hash), or null when [salt] is null. */
    fun hash(salt: ByteArray?, value: ByteArray?): String? {
        if (salt == null || value == null) return null
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        mac.update(DOMAIN.encodeToByteArray())
        val digest = mac.doFinal(value)
        return digest.take(DIGEST_PREFIX_BYTES).joinToString("") {
            ((it.toInt() and BYTE_MASK) + HEX_PADDING).toString(HEX_RADIX).substring(1)
        } + "…"
    }

    private fun validBytesOrNull(file: File): ByteArray? =
        if (file.isFile) file.readBytes().takeIf { it.size == SALT_BYTES } else null
}
