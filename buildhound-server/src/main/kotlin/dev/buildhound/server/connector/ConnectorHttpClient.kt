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
 * enforced once against the request URL (`AzureDevOpsConnector.isAllowedHost`), so a 3xx that Ktor
 * silently followed would carry the `Authorization: Basic <PAT>` header to an unvalidated host
 * (an allowlisted-but-compromised or open-redirect Azure host → credential exfiltration). Instead a
 * redirect surfaces as a non-2xx status the connector treats as a failed fetch.
 */
object ConnectorHttpClient {
    fun create(engine: HttpClientEngine = CIO.create()): HttpClient = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        install(HttpRequestRetry) {
            retryIf(maxRetries = 3) { _, response -> response.status.value == 429 || response.status.value in 500..599 }
            retryOnException(maxRetries = 3, retryOnTimeout = true)
            exponentialDelay()
        }
    }
}
