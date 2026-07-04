package dev.buildhound.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(s: String): JsonObject = json.parseToJsonElement(s).jsonObject

    private fun server(fetch: (String) -> String): Pair<McpServer, MutableList<String>> {
        val calls = mutableListOf<String>()
        return McpServer(Tools.all) { path -> calls.add(path); fetch(path) } to calls
    }

    @Test
    fun `initialize advertises the protocol version and server name`() {
        val (srv, _) = server { "" }
        val res = parse(srv.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")!!)
        assertEquals("1", res["id"]!!.jsonPrimitive.content)
        val result = res["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("buildhound-mcp", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools slash list returns every read-only tool`() {
        val (srv, _) = server { "" }
        val res = parse(srv.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!)
        val names = res["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(Tools.all.map { it.name }.toSet(), names.toSet())
    }

    @Test
    fun `tools slash call issues the read GET and wraps the body as text content`() {
        val (srv, calls) = server { "[{\"day\":\"2026-07-01\"}]" }
        val res = parse(srv.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"trends","arguments":{"days":7}}}""")!!)
        assertTrue(calls.contains("/v1/trends?days=7"), calls.toString())
        val result = res["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("2026-07-01"), text)
    }

    @Test
    fun `a fetch failure is a tool error result, not a protocol crash`() {
        val (srv, _) = server { throw McpToolException("query failed (503)") }
        val res = parse(srv.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"trends","arguments":{}}}""")!!)
        val result = res["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean(), "a fetch failure must surface as isError")
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("503"), text)
    }

    @Test
    fun `an unknown tool is a protocol error and issues no fetch`() {
        val (srv, calls) = server { "" }
        val res = parse(srv.handle("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"delete_everything","arguments":{}}}""")!!)
        assertTrue(res.containsKey("error"))
        assertTrue(calls.isEmpty(), "an unknown tool must never dial out")
    }

    @Test
    fun `an unknown method is method-not-found, and a notification gets no reply`() {
        val (srv, _) = server { "" }
        assertTrue(parse(srv.handle("""{"jsonrpc":"2.0","id":6,"method":"no/such"}""")!!).containsKey("error"))
        assertNull(srv.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `malformed input is a parse error, not a thrown exception`() {
        val (srv, _) = server { "" }
        assertTrue(parse(srv.handle("not json")!!).containsKey("error"))
    }
}
