package dev.buildhound.server.connector

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout

/**
 * Shared, timeout-bounded outbound client for connectors (plan 028). Connect/request/socket
 * deadlines plus a small exponential backoff on 429/5xx and transport errors, so a flaky or
 * rate-limiting provider can't stall or hammer the server. The engine is injectable so tests
 * supply a `MockEngine` instead of dialling the network.
 *
 * `followRedirects = false` is a security control, not a nicety: the SSRF host allowlist is
 * enforced once against the request URL (`ConnectorNet.isAllowedHost`, shared by every connector), so
 * a 3xx that Ktor silently followed would carry the credential header (`Authorization: Basic`/`Bearer`
 * or `PRIVATE-TOKEN`, per provider) to an unvalidated host (an allowlisted-but-compromised or
 * open-redirect host → credential exfiltration). Instead a redirect surfaces as a non-2xx status the
 * connector treats as a failed fetch.
 */
object ConnectorHttpClient {
    fun create(engine: HttpClientEngine = CIO.create()): HttpClient = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        install(HttpRequestRetry) {
            retryIf(maxRetries = DEFAULT_MAX_RETRIES) { _, response ->
                response.status.value == HTTP_RATE_LIMITED ||
                    response.status.value in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX
            }
            retryOnException(maxRetries = DEFAULT_MAX_RETRIES, retryOnTimeout = true)
            exponentialDelay()
        }
    }
}
