package dev.buildhound.commons.overhead

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfilerCsvTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/overhead/$name")) { "missing $name" }.readBytes().decodeToString()

    @Test
    fun `computes mean and stddev per scenario from a real benchmark csv`() {
        // Captured from an actual gradle-profiler 0.24.0 run (plan 106). It carries NO mean/stddev
        // rows — only per-build rows — and two columns per scenario (--measure-config-time), so this
        // fixture is what proves the parser reads what the tool emits rather than what we assumed.
        val stats = ProfilerCsv.parse(resource("benchmark-on.csv"))
        assertEquals(setOf("no_op", "incremental", "cc_hit", "no_op_upload", "no_op_ci"), stats.keys)
        assertEquals(1912.375, stats.getValue("no_op").meanMs, absoluteTolerance = 0.001)
        assertEquals(40.830, stats.getValue("no_op").stddevMs, absoluteTolerance = 0.001)
        assertEquals(2510.807, stats.getValue("incremental").meanMs, absoluteTolerance = 0.001)
    }

    @Test
    fun `excludes warm-up builds from the statistics`() {
        // Warm-ups carry cold-daemon cost; including them would inflate every mean.
        val csv = """
            scenario,no_op
            value,total execution time
            warm-up build #1,9000.0
            measured build #1,100.0
            measured build #2,200.0
        """.trimIndent()
        assertEquals(150.0, ProfilerCsv.parse(csv).getValue("no_op").meanMs)
    }

    @Test
    fun `still accepts a file that supplies summary rows instead of per-build rows`() {
        val csv = """
            scenario,no_op
            value,total execution time
            mean,600.0
            stddev,1.0
        """.trimIndent()
        val stats = ProfilerCsv.parse(csv).getValue("no_op")
        assertEquals(600.0, stats.meanMs)
        assertEquals(1.0, stats.stddevMs)
    }

    @Test
    fun `tolerates an unknown extra column and unknown rows`() {
        val csv = """
            scenario,no_op,future_scenario
            note,ignored,ignored
            tasks,help,help
            mean,600.0,999.0
            weird_unknown_row,x,y
            stddev,1.0,2.0
        """.trimIndent()
        val stats = ProfilerCsv.parse(csv)
        assertEquals(600.0, stats.getValue("no_op").meanMs)
        assertTrue(stats.containsKey("future_scenario"), "extra scenario columns are kept, not rejected")
    }

    @Test
    fun `a missing stddev row degrades to zero spread rather than failing`() {
        val csv = """
            scenario,no_op
            mean,600.0
        """.trimIndent()
        assertEquals(0.0, ProfilerCsv.parse(csv).getValue("no_op").stddevMs)
    }

    @Test
    fun `fails loudly on a missing mean row`() {
        val csv = """
            scenario,no_op
            stddev,1.0
        """.trimIndent()
        val error = assertFailsWith<IllegalArgumentException> { ProfilerCsv.parse(csv) }
        assertTrue(error.message!!.contains("mean"), error.message)
    }

    @Test
    fun `reads the total-execution-time column, not the config-time column beside it`() {
        // The shape --measure-config-time actually produces: two columns per scenario under one name.
        val csv = """
            scenario,no_op,no_op,cc_hit,cc_hit
            version,Gradle 9.6.1,Gradle 9.6.1,Gradle 9.6.1,Gradle 9.6.1
            value,total execution time,task start,total execution time,task start
            mean,1900.0,1200.0,1500.0,900.0
            stddev,20.0,9.0,15.0,7.0
        """.trimIndent()
        val stats = ProfilerCsv.parse(csv)
        assertEquals(setOf("no_op", "cc_hit"), stats.keys)
        assertEquals(1900.0, stats.getValue("no_op").meanMs, "must not read the 'task start' column")
        assertEquals(20.0, stats.getValue("no_op").stddevMs)
        assertEquals(1500.0, stats.getValue("cc_hit").meanMs)
    }

    @Test
    fun `fails loudly, naming the metric, when no column carries it`() {
        val csv = """
            scenario,no_op,no_op
            value,task start,garbage collection time
            mean,1200.0,30.0
        """.trimIndent()
        val error = assertFailsWith<IllegalArgumentException> { ProfilerCsv.parse(csv) }
        assertTrue(error.message!!.contains("total execution time"), error.message)
        assertTrue(error.message!!.contains("task start"), "names what the file did carry: ${error.message}")
    }

    @Test
    fun `fails loudly on a missing scenario row`() {
        val csv = """
            mean,600.0
            stddev,1.0
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { ProfilerCsv.parse(csv) }
    }
}
