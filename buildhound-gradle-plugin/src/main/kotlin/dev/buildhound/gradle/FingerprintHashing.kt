package dev.buildhound.gradle

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Salted input-fingerprint hashing (plan 022, spec §4). HMAC-SHA256 keyed with the shared
 * per-project identity salt ([IdentitySalt]), domain-separated with a `"fp:"` input prefix so a
 * fingerprint hash can never collide with the `user:`/`host:` identity families
 * ([IdentityHashing]). Truncated to 16 hex chars + `…` — the screenshot-compatible format.
 *
 * Equality within a project is preserved (same salt + value → same hash), which is all the
 * comparison endpoint needs; no plaintext (e.g. an absolute `jdk.home`) ever leaves the machine.
 * A fresh `Mac` per call — the Develocity-sample's shared-instance bug is deliberately not copied.
 */
internal object FingerprintHashing {

    private const val TRUNCATED_HEX_CHARS = 16
    const val ELLIPSIS: String = "…"

    fun hash(salt: ByteArray, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal("fp:$value".encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(TRUNCATED_HEX_CHARS) + ELLIPSIS
    }
}
