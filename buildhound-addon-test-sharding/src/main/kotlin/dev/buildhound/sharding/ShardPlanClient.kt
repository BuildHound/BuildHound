package dev.buildhound.sharding

import dev.buildhound.commons.payload.BuildHoundJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches the shard plan (plan 040) from `POST /v1/addons/test-sharding/plan`. Same JDK-`HttpClient`
 * posture as the core `PayloadUploader`: a 10 s timeout, `Redirect.NEVER` (a 3xx must never carry the
 * `Authorization` token to an unvalidated host), `Bearer` auth. **Never throws** — any failure returns
 * null so the caller runs all tests; the token is only ever a header, never logged.
 */
class ShardPlanClient(baseUrl: String, private val token: String?) {

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/v1/addons/test-sharding/plan")
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun fetch(request: ShardPlanRequest): ShardPlanResponse? = runCatching {
        val json = BuildHoundJson.payload.encodeToString(ShardPlanRequest.serializer(), request)
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        BuildHoundJson.payload.decodeFromString(ShardPlanResponse.serializer(), response.body())
    }.getOrNull()
}
