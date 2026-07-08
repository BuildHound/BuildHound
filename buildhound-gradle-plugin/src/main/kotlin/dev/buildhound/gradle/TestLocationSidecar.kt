package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Durable delivery channel for the Test-task JUnit XML locations (plan 044), a one-JSON-object-per-line
 * file mirroring [ArtifactRecordIo]. It exists because the plan-016/024 "mailbox" (config-time holder →
 * [TaskEventCollector] service param → finalizer) breaks in a **composite** build: included-build task
 * events instantiate the collector — freezing its parameters empty — before the root build's
 * `taskGraph.whenReady` fills the holder. The finalizer therefore **prefers** this file, falling back
 * to the (still-wired) service param only when it is empty/absent — the classpath path, where the
 * mailbox works.
 *
 * The file lives under **`.gradle/buildhound/`** (alongside `identity.salt`), not `build/`, so it
 * survives `clean` and its lifecycle tracks the configuration-cache entry: a CC hit skips
 * `whenReady` but the file persists from the storing build, and the CC key pins `requestedTasks` +
 * the task graph, so the persisted dirs still match the current run. Writing happens at configuration
 * time (a side effect, never a CC *input* — contrast the salt, which is read at execution precisely to
 * avoid being an input); reading happens in the Flow finalizer at execution time. All IO is defensive:
 * any failure degrades to an empty map, honoring the never-fail rule.
 */
internal object TestLocationSidecar {

    private const val SIDECAR_PATH = ".gradle/buildhound/test-locations.jsonl"

    /** Overwrite the sidecar with [locations]; best-effort, never throws. */
    fun write(rootDir: File, locations: Map<String, TestResultLocations>) {
        runCatching {
            val file = File(rootDir, SIDECAR_PATH)
            file.parentFile?.mkdirs()
            file.writeText(encode(locations))
        }
    }

    /** Read the sidecar back; missing/unreadable/malformed → empty map, never throws. */
    fun read(rootDir: File): Map<String, TestResultLocations> = runCatching {
        val file = File(rootDir, SIDECAR_PATH)
        if (!file.isFile) emptyMap() else decode(file.readText())
    }.getOrElse { emptyMap() }

    // --- pure String <-> map, unit-testable off the Gradle classpath ---

    fun encode(locations: Map<String, TestResultLocations>): String =
        locations.entries.joinToString("\n") { (path, loc) -> encodeLine(path, loc) }

    fun decode(text: String): Map<String, TestResultLocations> =
        text.lineSequence().mapNotNull { parseLine(it) }.toMap()

    private fun encodeLine(taskPath: String, loc: TestResultLocations): String {
        val obj = buildJsonObject {
            put("taskPath", taskPath)
            put("junitXmlDir", loc.junitXmlDir)
            if (loc.module != null) put("module", loc.module)
            // Omitted when true (the common case): parseLine below defaults an absent field to
            // true, so a pre-053 sidecar line (written before this field existed) decodes the same
            // way as one that explicitly carries `true` — no migration needed either direction.
            if (!loc.junitXmlRequired) put("junitXmlRequired", false)
        }
        return BuildHoundJson.payload.encodeToString(JsonObject.serializer(), obj)
    }

    private fun parseLine(line: String): Pair<String, TestResultLocations>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = BuildHoundJson.payload.parseToJsonElement(trimmed) as? JsonObject ?: return null
            val taskPath = obj.str("taskPath") ?: return null
            val dir = obj.str("junitXmlDir") ?: return null
            taskPath to TestResultLocations(
                junitXmlDir = dir,
                module = obj.str("module"),
                junitXmlRequired = obj.bool("junitXmlRequired") ?: true,
            )
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
