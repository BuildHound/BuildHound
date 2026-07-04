package dev.buildhound.mcp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** QueryClient sends a read-scoped Bearer GET and never dials out with anything but GET (plan 042). */
class QueryClientTest {

    private var server: HttpServer? = null

    @AfterTest
    fun stop() { server?.stop(0) }

    private fun stub(status: Int, body: String, capture: (com.sun.net.httpserver.HttpExchange) -> Unit = {}): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/trends") { ex ->
            capture(ex)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun `get sends a bearer read token and returns the body`() {
        var authHeader: String? = null
        var method: String? = null
        val url = stub(200, "OK-BODY") { ex -> authHeader = ex.requestHeaders.getFirst("Authorization"); method = ex.requestMethod }
        val body = QueryClient(url, "read-tok").get("/v1/trends?days=7")
        assertEquals("OK-BODY", body)
        assertEquals("Bearer read-tok", authHeader)
        assertEquals("GET", method, "the query client must only ever GET")
    }

    @Test
    fun `a non-2xx status raises a tool exception without leaking the query`() {
        val url = stub(503, "unavailable")
        val e = assertFailsWith<McpToolException> { QueryClient(url, "read-tok").get("/v1/trends?days=7") }
        assertTrue(e.message!!.contains("503"), e.message!!)
        assertTrue(!e.message!!.contains("read-tok"), "the token must never appear in an error message")
    }
}
