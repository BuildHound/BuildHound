package dev.buildhound.gradle

/**
 * The JDK-tool/`ps` probes the collector drives (plan 029). An interface so [ProcessProbeCollector]
 * can be unit-tested against a fake that records invocations, without spawning real processes.
 *
 * Five probes since plan 104, down from six: the separate `ps -o rss=` and `ps -o etime=` execs
 * collapsed into a single [psSnapshot] that also returns `time=` (cumulative process CPU). One exec
 * fewer per probed PID, and the highest-value usage metric arrives with it — the probe got cheaper
 * while getting richer, which is what let CPU usage ship under a "must not cost build performance"
 * constraint.
 */
internal interface ProcessTools {
    fun jpsListing(): BoundedExec.Result
    fun jstatGc(pid: Long): BoundedExec.Result
    fun jstatCapacity(pid: Long): BoundedExec.Result
    fun jinfoFlags(pid: Long): BoundedExec.Result
    fun psSnapshot(pid: Long): BoundedExec.Result
}

/**
 * Bounded-exec wrappers over the JDK tools + `ps` for the process probe (plan 029). Each call is one
 * timeout-bounded [BoundedExec] run, so a hung `jstat`/`jinfo` cannot stall the build. Returns the
 * raw [BoundedExec.Result] so the collector can tell a timeout (stop probing further PIDs) from a
 * non-zero exit (skip one field) from success (parse via [ProcessParsing]).
 *
 * Executable names are injectable so the unit `test` source set can point them at fake POSIX scripts
 * (the [GitExec] fake-binary pattern); production uses the tools on PATH.
 */
internal class ProcessMetrics(
    private val timeoutMillis: Long,
    private val jps: String = "jps",
    private val jstat: String = "jstat",
    private val jinfo: String = "jinfo",
    private val ps: String = "ps",
) : ProcessTools {
    override fun jpsListing(): BoundedExec.Result = run(jps, listOf("-l"))

    override fun jstatGc(pid: Long): BoundedExec.Result = run(jstat, listOf("-gc", pid.toString()))

    override fun jstatCapacity(pid: Long): BoundedExec.Result = run(jstat, listOf("-gccapacity", pid.toString()))

    override fun jinfoFlags(pid: Long): BoundedExec.Result = run(jinfo, listOf("-flags", pid.toString()))

    /**
     * One `ps` for all three per-process numbers (plan 104). `rss`, `etime` and `time` are POSIX
     * standard format keywords, so the merged format string is as portable as the two it replaced;
     * a platform that rejects it loses three best-effort-nullable fields instead of two, and Windows
     * (no `ps`) reported null before and after.
     *
     * Each keyword carries an empty `=` header override, and POSIX suppresses the header line only
     * when **every** header is null — hence the `=` on all three. [ProcessParsing.parsePsSnapshot]
     * still tolerates a stray header line rather than trusting that.
     */
    override fun psSnapshot(pid: Long): BoundedExec.Result =
        run(ps, listOf("-o", "rss=,etime=,time=", "-p", pid.toString()))

    private fun run(executable: String, args: List<String>): BoundedExec.Result =
        BoundedExec.run(listOf(executable) + args, timeoutMillis)
}
