package dev.buildhound.commons.payload

/**
 * Spec §3.7: payloads never carry absolute paths outside the project or secret-shaped
 * values. This scrubber covers the *free-text* fields — execution reasons and
 * `nonCacheableReason` (the `@DisableCachingByDefault(because = …)` text, free text from
 * plugin authors, plan 016); failure text MUST route through [scrubText] when its
 * collector lands — structured fields like `vcs.sha` are declared data and are not touched.
 * KMP-pure so the server can run it as a defensive second pass; note the fixed-length
 * lookbehinds are fine on JVM/Native, but a future js() target needs Safari 16.4+.
 *
 * Bias: over-redaction of free text is acceptable; under-redaction is not.
 *
 * Known accepted limitations (plan 007): tails of out-of-project paths containing
 * spaces can leak fragments ("<path> Doe/other"); sub-32-char high-entropy tokens
 * without a recognizable key or AKIA/JWT shape survive; space-separated flag secrets
 * (`--token abc`) survive — revisit before failure-text collection. Because roots
 * relativize before the blob rule, a keyless sub-32-char token written immediately
 * under the project root (`<root>/shorttoken1`) survives as its relative name — it is
 * an in-project path, which the payload carries by design.
 */
object PayloadScrubber {

    /**
     * Scrubs all free-text fields; everything else passes through unchanged.
     * [projectRoots] may carry both the plain and the canonical root (symlinked
     * checkouts) — the first matching root wins.
     */
    fun scrub(payload: BuildPayload, projectRoots: List<String>): BuildPayload =
        payload.copy(
            tasks = payload.tasks.map { task ->
                val reasons =
                    if (task.executionReasons.isEmpty()) task.executionReasons
                    else task.executionReasons.map { scrubText(it, projectRoots) }
                val nonCacheableReason = task.nonCacheableReason?.let { scrubText(it, projectRoots) }
                if (reasons === task.executionReasons && nonCacheableReason == task.nonCacheableReason) task
                else task.copy(executionReasons = reasons, nonCacheableReason = nonCacheableReason)
            },
        )

    fun scrub(payload: BuildPayload, projectRoot: String?): BuildPayload =
        scrub(payload, listOfNotNull(projectRoot))

    fun scrubText(text: String, projectRoot: String?): String =
        scrubText(text, listOfNotNull(projectRoot))

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
        var result = text
        result = urlCredentials.replace(result, "://<redacted>@")
        result = jwt.replace(result, "<redacted>")
        result = bearerToken.replace(result, "<redacted>")
        result = secretPair.replace(result) { match -> "${match.groupValues[1]}=<redacted>" }
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
