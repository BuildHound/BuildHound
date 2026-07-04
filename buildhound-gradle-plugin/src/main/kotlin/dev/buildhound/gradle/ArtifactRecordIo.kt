package dev.buildhound.gradle

import dev.buildhound.commons.payload.ArtifactSize
import dev.buildhound.commons.payload.ArtifactType
import dev.buildhound.commons.payload.BuildHoundJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The one-JSON-object-per-line wire format the Android size tasks write and the Flow finalizer reads
 * (plan 031). Gradle-free so it is unit-testable off the Gradle/AGP classpath; parsing is defensive
 * (a malformed or partial line is skipped, never fatal) since the files are produced by tasks that
 * must not break the finalizer.
 */
internal object ArtifactRecordIo {

    fun encode(module: String?, variant: String, type: ArtifactType, sizeBytes: Long): String {
        val obj = buildJsonObject {
            if (module != null) put("module", module)
            put("variant", variant)
            put("type", type.name)
            put("sizeBytes", sizeBytes)
        }
        return BuildHoundJson.payload.encodeToString(JsonObject.serializer(), obj)
    }

    fun parse(line: String): ArtifactSize? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = BuildHoundJson.payload.parseToJsonElement(trimmed) as? JsonObject ?: return null
            val variant = obj.str("variant") ?: return null
            val type = obj.str("type")?.let { name -> ArtifactType.entries.firstOrNull { it.name == name } } ?: return null
            val sizeBytes = obj.str("sizeBytes")?.toLongOrNull() ?: return null
            ArtifactSize(variant = variant, module = obj.str("module"), type = type, sizeBytes = sizeBytes)
        }.getOrNull()
    }

    /** Parse every line of every file's text, dropping malformed lines. */
    fun parseAll(texts: Iterable<String>): List<ArtifactSize> =
        texts.flatMap { text -> text.lineSequence().mapNotNull { parse(it) } }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
