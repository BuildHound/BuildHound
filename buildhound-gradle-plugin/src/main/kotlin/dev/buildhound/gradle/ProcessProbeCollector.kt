package dev.buildhound.gradle

/**
 * Gradle-free process-probe collection (plan 029): drive [ProcessTools] over each probed PID and
 * normalize via [ProcessParsing]. Kept off the Gradle classpath (log sinks injected) so the timeout
 * short-circuit and per-field degradation are unit-testable against a fake `ProcessTools`.
 *
 * Timeout contract: a hung tool will hang again, so the FIRST timeout latches and **skips** every
 * later exec — within the same PID and for all further PIDs — bounding the whole collection at one
 * timeout budget, not one per exec. (The exec is behind a lambda so a timed-out probe is never even
 * invoked, not merely discarded after running.)
 */
internal object ProcessProbeCollector {

    fun collect(
        tools: ProcessTools,
        onTimeout: (tool: String) -> Unit = {},
        onFailure: (exceptionClass: String) -> Unit = {},
    ): List<CollectedProcess> {
        val probe = Probe(onTimeout, onFailure)
        // No jps (JRE-only agent) or a jps timeout → nothing observable, an empty list, never an error.
        val listing = probe.stdout("jps") { tools.jpsListing() } ?: return emptyList()
        val processes = ArrayList<CollectedProcess>()
        for ((pid, role) in ProcessParsing.parseJpsLines(listing)) {
            val gc = probe.stdout("jstat") { tools.jstatGc(pid) }?.let(ProcessParsing::parseJstat)
            val capacity = probe.stdout("jstat") { tools.jstatCapacity(pid) }?.let(ProcessParsing::parseJstat)
            val flags = probe.stdout("jinfo") { tools.jinfoFlags(pid) }
            val rss = probe.stdout("ps") { tools.psRss(pid) }
            val etime = probe.stdout("ps") { tools.psEtime(pid) }
            processes.add(
                CollectedProcess(
                    role = role,
                    heapUsedMb = gc?.let(ProcessParsing::heapUsedMb),
                    heapCommittedMb = gc?.let(ProcessParsing::heapCommittedMb),
                    heapMaxMb = capacity?.let(ProcessParsing::heapMaxMb),
                    configuredXmxMb = flags?.let(ProcessParsing::configuredXmxMb),
                    gcTimeMs = gc?.let(ProcessParsing::gcTimeMs),
                    rssMb = rss?.let(ProcessParsing::rssMb),
                    uptimeS = etime?.let(ProcessParsing::uptimeSeconds),
                ),
            )
            if (probe.timedOut) break
        }
        return processes
    }

    /**
     * Runs an exec only while no earlier exec has timed out; interprets the result. Success → stdout,
     * non-zero/failure → null (drop one field), timeout → null plus a latch that short-circuits every
     * later probe. Tool labels are static — process output (which can embed paths/args) is never logged.
     */
    private class Probe(
        private val onTimeout: (tool: String) -> Unit,
        private val onFailure: (exceptionClass: String) -> Unit,
    ) {
        var timedOut = false
            private set

        fun stdout(tool: String, exec: () -> BoundedExec.Result): String? {
            if (timedOut) return null
            return interpret(tool, exec())
        }

        private fun interpret(tool: String, result: BoundedExec.Result): String? = when (result) {
            is BoundedExec.Result.Success -> result.stdout
            is BoundedExec.Result.NonZeroExit -> null
            is BoundedExec.Result.TimedOut -> {
                timedOut = true
                onTimeout(tool)
                null
            }
            is BoundedExec.Result.Failed -> {
                onFailure(result.exceptionClass)
                null
            }
        }
    }
}
