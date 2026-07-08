package dev.buildhound.mcp

import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A single **read-only** MCP tool (plan 042): its JSON-Schema + how its arguments become a GET path. */
class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val buildPath: (JsonObject) -> String,
)

/** Thrown when a tool's arguments are invalid; surfaced to the agent as an MCP tool error, never a crash. */
class McpToolException(message: String) : RuntimeException(message)

/**
 * The read-only tool surface. Every tool maps to a single `GET /v1/…` query — there is deliberately no
 * write or admin tool (a leaked read token must not be able to ingest, change retention, or reach
 * another tenant). A test asserts this set never grows a mutating verb.
 */
object Tools {

    private const val MAX_DAYS = 3650
    private const val MAX_LIMIT = 500

    val all: List<McpTool> = listOf(
        McpTool(
            name = "list_builds",
            description = "List recent builds (newest first), optionally filtered by branch/mode/outcome.",
            inputSchema = objectSchema {
                put("limit", intProp("Max builds to return (default 50)."))
                put("offset", intProp("Offset for pagination (default 0)."))
                put("branch", stringProp("Filter to a VCS branch."))
                put("mode", stringProp("Filter to a build mode (CI, LOCAL, BENCHMARK)."))
                put("outcome", stringProp("Filter to an outcome (SUCCESS, FAILED, INTERRUPTED)."))
            },
        ) { args ->
            val q = buildList {
                add("limit=${intArg(args, "limit", 50).coerceIn(1, MAX_LIMIT)}")
                add("offset=${intArg(args, "offset", 0).coerceAtLeast(0)}")
                strArg(args, "branch")?.let { add("branch=${enc(it)}") }
                strArg(args, "mode")?.let { add("mode=${enc(it)}") }
                strArg(args, "outcome")?.let { add("outcome=${enc(it)}") }
            }
            "/v1/builds?" + q.joinToString("&")
        },
        McpTool(
            name = "get_build",
            description = "Fetch one build's full payload by its build id.",
            inputSchema = objectSchema(required = listOf("buildId")) {
                put("buildId", stringProp("The build id (a UUID)."))
            },
        ) { args ->
            val buildId = strArg(args, "buildId") ?: throw McpToolException("buildId is required")
            // The id is a single path segment; a '/' would repoint the request, so it's rejected then encoded.
            if (buildId.contains('/')) throw McpToolException("buildId must not contain '/'")
            "/v1/builds/${enc(buildId)}"
        },
        McpTool(
            name = "diagnose",
            description = "Synthesize one build's already-collected signals into a single agent-consumable " +
                "diagnosis: dominant phase (config vs execution), cache-hit-rate vs target, top hotspots, " +
                "and deltas vs the comparable baseline. The privacy-preserving alternative to a `--scan` upload.",
            inputSchema = objectSchema(required = listOf("buildId")) {
                put("buildId", stringProp("The build id (a UUID)."))
            },
        ) { args ->
            val buildId = strArg(args, "buildId") ?: throw McpToolException("buildId is required")
            // Same guard as get_build: a '/' would repoint the request, so it's rejected then encoded.
            if (buildId.contains('/')) throw McpToolException("buildId must not contain '/'")
            "/v1/builds/${enc(buildId)}/diagnosis"
        },
        daysTool("trends", "Fleet build duration/cache trend points over the last N days.", "/v1/trends"),
        daysTool("project_cost", "Per-module build-cost rollup over the last N days.", "/v1/rollups/project-cost"),
        daysTool("task_duration", "Task-duration rollup (by name/type) over the last N days.", "/v1/rollups/task-duration"),
        daysTool("negative_avoidance", "Tasks that cost more to check than to run, over the last N days.", "/v1/rollups/negative-avoidance"),
    )

    /** A rollup/trend tool keyed only on a `days` window. */
    private fun daysTool(name: String, description: String, path: String) = McpTool(
        name = name,
        description = description,
        inputSchema = objectSchema { put("days", intProp("Window in days (default 30).")) },
    ) { args -> "$path?days=${intArg(args, "days", 30).coerceIn(1, MAX_DAYS)}" }

    private fun intArg(args: JsonObject, key: String, default: Int): Int =
        (args[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: default

    private fun strArg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    // --- tiny JSON-Schema builders (an object schema with typed string/integer properties) ---

    private fun objectSchema(
        required: List<String> = emptyList(),
        props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject(props))
        if (required.isNotEmpty()) {
            put("required", kotlinx.serialization.json.buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun stringProp(description: String): JsonObject = buildJsonObject { put("type", "string"); put("description", description) }
    private fun intProp(description: String): JsonObject = buildJsonObject { put("type", "integer"); put("description", description) }
}
