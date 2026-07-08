package dev.buildhound.gradle

import dev.buildhound.commons.payload.JvmArtifactKind
import dev.buildhound.commons.payload.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The load-bearing "measure-only-what-ran" gate (plan 072, research F22): [readJvmArtifacts] measures
 * a [JvmArtifactLocation] only when its `taskPath` resolved to a produced-output outcome
 * (EXECUTED/UP_TO_DATE/FROM_CACHE) **and** the archive file exists — never a stale/absent artifact for
 * a SKIPPED/FAILED/NO_SOURCE/un-joined task, and never a phantom row when a task produced output but
 * (e.g. a user-disabled `jar`) never wrote the file. Mirrors the [joinTaskMetadata]/[JoinTaskMetadataTest]
 * extraction precedent.
 */
class ReadJvmArtifactsTest {

    private val tmp: File = Files.createTempDirectory("jvm-artifacts").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun archive(name: String, write: Boolean = true): File =
        File(tmp, name).apply { if (write) writeText("stub archive bytes") }

    private fun location(module: String, kind: JvmArtifactKind, taskPath: String, file: File) =
        JvmArtifactLocation(module = module, kind = kind, taskPath = taskPath, archivePath = file.absolutePath)

    @Test
    fun `a SKIPPED outcome location is filtered out`() {
        val file = archive("jar-a.jar")
        val locations = listOf(location(":app", JvmArtifactKind.JAR, ":app:jar", file))
        val outcomes = mapOf(":app:jar" to TaskOutcome.SKIPPED)

        assertEquals(emptyList(), readJvmArtifacts(locations, outcomes))
    }

    @Test
    fun `a FAILED outcome location is filtered out`() {
        val file = archive("jar-b.jar")
        val locations = listOf(location(":app", JvmArtifactKind.JAR, ":app:jar", file))
        val outcomes = mapOf(":app:jar" to TaskOutcome.FAILED)

        assertEquals(emptyList(), readJvmArtifacts(locations, outcomes))
    }

    @Test
    fun `a task path absent from the outcomes map is filtered out`() {
        val file = archive("jar-c.jar")
        val locations = listOf(location(":app", JvmArtifactKind.JAR, ":app:jar", file))

        assertEquals(emptyList(), readJvmArtifacts(locations, emptyMap()))
    }

    @Test
    fun `a stale file that exists is still filtered when the task did not produce output`() {
        // A leftover archive from a previous run sits on disk, but this build's outcome says the task
        // never (re)produced it (e.g. NO_SOURCE) — the outcome gate must win over File.exists().
        val file = archive("stale-boot.jar", write = true)
        val locations = listOf(location(":app", JvmArtifactKind.BOOT_JAR, ":app:bootJar", file))
        val outcomes = mapOf(":app:bootJar" to TaskOutcome.NO_SOURCE)

        assertEquals(emptyList(), readJvmArtifacts(locations, outcomes))
    }

    @Test
    fun `a produced-output outcome whose file is missing is filtered out`() {
        // The task ran (outcome says so) but the declared archive never landed on disk — e.g. a
        // user-disabled `jar` task path. File.exists() must still gate this out.
        val file = archive("never-written.jar", write = false)
        val locations = listOf(location(":app", JvmArtifactKind.JAR, ":app:jar", file))
        val outcomes = mapOf(":app:jar" to TaskOutcome.EXECUTED)

        assertEquals(emptyList(), readJvmArtifacts(locations, outcomes))
    }

    @Test
    fun `an EXECUTED outcome with an existing file is measured`() {
        val file = archive("app.jar")
        val locations = listOf(location(":app", JvmArtifactKind.JAR, ":app:jar", file))
        val outcomes = mapOf(":app:jar" to TaskOutcome.EXECUTED)

        val result = readJvmArtifacts(locations, outcomes)

        assertEquals(1, result.size)
        assertEquals(":app", result[0].module)
        assertEquals(JvmArtifactKind.JAR, result[0].kind)
        assertEquals(file.length(), result[0].sizeBytes)
        assertTrue(result[0].sizeBytes > 0)
    }

    @Test
    fun `UP_TO_DATE and FROM_CACHE also count as produced output`() {
        val upToDate = archive("up-to-date.jar")
        val fromCache = archive("from-cache.jar")
        val locations = listOf(
            location(":a", JvmArtifactKind.JAR, ":a:jar", upToDate),
            location(":b", JvmArtifactKind.JAR, ":b:jar", fromCache),
        )
        val outcomes = mapOf(":a:jar" to TaskOutcome.UP_TO_DATE, ":b:jar" to TaskOutcome.FROM_CACHE)

        val result = readJvmArtifacts(locations, outcomes)

        assertEquals(setOf(":a", ":b"), result.map { it.module }.toSet())
    }

    @Test
    fun `a default Boot module yields two coexisting rows, plain jar and bootJar`() {
        // Boot builds both `jar` (plain classifier) and `bootJar` by default (Boot 2.5+) — the
        // corrected rationale (plan-072 review fix): both tasks ran, both files exist, both rows ship.
        val jarFile = archive("boot-module-plain.jar")
        val bootJarFile = archive("boot-module.jar")
        val locations = listOf(
            location(":service", JvmArtifactKind.JAR, ":service:jar", jarFile),
            location(":service", JvmArtifactKind.BOOT_JAR, ":service:bootJar", bootJarFile),
        )
        val outcomes = mapOf(":service:jar" to TaskOutcome.EXECUTED, ":service:bootJar" to TaskOutcome.EXECUTED)

        val result = readJvmArtifacts(locations, outcomes)

        assertEquals(2, result.size)
        assertEquals(setOf(JvmArtifactKind.JAR, JvmArtifactKind.BOOT_JAR), result.map { it.kind }.toSet())
        assertTrue(result.all { it.module == ":service" && it.sizeBytes > 0 })
    }

    @Test
    fun `an empty location list yields an empty result`() {
        assertEquals(emptyList(), readJvmArtifacts(emptyList(), emptyMap()))
    }
}
