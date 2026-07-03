package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FingerprintHashingTest {

    private val salt = ByteArray(32) { it.toByte() }
    private val otherSalt = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun deterministic_for_the_same_salt_and_value() {
        assertEquals(FingerprintHashing.hash(salt, "value"), FingerprintHashing.hash(salt, "value"))
    }

    @Test
    fun differs_across_salts() {
        assertNotEquals(FingerprintHashing.hash(salt, "value"), FingerprintHashing.hash(otherSalt, "value"))
    }

    @Test
    fun differs_across_values() {
        assertNotEquals(FingerprintHashing.hash(salt, "a"), FingerprintHashing.hash(salt, "b"))
    }

    @Test
    fun format_is_16_hex_chars_plus_ellipsis() {
        val hash = FingerprintHashing.hash(salt, "/opt/jdk-21")
        assertEquals(17, hash.length, hash)
        assertTrue(hash.endsWith("…"), hash)
        assertTrue(hash.dropLast(1).matches(Regex("[0-9a-f]{16}")), hash)
        // No plaintext leaks into the hash.
        assertTrue(!hash.contains("opt"))
    }

    @Test
    fun domain_separated_from_identity_hashes() {
        // Fingerprints prefix "fp:"; identity uses "host:"/"user:". Same salt + same tail value
        // must still produce different digests, so the two families can never collide.
        val fp = FingerprintHashing.hash(salt, "host:h").dropLast(1).take(12)
        val identity = IdentityHashing.hostnameHash(salt, "h").removePrefix("h_")
        assertNotEquals(identity, fp)
    }
}
