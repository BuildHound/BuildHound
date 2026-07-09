package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.FailureInfo
import dev.buildhound.commons.payload.PayloadCapper
import dev.buildhound.commons.payload.PayloadCaps
import dev.buildhound.commons.payload.PayloadScrubber
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/**
 * Plan 076: a defensive [PayloadScrubber] pass at ingest, run *before* [PayloadCapper] (`Routes.kt`'s
 * `PayloadCapper.cap(PayloadScrubber.scrub(payload, emptyList()))`) so a non-compliant or buggy
 * client's unscrubbed absolute paths / secret-shaped strings never reach storage or any read path.
 *
 * **Divergence from the committed plan's Test strategy:** the plan specifies the idempotency case
 * (below) as a new test in `buildhound-commons`' `PayloadScrubberTest`. This implementation session
 * runs alongside a concurrent agent with uncommitted edits in `buildhound-commons`
 * (`buildhound-gradle-plugin`, `buildhound-ci-assets`) and is barred from touching that module to
 * avoid clobbering that work. `PayloadScrubber` is KMP-pure and already a compile dependency of
 * `buildhound-server` ([Warnings.kt] imports it), so the same idempotency property is verified here
 * instead, from the consumer side — no scrubber *behavior* changes, only where the proof lives. A
 * follow-up should port (or duplicate) this case into `PayloadScrubberTest` once commons is free.
 */
class IngestScrubTest {

    private val root = "/home/ci/agent/work/project"

    /**
     * Unit — composition (plan's "Unit (buildhound-server, e.g. IngestScrubTest)" bullet): the exact
     * `scrub → cap` composition `Routes.kt` runs at ingest, fed a deliberately unscrubbed payload
     * carrying an absolute path plus jwt/bearer/aws-key/secret-pair shaped strings in both
     * `executionReasons` and the build `failure` text. This is about the *composition*, not the
     * regexes (`PayloadScrubberTest` already proves the scrubber alone).
     */
    @Test
    fun `scrub-then-cap composition redacts absolute paths and every secret shape`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val payload = BuildPayload(
            buildId = "unscrubbed-1",
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
                        "Authorization: Bearer $jwt",
                        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                    ),
                ),
            ),
            failure = FailureInfo(
                exceptionClass = "java.lang.IllegalStateException",
                messageHash = "deadbeef",
                message = "Execution failed: token=abc123XYZ456def in $root/src/Main.kt, key AKIAIOSFODNN7EXAMPLE used",
                stackTrace = "java.lang.IllegalStateException: boom at $root/src/Main.kt:10",
            ),
        )

        // Deliberately NOT client-scrubbed: exactly the composition Routes.kt runs at ingest.
        val composed = PayloadCapper.cap(PayloadScrubber.scrub(payload, emptyList()))

        val reasons = composed.tasks.single().executionReasons
        assertTrue(reasons.none { it.contains(root) }, reasons.toString())
        assertTrue(reasons.none { it.contains(jwt) }, reasons.toString())
        assertTrue(reasons.none { it.contains("wJalrXUtnFEMI") }, reasons.toString())

        val failure = composed.failure!!
        val message = failure.message!!
        assertFalse(message.contains(root), message)
        assertFalse(message.contains("abc123XYZ456def"), message)
        assertFalse(message.contains("AKIAIOSFODNN7EXAMPLE"), message)
        assertFalse(failure.stackTrace!!.contains(root), failure.stackTrace)
        // Declared fields survive the whole composition untouched.
        assertEquals("java.lang.IllegalStateException", failure.exceptionClass)
        assertEquals("deadbeef", failure.messageHash)
    }

    /**
     * Unit — idempotency (relocated from the plan's `PayloadScrubberTest` bullet; see class KDoc).
     * The load-bearing case: scrub **with real project roots** first (simulating the plugin's
     * client-side pass — in-project paths already relativized, secrets already `<redacted>`), then
     * scrub that output again with `emptyList()` (the server's ingest call) — the second pass must be
     * byte-identical. This is not "scrub-empty-roots twice" (which passes trivially and proves
     * nothing about the real client→server flow).
     */
    @Test
    fun `a client-scrubbed payload is byte-identical after the server's empty-root pass`() {
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

    /**
     * Micro-benchmark note (plan 076, no perf harness): scrub a 20,000-task payload —
     * [PayloadCaps.DEFAULT]'s `maxTasks` ceiling, the realistic worst case since scrub runs on the
     * pre-cap payload (scrub-before-cap, see Routes.kt). Logs the wall-clock cost; a single loose
     * upper bound keeps this from ever being flaky. The logged number is the artifact — reviewed by
     * eye once and copied into the plan's Exit criteria section, not re-derived per run.
     */
    @Test
    fun `scrub cost on a 20,000-task payload logs under a loose bound`() {
        val taskCount = PayloadCaps.DEFAULT.maxTasks
        val tasks = (0 until taskCount).map { i ->
            TaskExecution(
                path = ":module$i:compileTask$i",
                startMs = 0,
                durationMs = 1,
                outcome = TaskOutcome.EXECUTED,
                executionReasons = listOf(
                    "Input property 'x' file $root/src/main/File$i.kt has changed.",
                    "token=secretValue$i in the reason text",
                    "Task ':module$i:compileTask$i' is not up-to-date because output file /var/cache/build$i/out.bin has changed.",
                ),
            )
        }
        val payload = BuildPayload(buildId = "bench-1", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.SUCCESS, tasks = tasks)

        val startedAt = System.currentTimeMillis()
        val scrubbed = PayloadScrubber.scrub(payload, emptyList())
        val elapsedMs = System.currentTimeMillis() - startedAt

        benchLogger.info("PayloadScrubber.scrub over a {}-task payload took {} ms", taskCount, elapsedMs)
        assertEquals(taskCount, scrubbed.tasks.size)
        // Loose on purpose (measured ~1.8-1.9s cold-JVM on the reference dev machine, logged into the
        // plan's Exit criteria) — this bound only needs to catch a real regression, not pin a number;
        // a tighter bound would flake on a slower CI runner or a cold JIT.
        assertTrue(elapsedMs < 5000, "scrub of a $taskCount-task payload took ${elapsedMs}ms, expected < 5000ms")
    }

    /**
     * Integration (ingest route, `testApplication` + [InMemoryBuildStore]): POST a payload with an
     * unscrubbed absolute path and a secret-shaped value in a reason string and in the build
     * failure's message; `GET /v1/builds/{buildId}` on the same build must return the scrubbed
     * string, never the raw one — the observable boundary the two §3.2 reviews (plans 060/061)
     * actually flagged.
     */
    @Test
    fun `ingest scrubs a non-compliant payload before storing, and it reads back scrubbed`() = testApplication {
        val stores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
        stores.tokens.ensureProjectWithToken("pilot", sha256Hex("test-token"))
        application { buildHoundModule(stores) }

        val unscrubbedJson = """
            {
              "schemaVersion": 1,
              "buildId": "scrub-me",
              "startedAt": 1,
              "finishedAt": 2,
              "outcome": "FAILED",
              "mode": "ci",
              "tasks": [
                {
                  "path": ":app:compile",
                  "startMs": 0,
                  "durationMs": 1,
                  "outcome": "EXECUTED",
                  "executionReasons": [
                    "Input property 'x' file $root/src/main/A.kt has changed.",
                    "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                  ]
                }
              ],
              "failure": {
                "exceptionClass": "java.lang.IllegalStateException",
                "message": "Execution failed: token=abc123XYZ456def in $root/src/Main.kt"
              }
            }
        """.trimIndent()

        val ingest = client.post("/v1/builds") {
            header("Authorization", "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody(unscrubbedJson)
        }
        assertEquals(HttpStatusCode.Accepted, ingest.status)

        val detail = client.get("/v1/builds/scrub-me") { header("Authorization", "Bearer test-token") }
        assertEquals(HttpStatusCode.OK, detail.status)
        val body = detail.bodyAsText()

        // The raw absolute path and secret values must never appear in the stored/read-back payload.
        assertFalse(body.contains(root), body)
        assertFalse(body.contains("wJalrXUtnFEMI"), body)
        assertFalse(body.contains("abc123XYZ456def"), body)

        val decoded = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), body)
        // The server scrubs with emptyList() roots (it has no notion of the client's project root),
        // so an absolute path — even one under what would be the client's own project root — is
        // redacted to the sentinel, not relativized: relativization is a client-side-root behavior.
        assertTrue(
            decoded.tasks.single().executionReasons.any { it.contains("<path>") },
            "absolute path redacted to the sentinel: ${decoded.tasks.single().executionReasons}",
        )
        assertEquals("java.lang.IllegalStateException", decoded.failure?.exceptionClass, "declared field untouched")
    }

    /**
     * DoS bound (whole-branch review, HIGH#2): a hostile high-*count* payload — thousands of pathological
     * ~8 KiB strings whose shape drives [PayloadScrubber]'s super-linear regexes (a word-char run for
     * `secretPair`; a `/` run for `longBlob`). Per-string cost is already clamped by 076's 8 KiB input
     * clamp, but string COUNT is unbounded before [PayloadCapper]'s count caps (which run *after* scrub),
     * so without [IngestScrub]'s wall-clock guard this pins one thread for minutes. Asserts the guard
     * bounds the whole scrub under a hard wall-clock ceiling — it never hangs, whatever the machine. (Not
     * asserted here: whether the guard *tripped* — that depends on the runner's regex speed, since a fast
     * machine may scrub every string before the 3 s budget is spent; the deterministic trip + fail-closed
     * behavior is proven in the `budgetMs = 0` test below, machine-independently.)
     */
    @Test
    fun `timed ingest scrub keeps a max pathological payload under the DoS wall-clock bound`() {
        val wordRun = "a".repeat(8192) // secretPair's word-char backtracking shape (no '='/':' to resolve)
        val slashRun = "/".repeat(8192) // longBlob's shape (no digit for its lookahead)
        val taskCount = 1000
        val tasks = (0 until taskCount).map { i ->
            TaskExecution(
                path = ":m$i:t$i", startMs = 0, durationMs = 1, outcome = TaskOutcome.EXECUTED,
                executionReasons = listOf(wordRun, slashRun),
            )
        }
        val payload = BuildPayload(buildId = "dos-1", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.SUCCESS, tasks = tasks)

        val startedAt = System.currentTimeMillis()
        val result = IngestScrub.scrub(payload, emptyList())
        val elapsedMs = System.currentTimeMillis() - startedAt

        benchLogger.info(
            "IngestScrub over {} pathological tasks ({} strings) took {} ms (budgetExceeded={})",
            taskCount, taskCount * 2, elapsedMs, result.budgetExceeded,
        )
        // The guard caps total scrub CPU at ≈ budget (3 s) + one 8 KiB-clamped string worst-case; 10 s
        // leaves ample CI margin. Without the guard this same payload runs for many minutes.
        assertTrue(elapsedMs < 10_000, "timed scrub took ${elapsedMs}ms, expected < 10000ms (DoS bound)")
        assertEquals(taskCount, result.payload.tasks.size, "the build envelope + every task survives")
    }

    /**
     * Deterministic trip + fail-closed (whole-branch review, HIGH#2): a `budgetMs = 0` guard trips on the
     * very first field regardless of machine speed, so every free-text field is wholesale-redacted to
     * [IngestScrub.SENTINEL] rather than scrubbed. Proves (a) the [IngestScrub.Result.budgetExceeded] flag,
     * (b) that a secret-shaped value in a redacted field does NOT survive (fail-closed — dropped whole, not
     * stored raw), and (c) redaction replaces the field wholesale (never a char-split).
     */
    @Test
    fun `a zero-budget ingest scrub trips immediately and wholesale-redacts every field fail-closed`() {
        val secret = "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        val payload = BuildPayload(
            buildId = "trip-1", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.SUCCESS,
            tasks = listOf(
                TaskExecution(
                    path = ":app:compile", startMs = 0, durationMs = 1, outcome = TaskOutcome.EXECUTED,
                    executionReasons = listOf("some benign reason", secret),
                ),
            ),
        )

        val result = IngestScrub.scrub(payload, emptyList(), budgetMs = 0)

        assertTrue(result.budgetExceeded, "a zero budget must trip on the first field")
        val reasons = result.payload.tasks.single().executionReasons
        assertTrue(reasons.all { it == IngestScrub.SENTINEL }, "every field wholesale-redacted to the sentinel: $reasons")
        assertFalse(reasons.any { it.contains("wJalrXUtnFEMI") }, "the secret must not survive a budget-exceeded redaction")
    }

    /**
     * The other side of the bound: a legitimate payload scrubs in well under [IngestScrub.BUDGET_MS], so the
     * timed guard must (a) never trip and (b) produce output byte-identical to the pure untimed scrubber —
     * the wall-clock guard is invisible on every honest build (whole-branch review, HIGH#2).
     */
    @Test
    fun `timed ingest scrub matches the untimed scrub on a legitimate payload and never trips`() {
        val payload = BuildPayload(
            buildId = "legit-1", startedAt = 0, finishedAt = 1, outcome = BuildOutcome.FAILED,
            tasks = (0 until 50).map { i ->
                TaskExecution(
                    path = ":m$i:compile", startMs = 0, durationMs = 1, outcome = TaskOutcome.EXECUTED,
                    executionReasons = listOf("Input property 'x' file $root/src/main/File$i.kt has changed."),
                )
            },
            failure = FailureInfo(
                exceptionClass = "java.lang.IllegalStateException",
                message = "Execution failed: token=abc123XYZ456def in $root/src/Main.kt",
                stackTrace = "java.lang.IllegalStateException: boom at $root/src/Main.kt:10",
            ),
        )

        val untimed = PayloadScrubber.scrub(payload, emptyList())
        val result = IngestScrub.scrub(payload, emptyList())

        assertFalse(result.budgetExceeded, "a legitimate payload must never trip the budget")
        assertEquals(untimed, result.payload, "timed scrub is byte-identical to the untimed scrub on honest input")
    }

    private val benchLogger = LoggerFactory.getLogger("dev.buildhound.server.IngestScrubBenchmark")
}
