package dev.buildhound.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolsTest {

    private fun tool(name: String) = Tools.all.single { it.name == name }
    private fun path(name: String, args: JsonObject = JsonObject(emptyMap())) = tool(name).buildPath(args)

    @Test
    fun `every tool is a read-only GET under v1 and never touches admin or a mutating verb`() {
        val names = Tools.all.map { it.name }.toSet()
        assertEquals(
            setOf("list_builds", "get_build", "trends", "project_cost", "task_duration", "negative_avoidance"),
            names,
            "the tool surface must stay exactly these six read-only queries",
        )
        val mutating = listOf("set", "put", "post", "delete", "create", "update", "write", "admin", "retention", "ingest")
        for (tool in Tools.all) {
            assertTrue(mutating.none { tool.name.contains(it, ignoreCase = true) }, "tool ${tool.name} names a mutation")
            // A tool that takes only defaults must produce a /v1 read path, never the admin namespace.
            val defaultPath = if (tool.name == "get_build") tool.buildPath(buildJsonObject { put("buildId", "x") }) else tool.buildPath(JsonObject(emptyMap()))
            assertTrue(defaultPath.startsWith("/v1/"), "tool ${tool.name} path must be under /v1")
            assertTrue(!defaultPath.startsWith("/v1/admin"), "tool ${tool.name} must never reach /v1/admin")
        }
    }

    @Test
    fun `list_builds builds a paginated path with optional filters`() {
        assertEquals("/v1/builds?limit=50&offset=0", path("list_builds"))
        val filtered = buildJsonObject { put("limit", 10); put("branch", "main"); put("outcome", "FAILED") }
        assertEquals("/v1/builds?limit=10&offset=0&branch=main&outcome=FAILED", path("list_builds", filtered))
    }

    @Test
    fun `get_build requires a build id and rejects a path-splitting id`() {
        assertEquals("/v1/builds/abc-123", path("get_build", buildJsonObject { put("buildId", "abc-123") }))
        assertFailsWith<McpToolException> { path("get_build") }
        assertFailsWith<McpToolException> { path("get_build", buildJsonObject { put("buildId", "a/b") }) }
    }

    @Test
    fun `days tools default to 30 and clamp out-of-range windows`() {
        assertEquals("/v1/trends?days=30", path("trends"))
        assertEquals("/v1/trends?days=7", path("trends", buildJsonObject { put("days", 7) }))
        assertEquals("/v1/trends?days=1", path("trends", buildJsonObject { put("days", 0) })) // clamped up
        assertEquals("/v1/rollups/project-cost?days=14", path("project_cost", buildJsonObject { put("days", 14) }))
        assertEquals("/v1/rollups/task-duration?days=30", path("task_duration"))
        assertEquals("/v1/rollups/negative-avoidance?days=30", path("negative_avoidance"))
    }
}
