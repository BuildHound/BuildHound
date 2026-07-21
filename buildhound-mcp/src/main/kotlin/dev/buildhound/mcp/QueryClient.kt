package dev.buildhound.mcp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The outbound half of the MCP server (plan 042): a single **read-only** `GET` against the BuildHound
 * query API. Same posture as the plugin's uploader — a bounded timeout, `Redirect.NEVER` (a 3xx must
 * never carry the token to an unvalidated host), and a `read`-scoped `Bearer` token that is only ever a
 * header, never logged. There is no POST/PUT/DELETE here, so a leaked token can only read this tenant.
 */
class QueryClient(baseUrl: String, private val token: String?) {

    private val base: String = baseUrl.trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** GETs [path] (a `/v1/...` path built by a tool) and returns the response body; throws on non-2xx. */
    fun get(path: String): String {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            // Never echo the token or the full URL query; the status is enough for the agent to react.
            throw McpToolException("query failed (${response.statusCode()}) for ${path.substringBefore('?')}")
        }
        return response.body()
    }
}

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
