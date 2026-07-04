package dev.buildhound.overheadfixture.b

import kotlin.test.Test
import kotlin.test.assertTrue

/** One test so the `assemble`/`build` graph the profiler drives includes a Test task. */
class FormatterTest {
    @Test
    fun `banner greets and appends the lucky number`() {
        val banner = Formatter().banner("World")
        assertTrue(banner.contains("World"))
        assertTrue(banner.contains("55")) // sum of squares 1..5
    }
}
