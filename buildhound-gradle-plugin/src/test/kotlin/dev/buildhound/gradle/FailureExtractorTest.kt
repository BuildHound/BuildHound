package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailureExtractorTest {

    /** Mimics Gradle's `MultipleBuildFailures` / `DefaultMultiCauseException`: exposes `getCauses()`. */
    private class FakeMultiCause(private val causes: List<Throwable>) :
        RuntimeException("Build completed with ${causes.size} failures") {
        @Suppress("unused") // reflected by FailureExtractor
        fun getCauses(): List<Throwable> = causes
    }

    @Test
    fun `extracts class, message, hash and stacktrace from a plain throwable`() {
        val collected = FailureExtractor.extract(IllegalStateException("boom happened"))

        assertEquals("java.lang.IllegalStateException", collected.exceptionClass)
        assertEquals("boom happened", collected.message)
        // messageHash is a 64-char lowercase SHA-256 hex over the raw message.
        val hash = collected.messageHash
        assertEquals(64, hash?.length)
        assertTrue(hash != null && hash.all { it in "0123456789abcdef" })
        assertNotNull(collected.stackTrace)
        assertTrue(collected.stackTrace!!.contains("IllegalStateException"))
    }

    @Test
    fun `flattens a multi-cause aggregate by joining child messages`() {
        val aggregate = FakeMultiCause(listOf(RuntimeException("first failure"), RuntimeException("second failure")))

        val collected = FailureExtractor.extract(aggregate)

        assertEquals("dev.buildhound.gradle.FailureExtractorTest\$FakeMultiCause", collected.exceptionClass)
        assertEquals("first failure; second failure", collected.message)
    }

    @Test
    fun `a blank message yields null message and null hash`() {
        val collected = FailureExtractor.extract(RuntimeException())

        assertNull(collected.message)
        assertNull(collected.messageHash)
        // The stacktrace is still captured (it carries the throw site).
        assertNotNull(collected.stackTrace)
    }

    @Test
    fun `the same message always hashes to the same key`() {
        val a = FailureExtractor.extract(RuntimeException("stable"))
        val b = FailureExtractor.extract(IllegalArgumentException("stable"))
        assertEquals(a.messageHash, b.messageHash, "hash is over the message text only, not the type")
    }
}
