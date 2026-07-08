package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun benchmark_seedRef_is_scrubbed() {
        val payload = BuildPayload(
            buildId = "b", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.SUCCESS,
            benchmark = BenchmarkInfo(scenario = "clean", seedRef = "seed at /home/ci/agent/work/project/out and token=abc123XYZ"),
        )
        val scrubbed = PayloadScrubber.scrub(payload, root).benchmark!!
        assertEquals("clean", scrubbed.scenario) // allowlisted label passes through unchanged
        assertTrue(scrubbed.seedRef!!.contains("out"), "in-project path relativizes: ${scrubbed.seedRef}")
        assertFalse(scrubbed.seedRef.contains("/home/ci/agent"), "out-of-root path stripped: ${scrubbed.seedRef}")
        assertFalse(scrubbed.seedRef.contains("abc123XYZ"), "secret-shaped token redacted: ${scrubbed.seedRef}")
    }

    @Test
    fun failure_message_and_stacktrace_are_scrubbed_and_truncated() {
        val secretMessage = "Execution failed: token=abc123XYZ456def in $root/src/Main.kt"
        val stack = "java.lang.IllegalStateException: boom at $root/src/Main.kt:10\n" +
            "\tat $root/build/tmp/other.kt\n" +
            "password: hunter2secret\n" +
            "x".repeat(20_000) // pushes the trace past the 8 KiB stacktrace cap
        val payload = BuildPayload(
            buildId = "b", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.FAILED,
            failure = FailureInfo(
                exceptionClass = "java.lang.IllegalStateException",
                messageHash = "deadbeef",
                message = secretMessage,
                stackTrace = stack,
            ),
        )
        val scrubbed = PayloadScrubber.scrub(payload, root).failure!!
        // Declared data — exceptionClass + messageHash pass through untouched.
        assertEquals("java.lang.IllegalStateException", scrubbed.exceptionClass)
        assertEquals("deadbeef", scrubbed.messageHash)
        // message: in-project path relativized, secret-shaped token redacted, within the 512 cap.
        assertTrue(scrubbed.message!!.contains("src/Main.kt"), scrubbed.message)
        assertFalse(scrubbed.message.contains("/home/ci/agent"), scrubbed.message)
        assertFalse(scrubbed.message.contains("abc123XYZ456def"), scrubbed.message)
        assertTrue(scrubbed.message.length <= 512, "message capped: ${scrubbed.message.length}")
        // stacktrace: secret gone, out-of-project + in-project absolute paths stripped, truncated to 8 KiB.
        assertFalse(scrubbed.stackTrace!!.contains("hunter2secret"), "secret in stacktrace redacted")
        assertFalse(scrubbed.stackTrace.contains("/home/ci/agent"), "absolute path in stacktrace stripped")
        assertTrue(scrubbed.stackTrace.length <= 8192, "stacktrace truncated to cap: ${scrubbed.stackTrace.length}")
    }

    @Test
    fun space_separated_flag_secrets_are_redacted() {
        // The `=`/`:` form is covered elsewhere; this is the whitespace CLI form failure text carries.
        // Short, low-entropy values that the 32-char blob rule can't catch are the point.
        assertEquals("git clone failed: --token <redacted>", PayloadScrubber.scrubText("git clone failed: --token ghp_short12", root))
        assertEquals("--password <redacted>", PayloadScrubber.scrubText("--password hunter2", root))
        // A value-less flag (no following space+value) is left intact — it is not a secret.
        assertEquals("--password-file config.txt", PayloadScrubber.scrubText("--password-file config.txt", root))
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
    fun scrub_redacts_non_cacheable_reason_free_text() {
        val payload = payloadWith(reason = "up to date").copy(
            tasks = listOf(
                TaskExecution(
                    path = ":app:foo",
                    startMs = 0,
                    durationMs = 1,
                    outcome = TaskOutcome.EXECUTED,
                    cacheable = false,
                    nonCacheableReason = "Caching disabled: reads $root/local.props and token=sk-live-42",
                    executionReasons = listOf("file /etc/passwd changed"),
                ),
            ),
        )

        val scrubbed = PayloadScrubber.scrub(payload, root).tasks.single()

        assertEquals("Caching disabled: reads local.props and token=<redacted>", scrubbed.nonCacheableReason)
        assertEquals("file <path> changed", scrubbed.executionReasons.single())
        // A null reason must stay null and not blow up the mapper.
        assertNull(PayloadScrubber.scrub(payloadWith(reason = "x"), root).tasks.single().nonCacheableReason)
    }

    @Test
    fun scrub_redacts_kotlin_non_incremental_reason_free_text() {
        val payload = payloadWith(reason = "x").copy(
            kotlin = KotlinInfo(
                perTask = listOf(
                    KotlinTaskReport(
                        taskPath = ":app:compileKotlin",
                        nonIncrementalReasons = listOf("changed file $root/src/A.kt", "UNKNOWN_CHANGES_IN_GRADLE_INPUTS"),
                    ),
                ),
            ),
        )

        val reasons = PayloadScrubber.scrub(payload, root).kotlin?.perTask?.single()?.nonIncrementalReasons
        assertEquals(listOf("changed file src/A.kt", "UNKNOWN_CHANGES_IN_GRADLE_INPUTS"), reasons)
    }

    @Test
    fun scrub_redacts_hostile_kotlin_task_path_and_phase_keys() {
        // taskPath and compilerTimesMs keys are lifted from the untrusted KGP report file, so a
        // hostile report must not smuggle a path/secret past §3.7 through either of them.
        val payload = payloadWith(reason = "x").copy(
            kotlin = KotlinInfo(
                perTask = listOf(
                    KotlinTaskReport(
                        taskPath = "$root/leaked/output",
                        compilerTimesMs = mapOf("token=ghp_AbCd1234" to 5, "RUN_COMPILATION" to 10),
                    ),
                ),
            ),
        )

        val report = PayloadScrubber.scrub(payload, root).kotlin?.perTask?.single()
        assertEquals("leaked/output", report?.taskPath, "an out-of-place root in taskPath must relativize")
        assertTrue(report!!.compilerTimesMs.keys.contains("token=<redacted>"), report.compilerTimesMs.keys.toString())
        assertFalse(report.compilerTimesMs.keys.any { it.contains("ghp_AbCd1234") }, "secret phase key must not survive")
        assertEquals(10, report.compilerTimesMs["RUN_COMPILATION"], "a benign phase key passes through unchanged")
    }

    @Test
    fun scrub_redacts_test_case_failure_message_free_text() {
        // Test failure text (plan 024) routinely embeds an absolute path and can carry a secret.
        val payload = payloadWith(reason = "x").copy(
            tests = listOf(
                TestTaskResult(
                    taskPath = ":app:test",
                    module = ":app",
                    failedOrRetried = listOf(
                        TestCaseDetail(
                            className = "com.example.FooTest",
                            name = "reads()",
                            message = "expected file $root/src/expected.txt with token=ghp_AbCd1234",
                        ),
                    ),
                ),
            ),
        )

        val message = PayloadScrubber.scrub(payload, root).tests.single().failedOrRetried.single().message
        assertEquals("expected file src/expected.txt with token=<redacted>", message)
        // Class and method names are declared data — untouched.
        assertEquals("com.example.FooTest", PayloadScrubber.scrub(payload, root).tests.single().failedOrRetried.single().className)
    }

    @Test
    fun scrub_leaves_a_null_test_message_null() {
        val payload = payloadWith(reason = "x").copy(
            tests = listOf(
                TestTaskResult(
                    taskPath = ":app:test",
                    failedOrRetried = listOf(TestCaseDetail(className = "com.example.FooTest", name = "x()", message = null)),
                ),
            ),
        )
        assertNull(PayloadScrubber.scrub(payload, root).tests.single().failedOrRetried.single().message)
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

    // --- 076 review-fix: ReDoS guard (HIGH) ---------------------------------------------------
    //
    // [longBlob] and [secretPair] both exhibit super-linear (empirically quadratic) backtracking
    // on a long run of matching-shape characters that never resolves the pattern. Since server
    // ingest (plan 076) now runs scrubText on unbounded, attacker-controlled, pre-cap fields, an
    // unclamped input is a CPU-exhaustion DoS. The fix clamps every scrub input to 8192 chars
    // before any regex runs — measured (not guessed): the review's proposed 64 KiB clamp was
    // rejected because it does NOT bound cost (longBlob alone ~23s at 65536 chars; secretPair,
    // the worse offender, far longer) — see PayloadScrubber's MAX_SCRUB_INPUT_CHARS KDoc for the
    // full measurement table and the 8192 derivation (at/above every downstream text cap in the
    // file, comfortably bounded regex cost).

    @Test
    fun redos_guard_clamps_a_pathological_slash_run_to_bounded_time() {
        // longBlob's worst case: a long run of '/' with no digit ever satisfies its lookahead.
        val slashes = "/".repeat(1_000_000) // 1 MiB pathological input
        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        val scrubbed = PayloadScrubber.scrubText(slashes, root)
        val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds
        assertTrue(elapsedMs < 2000, "scrub of a 1 MiB slash-run took ${elapsedMs}ms, expected < 2000ms (076 review fix)")
        // Clamped to 8192 chars before any regex runs; a pure slash run matches none of the
        // scrubber's regexes, so the clamped slice survives untouched, with the marker appended.
        assertEquals("/".repeat(8192) + "…<truncated>", scrubbed)
    }

    @Test
    fun redos_guard_bounds_the_worse_secretPair_word_run_shape() {
        // secretPair's worst case is a long run of plain word characters with no '='/':' anywhere
        // — measured worse than longBlob's slash-run at the same size (8192 chars: ~1.9s vs
        // ~340ms). A wider bound avoids flaking near that margin; the slash-run test above stays
        // tight at < 2s.
        val wordRun = "a".repeat(1_000_000)
        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        PayloadScrubber.scrubText(wordRun, root)
        val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds
        assertTrue(elapsedMs < 5000, "scrub of a 1 MiB word-character run took ${elapsedMs}ms, expected < 5000ms (076 review fix)")
    }

    @Test
    fun secret_sitting_before_the_clamp_boundary_is_still_redacted_even_when_the_tail_is_truncated() {
        // The secret sits entirely inside the first 8192 chars (well clear of the boundary); a
        // long, unrelated tail past the clamp must not stop the whole-value secret match.
        val secret = "token=abc123XYZ456def"
        val prefix = "z".repeat(8000) // filler with no secret/path shape, under the 8192 clamp
        val tail = "y".repeat(50_000) // pushes total length far past the clamp
        val scrubbed = PayloadScrubber.scrubText("$prefix $secret $tail", root)
        assertFalse(scrubbed.contains("abc123XYZ456def"), "secret before the clamp boundary must still be redacted")
        assertTrue(scrubbed.contains("token=<redacted>"), scrubbed.take(8100))
    }

    @Test
    fun inputs_under_the_clamp_are_byte_identical_to_pre_clamp_behavior() {
        // Realistic, well-under-8192-char text must scrub exactly as before the clamp landed —
        // no truncation marker, same redaction/relativization as every other test in this file.
        val text = "Input property 'x' file $root/src/main/A.kt has changed, token=abc123 also seen. " + "z".repeat(200) + " tail."
        assertTrue(text.length < 8192)
        val scrubbed = PayloadScrubber.scrubText(text, root)
        assertFalse(scrubbed.contains("…<truncated>"), scrubbed)
        assertTrue(scrubbed.contains("src/main/A.kt"), scrubbed)
        assertTrue(scrubbed.contains("token=<redacted>"), scrubbed)
        assertTrue(scrubbed.endsWith("z".repeat(200) + " tail."), scrubbed)
    }

    // --- 076 review-fix: port the idempotency case out of buildhound-server's IngestScrubTest --
    //
    // The plan 076 Divergences section notes this case was implemented in `IngestScrubTest`
    // instead of here because the implementation session was barred from touching commons
    // (a concurrent agent held uncommitted edits there). Commons is free now — ported verbatim
    // (same fixture shape) per the plan's own follow-up.

    @Test
    fun a_client_scrubbed_payload_is_byte_identical_after_a_second_pass_with_empty_roots() {
        val payload = BuildPayload(
            buildId = "compliant-1",
            startedAt = 0,
            finishedAt = 1,
            outcome = BuildOutcome.FAILED,
            tasks = listOf(
                TaskExecution(
                    path = ":app:compile",
                    startMs = 0,
                    durationMs = 1,
                    outcome = TaskOutcome.EXECUTED,
                    executionReasons = listOf(
                        "Input property 'x' file $root/src/main/A.kt has changed.",
                        "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                    ),
                ),
            ),
            failure = FailureInfo(
                exceptionClass = "java.lang.IllegalStateException",
                messageHash = "deadbeef",
                message = "Execution failed: token=abc123XYZ456def in $root/src/Main.kt",
                stackTrace = "java.lang.IllegalStateException: boom at $root/src/Main.kt:10",
            ),
        )

        val clientScrubbed = PayloadScrubber.scrub(payload, root) // real client-side root
        val serverScrubbed = PayloadScrubber.scrub(clientScrubbed, emptyList()) // the server's ingest call

        assertEquals(clientScrubbed, serverScrubbed)
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
