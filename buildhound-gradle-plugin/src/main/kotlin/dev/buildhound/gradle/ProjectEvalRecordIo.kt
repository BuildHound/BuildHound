package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.ProjectEvaluation
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Per-project configuration-time sidecar (plan 052): one JSON object per file, one file per project,
 * mirroring [ArtifactRecordIo] (wire encode/parse) + [TestLocationSidecar] (durable file channel).
 *
 * It exists because the two `gradle.lifecycle.beforeProject`/`afterProject` [IsolatedAction]s cannot
 * share mutable in-process state with each other or with the finalizer (research §F2 narrowing 2):
 * each runs isolated, so a plain `AtomicReference` mailbox (the plan-016 pattern for a plain
 * `taskGraph.whenReady` closure) is not an option here. A durable per-project file — written directly
 * at configuration time inside `afterProject`, never through a `Task` — is therefore the only channel.
 * One file per project means concurrent per-project writes under parallel/isolated-projects execution
 * never contend for the same file.
 *
 * The write is a side effect, never a configuration-cache *input* (the [TestLocationSidecar] contract):
 * it only ever `mkdirs`+`writeText`, and never reads back what it wrote at configuration time. Gradle-free
 * (`java.io.File` only, no `org.gradle.*` type) so it unit-tests without the Gradle API on the classpath.
 */
internal object ProjectEvalRecordIo {

    fun encode(path: String, evaluationMs: Long): String {
        val obj = buildJsonObject {
            put("path", path)
            put("evaluationMs", evaluationMs)
        }
        return BuildHoundJson.payload.encodeToString(JsonObject.serializer(), obj)
    }

    /** Defensive: a malformed or partial file/line is skipped, never fatal. */
    @Suppress("ReturnCount") // Defensive record parsing rejects each malformed field at its boundary.
    fun parse(text: String): ProjectEvaluation? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = BuildHoundJson.payload.parseToJsonElement(trimmed) as? JsonObject ?: return null
            val path = obj.str("path") ?: return null
            val evaluationMs = obj.str("evaluationMs")?.toLongOrNull() ?: return null
            ProjectEvaluation(path = path, evaluationMs = evaluationMs)
        }.getOrNull()
    }

    /**
     * Deterministic, collision-free file name for a canonical Gradle project [path] (052 review fix).
     *
     * Per-character prefix-free code over the `:`-trimmed path (the root project `:` maps to the
     * reserved stem `_root_`):
     *  - `[A-Za-z0-9.]` pass through unchanged;
     *  - `:` (the project-path separator) becomes `-`;
     *  - any other character — including `-` and `_` themselves — becomes `_<hex>_` (lowercase hex
     *    of the UTF-16 code unit).
     *
     * Why not the old plain `':' -> '-'`: it collided `:a:b` with a project literally named `:a-b`
     * (silent last-write-wins; under isolated projects two racing writers could even tear the shared
     * file and lose both entries). Why not doubling (`- -> --` before `: -> -`): hyphen *runs* stay
     * ambiguous — `:a-:b` and `:a:-b` would both encode to `a---b`. The code here is prefix-free
     * (only the separator codeword starts with `-`; an `_…_` escape is delimited by `_`, which is
     * never a hex digit and is never emitted bare; pass-through chars start no other codeword), so
     * concatenation is uniquely decodable and the encoding is injective: two distinct canonical
     * paths can never produce the same filename — no hash disambiguator needed. The output alphabet
     * is whitelisted to `[A-Za-z0-9._-]`, so the filesystem sink no longer depends on Gradle's own
     * project-name rules for safety. `_root_` is unreachable by the encoding (a bare `_` would have
     * to open a hex escape, and `root` is not hex), so the root file can never collide with a
     * subproject literally named `root` (the old encoding's second collision).
     *
     * Typical paths stay readable: `:app` -> `app.jsonl`, `:core:common` -> `core-common.jsonl`;
     * a hyphenated project name pays the escape: `:x-y` -> `x_2d_y.jsonl`.
     */
    fun fileNameFor(path: String): String {
        val relative = path.trim(':')
        if (relative.isEmpty()) return "_root_.jsonl"
        val encoded = buildString(relative.length) {
            for (ch in relative) {
                when {
                    ch == ':' -> append('-')
                    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' -> append(ch)
                    else -> append('_').append(ch.code.toString(16)).append('_')
                }
            }
        }
        return "$encoded.jsonl"
    }

    /**
     * Overwrite this project's file in [dir] with its current [evaluationMs]; best-effort — a
     * configuration-time write must never throw (architecture §2 rule 3, "never fail a build").
     */
    fun write(dir: File, path: String, evaluationMs: Long) {
        runCatching {
            dir.mkdirs()
            File(dir, fileNameFor(path)).writeText(encode(path, evaluationMs))
        }
    }

    /**
     * Read every per-project file in [dir] back, then delete each one (plan 052's read-then-clear
     * contract): a later invocation must never inherit this build's leftover per-project entries for
     * projects it did not itself (re)configure. The finalizer calls this unconditionally — even on a
     * DSL-disabled or CC-hit build (052 review fix) — because the `beforeProject`/`afterProject`
     * writer is gated only by the apply-time master switch and cannot be stopped by a DSL-only
     * disable. Listing/reading is intentionally left unguarded here — like [readArtifacts] — so a
     * genuinely corrupt/locked directory propagates to the caller's own guard (the finalizer's
     * dedicated drain `runCatching`); only per-file parsing is defensive.
     * Missing [dir] (nothing ever captured, or the master switch was off) returns an empty list.
     */
    fun readAndClear(dir: File): List<ProjectEvaluation> {
        val files = dir.listFiles { file -> file.name.endsWith(".jsonl") } ?: return emptyList()
        val records = files.mapNotNull { file -> parse(file.readText()) }
        for (file in files) runCatching { file.delete() }
        return records
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
