package dev.buildhound.commons.payload

/**
 * Spec §3.7: payloads never carry absolute paths outside the project or secret-shaped
 * values. This scrubber covers the *free-text* fields — execution reasons and
 * `nonCacheableReason` (the `@DisableCachingByDefault(because = …)` text, free text from
 * plugin authors, plan 016), the Kotlin report's `nonIncrementalReasons`, `taskPath`, and
 * `compilerTimesMs` phase keys (all sourced from the untrusted KGP report file, plan 023),
 * test-case failure `message` text (from JUnit XML, plan 024), fingerprint key names
 * (plan 022), and the build-`failure` `message` + `stackTrace` (plan 044) — structured
 * fields like `vcs.sha`, class/method names, `failure.exceptionClass`/`messageHash`, and counts
 * are declared data and are not touched.
 * KMP-pure so the server can run it as a defensive second pass; note the fixed-length
 * lookbehinds are fine on JVM/Native, but a future js() target needs Safari 16.4+.
 *
 * Bias: over-redaction of free text is acceptable; under-redaction is not.
 *
 * Known accepted limitations: tails of out-of-project paths containing spaces can leak
 * fragments ("<path> Doe/other"); sub-32-char high-entropy tokens without a recognizable key
 * or AKIA/JWT shape survive. Both were ruled acceptable-and-documented residuals for failure
 * text + warnings by the plan-044 §3.2 review — a stacktrace's paths render space-free
 * (`at pkg.Class.method(File.kt:NN)`), and lowering the 32-char blob floor would redact
 * legitimate identifiers (git SHAs, build hashes, camelCase task names). Space-separated
 * **keyword** flag secrets (`--token abc`) ARE now redacted ([flagSecret], plan 044). Because
 * roots relativize before the blob rule, a keyless sub-32-char token written immediately
 * under the project root (`<root>/shorttoken1`) survives as its relative name — it is
 * an in-project path, which the payload carries by design.
 */
object PayloadScrubber {

    /**
     * A single free-text field's scrub operation, factored out so a caller can wrap it (plan 076
     * whole-branch review, HIGH). The pure default [Direct] runs the full [scrubText] regex pipeline;
     * the server injects a wall-clock-bounded variant at ingest so a hostile high-count payload cannot
     * pin a thread in the (super-linear) regexes. **This module stays clock-free and KMP-pure** — all
     * timing/non-determinism lives in the caller-supplied implementation (`Routes.kt`), never here.
     */
    fun interface FieldScrubber {
        /** Scrub one free-text field; the returned string replaces the field. */
        fun scrub(text: String, projectRoots: List<String>): String

        companion object {
            /** The pure, deterministic default: run the full regex pipeline on every field (== plain [scrub]). */
            val Direct: FieldScrubber = FieldScrubber { text, roots -> PayloadScrubber.scrubText(text, roots) }
        }
    }

    /**
     * Scrubs all free-text fields; everything else passes through unchanged.
     * [projectRoots] may carry both the plain and the canonical root (symlinked
     * checkouts) — the first matching root wins.
     */
    fun scrub(payload: BuildPayload, projectRoots: List<String>): BuildPayload =
        scrub(payload, projectRoots, FieldScrubber.Direct)

    /**
     * The field walk of [scrub], with every free-text field routed through [fieldScrubber] instead of
     * [scrubText] directly. With [FieldScrubber.Direct] (the plain [scrub] default) the output is
     * byte-for-byte identical to the two-arg overload; the server passes a time-bounded scrubber that,
     * once its CPU budget is spent, wholesale-redacts remaining fields to a sentinel (never char-splits
     * a value mid-secret) — the DoS backstop. The scrub-then-truncate ordering and the length caps stay
     * here so no caller has to duplicate them (whole-branch review).
     */
    fun scrub(payload: BuildPayload, projectRoots: List<String>, fieldScrubber: FieldScrubber): BuildPayload =
        payload.copy(
            tasks = payload.tasks.map { task ->
                val reasons =
                    if (task.executionReasons.isEmpty()) task.executionReasons
                    else task.executionReasons.map { fieldScrubber.scrub(it, projectRoots) }
                val nonCacheableReason = task.nonCacheableReason?.let { fieldScrubber.scrub(it, projectRoots) }
                if (reasons === task.executionReasons && nonCacheableReason == task.nonCacheableReason) task
                else task.copy(executionReasons = reasons, nonCacheableReason = nonCacheableReason)
            },
            // Fingerprint values are salted hashes; key names are defensively scrubbed
            // (deterministic, so cross-build key equality survives — plan 022).
            fingerprints = payload.fingerprints?.let { fp ->
                FingerprintInfo(
                    build = fp.build.mapKeys { fieldScrubber.scrub(it.key, projectRoots) },
                    tasks = fp.tasks.mapValues { (_, keys) -> keys.mapKeys { fieldScrubber.scrub(it.key, projectRoots) } },
                )
            },
            // Every Kotlin string that originates in the (untrusted) KGP report file is scrubbed:
            // the non-incremental reasons and the compiler-phase names are free text, and the
            // task path — unlike Gradle's own trusted `TaskExecution.path` — comes from that file,
            // so a hostile report can't smuggle a path/secret past §3.7 as a taskPath or phase key.
            kotlin = payload.kotlin?.let { k ->
                k.copy(
                    perTask = k.perTask.map { report ->
                        report.copy(
                            taskPath = fieldScrubber.scrub(report.taskPath, projectRoots),
                            nonIncrementalReasons = report.nonIncrementalReasons.map { fieldScrubber.scrub(it, projectRoots) },
                            compilerTimesMs = report.compilerTimesMs.mapKeys { fieldScrubber.scrub(it.key, projectRoots) },
                        )
                    },
                )
            },
            // Test failure/assertion text (plan 024) routinely embeds absolute paths and can carry
            // secret-shaped values — scrub the one free-text field on every retained case. Class and
            // method names are declared data (like task paths) and pass through.
            tests = payload.tests.map { task ->
                if (task.failedOrRetried.isEmpty() && task.allCases.isEmpty()) task
                else task.copy(
                    failedOrRetried = task.failedOrRetried.map { it.scrubMessage(projectRoots, fieldScrubber) },
                    allCases = task.allCases.map { it.scrubMessage(projectRoots, fieldScrubber) },
                )
            },
            // Benchmark labels (plan 030): scenario/isolationMode are allowlist-validated plugin-side,
            // but seedRef is free-text env — a mis-set BUILDHOUND_BENCHMARK_SEED_REF could carry a path
            // or secret-shaped value, so route all three through the scrubber (defense-in-depth; §3.7).
            benchmark = payload.benchmark?.let { b ->
                b.copy(
                    scenario = fieldScrubber.scrub(b.scenario, projectRoots),
                    isolationMode = b.isolationMode?.let { fieldScrubber.scrub(it, projectRoots) },
                    seedRef = b.seedRef?.let { fieldScrubber.scrub(it, projectRoots) },
                )
            },
            // Build-failure message + stacktrace: routinely embed absolute paths (every stack frame
            // carries one) and can carry secret-shaped values from the failing command — the highest
            // PII surface in the payload, so scrub every character. `exceptionClass` is a declared
            // type name and `messageHash` is a digest of the raw text — neither is touched.
            failure = payload.failure?.scrubFailure(projectRoots, fieldScrubber),
        )

    /**
     * Scrubs the failure `message` and `stackTrace`, then truncates each — scrub-then-truncate so a
     * secret straddling the char cap is redacted whole before slicing (the plan-019 rule, as for test
     * messages). `messageHash` is computed upstream over the raw text, so the correlation key is
     * unaffected. The stacktrace cap is generous (~8 KiB) so the trace stays diagnostic; the local
     * HTML artifact may render a fuller (still-scrubbed) copy — see the finalizer.
     */
    private fun FailureInfo.scrubFailure(projectRoots: List<String>, fieldScrubber: FieldScrubber): FailureInfo {
        if (message == null && stackTrace == null) return this
        val scrubbedMessage = message?.let { fieldScrubber.scrub(it, projectRoots).take(MAX_FAILURE_MESSAGE_CHARS) }
        val scrubbedStack = stackTrace?.let { fieldScrubber.scrub(it, projectRoots).take(MAX_FAILURE_STACKTRACE_CHARS) }
        return copy(message = scrubbedMessage, stackTrace = scrubbedStack)
    }

    private const val MAX_FAILURE_MESSAGE_CHARS = 512
    private const val MAX_FAILURE_STACKTRACE_CHARS = 8192

    /**
     * Scrubs the failure message, then truncates to [MAX_TEST_MESSAGE_CHARS] — in that order so a
     * secret straddling the char cap is redacted whole before slicing (the plan-019 scrub-then-cap
     * rule). `messageHash` is computed upstream over the raw text, so the flaky key is unaffected.
     */
    private fun TestCaseDetail.scrubMessage(projectRoots: List<String>, fieldScrubber: FieldScrubber): TestCaseDetail {
        if (message == null) return this
        val scrubbed = fieldScrubber.scrub(message, projectRoots)
        return copy(message = if (scrubbed.length > MAX_TEST_MESSAGE_CHARS) scrubbed.substring(0, MAX_TEST_MESSAGE_CHARS) else scrubbed)
    }

    private const val MAX_TEST_MESSAGE_CHARS = 512

    fun scrub(payload: BuildPayload, projectRoot: String?): BuildPayload =
        scrub(payload, listOfNotNull(projectRoot))

    fun scrubText(text: String, projectRoot: String?): String =
        scrubText(text, listOfNotNull(projectRoot))

    /**
     * Hard length clamp applied before any regex in [scrubText] runs — a ReDoS guard (plan 076
     * review fix, HIGH). [longBlob] and [secretPair] both exhibit super-linear (empirically
     * quadratic) backtracking cost on a long run of matching-shape characters that never
     * resolves the pattern (no digit for [longBlob]'s lookahead; no `=`/`:` for [secretPair]'s
     * alternation) — e.g. a multi-megabyte run of `/`, `+`, or plain word characters. Since plan
     * 076 wired [scrub]/[scrubText] into the server's ingest path (`Routes.kt`), this now runs
     * *before* [PayloadCapper] on an unbounded, attacker-controlled, gzip-amplified field (a
     * small compressed POST can inflate to tens of MB in a single string) — unbounded regex cost
     * here is a CPU-exhaustion DoS pinning a shared Netty worker, not just a latency concern.
     *
     * **The review's proposed clamp value (64 KiB / 65536 chars) was measured and rejected.**
     * On the reference dev machine (JVM 21, `java.util.regex`), a pure `/` run of 65536 chars
     * alone costs [longBlob] ~23s, and a pure word-character run of the same size costs
     * [secretPair] far longer still (secretPair's worst case is *worse* than longBlob's, not
     * milder — measured ~1.9s already at 8192 chars, ~7.7s at 16384) — nowhere near a "bounded
     * time" fix. 8192 chars (8 KiB) is the value that satisfies both constraints at once: it
     * sits at or above every downstream free-text cap in this file ([MAX_FAILURE_STACKTRACE_CHARS]
     * = 8192 is the largest; [MAX_FAILURE_MESSAGE_CHARS]/[MAX_TEST_MESSAGE_CHARS] = 512), so no
     * legitimate value is ever truncated earlier than its own downstream cap would already cut
     * it — and at 8192 chars the measured worst case is [longBlob] ~340ms / [secretPair] ~1.9s,
     * comfortably bounded and CI-safe. 8 KiB is still far above any real secret shape (a JWT
     * runs a few KB at most, an AWS key is 20 chars, the blob floor is 32 chars), so whole-value
     * secret matching is preserved for every legitimate case; only pathological/hostile input is
     * ever truncated.
     *
     * This is a mitigation, not a fix for the underlying shape: both regexes stay O(n²), just
     * bounded to a fixed, small ceiling regardless of the caller's input size (plan 076 Risks).
     */
    private const val MAX_SCRUB_INPUT_CHARS = 8192

    /** Appended when [clampForScrub] truncates — a visible signal that the field was cut short. */
    private const val SCRUB_TRUNCATION_MARKER = "…<truncated>"

    private fun clampForScrub(text: String): String =
        if (text.length <= MAX_SCRUB_INPUT_CHARS) text else text.substring(0, MAX_SCRUB_INPUT_CHARS) + SCRUB_TRUNCATION_MARKER

    /**
     * Keyed/shaped secrets first, then paths — a secret value containing a path must
     * not survive as a "relativized" fragment. The literal-root strip runs BEFORE the
     * blob rule: the roots are not secrets (they are the location the payload
     * deliberately describes), and [longBlob]'s char class spans `/`, so a long
     * dot-free, digit-bearing root (macOS `/private/var/folders/...` temp dirs) would
     * otherwise be eaten as a blob before it can relativize — in-project reasons came
     * out as `<redacted><path>` instead of `input.txt`. The root strip also lets
     * in-project paths with spaces relativize (the path regexes cannot span spaces).
     */
    fun scrubText(text: String, projectRoots: List<String>): String {
        var result = clampForScrub(text)
        result = urlCredentials.replace(result, "://<redacted>@")
        result = jwt.replace(result, "<redacted>")
        result = bearerToken.replace(result, "<redacted>")
        result = secretPair.replace(result) { match -> "${match.groupValues[1]}=<redacted>" }
        result = flagSecret.replace(result) { match -> "${match.groupValues[1]} <redacted>" }
        result = awsAccessKey.replace(result, "<redacted>")
        // Longest root first, and only at a path boundary — a root must not be eaten
        // out of the middle of a longer path (/tmp/proj inside /private/tmp/proj).
        for (root in usableRoots(projectRoots).sortedByDescending { it.length }) {
            result = Regex("""(?<![\w.@+-])${Regex.escape(root)}[/\\]""").replace(result, "")
        }
        result = longBlob.replace(result, "<redacted>")
        result = unixPath.replace(result) { match -> relativizeOrRedact(match.value, projectRoots) }
        result = uncPath.replace(result, "<path>")
        result = windowsPath.replace(result) { match -> relativizeOrRedact(match.value, projectRoots) }
        return result
    }

    private fun relativizeOrRedact(path: String, projectRoots: List<String>): String {
        for (root in usableRoots(projectRoots)) {
            if (path == root) return "."
            for (separator in charArrayOf('/', '\\')) {
                if (path.startsWith(root + separator)) {
                    return path.substring(root.length + 1)
                }
            }
        }
        return "<path>"
    }

    /** A root of `/` or a bare drive letter would "relativize" the whole filesystem. */
    private fun usableRoots(projectRoots: List<String>): List<String> =
        projectRoots
            .map { it.trimEnd('/', '\\') }
            .filter { it.isNotEmpty() && !it.matches(Regex("[A-Za-z]:")) }

    /** `https://user:pass@host/...` — exactly the shape git failure text carries. */
    private val urlCredentials = Regex("""://[^/\s:@]+:[^/\s@]+@""")

    /** Dot-joined JWT segments defeat blob matching, so they get their own shape. */
    private val jwt = Regex("""(?<![\w-])ey[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    /**
     * `GITHUB_TOKEN=...`, `apiKey: '...'` — keyword anywhere in the key (snake_case is
     * the common CI shape), quote-aware value so multi-word quoted secrets vanish whole.
     * `PAT` only as its own word/suffix: `classpath=` must not match.
     */
    private val secretPair = Regex(
        """(?i)([\w-]*(?:token|secret|password|passwd|pwd|api[_-]?key|credential|authorization|bearer)[\w-]*|[\w-]+_pat|(?<![\w-])pat)\s*[=:]\s*(?:["'][^"']*["']|[^\s"']+)""",
    )

    /**
     * Space-separated CLI flag secrets (`--token abc`, `--password x`) — the `=`/`:` form is
     * [secretPair]'s; this catches the whitespace form a failed CLI invocation echoed into an exception
     * message routinely carries (plan 044 review). Keyword flags only (no bare `-p`) to keep false
     * positives near-zero; the flag name is kept, the value redacted. `\b` after the keyword means a
     * value-less flag like `--password-file path` (no following space+value) is left alone.
     */
    private val flagSecret =
        Regex("""(?i)(--?(?:token|password|passwd|pwd|api[-_]?key|secret|credential|access[-_]?key)\b)\s+(\S+)""")

    /** `Bearer <token>` without a key=value shape. */
    private val bearerToken = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")

    /** AWS access key ids are 20 chars — below the blob floor, so matched explicitly. */
    private val awsAccessKey = Regex("""(?<![\w-])(?:AKIA|ASIA)[0-9A-Z]{16}(?![\w-])""")

    /**
     * Standalone long base64-ish blobs. Requires a digit: long camelCase identifiers
     * (`compileProductionDebugAndroidTestKotlin`) are routine in reason text and must
     * survive; real key material virtually always carries digits.
     */
    private val longBlob = Regex("""(?<![\w.-])(?=[A-Za-z+/_-]*\d)[A-Za-z0-9+/_-]{32,}={0,2}(?![\w.-])""")

    /** Absolute unix path (segments cannot span spaces — see class KDoc). */
    private val unixPath = Regex("""(?<![\w:.\-/])/(?:[\w.@+-]+/)*[\w.@+-]+""")

    /** `\\server\share\...` UNC shape (always out-of-project → straight to `<path>`). */
    private val uncPath = Regex("""\\\\[\w.$-]+(?:\\[\w.@+-]+)+""")

    /** `C:\...` style. */
    private val windowsPath = Regex("""[A-Za-z]:\\(?:[\w.@+-]+\\)*[\w.@+-]+""")
}
