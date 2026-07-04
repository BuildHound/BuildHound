package dev.buildhound.commons.overhead

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfilerCsvTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/overhead/$name")) { "missing $name" }.readBytes().decodeToString()

    @Test
    fun `parses mean and stddev per scenario, keyed by name`() {
        val stats = ProfilerCsv.parse(resource("benchmark-on.csv"))
        assertEquals(setOf("no_op", "incremental", "cc_hit", "no_op_upload", "no_op_ci"), stats.keys)
        assertEquals(652.3, stats.getValue("no_op").meanMs)
        assertEquals(2.5, stats.getValue("no_op").stddevMs)
        assertEquals(3130.0, stats.getValue("incremental").meanMs)
        assertEquals(10.0, stats.getValue("incremental").stddevMs)
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
    fun `fails loudly on a missing scenario row`() {
        val csv = """
            mean,600.0
            stddev,1.0
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { ProfilerCsv.parse(csv) }
    }
}
