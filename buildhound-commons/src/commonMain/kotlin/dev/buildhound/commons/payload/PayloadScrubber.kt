package dev.buildhound.commons.payload

/**
 * Spec §3.7: payloads never carry absolute paths outside the project or secret-shaped
 * values. This scrubber covers the *free-text* fields (execution reasons today, failure
 * text when it lands) — structured fields like `vcs.sha` are declared data and are not
 * touched. KMP-pure so the server can run it as a defensive second pass.
 *
 * Bias: over-redaction of free text is acceptable; under-redaction is not.
 */
object PayloadScrubber {

    /** Scrubs all free-text fields; everything else passes through unchanged. */
    fun scrub(payload: BuildPayload, projectRoot: String?): BuildPayload {
        if (payload.tasks.isEmpty()) return payload
        return payload.copy(
            tasks = payload.tasks.map { task ->
                if (task.executionReasons.isEmpty()) task
                else task.copy(executionReasons = task.executionReasons.map { scrubText(it, projectRoot) })
            },
        )
    }

    /**
     * Secrets first, then paths — a secret value containing a path must not survive as
     * a "relativized" fragment.
     */
    fun scrubText(text: String, projectRoot: String?): String {
        var result = text
        result = bearerToken.replace(result, "<redacted>")
        result = secretPair.replace(result) { match -> "${match.groupValues[1]}=<redacted>" }
        result = longBlob.replace(result, "<redacted>")
        result = unixPath.replace(result) { match -> relativizeOrRedact(match.value, projectRoot) }
        result = windowsPath.replace(result) { match -> relativizeOrRedact(match.value, projectRoot) }
        return result
    }

    private fun relativizeOrRedact(path: String, projectRoot: String?): String {
        if (projectRoot != null && projectRoot.isNotEmpty()) {
            val root = projectRoot.trimEnd('/', '\\')
            if (path == root) return "."
            for (separator in charArrayOf('/', '\\')) {
                if (path.startsWith(root + separator)) {
                    return path.substring(root.length + 1)
                }
            }
        }
        return "<path>"
    }

    /** `token=...`, `apiKey: ...`, quoted or bare values; the key survives, the value never. */
    private val secretPair = Regex(
        """(?i)\b(token|secret|password|passwd|api[_-]?key|credential|authorization|bearer)\b\s*[=:]\s*["']?[^\s"']+["']?""",
    )

    /** `Bearer <token>` without a key=value shape. */
    private val bearerToken = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")

    /** Standalone long base64/hex-looking blobs — token-shaped even without a key. */
    private val longBlob = Regex("""(?<![\w/.-])[A-Za-z0-9+_-]{32,}={0,2}(?![\w/.-])""")

    /** Absolute unix path with at least two segments (avoids matching lone `/`). */
    private val unixPath = Regex("""(?<![\w:.\-/])/(?:[\w.@+-]+/)*[\w.@+-]+""")

    /** `C:\...` style. */
    private val windowsPath = Regex("""[A-Za-z]:\\(?:[\w.@+-]+\\)*[\w.@+-]+""")
}
