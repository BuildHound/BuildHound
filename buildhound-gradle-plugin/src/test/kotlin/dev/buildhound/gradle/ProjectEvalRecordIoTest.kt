package dev.buildhound.gradle

import dev.buildhound.commons.payload.ProjectEvaluation
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectEvalRecordIoTest {

    private val tmp: File = Files.createTempDirectory("project-eval").toFile()
    private val dir: File get() = File(tmp, "config-timings")

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test
    fun `encode then parse round-trips`() {
        val text = ProjectEvalRecordIo.encode(path = ":app", evaluationMs = 4200)
        assertEquals(ProjectEvaluation(path = ":app", evaluationMs = 4200), ProjectEvalRecordIo.parse(text))
    }

    @Test
    fun `malformed or blank text is skipped, never throws`() {
        assertNull(ProjectEvalRecordIo.parse(""))
        assertNull(ProjectEvalRecordIo.parse("not json"))
        assertNull(ProjectEvalRecordIo.parse("""{"evaluationMs":1}""")) // missing path
        assertNull(ProjectEvalRecordIo.parse("""{"path":":app","evaluationMs":"slow"}""")) // non-numeric
    }

    @Test
    fun `fileNameFor keeps typical project paths readable`() {
        assertEquals("app.jsonl", ProjectEvalRecordIo.fileNameFor(":app"))
        assertEquals("core-common.jsonl", ProjectEvalRecordIo.fileNameFor(":core:common"))
        assertEquals("_root_.jsonl", ProjectEvalRecordIo.fileNameFor(":"))
    }

    @Test
    fun `fileNameFor never maps two distinct project paths to the same file`() {
        // Written against the 052-review-fix encoding: with the original `':' -> '-'` substitution,
        // :a:b and a top-level project literally named :a-b both produced "a-b.jsonl" (silent
        // last-write-wins) and every pair below would have failed this test.
        assertNotEquals(ProjectEvalRecordIo.fileNameFor(":a:b"), ProjectEvalRecordIo.fileNameFor(":a-b"))
        // Hyphens adjacent to separators — the reason '-' is hex-escaped instead of doubled
        // ('- -> --' would leave :a-:b and :a:-b colliding on "a---b").
        assertNotEquals(ProjectEvalRecordIo.fileNameFor(":a-:b"), ProjectEvalRecordIo.fileNameFor(":a:-b"))
        // The root project vs a subproject literally named "root".
        assertNotEquals(ProjectEvalRecordIo.fileNameFor(":"), ProjectEvalRecordIo.fileNameFor(":root"))
        // The escape delimiter itself vs a name that spells out an escape sequence.
        assertNotEquals(ProjectEvalRecordIo.fileNameFor(":a-b"), ProjectEvalRecordIo.fileNameFor(":a_2d_b"))
    }

    @Test
    fun `fileNameFor whitelists every name to filesystem-safe chars, still without collisions`() {
        // The sink must not depend on Gradle's project-name invariant (§3.2 review): whatever the
        // path contains, the emitted name stays within [A-Za-z0-9._-] + the .jsonl suffix.
        val exotic = listOf(":a b", ":a?b", ":a!b", ":emojié", ":back\\slash")
        for (path in exotic) {
            val stem = ProjectEvalRecordIo.fileNameFor(path).removeSuffix(".jsonl")
            assertTrue(
                stem.isNotEmpty() && stem.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" },
                "expected a whitelisted stem for $path, got: $stem",
            )
        }
        // The whitelist transformation must stay injective too — collapsed characters must not merge.
        assertEquals(exotic.size, exotic.map { ProjectEvalRecordIo.fileNameFor(it) }.toSet().size)
    }

    @Test
    fun `colliding project paths both survive write then readAndClear as distinct entries`() {
        // The genuine collision pair from the 052 review: with the old encoding both writes landed
        // in "a-b.jsonl" and only the last one survived — this test failed against that code.
        ProjectEvalRecordIo.write(dir, ":a:b", 100)
        ProjectEvalRecordIo.write(dir, ":a-b", 200)

        assertEquals(
            setOf(ProjectEvaluation(":a:b", 100), ProjectEvaluation(":a-b", 200)),
            ProjectEvalRecordIo.readAndClear(dir).toSet(),
        )
    }

    @Test
    fun `write then readAndClear round-trips one file per project`() {
        ProjectEvalRecordIo.write(dir, ":app", 4200)
        ProjectEvalRecordIo.write(dir, ":core:common", 300)

        assertTrue(File(dir, "app.jsonl").isFile)
        assertTrue(File(dir, "core-common.jsonl").isFile)

        val read = ProjectEvalRecordIo.readAndClear(dir)
        assertEquals(
            setOf(ProjectEvaluation(":app", 4200), ProjectEvaluation(":core:common", 300)),
            read.toSet(),
        )
    }

    @Test
    fun `readAndClear deletes every file, leaving the directory empty`() {
        ProjectEvalRecordIo.write(dir, ":app", 100)
        ProjectEvalRecordIo.readAndClear(dir)

        assertEquals(emptyList(), dir.listFiles()?.toList().orEmpty())
        // A second read finds nothing left over — proves the directory was actually cleared.
        assertEquals(emptyList(), ProjectEvalRecordIo.readAndClear(dir))
    }

    @Test
    fun `readAndClear of a missing directory is empty, never throws`() {
        assertEquals(emptyList(), ProjectEvalRecordIo.readAndClear(dir))
    }

    @Test
    fun `write overwrites this project's own file rather than appending`() {
        ProjectEvalRecordIo.write(dir, ":app", 100)
        ProjectEvalRecordIo.write(dir, ":app", 200)

        assertEquals(listOf(ProjectEvaluation(":app", 200)), ProjectEvalRecordIo.readAndClear(dir))
    }

    @Test
    fun `readAndClear skips a malformed file but still clears it`() {
        dir.mkdirs()
        File(dir, "bad.jsonl").writeText("not json")
        ProjectEvalRecordIo.write(dir, ":app", 100)

        val read = ProjectEvalRecordIo.readAndClear(dir)
        assertEquals(listOf(ProjectEvaluation(":app", 100)), read)
        assertEquals(emptyList(), dir.listFiles()?.toList().orEmpty(), "the malformed file must be cleared too")
    }
}
