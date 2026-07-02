package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VcsParsingTest {

    @Test
    fun `branch is trimmed and detached head maps to null`() {
        assertEquals("main", VcsValueSource.parseBranch("main\n"))
        assertEquals("feature/x", VcsValueSource.parseBranch("feature/x"))
        assertNull(VcsValueSource.parseBranch("HEAD\n"))
        assertNull(VcsValueSource.parseBranch("  "))
    }

    @Test
    fun `sha accepts full hex ids only`() {
        assertEquals(
            "cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a",
            VcsValueSource.parseSha("cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a\n"),
        )
        assertNull(VcsValueSource.parseSha("HEAD"))
        assertNull(VcsValueSource.parseSha("cfb5b38")) // abbreviated: not accepted
        assertNull(VcsValueSource.parseSha("fatal: not a git repository"))
    }

    @Test
    fun `dirty reflects porcelain emptiness`() {
        assertFalse(VcsValueSource.parseDirty(""))
        assertFalse(VcsValueSource.parseDirty("\n"))
        assertTrue(VcsValueSource.parseDirty(" M build.gradle.kts\n"))
    }
}
