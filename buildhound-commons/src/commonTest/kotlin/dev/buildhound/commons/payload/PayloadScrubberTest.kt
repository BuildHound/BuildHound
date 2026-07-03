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
    fun long_dotfree_roots_still_relativize_instead_of_being_eaten_as_blobs() {
        // macOS temp shape: /private/var/folders/<xx>/<~30-char hash>/T/junitNNN/... —
        // a digit-bearing, dot-free run >= 32 chars. The blob regex used to eat the
        // root (its char class spans '/') before relativization could happen, turning
        // "file <root>/input.txt" into "file <redacted><path>" (user-reported bug).
        val macRoot = "/private/var/folders/5d/abcdef1234567890abcdef1234567890/T/junit1234567890/project"
        assertEquals(
            "Input property 'source' file input.txt has changed.",
            PayloadScrubber.scrubText("Input property 'source' file $macRoot/input.txt has changed.", macRoot),
        )
        // Plain + canonical root pair (the /var -> /private/var symlink), deeper file.
        assertEquals(
            "Input property 'source' file src/main/A.kt has changed.",
            PayloadScrubber.scrubText(
                "Input property 'source' file $macRoot/src/main/A.kt has changed.",
                listOf("/var/folders/5d/abcdef1234567890abcdef1234567890/T/junit1234567890/project", macRoot),
            ),
        )
        // An OUT-of-project path of the same shape must still vanish entirely —
        // which token replaces it doesn't matter, but no absolute fragment survives.
        val outside = PayloadScrubber.scrubText(
            "file /private/var/folders/5d/abcdef1234567890abcdef1234567890/T/other/cache.bin was removed",
            macRoot,
        )
        assertFalse(outside.contains("folders"), outside)
        assertFalse(outside.contains("private"), outside)
        assertFalse(outside.contains("cache.bin"), outside)
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
    fun snake_case_secret_keys_are_redacted() {
        assertEquals("GITHUB_TOKEN=<redacted>", PayloadScrubber.scrubText("GITHUB_TOKEN=ghp_AbCd1234", root))
        assertEquals("access_token=<redacted>", PayloadScrubber.scrubText("access_token: abc123", root))
        assertEquals("AWS_SECRET_ACCESS_KEY=<redacted>", PayloadScrubber.scrubText("AWS_SECRET_ACCESS_KEY=wJalr/K7MD", root))
        assertEquals("GITHUB_PAT=<redacted>", PayloadScrubber.scrubText("GITHUB_PAT=xyz", root))
        assertEquals("PAT=<redacted>", PayloadScrubber.scrubText("PAT=xyz", root))
    }

    @Test
    fun classpath_keys_are_not_mistaken_for_pat_secrets() {
        assertEquals("classpath=lib.jar", PayloadScrubber.scrubText("classpath=lib.jar", root))
    }

    @Test
    fun jwts_and_url_credentials_and_aws_keys_are_redacted() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("got <redacted> back", PayloadScrubber.scrubText("got $jwt back", root))
        assertEquals(
            "fetch https://<redacted>@git.example.com/repo.git failed",
            PayloadScrubber.scrubText("fetch https://ci-user:S3cr3t@git.example.com/repo.git failed", root),
        )
        assertEquals("key <redacted> used", PayloadScrubber.scrubText("key AKIAIOSFODNN7EXAMPLE used", root))
    }

    @Test
    fun long_camel_case_identifiers_survive() {
        val text = "Value of input property 'transformClassesWithDexBuilderForDebugStuff' has changed."
        assertEquals(text, PayloadScrubber.scrubText(text, root))
    }

    @Test
    fun unc_paths_are_redacted() {
        assertEquals("file <path> changed", PayloadScrubber.scrubText("file \\\\server\\share\\users\\dylan\\f.txt changed", root))
    }

    @Test
    fun quoted_multi_word_secrets_vanish_whole() {
        assertEquals("password=<redacted>", PayloadScrubber.scrubText("password = \"hunter two words\"", root))
    }

    @Test
    fun in_project_paths_with_spaces_relativize_via_literal_root_pass() {
        val spacedRoot = "/Users/John Doe/project"
        assertEquals(
            "file src/A.kt changed",
            PayloadScrubber.scrubText("file /Users/John Doe/project/src/A.kt changed", spacedRoot),
        )
    }

    @Test
    fun degenerate_roots_never_relativize_the_filesystem() {
        assertEquals("file <path> changed", PayloadScrubber.scrubText("file /etc/passwd changed", "/"))
        assertEquals("file <path> changed", PayloadScrubber.scrubText("file C:\\Windows\\secret changed", "C:\\"))
    }

    @Test
    fun canonical_root_alternates_relativize_too() {
        assertEquals(
            "file src/A.kt changed",
            PayloadScrubber.scrubText("file /private/tmp/proj/src/A.kt changed", listOf("/tmp/proj", "/private/tmp/proj")),
        )
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
