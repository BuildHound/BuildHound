package dev.buildhound.gradle

import dev.buildhound.commons.payload.ProcessRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Gradle-free unit tests pinning the process-probe measurement math (research §4.1 traps). */
class ProcessParsingTest {

    // Standard JDK layout: survivors present, GCT total distinct from YGCT+FGCT.
    private val gcStandard = ProcessParsing.parseJstat(
        """
        S0C    S1C    S0U    S1U      EC       EU       OC       OU     YGCT    FGCT    CGCT    GCT
        600.0  700.0  1500.0 500.0    350000.0 1000000.0 900000.0 500000.0 2.0    1.0     0.5     4.2
        """.trimIndent(),
    )

    @Test
    fun `heap used includes survivors`() {
        // EU+OU+S0U+S1U = 1000000+500000+1500+500 = 1502000 KB → 1467 MB.
        // Dropping survivors (the build-process-watcher bug) would give 1465 — different, so the
        // fixture proves survivors are counted.
        assertEquals(1467L, ProcessParsing.heapUsedMb(gcStandard))
        assertEquals(1465L, Math.round((1_000_000.0 + 500_000.0) / 1024.0)) // sanity: the wrong number
    }

    @Test
    fun `heap committed sums the committed columns`() {
        // EC+OC+S0C+S1C = 350000+900000+600+700 = 1251300 KB → 1222 MB.
        assertEquals(1222L, ProcessParsing.heapCommittedMb(gcStandard))
    }

    @Test
    fun `gc time reads the GCT total column, never YGCT plus FGCT`() {
        // GCT = 4.2 s → 4200 ms. YGCT+FGCT = 3.0 s would undercount (omits CGCT) — the headline trap.
        assertEquals(4200L, ProcessParsing.gcTimeMs(gcStandard))
    }

    @Test
    fun `columns are keyed by name across a reordered, renamed layout`() {
        // GCT first, EU/OU later, and S0U ABSENT — name-keying reads GCT/EU correctly and used is null.
        val gc = ProcessParsing.parseJstat(
            """
            GCT   EU        OU        EC        OC
            9.9   100000.0  200000.0  300000.0  400000.0
            """.trimIndent(),
        )
        assertEquals(9900L, ProcessParsing.gcTimeMs(gc))
        assertNull(ProcessParsing.heapUsedMb(gc)) // S0U/S1U absent → no guess
    }

    @Test
    fun `heap max reads capacity NGCMX plus OGCMX, not Xmx`() {
        val cap = ProcessParsing.parseJstat(
            """
            NGCMN    NGCMX     OGCMN    OGCMX      MCMN   MCMX
            1024.0   1398144.0 2048.0   2796288.0  0.0    1155072.0
            """.trimIndent(),
        )
        // (1398144+2796288)/1024 = 4096 MB.
        assertEquals(4096L, ProcessParsing.heapMaxMb(cap))
    }

    @Test
    fun `configured Xmx parses MaxHeapSize bytes to MB and tolerates a missing flag`() {
        val flags = "VM Flags:\n-XX:CICompilerCount=4 -XX:MaxHeapSize=4294967296 -XX:MinHeapSize=8388608"
        assertEquals(4096L, ProcessParsing.configuredXmxMb(flags))
        assertNull(ProcessParsing.configuredXmxMb("VM Flags:\n-XX:CICompilerCount=4"))
    }

    @Test
    fun `jps keeps only the three probed JVMs`() {
        val out = """
            12345 org.gradle.launcher.daemon.bootstrap.GradleDaemon
            23456 org.jetbrains.kotlin.daemon.KotlinCompileDaemon
            34567 org.gradle.process.internal.worker.GradleWorkerMain
            34568 org.gradle.process.internal.worker.GradleWorkerMain
            45678 org.gradle.wrapper.GradleWrapperMain
            56789 jdk.jcmd/sun.tools.jps.Jps
            99999
        """.trimIndent()
        val parsed = ProcessParsing.parseJpsLines(out)
        assertEquals(
            listOf(
                12345L to ProcessRole.GRADLE_DAEMON,
                23456L to ProcessRole.KOTLIN_DAEMON,
                34567L to ProcessRole.GRADLE_WORKER,
                34568L to ProcessRole.GRADLE_WORKER, // multiple workers collapse to repeated rows
            ),
            parsed,
        )
    }

    @Test
    fun `rss parses ps kilobytes to MB`() {
        assertEquals(2712L, ProcessParsing.rssMb("2776924\n")) // round(2776924/1024)
        assertNull(ProcessParsing.rssMb("not-a-number"))
    }

    @Test
    fun `etime parses the portable dd-hh-mm-ss elapsed format`() {
        assertEquals(3723L, ProcessParsing.uptimeSeconds("01:02:03")) // hh:mm:ss
        assertEquals(754L, ProcessParsing.uptimeSeconds("12:34")) // mm:ss
        assertEquals(2L * 86400 + 3 * 3600 + 4 * 60 + 5, ProcessParsing.uptimeSeconds("2-03:04:05"))
        assertNull(ProcessParsing.uptimeSeconds(""))
        assertNull(ProcessParsing.uptimeSeconds("garbage"))
    }

    @Test
    fun `a malformed jstat output parses to an empty map`() {
        assertEquals(emptyMap(), ProcessParsing.parseJstat("only-a-header-no-values"))
        assertNull(ProcessParsing.heapUsedMb(ProcessParsing.parseJstat("")))
    }
}
