package dev.buildhound.gradle

import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The finalizer joins the task path → static type/cacheable/nonCacheableReason dictionary
 * (plan 016) onto the collector's task snapshot (plan 056, closes plan 045): a total lookup —
 * a task with no dictionary entry keeps its already-null fields, never a partial join.
 */
class JoinTaskMetadataTest {

    private fun task(path: String) = TaskExecution(
        path = path,
        startMs = 0L,
        durationMs = 10L,
        outcome = TaskOutcome.EXECUTED,
    )

    @Test
    fun `a dictionary hit copies type, cacheable, and nonCacheableReason onto the task`() {
        val tasks = listOf(task(":app:compileKotlin"))
        val dictionary = mapOf(
            ":app:compileKotlin" to TaskMetadata(
                type = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile",
                cacheable = true,
                nonCacheableReason = null,
            ),
        )

        val joined = joinTaskMetadata(tasks, dictionary)

        assertEquals(1, joined.size)
        assertEquals("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", joined[0].type)
        assertEquals(true, joined[0].cacheable)
        assertEquals(null, joined[0].nonCacheableReason)
    }

    @Test
    fun `a dictionary miss leaves the task unchanged`() {
        val original = task(":app:test")
        val joined = joinTaskMetadata(listOf(original), mapOf(":other:task" to TaskMetadata(
            type = "SomeTask",
            cacheable = false,
            nonCacheableReason = "not cacheable",
        )))

        assertEquals(1, joined.size)
        assertEquals(original, joined[0])
    }

    @Test
    fun `an empty dictionary leaves the whole list unchanged`() {
        val tasks = listOf(task(":app:compileJava"), task(":app:test"))

        val joined = joinTaskMetadata(tasks, emptyMap())

        assertEquals(tasks, joined)
    }
}
