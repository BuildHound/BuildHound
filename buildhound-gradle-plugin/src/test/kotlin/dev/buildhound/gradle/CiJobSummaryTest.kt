package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.DerivedMetrics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CiJobSummaryTest {
    @field:TempDir
    lateinit var tempDir: File

    private val payload = BuildPayload(
        buildId = "build / 7",
        startedAt = 1_000,
        finishedAt = 63_500,
        outcome = BuildOutcome.SUCCESS,
        requestedTasks = listOf("build", "danger|\nrow"),
        mode = BuildMode.CI,
        derived = DerivedMetrics(cacheableHitRate = 0.875),
    )

    @Test
    fun `render contains bounded escaped metrics and a safe deep link`() {
        val summary = CiJobSummary.render(payload, "https://buildhound.example.com/")

        assertTrue(summary.contains("1m 02s"))
        assertTrue(summary.contains("87.5%"))
        assertTrue(summary.contains("danger\\| row"))
        assertTrue(summary.contains("https://buildhound.example.com/#/build/build%20%2F%207"))
        assertFalse(summary.contains("danger|\nrow"))
    }

    @Test
    fun `non-http dashboard base omits the link`() {
        assertFalse(CiJobSummary.render(payload, "javascript:alert(1)").contains("Open build"))
    }

    @Test
    fun `credential query and markdown delimiter dashboard bases omit the link`() {
        val unsafe = listOf(
            "https://user:secret@example.com",
            "https://example.com/dashboard?token=secret",
            "https://example.com/dashboard)|injected",
            "https://example.com/dashboard\nnext-row",
        )
        unsafe.forEach { assertFalse(CiJobSummary.render(payload, it).contains("Open build"), it) }
    }

    @Test
    fun `missing derived metrics render n-a without leaking unrelated sensitive text`() {
        val summary = CiJobSummary.render(
            payload.copy(derived = null, requestedTasks = listOf(":safe:build")),
            "https://buildhound.example.com",
        )
        assertTrue(summary.contains("Cacheable hit rate | n/a"))
        assertFalse(summary.contains("token", ignoreCase = true))
        assertFalse(summary.contains("/Users/"))
        assertFalse(summary.contains("C:\\"))
    }

    @Test
    fun `github write is a no-op without its step-summary environment`() {
        CiJobSummary.write(payload, "github-actions", emptyMap(), null, tempDir) { error(it) }
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }
}
