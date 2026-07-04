package dev.buildhound.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** Fake JDK-tool scripts are POSIX shell; CI runs ubuntu (plan 015/029 fake-binary pattern). */
@DisabledOnOs(OS.WINDOWS)
class ProcessMetricsTest {

    @field:TempDir
    lateinit var dir: File

    private fun fake(name: String, body: String): String {
        val script = File(dir, name)
        script.writeText("#!/bin/sh\n$body\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    @Test
    fun `jps listing captures stdout on success`() {
        val jps = fake("fake-jps", "printf '12345 org.gradle.launcher.daemon.bootstrap.GradleDaemon\\n'")
        val result = ProcessMetrics(timeoutMillis = 10_000, jps = jps).jpsListing()
        val success = assertIs<BoundedExec.Result.Success>(result)
        assertEquals("12345 org.gradle.launcher.daemon.bootstrap.GradleDaemon\n", success.stdout)
    }

    @Test
    fun `a hung tool times out rather than blocking`() {
        val jstat = fake("fake-jstat", "exec sleep 300")
        val result = ProcessMetrics(timeoutMillis = 250, jstat = jstat).jstatGc(1)
        assertIs<BoundedExec.Result.TimedOut>(result)
    }

    @Test
    fun `a non-zero exit is reported as NonZeroExit, not a crash`() {
        val jinfo = fake("fake-jinfo", "exit 1")
        assertIs<BoundedExec.Result.NonZeroExit>(ProcessMetrics(timeoutMillis = 10_000, jinfo = jinfo).jinfoFlags(1))
    }

    @Test
    fun `stderr is never captured`() {
        // Writes only to stderr, exits 0 with empty stdout — stderr (which can hold paths/args) is discarded.
        val ps = fake("fake-ps", "printf 'secret /abs/path\\n' 1>&2")
        val success = assertIs<BoundedExec.Result.Success>(ProcessMetrics(timeoutMillis = 10_000, ps = ps).psRss(1))
        assertEquals("", success.stdout)
    }
}
