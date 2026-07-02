package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VcsParsingTest {

    @Test
    fun `branch is trimmed and detached head maps to null`() {
        assertEquals("main", VcsParsing.parseBranch("main\n"))
        assertEquals("feature/x", VcsParsing.parseBranch("feature/x"))
        assertNull(VcsParsing.parseBranch("HEAD\n"))
        assertNull(VcsParsing.parseBranch("  "))
    }

    @Test
    fun `sha accepts full hex ids only`() {
        assertEquals(
            "cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a",
            VcsParsing.parseSha("cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a\n"),
        )
        assertNull(VcsParsing.parseSha("HEAD"))
        assertNull(VcsParsing.parseSha("cfb5b38")) // abbreviated: not accepted
        assertNull(VcsParsing.parseSha("fatal: not a git repository"))
    }

    @Test
    fun `dirty reflects porcelain emptiness`() {
        assertFalse(VcsParsing.parseDirty(""))
        assertFalse(VcsParsing.parseDirty("\n"))
        assertTrue(VcsParsing.parseDirty(" M build.gradle.kts\n"))
    }
}
