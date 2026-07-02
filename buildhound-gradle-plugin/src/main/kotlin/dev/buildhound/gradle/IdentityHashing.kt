package dev.buildhound.gradle

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pseudonymization per spec §3.7: identity fields are salted HMAC-SHA256 digests,
 * truncated and prefixed (`u_…` for users, `h_…` for hostnames) so values are readable
 * as identifiers but carry no plaintext. The salt is per-project (interim: local file,
 * see plan 003), so the same user on two projects yields unrelated ids.
 */
internal object IdentityHashing {

    private const val TRUNCATED_HEX_CHARS = 12

    fun userId(salt: ByteArray, username: String, hostname: String): String =
        "u_" + hmacHex(salt, "$username@$hostname")

    fun hostnameHash(salt: ByteArray, hostname: String): String =
        "h_" + hmacHex(salt, hostname)

    private fun hmacHex(salt: ByteArray, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(TRUNCATED_HEX_CHARS)
    }
}
