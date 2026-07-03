package dev.buildhound.gradle

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

/**
 * The interim per-project identity salt (plan 003), read or created at **execution time** —
 * configuration-phase file access is a CC fingerprint input, so creating the salt at apply
 * time would invalidate the very next build's cache entry. Shared by the environment identity
 * fields ([IdentityHashing]) and the input fingerprints ([FingerprintHashing], plan 022): both
 * only need equality *within* a project, which a single per-project salt gives.
 *
 * Returns null when no path is supplied or the salt cannot be established (e.g. the path is a
 * directory) — callers omit the salted data rather than emit plaintext.
 */
internal object IdentitySalt {

    const val SALT_BYTES = 32

    fun readOrCreate(saltFilePath: String?): ByteArray? {
        val saltFile = File(saltFilePath ?: return null)
        validBytesOrNull(saltFile)?.let { return it }
        val dir = (saltFile.parentFile ?: return null).apply { mkdirs() }
        // Safety net for consumer repos that don't ignore .gradle/: a committed salt
        // makes the low-entropy identity inputs enumerable offline.
        runCatching { File(dir, ".gitignore").takeIf { !it.exists() }?.writeText("*\n") }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val tmp = File(dir, ".identity.salt.${ProcessHandle.current().pid()}.tmp")
        return try {
            tmp.writeBytes(salt)
            runCatching {
                Files.setPosixFilePermissions(
                    tmp.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } // non-POSIX file systems: best effort
            Files.move(tmp.toPath(), saltFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            salt
        } catch (_: Exception) {
            // Concurrent build won the race (or the move failed): use whatever is there.
            validBytesOrNull(saltFile)
        } finally {
            tmp.delete()
        }
    }

    private fun validBytesOrNull(file: File): ByteArray? =
        if (file.isFile) file.readBytes().takeIf { it.size == SALT_BYTES } else null
}
