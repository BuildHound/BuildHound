package dev.buildhound.gradle

import dev.buildhound.commons.payload.ProjectEvaluation
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `fileNameFor sanitizes project paths, colliding safely`() {
        assertEquals("app.jsonl", ProjectEvalRecordIo.fileNameFor(":app"))
        assertEquals("core-common.jsonl", ProjectEvalRecordIo.fileNameFor(":core:common"))
        assertEquals("root.jsonl", ProjectEvalRecordIo.fileNameFor(":"))
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
