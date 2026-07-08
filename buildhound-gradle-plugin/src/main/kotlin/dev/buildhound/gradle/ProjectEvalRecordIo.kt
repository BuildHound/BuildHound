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

    /** Collision-free per project path; `:` separators become `-`, the root project maps to `root`. */
    fun fileNameFor(path: String): String {
        val sanitized = path.trim(':').ifEmpty { "root" }.replace(':', '-')
        return "$sanitized.jsonl"
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
     * contract): a narrower next invocation must never inherit a wider build's leftover per-project
     * entries for projects it did not itself (re)configure. Listing/reading is intentionally left
     * unguarded here — like [readArtifacts] — so a genuinely corrupt/locked directory propagates to the
     * caller's own guard (the finalizer's outer `runCatching`); only per-file parsing is defensive.
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
