package dev.buildhound.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A minimal MCP server (plan 042) speaking JSON-RPC 2.0 over newline-delimited stdio. Deliberately
 * hand-rolled rather than pulling the 0.x MCP Kotlin SDK — the read-only surface is small. Every tool
 * call is a single read `GET` via the injected [fetch]; there is no write path. [handle] is pure
 * (request line → response line, or null for a notification) so the dispatch is unit-tested without a
 * real stdin/stdout.
 */
class McpServer(
    private val tools: List<McpTool>,
    private val fetch: (String) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Handles one JSON-RPC message; returns the response line, or null for a notification / no reply. */
    fun handle(requestLine: String): String? {
        val request = runCatching { json.parseToJsonElement(requestLine) as? JsonObject }.getOrNull()
            ?: return error(JsonNull, -32700, "parse error")
        val id: JsonElement? = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull

        return when (method) {
            "initialize" -> result(id, initializeResult())
            "ping" -> result(id, buildJsonObject {})
            "tools/list" -> result(id, toolsListResult())
            "tools/call" -> toolCall(id, request["params"] as? JsonObject)
            // A notification (initialized, cancelled, …) has no id and expects no reply.
            "notifications/initialized" -> null
            else -> if (id == null) null else error(id, -32601, "method not found: $method")
        }
    }

    /** Reads newline-delimited JSON-RPC from stdin and writes responses to stdout until EOF. */
    fun serveStdio() {
        System.`in`.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val response = runCatching { handle(line) }.getOrElse { error(JsonNull, -32603, "internal error") }
            if (response != null) {
                println(response)
                System.out.flush()
            }
        }
    }

    private fun toolCall(id: JsonElement?, params: JsonObject?): String {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return error(id, -32602, "missing tool name")
        val tool = tools.firstOrNull { it.name == name }
            ?: return error(id, -32602, "unknown tool: $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        // A tool/argument/fetch failure is an MCP tool error (isError: true), not a JSON-RPC protocol
        // error — the agent sees the message and can retry, and the server never crashes on one call.
        return try {
            val body = fetch(tool.buildPath(args))
            result(id, toolContent(body, isError = false))
        } catch (e: Exception) {
            result(id, toolContent("error: ${e.message}", isError = true))
        }
    }

    private fun toolContent(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        put("isError", isError)
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
        put("serverInfo", buildJsonObject { put("name", "buildhound-mcp"); put("version", "1.0") })
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                })
            }
        })
    }

    private fun result(id: JsonElement?, resultObj: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", resultObj)
    }.toString()

    private fun error(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString()

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
    }
}
