package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.GuhWarmth
import dev.buildhound.commons.payload.WrapperDistributionType
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Wrapper & startup-phase telemetry (plan 066, research F16): the [WrapperValueSource] reading a
 * real `gradle/wrapper/gradle-wrapper.properties` + `.jar` from the fixture, and the
 * `guhWarmth` classification folded in by [PayloadAssembler].
 */
class WrapperFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    /** A Gradle User Home distinct from the project dir and from TestKit's own working dir. */
    @field:TempDir
    lateinit var guhDir: File

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .freshDaemon()
            // Same rationale as InvocationFunctionalTest: an explicit -g pins StartParameter's
            // gradleUserHomeDir to a directory this test controls.
            .withArguments(*arguments, "-g", guhDir.absolutePath)

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun summaryLine(output: String): String =
        output.lineSequence().single { it.startsWith("[buildhound] build ") }

    private fun setUpSimpleProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "wrapper-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
    }

    private fun writeWrapperFiles(distributionUrl: String, pinned: Boolean, jarBytes: ByteArray = "fixture wrapper jar".toByteArray()) {
        val wrapperDir = File(projectDir, "gradle/wrapper").apply { mkdirs() }
        val pinLine = if (pinned) "distributionSha256Sum=deadbeef00000000000000000000000000000000000000000000000000000\n" else ""
        File(wrapperDir, "gradle-wrapper.properties").writeText(
            "distributionUrl=$distributionUrl\n$pinLine",
        )
        File(wrapperDir, "gradle-wrapper.jar").writeBytes(jarBytes)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    @Test
    fun `a real wrapper-properties and jar populate variant, pinned, and the full-hex jar sha256`() {
        setUpSimpleProject()
        val jarBytes = "known wrapper jar contents".toByteArray()
        writeWrapperFiles(
            distributionUrl = "https\\://services.gradle.org/distributions/gradle-8.14-bin.zip",
            pinned = true,
            jarBytes = jarBytes,
        )

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)

        val wrapper = readPayload().wrapper ?: error("expected a wrapper block")
        assertEquals(WrapperDistributionType.BIN, wrapper.distributionVariant)
        assertEquals(true, wrapper.distributionSha256Pinned)
        assertEquals(sha256Hex(jarBytes), wrapper.wrapperJarSha256)
        assertEquals(64, wrapper.wrapperJarSha256?.length)
        // TestKit does not unpack this Gradle distribution under the fixture's -g GUH the way a
        // real `./gradlew` bootstrap would — so, exactly like a system/IDE Gradle invocation, there
        // is no dist dir to compare against. variant/pinned still ship (the committed config), and
        // guhWarmth is honestly UNKNOWN rather than a guess.
        assertEquals(GuhWarmth.UNKNOWN, wrapper.guhWarmth)
    }

    /**
     * End-to-end COLD/WARM coverage (plan 066 review): every other test in this file only ever
     * reaches `UNKNOWN`, because TestKit's own distribution resolution never populates a real GUH
     * dist under an explicit `-g` the way a genuine `./gradlew` bootstrap would. To exercise a
     * genuine cold/warm decision, this fabricates the exact `wrapper/dists/gradle-<version>-<variant>`
     * directory GuhWarmth compares against — using the Gradle version the *same* TestKit build
     * itself reports via `payload.toolchain.gradle`, so the fabricated name always matches the
     * running distribution — with a controlled mtime, then asserts the resulting classification.
     */
    private fun fabricateGuhDist(gradleVersion: String, variant: String, mtimeMs: Long) {
        val outerDir = File(guhDir, "wrapper/dists/gradle-$gradleVersion-$variant")
        val hashDir = File(outerDir, "deadbeef0123456789").apply { mkdirs() }
        // Stamp both after every mkdirs() call finishes: creating hashDir bumps outerDir's own
        // mtime as a filesystem side effect, so setting timestamps only afterward is what keeps
        // this deterministic (same discipline as WrapperParsingTest's probeDist coverage).
        outerDir.setLastModified(mtimeMs)
        hashDir.setLastModified(mtimeMs)
    }

    @Test
    fun `a dist unpacked around this daemon's own start reports COLD end to end`() {
        setUpSimpleProject()
        writeWrapperFiles(
            distributionUrl = "https\\://services.gradle.org/distributions/gradle-8.14-bin.zip",
            pinned = true,
        )

        val discovery = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, discovery.task(":hello")?.outcome, discovery.output)
        val gradleVersion = readPayload().toolchain?.gradle ?: error("expected toolchain.gradle to be populated")

        // "Just now": well within GuhWarmth.FRESH_WINDOW_MS of the next build's own (fresh) daemon start.
        fabricateGuhDist(gradleVersion, "bin", System.currentTimeMillis())

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)
        val wrapper = readPayload().wrapper ?: error("expected a wrapper block")
        assertEquals(GuhWarmth.COLD, wrapper.guhWarmth, "the fabricated dist sits within the fresh window of this daemon's own start")
    }

    @Test
    fun `a dist that predates this daemon's own start by more than the fresh window reports WARM end to end`() {
        setUpSimpleProject()
        writeWrapperFiles(
            distributionUrl = "https\\://services.gradle.org/distributions/gradle-8.14-bin.zip",
            pinned = true,
        )

        val discovery = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, discovery.task(":hello")?.outcome, discovery.output)
        val gradleVersion = readPayload().toolchain?.gradle ?: error("expected toolchain.gradle to be populated")

        // Comfortably outside GuhWarmth.FRESH_WINDOW_MS — a persisted, reused Gradle User Home.
        val old = System.currentTimeMillis() - GuhWarmth.FRESH_WINDOW_MS - 60_000
        fabricateGuhDist(gradleVersion, "bin", old)

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)
        val wrapper = readPayload().wrapper ?: error("expected a wrapper block")
        assertEquals(GuhWarmth.WARM, wrapper.guhWarmth, "the fabricated dist predates this daemon's start well beyond the fresh window")
    }

    @Test
    fun `an -all distribution and an unpinned wrapper classify accordingly`() {
        setUpSimpleProject()
        writeWrapperFiles(
            distributionUrl = "https\\://services.gradle.org/distributions/gradle-8.14-all.zip",
            pinned = false,
        )

        runner("hello").build()

        val wrapper = readPayload().wrapper ?: error("expected a wrapper block")
        assertEquals(WrapperDistributionType.ALL, wrapper.distributionVariant)
        assertEquals(false, wrapper.distributionSha256Pinned)
    }

    @Test
    fun `a CUSTOM distributionUrl reports the variant but never guesses at a dist probe`() {
        setUpSimpleProject()
        writeWrapperFiles(
            distributionUrl = "https\\://artifacts.example.internal/mirror/gradle-8.14.zip",
            pinned = true,
        )

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)

        val wrapper = readPayload().wrapper ?: error("expected a wrapper block")
        assertEquals(WrapperDistributionType.CUSTOM, wrapper.distributionVariant)
        assertEquals(true, wrapper.distributionSha256Pinned)
        // WrapperParsing.distDirName returns null for CUSTOM (the real unpack directory name
        // derives from the discarded raw URL, spec §3.7) — obtain() short-circuits the GUH dist
        // probe entirely rather than guessing at a private mirror's unpack layout, so guhWarmth is
        // honestly UNKNOWN, exactly like the no-dist-at-all case.
        assertEquals(GuhWarmth.UNKNOWN, wrapper.guhWarmth)
    }

    @Test
    fun `no wrapper files at all leaves the whole block uncaptured`() {
        setUpSimpleProject()

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)
        assertNull(readPayload().wrapper, "no gradle/wrapper directory at all must degrade to a null block")
    }

    @Test
    fun `a config-cache hit keeps the wrapper block present and re-freshes it`() {
        setUpSimpleProject()
        val firstJar = "first jar bytes".toByteArray()
        writeWrapperFiles(
            distributionUrl = "https\\://services.gradle.org/distributions/gradle-8.14-bin.zip",
            pinned = false,
            jarBytes = firstJar,
        )

        val store = runner("hello", "--configuration-cache").build()
        assertTrue(summaryLine(store.output).contains("cc=MISS_STORED"), summaryLine(store.output))
        val stored = readPayload().wrapper ?: error("expected a wrapper block on the store build")
        assertEquals(false, stored.distributionSha256Pinned)
        assertEquals(sha256Hex(firstJar), stored.wrapperJarSha256)

        // Change the wrapper files between builds — a real CC-hit rerun must still re-read them:
        // the ValueSource's obtain() runs at execution time, not baked config-time state.
        val secondJar = "second jar bytes, changed between builds".toByteArray()
        writeWrapperFiles(
            distributionUrl = "https\\://services.gradle.org/distributions/gradle-8.14-bin.zip",
            pinned = true,
            jarBytes = secondJar,
        )

        val hit = runner("hello", "--configuration-cache").build()
        assertTrue(hit.output.contains("Reusing configuration cache"), hit.output)
        assertTrue(summaryLine(hit.output).contains("cc=HIT"), summaryLine(hit.output))
        val replayed = readPayload().wrapper ?: error("expected wrapper to survive a CC hit")
        assertEquals(true, replayed.distributionSha256Pinned, "the pinned flag must re-freshen on a CC hit")
        assertEquals(sha256Hex(secondJar), replayed.wrapperJarSha256, "the jar hash must re-freshen on a CC hit")
    }

    @Test
    fun `the master switch off leaves the wrapper block uncaptured`() {
        setUpSimpleProject()
        writeWrapperFiles(
            distributionUrl = "https\\://services.gradle.org/distributions/gradle-8.14-bin.zip",
            pinned = true,
        )

        val result = runner("hello", "-Pbuildhound.enabled=false").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)
        assertFalse(result.output.contains("[buildhound] build "), result.output)
    }
}
