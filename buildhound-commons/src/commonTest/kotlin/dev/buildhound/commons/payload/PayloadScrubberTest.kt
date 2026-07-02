package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayloadScrubberTest {

    private val root = "/home/ci/agent/work/project"

    @Test
    fun paths_under_the_project_root_are_relativized() {
        assertEquals(
            "Input property 'x' file src/main/A.kt has changed.",
            PayloadScrubber.scrubText("Input property 'x' file /home/ci/agent/work/project/src/main/A.kt has changed.", root),
        )
        // Trailing slash on the root must not matter.
        assertEquals(
            "src/main/A.kt changed",
            PayloadScrubber.scrubText("$root/src/main/A.kt changed", "$root/"),
        )
        assertEquals(".", PayloadScrubber.scrubText(root, root))
    }

    @Test
    fun paths_outside_the_project_are_redacted() {
        assertEquals(
            "file <path> has been removed.",
            PayloadScrubber.scrubText("file /home/dylan/.gradle/caches/thing.bin has been removed.", root),
        )
        assertEquals("file <path> changed", PayloadScrubber.scrubText("file /etc/passwd changed", root))
        assertEquals("file <path> changed", PayloadScrubber.scrubText("file /home/dylan/x changed", null))
    }

    @Test
    fun windows_paths_are_covered() {
        assertEquals(
            "file <path> changed",
            PayloadScrubber.scrubText("""file C:\Users\dylan\secrets.txt changed""", root),
        )
        assertEquals(
            "file src\\A.kt changed",
            PayloadScrubber.scrubText("""file C:\work\proj\src\A.kt changed""", """C:\work\proj"""),
        )
    }

    @Test
    fun secret_pairs_are_redacted_keeping_the_key() {
        assertEquals("token=<redacted>", PayloadScrubber.scrubText("token=abc123", root))
        assertEquals("API_KEY=<redacted> in args", PayloadScrubber.scrubText("API_KEY: 'sk-live-42' in args", root))
        assertEquals("password=<redacted>", PayloadScrubber.scrubText("""password = "hunter2"""", root))
        assertEquals("Authorization=<redacted>", PayloadScrubber.scrubText("Authorization: Bearer abc.def", root))
    }

    @Test
    fun long_blobs_are_redacted_but_shas_in_declared_fields_survive() {
        val blob = "A".repeat(20) + "b1c2d3e4f5g6h7i8"
        assertEquals("value <redacted> seen", PayloadScrubber.scrubText("value $blob seen", root))

        val payload = payloadWith(reason = "input $blob changed").copy(
            vcs = VcsInfo(sha = "cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a"),
        )
        val scrubbed = PayloadScrubber.scrub(payload, root)
        assertEquals("input <redacted> changed", scrubbed.tasks.single().executionReasons.single())
        assertEquals("cfb5b38c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a", scrubbed.vcs?.sha, "declared fields are untouched")
    }

    @Test
    fun plain_prose_and_task_paths_pass_through() {
        val untouched = listOf(
            "Task has not declared any outputs despite executing actions.",
            "Output property 'binaryResultsDirectory' has been removed.",
            "Task ':app:compileKotlin' is not up-to-date.",
            "No history is available.",
        )
        for (text in untouched) {
            assertEquals(text, PayloadScrubber.scrubText(text, root), text)
        }
    }

    @Test
    fun scrub_touches_only_execution_reasons() {
        val payload = payloadWith(reason = "file /etc/passwd changed")

        val scrubbed = PayloadScrubber.scrub(payload, root)

        assertEquals("file <path> changed", scrubbed.tasks.single().executionReasons.single())
        assertEquals(payload.buildId, scrubbed.buildId)
        assertEquals(payload.tasks.single().path, scrubbed.tasks.single().path)
    }

    @Test
    fun secret_containing_a_path_is_redacted_as_a_secret() {
        val scrubbed = PayloadScrubber.scrubText("token=/home/dylan/.ssh/id_rsa", root)

        assertEquals("token=<redacted>", scrubbed)
        assertFalse(scrubbed.contains("id_rsa"))
    }

    private fun payloadWith(reason: String) = BuildPayload(
        buildId = "b-1",
        startedAt = 0,
        finishedAt = 1,
        outcome = BuildOutcome.SUCCESS,
        tasks = listOf(
            TaskExecution(
                path = ":app:compile",
                startMs = 0,
                durationMs = 1,
                outcome = TaskOutcome.EXECUTED,
                executionReasons = listOf(reason),
            ),
        ),
    )
}
