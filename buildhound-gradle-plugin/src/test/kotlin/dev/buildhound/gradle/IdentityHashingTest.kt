package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityHashingTest {

    private val salt = ByteArray(32) { it.toByte() }
    private val otherSalt = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `stable for the same salt and input`() {
        assertEquals(
            IdentityHashing.userId(salt, "dylan", "workstation"),
            IdentityHashing.userId(salt, "dylan", "workstation"),
        )
        assertEquals(
            IdentityHashing.hostnameHash(salt, "workstation"),
            IdentityHashing.hostnameHash(salt, "workstation"),
        )
    }

    @Test
    fun `different salts yield unrelated ids`() {
        assertNotEquals(
            IdentityHashing.userId(salt, "dylan", "workstation"),
            IdentityHashing.userId(otherSalt, "dylan", "workstation"),
        )
        assertNotEquals(
            IdentityHashing.hostnameHash(salt, "workstation"),
            IdentityHashing.hostnameHash(otherSalt, "workstation"),
        )
    }

    @Test
    fun `ids carry the documented prefix and truncated hex shape`() {
        val userId = IdentityHashing.userId(salt, "dylan", "workstation")
        val hostnameHash = IdentityHashing.hostnameHash(salt, "workstation")

        assertTrue(userId.matches(Regex("u_[0-9a-f]{12}")), userId)
        assertTrue(hostnameHash.matches(Regex("h_[0-9a-f]{12}")), hostnameHash)
    }

    @Test
    fun `no plaintext leaks into the pseudonym`() {
        val userId = IdentityHashing.userId(salt, "dylan", "workstation")

        assertFalse(userId.contains("dylan"))
        assertFalse(userId.contains("workstation"))
    }

    @Test
    fun `domain separation keeps user and host families disjoint`() {
        // Without domain separation these two would share a digest.
        assertNotEquals(
            IdentityHashing.hostnameHash(salt, "alice@laptop").removePrefix("h_"),
            IdentityHashing.userId(salt, "alice", "laptop").removePrefix("u_"),
        )
    }

    @Test
    fun `identity fields are hashed when pseudonymizing with a salt`() {
        val fields = IdentityHashing.identityFields(true, salt, "dylan", "workstation")

        assertEquals(IdentityHashing.hostnameHash(salt, "workstation"), fields.hostnameHash)
        assertEquals(IdentityHashing.userId(salt, "dylan", "workstation"), fields.userId)
    }

    @Test
    fun `identity fields are omitted when pseudonymizing without a salt`() {
        val fields = IdentityHashing.identityFields(true, null, "dylan", "workstation")

        assertNull(fields.hostnameHash)
        assertNull(fields.userId)
    }

    @Test
    fun `identity fields are plaintext only on explicit opt-out`() {
        val fields = IdentityHashing.identityFields(false, null, "dylan", "workstation")

        assertEquals("workstation", fields.hostnameHash)
        assertEquals("dylan", fields.userId)
    }

    @Test
    fun `missing probes yield null fields`() {
        assertNull(IdentityHashing.identityFields(true, salt, null, "workstation").userId)
        assertNull(IdentityHashing.identityFields(true, salt, "dylan", null).userId)
        assertNull(IdentityHashing.identityFields(true, salt, "dylan", null).hostnameHash)
    }
}
