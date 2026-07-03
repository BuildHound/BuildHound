package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory

/** What an alert carries — pseudonymized verdict data only (no task detail, identity, or secrets). */
data class AlertContext(
    val projectKey: String,
    val buildId: String,
    val baselineKey: String,
    val verdict: Verdict,
    val dashboardBaseUrl: String?,
)

interface AlertDispatcher {
    fun dispatch(channels: List<AlertChannel>, context: AlertContext)
}

/** Records dispatches instead of sending — the default in DB-less/in-memory mode and in tests. */
class RecordingAlertDispatcher : AlertDispatcher {
    val sent: MutableList<Pair<AlertChannel, AlertContext>> = java.util.Collections.synchronizedList(mutableListOf())

    override fun dispatch(channels: List<AlertChannel>, context: AlertContext) {
        channels.forEach { sent.add(it to context) }
    }
}

/** Transport seam: returns the HTTP status; throws on a transport error. Injectable for tests. */
fun interface HttpSend {
    fun post(url: String, body: String): Int
}

/**
 * Outbound alert delivery (plan 025) — the server's first outbound call. Hard rules (architecture
 * §5): URLs come only from stored settings (never the payload → no SSRF steering by ingested data);
 * `https://` only, except loopback when [allowLoopbackHttp] (tests); dispatch is fire-and-forget on
 * a bounded executor with a short per-request timeout, so an unreachable webhook logs `warn` and can
 * never delay ingest. Bodies carry only pseudonymized verdict data.
 */
class HttpAlertDispatcher(
    private val send: HttpSend = defaultSend(),
    private val allowLoopbackHttp: Boolean = false,
    private val executor: Executor = boundedExecutor(),
    // Injectable so tests can exercise the SSRF host filter without real DNS.
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : AlertDispatcher {

    override fun dispatch(channels: List<AlertChannel>, context: AlertContext) {
        for (channel in channels) {
            if (!isAllowedUrl(channel.url)) {
                // Never log the URL itself — a Slack/webhook URL is a bearer capability. Kind + scheme only.
                logger.warn("refusing alert URL for a '{}' channel (must be https to a public host)", channel.kind)
                continue
            }
            val body = bodyFor(channel, context)
            executor.execute {
                runCatching { send.post(channel.url, body) }
                    .onFailure { logger.warn("alert dispatch to a '{}' channel failed: {}", channel.kind, it::class.java.simpleName) }
            }
        }
    }

    /**
     * Fail-closed SSRF guard (plan 025 security review): https-only (http only to loopback in test
     * mode), no userinfo, and — crucially — the host must not resolve to a loopback/link-local/
     * private/metadata address (169.254.169.254, RFC1918, ULA, ::1, …). Rejects an unresolvable host
     * and rejects if ANY resolved address is internal (a partial DNS-rebinding mitigation).
     */
    private fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.userInfo != null) return false // webhooks never carry userinfo; blocks user@host obfuscation
        val host = uri.host ?: return false
        return when (uri.scheme?.lowercase()) {
            // Test mode trusts local targets and skips the DNS/host checks entirely.
            "https" -> allowLoopbackHttp || !resolvesToInternalHost(host)
            "http" -> allowLoopbackHttp && (host == "localhost" || host == "127.0.0.1" || host == "::1")
            else -> false
        }
    }

    private fun resolvesToInternalHost(host: String): Boolean {
        val addresses = runCatching { resolver(host) }.getOrNull()
        if (addresses.isNullOrEmpty()) return true // unresolvable → refuse (fail closed)
        return addresses.any(::isInternalAddress)
    }

    private fun isInternalAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }
        // IPv6 unique-local fc00::/7 — Java's isSiteLocalAddress misses ULA.
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    /** Slack: `{text}`. Teams: a MessageCard (its incoming webhook rejects a bare `{text}`). */
    private fun bodyFor(channel: AlertChannel, context: AlertContext): String = when (channel.kind.lowercase()) {
        "slack" -> textBody(mapOf("text" to summary(context)))
        "teams" -> textBody(
            mapOf("@type" to "MessageCard", "@context" to "http://schema.org/extensions", "text" to summary(context)),
        )
        else -> BuildHoundJson.payload.encodeToString(WebhookBody.serializer(), WebhookBody(context))
    }

    private fun textBody(fields: Map<String, String>): String =
        BuildHoundJson.payload.encodeToString(MapSerializer(String.serializer(), String.serializer()), fields)

    private fun summary(c: AlertContext): String {
        val regressed = c.verdict.metrics.filter { it.status == VerdictStatus.FAIL.name || it.status == VerdictStatus.WARN.name }
            .joinToString(", ") { it.name }
        val link = c.dashboardBaseUrl?.let { " ${it.trimEnd('/')}/#/build/${c.buildId}" } ?: ""
        return "BuildHound ${c.verdict.status}: ${c.projectKey} [${c.baselineKey}] build ${c.buildId}" +
            (if (regressed.isNotEmpty()) " — regressed: $regressed" else "") + link
    }

    @Serializable
    data class WebhookBody(
        val kind: String = "buildhound.verdict",
        val projectKey: String,
        val buildId: String,
        val baselineKey: String,
        val status: String,
        val metrics: List<MetricVerdict>,
        val dashboardUrl: String? = null,
    ) {
        constructor(c: AlertContext) : this(
            projectKey = c.projectKey, buildId = c.buildId, baselineKey = c.baselineKey,
            status = c.verdict.status, metrics = c.verdict.metrics,
            dashboardUrl = c.dashboardBaseUrl?.let { "${it.trimEnd('/')}/#/build/${c.buildId}" },
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger("dev.buildhound.server.Alert")

        /** Small bounded pool with a bounded queue: alerts are rare, and a FAIL storm must not grow
         *  memory without bound — excess dispatches are discarded (DiscardPolicy), never queued forever. */
        fun boundedExecutor(): Executor =
            ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(64),
                { r -> Thread(r, "buildhound-alert").apply { isDaemon = true } },
                ThreadPoolExecutor.DiscardPolicy(),
            )

        fun defaultSend(): HttpSend {
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
            return HttpSend { url, body ->
                val request = java.net.http.HttpRequest.newBuilder(URI(url))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build()
                client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
            }
        }
    }
}
