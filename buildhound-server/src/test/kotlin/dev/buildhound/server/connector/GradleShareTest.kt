package dev.buildhound.server.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleShareTest {

    private fun run(startedAt: Long?, finishedAt: Long?) = CiRun(startedAt = startedAt, finishedAt = finishedAt)

    @Test
    fun `share is build wall over pipeline wall`() {
        // build 60s of a 300s pipeline → 0.2.
        assertEquals(0.2, GradleShare.percent(60_000, run(0, 300_000)))
    }

    @Test
    fun `share clamps to one when the build somehow exceeds the pipeline`() {
        assertEquals(1.0, GradleShare.percent(400_000, run(0, 300_000)))
    }

    @Test
    fun `null when the build duration is unknown`() {
        assertNull(GradleShare.percent(null, run(0, 300_000)))
    }

    @Test
    fun `null when the pipeline has no start or finish`() {
        assertNull(GradleShare.percent(60_000, run(0, null)))
        assertNull(GradleShare.percent(60_000, run(null, 300_000)))
        assertNull(GradleShare.percent(60_000, null))
    }

    @Test
    fun `null when the pipeline span is non-positive`() {
        assertNull(GradleShare.percent(60_000, run(300_000, 300_000)))
        assertNull(GradleShare.percent(60_000, run(300_000, 0)))
    }
}
