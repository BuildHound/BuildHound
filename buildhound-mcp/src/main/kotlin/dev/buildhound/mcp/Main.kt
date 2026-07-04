package dev.buildhound.mcp

import kotlin.system.exitProcess

/**
 * Entry point (plan 042): wire the read query API into the stdio MCP server. Config is env-only
 * (architecture §6) — `BUILDHOUND_URL` (required) and a `read`-scoped `BUILDHOUND_TOKEN`. Diagnostics
 * go to stderr so they never corrupt the JSON-RPC stream on stdout.
 */
fun main() {
    val baseUrl = System.getenv("BUILDHOUND_URL")?.takeIf { it.isNotBlank() }
    if (baseUrl == null) {
        System.err.println("buildhound-mcp: BUILDHOUND_URL is required (e.g. https://buildhound.example.com)")
        exitProcess(1)
    }
    val token = System.getenv("BUILDHOUND_TOKEN") // read scope; only ever an Authorization header
    if (token.isNullOrBlank()) {
        System.err.println("buildhound-mcp: warning — no BUILDHOUND_TOKEN set; read queries will be rejected")
    }
    val client = QueryClient(baseUrl, token)
    System.err.println("buildhound-mcp: ready (${Tools.all.size} read-only tools) against $baseUrl")
    McpServer(Tools.all) { path -> client.get(path) }.serveStdio()
}
