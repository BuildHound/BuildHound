package dev.buildhound.gradle

import dev.buildhound.commons.payload.GcCollector
import dev.buildhound.commons.payload.ProcessRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Timeout short-circuit + per-field degradation of the process-probe collector, sans real execs. */
class ProcessProbeCollectorTest {

    /** Records every probe invocation; jps lists two JVMs, jstat -gc is caller-controlled. */
    private class FakeTools(
        private val jstatGcFn: (Long) -> BoundedExec.Result = { ok(GC_ROW) },
        private val jpsResult: BoundedExec.Result = ok(
            "1 org.gradle.launcher.daemon.bootstrap.GradleDaemon\n2 org.jetbrains.kotlin.daemon.KotlinCompileDaemon\n",
        ),
    ) : ProcessTools {
        val calls = mutableListOf<String>()

        override fun jpsListing(): BoundedExec.Result { calls.add("jps"); return jpsResult }
        override fun jstatGc(pid: Long): BoundedExec.Result { calls.add("jstatGc($pid)"); return jstatGcFn(pid) }
        override fun jstatCapacity(pid: Long): BoundedExec.Result { calls.add("jstatCapacity($pid)"); return ok(CAP_ROW) }
        override fun jinfoFlags(pid: Long): BoundedExec.Result {
            calls.add("jinfoFlags($pid)")
            return ok("-XX:MaxHeapSize=4294967296 -XX:+UseG1GC -XX:-UseCompactObjectHeaders")
        }
        override fun psRss(pid: Long): BoundedExec.Result { calls.add("psRss($pid)"); return ok("1048576") }
        override fun psEtime(pid: Long): BoundedExec.Result { calls.add("psEtime($pid)"); return ok("01:00") }
    }

    @Test
    fun `a timeout short-circuits the rest of the PID and every further PID`() {
        val tools = FakeTools(jstatGcFn = { BoundedExec.Result.TimedOut })
        var timedOutTool: String? = null

        val result = ProcessProbeCollector.collect(tools, onTimeout = { timedOutTool = it })

        // Only jps + the first PID's jstatGc ran — no jstatCapacity/jinfo/ps for PID 1, no PID 2 at all.
        assertEquals(listOf("jps", "jstatGc(1)"), tools.calls)
        assertEquals("jstat", timedOutTool)
        // The first PID keeps its role-only partial row; the second is dropped.
        assertEquals(1, result.size)
        assertEquals(ProcessRole.GRADLE_DAEMON, result[0].role)
        assertNull(result[0].heapUsedMb)
    }

    @Test
    fun `a full pass probes every field for every PID`() {
        val tools = FakeTools()
        val result = ProcessProbeCollector.collect(tools)

        assertEquals(2, result.size)
        // jps + 5 probes per PID — plan 065's collector/headers/pid ride existing execs, adding none.
        assertEquals(1 + 2 * 5, tools.calls.size)
        val daemon = result.first { it.role == ProcessRole.GRADLE_DAEMON }
        assertEquals(4096, daemon.configuredXmxMb)
        assertNotNull(daemon.heapUsedMb)
        assertEquals(1024, daemon.rssMb) // 1048576 KB → 1024 MB
        // Plan 065: the jps pid is carried, and the SAME jinfo line yields the typed tuning flags.
        assertEquals(1L, daemon.pid)
        assertEquals(GcCollector.G1, daemon.gcCollector)
        assertEquals(false, daemon.compactObjectHeaders)
        assertEquals(2L, result.first { it.role == ProcessRole.KOTLIN_DAEMON }.pid)
    }

    @Test
    fun `no jps output yields an empty list without probing any PID`() {
        val tools = FakeTools(jpsResult = BoundedExec.Result.Failed("IOException"))
        var failure: String? = null

        val result = ProcessProbeCollector.collect(tools, onFailure = { failure = it })

        assertTrue(result.isEmpty())
        assertEquals(listOf("jps"), tools.calls)
        assertEquals("IOException", failure)
    }

    @Test
    fun `a non-zero exit on one field drops only that field, not the process`() {
        // jstat -gc fails (non-zero), but capacity/jinfo/ps still run: heap-used null, Xmx present.
        val tools = FakeTools(jstatGcFn = { BoundedExec.Result.NonZeroExit })
        val result = ProcessProbeCollector.collect(tools)

        assertEquals(2, result.size)
        val daemon = result.first { it.role == ProcessRole.GRADLE_DAEMON }
        assertNull(daemon.heapUsedMb) // dropped
        assertEquals(4096, daemon.configuredXmxMb) // still collected
    }

    private companion object {
        fun ok(stdout: String): BoundedExec.Result = BoundedExec.Result.Success(stdout)
        const val GC_ROW = "EU OU S0U S1U EC OC S0C S1C GCT\n409600.0 1024000.0 30720.0 2048.0 300000.0 900000.0 600.0 700.0 4.2"
        const val CAP_ROW = "NGCMX OGCMX\n1398144.0 2796288.0"
    }
}
