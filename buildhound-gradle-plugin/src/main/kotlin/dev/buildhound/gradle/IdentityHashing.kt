package dev.buildhound.gradle

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pseudonymization per spec §3.7: identity fields are salted HMAC-SHA256 digests,
 * truncated to 12 hex chars and prefixed (`u_…` for users, `h_…` for hostnames) so
 * values read as identifiers but carry no plaintext. HMAC inputs are domain-separated
 * (`user:`/`host:`) so the two field families can never collide. Enumeration resistance
 * rests on salt secrecy, not digest length, so truncation is safe; the salt is
 * per-project (interim: local file, see plan 003), so the same user on two projects
 * yields unrelated ids.
 */
internal object IdentityHashing {

    private const val TRUNCATED_HEX_CHARS = 12

    fun userId(salt: ByteArray, username: String, hostname: String): String =
        "u_" + hmacHex(salt, "user:$username@$hostname")

    fun hostnameHash(salt: ByteArray, hostname: String): String =
        "h_" + hmacHex(salt, "host:$hostname")

    /**
     * The one place deciding what identity leaves the machine (spec §3.7):
     * pseudonymize=true requires a salt — without one the fields are omitted, never
     * silently plaintext. pseudonymize=false is the explicit plaintext opt-out.
     */
    fun identityFields(pseudonymize: Boolean, salt: ByteArray?, username: String?, hostname: String?): IdentityFields {
        val hostnameField = hostname?.let {
            when {
                !pseudonymize -> it
                salt != null -> hostnameHash(salt, it)
                else -> null
            }
        }
        val userField = if (username != null && hostname != null) {
            when {
                !pseudonymize -> username
                salt != null -> userId(salt, username, hostname)
                else -> null
            }
        } else {
            null
        }
        return IdentityFields(hostnameHash = hostnameField, userId = userField)
    }

    data class IdentityFields(val hostnameHash: String?, val userId: String?)

    private fun hmacHex(salt: ByteArray, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(TRUNCATED_HEX_CHARS)
    }
}
