package dev.buildhound.gradle

import dev.buildhound.commons.payload.GcCollector
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

    // ---- Plan 065: typed-allowlist GC/JVM tuning-flag extraction over the jinfo -flags line ----

    /** A realistic JDK-21 `jinfo -flags` output: header lines, dozens of flags, one collector. */
    private val realisticJinfo =
        "VM Flags:\n" +
            "-XX:CICompilerCount=4 -XX:ConcGCThreads=2 -XX:G1ConcRefinementThreads=8 " +
            "-XX:G1HeapRegionSize=2097152 -XX:GCDrainStackTargetSize=64 -XX:InitialHeapSize=268435456 " +
            "-XX:MarkStackSize=4194304 -XX:MaxHeapSize=4294967296 -XX:MaxMetaspaceSize=536870912 " +
            "-XX:MaxNewSize=2576351232 -XX:MinHeapDeltaBytes=2097152 -XX:MinHeapSize=8388608 " +
            "-XX:NonNMethodCodeHeapSize=5839372 -XX:ReservedCodeCacheSize=251658240 " +
            "-XX:+SegmentedCodeCache -XX:SoftMaxHeapSize=4294967296 -XX:-THPStackMitigation " +
            "-XX:+UseCompressedOops -XX:+UseG1GC -XX:+UseNUMA -XX:+UseNUMAInterleaving"

    @Test
    fun `the collector is picked from a realistic jinfo flags line`() {
        assertEquals(GcCollector.G1, ProcessParsing.parseGcCollector(realisticJinfo))
        assertEquals(
            GcCollector.PARALLEL,
            ProcessParsing.parseGcCollector("-XX:MaxHeapSize=1073741824 -XX:+UseParallelGC -XX:+UseNUMA"),
        )
        assertEquals(GcCollector.ZGC, ProcessParsing.parseGcCollector("-XX:+UseZGC -XX:+ZGenerational"))
        assertEquals(GcCollector.SERIAL, ProcessParsing.parseGcCollector("-XX:+UseSerialGC"))
        assertEquals(GcCollector.SHENANDOAH, ProcessParsing.parseGcCollector("-XX:+UseShenandoahGC"))
        assertEquals(GcCollector.EPSILON, ProcessParsing.parseGcCollector("-XX:+UseEpsilonGC"))
    }

    @Test
    fun `an unknown enabled collector is honest UNKNOWN and no collector flag at all is null`() {
        // A future JDK's collector the allowlist doesn't know → UNKNOWN, never a guessed name.
        assertEquals(GcCollector.UNKNOWN, ProcessParsing.parseGcCollector("-XX:MaxHeapSize=1073741824 -XX:+UseFancyNewGC"))
        // No collector-selection flag printed → null.
        assertNull(ProcessParsing.parseGcCollector("VM Flags:\n-XX:CICompilerCount=4 -XX:MaxHeapSize=1073741824"))
        // A DISABLED collector flag never counts as a selection.
        assertNull(ProcessParsing.parseGcCollector("-XX:-UseParallelGC"))
        // Known Use…SystemGC tuning flags are not collectors — excluded from the UNKNOWN fallback.
        assertNull(ProcessParsing.parseGcCollector("-XX:+UseMaximumCompactionOnSystemGC"))
    }

    @Test
    fun `compact object headers reads plus as true, minus as false, absent as null`() {
        assertEquals(true, ProcessParsing.parseCompactObjectHeaders("-XX:+UseCompactObjectHeaders -XX:+UseG1GC"))
        assertEquals(false, ProcessParsing.parseCompactObjectHeaders("-XX:-UseCompactObjectHeaders -XX:+UseG1GC"))
        assertNull(ProcessParsing.parseCompactObjectHeaders(realisticJinfo))
    }

    @Test
    fun `a hostile jinfo line yields ONLY the typed allowlisted fields — leak-proof by construction`() {
        // jinfo also prints -D args and the classpath: secrets and absolute paths ride in the same
        // string the allowlist reads. The three extractions are enum/bool/long — no string output
        // exists, so nothing scrubbable can leak (the reason plan 029 rejected a jvmargs summary).
        val hostile =
            "VM Flags:\n" +
                "-XX:MaxHeapSize=2147483648 -XX:+UseG1GC -XX:-UseCompactObjectHeaders " +
                "-Dtoken=super-secret-value -Dapi.key=AKIA1234567890SECRET " +
                "-cp /Users/jane/work/secret-project/classes:/opt/keys/creds.jar " +
                "-javaagent:/home/jane/.tokens/agent.jar"
        assertEquals(2048L, ProcessParsing.configuredXmxMb(hostile))
        assertEquals(GcCollector.G1, ProcessParsing.parseGcCollector(hostile))
        assertEquals(false, ProcessParsing.parseCompactObjectHeaders(hostile))
        // And with no allowlisted flag present, the hostile remainder produces nothing at all.
        val onlyHostile = "-Dtoken=super-secret-value -cp /Users/jane/secret/classes"
        assertNull(ProcessParsing.configuredXmxMb(onlyHostile))
        assertNull(ProcessParsing.parseGcCollector(onlyHostile))
        assertNull(ProcessParsing.parseCompactObjectHeaders(onlyHostile))
    }
}
