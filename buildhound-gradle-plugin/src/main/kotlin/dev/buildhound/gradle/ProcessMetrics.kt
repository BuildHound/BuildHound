package dev.buildhound.gradle

/**
 * The six JDK-tool/`ps` probes the collector drives (plan 029). An interface so [ProcessProbeCollector]
 * can be unit-tested against a fake that records invocations, without spawning real processes.
 */
internal interface ProcessTools {
    fun jpsListing(): BoundedExec.Result
    fun jstatGc(pid: Long): BoundedExec.Result
    fun jstatCapacity(pid: Long): BoundedExec.Result
    fun jinfoFlags(pid: Long): BoundedExec.Result
    fun psRss(pid: Long): BoundedExec.Result
    fun psEtime(pid: Long): BoundedExec.Result
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

    override fun psRss(pid: Long): BoundedExec.Result = run(ps, listOf("-o", "rss=", "-p", pid.toString()))

    override fun psEtime(pid: Long): BoundedExec.Result = run(ps, listOf("-o", "etime=", "-p", pid.toString()))

    private fun run(executable: String, args: List<String>): BoundedExec.Result =
        BoundedExec.run(listOf(executable) + args, timeoutMillis)
}
