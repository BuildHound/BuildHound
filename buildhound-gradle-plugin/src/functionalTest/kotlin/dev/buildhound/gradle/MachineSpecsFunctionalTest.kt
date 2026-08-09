package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.EnvironmentInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * Machine hardware specs + execution-window resource usage (plan 104): both blocks populate on a
 * real build, both stay fresh on a configuration-cache **hit**, and neither leaks a device path or
 * filesystem name (spec §3.7).
 */
class MachineSpecsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    /** Stable GUH across runs so a store→hit pair finds its entry (the [InvocationFunctionalTest] pattern). */
    private val guhDir: File = newGradleUserHome()

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .freshDaemon()
            .withArguments(*arguments, "-g", guhDir.absolutePath)

    private fun payloadFile(): File = File(projectDir, "build/buildhound/build-payload.json")

    private fun readPayload(): BuildPayload {
        val file = payloadFile()
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    private fun fixture() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins { id("dev.buildhound") }
            rootProject.name = "machine-fixture"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """tasks.register("hello") { doLast { println("hello") } }""",
        )
    }

    /**
     * The window is measured from the collector service's instantiation to the finalizer, so it can
     * never exceed the build's own wall duration. A baseline replayed from a stale configuration-cache
     * entry — the failure mode this whole placement exists to avoid — would span the *previous* build
     * and the gap since, blowing straight through this bound.
     */
    private fun assertWindowFitsInsideTheBuild(payload: BuildPayload, label: String) {
        val usage = assertNotNull(payload.resourceUsage, "expected resourceUsage on the $label build")
        val windowMs = assertNotNull(usage.windowMs, "expected a measured window on the $label build")
        val buildMs = payload.finishedAt - payload.startedAt
        assertTrue(
            windowMs in 0..(buildMs + SLACK_MS),
            "$label window ($windowMs ms) must fit inside the build's own duration ($buildMs ms) — a larger " +
                "value means a stale baseline was replayed rather than stamped fresh this build",
        )
        usage.daemonCpuMs?.let { cpuMs ->
            assertTrue(cpuMs >= 0, "$label daemon CPU delta must never be negative, was $cpuMs")
        }
    }

    /**
     * The classifier reads the `FileStore` device name (`/dev/nvme0n1p2`) and filesystem type, then
     * returns an enum — so neither may appear anywhere in the payload. `/sys/block` is distinctive
     * enough for a whole-payload check; the generic `/dev/` token is scoped to the encoded
     * environment block, where the guarantee actually binds (a whole-payload search for a generic
     * token false-positives on CI metadata — see BuildCacheConfigFunctionalTest's KDoc).
     */
    private fun assertNoDevicePathLeak(environment: EnvironmentInfo) {
        val raw = payloadFile().readText()
        assertTrue(!raw.contains("/sys/block"), "no sysfs path may reach the payload (spec §3.7)")

        val environmentJson = BuildHoundJson.payload.encodeToString(EnvironmentInfo.serializer(), environment)
        assertTrue(!environmentJson.contains("/dev/"), "no block-device path may reach the environment block (spec §3.7)")
        for (filesystem in listOf("ext4", "apfs", "overlay", "ntfs", "xfs", "btrfs")) {
            assertTrue(
                !environmentJson.lowercase().contains(filesystem),
                "no filesystem type name may reach the environment block (spec §3.7): found $filesystem",
            )
        }
    }

    @Test
    fun `a build reports its disk capacity, media class, and execution-window resource usage`() {
        fixture()

        val result = runner("hello").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome, result.output)

        val payload = readPayload()
        val environment = assertNotNull(payload.environment, "expected an environment block")
        // The pre-existing hardware fields the report now surfaces — asserted here so the rendering
        // work has a pinned source, not merely an assumed one.
        assertTrue((environment.cores ?: 0) > 0, "expected a positive core count, was ${environment.cores}")
        assertTrue((environment.ramMb ?: 0) > 0, "expected positive total RAM, was ${environment.ramMb}")

        val machine = assertNotNull(environment.machine, "expected environment.machine")
        val total = assertNotNull(machine.diskTotalMb, "expected the build root's filesystem capacity")
        val free = assertNotNull(machine.diskFreeMb, "expected the build root's usable space")
        assertTrue(total > 0, "expected a positive filesystem capacity, was $total")
        assertTrue(free in 0..total, "usable space ($free MB) must fit within capacity ($total MB)")
        // The media class is best-effort: UNKNOWN is a legitimate answer on macOS/Windows and on the
        // container/LVM layouts common in CI. What must always hold is that we resolved *something*
        // rather than leaving the field unset while claiming to have looked.
        assertNotNull(machine.diskMedia, "expected a resolved media class, even if UNKNOWN")

        assertWindowFitsInsideTheBuild(payload, "first")
        assertNoDevicePathLeak(environment)
    }

    @Test
    fun `machine specs and resource usage survive a configuration-cache hit and stay fresh`() {
        fixture()

        val first = runner("hello", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":hello")?.outcome, first.output)
        val stored = assertNotNull(readPayload().environment?.machine, "expected environment.machine on the miss")

        val second = runner("hello", "--configuration-cache").build()
        assertTrue(
            second.output.lineSequence().any { it.startsWith("[buildhound] build ") && it.contains("cc=HIT") },
            second.output,
        )

        val payload = readPayload()
        val environment = assertNotNull(payload.environment, "expected an environment block on the hit")
        val replayed = assertNotNull(environment.machine, "expected environment.machine on the hit")
        // Hardware is stable across two runs on one machine, so the value source re-executing on a
        // CC hit must reproduce it — not drop it, which is what a config-time read would do.
        // Capacity and media are compared; usable space deliberately is NOT — the first build just
        // wrote a payload, a CC entry and task outputs, so `diskFreeMb` legitimately moves between
        // runs (it did, by 1 MB, the first time this test ran). Asserting it equal would be pinning
        // a measurement as if it were a constant.
        assertEquals(stored.diskTotalMb, replayed.diskTotalMb, "filesystem capacity must survive a CC hit")
        assertEquals(stored.diskMedia, replayed.diskMedia, "the media class must survive a CC hit")
        val free = assertNotNull(replayed.diskFreeMb, "usable space must still be measured on a CC hit")
        assertTrue(free in 0..replayed.diskTotalMb!!, "usable space ($free MB) must fit within capacity")
        // Usage is per-build state, so the hit gets its OWN window rather than the stored one.
        assertWindowFitsInsideTheBuild(payload, "cc-hit")
        assertNoDevicePathLeak(environment)
    }

    private companion object {
        /** Covers finalizer work that runs after `finishedAt` is stamped; a stale baseline is orders larger. */
        const val SLACK_MS = 10_000L
    }
}
