package dev.buildhound.server.connector

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * Azure DevOps connector (plan 028): pulls the Build + Timeline REST APIs and normalizes them to a
 * [CiRun]. Two GETs against `{baseUrl}/{project}/_apis/build/builds/{buildId}` (for queue/start/finish)
 * and `…/{buildId}/timeline` (records → spans). Defensive: the JSON is parsed via `JsonElement`
 * (tolerant of Azure schema drift) and every failure returns null (the enrichment worker records
 * `FAILED`, never crashes). SSRF guard: the outbound host must be `https` **and** in
 * [ConnectorConfig.allowedHosts] — an ingested `ci.buildUrl` can pick which configured org, never a
 * new host. The PAT is sent as Basic auth (empty user) and never logged.
 */
class AzureDevOpsConnector(
    private val client: HttpClient,
    private val apiVersion: String = "7.1",
) : CiConnector {

    override val id: String = "azure-devops"
    override val capabilities: Set<Capability> =
        setOf(Capability.TIMELINE_PULL, Capability.WEBHOOK, Capability.DEEP_LINKS)

    override suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun? {
        val base = (config.baseUrl ?: ref.collectionUri)?.trimEnd('/') ?: return null
        val project = config.project ?: ref.project ?: return null
        val pat = (config.credential as? Credential.Pat)?.token ?: return null
        if (!isAllowedHost(base, config.allowedHosts)) {
            logger.warn("azure connector: refusing outbound host not in the allowlist")
            return null
        }
        // runId is ingested (attacker-controlled). An Azure build id is numeric — reject anything else
        // so it cannot inject an extra path/query segment into the outbound URL (a path pivot within the
        // allowlisted host, using the shared PAT). Matches the GitHub/GitLab connectors' guard.
        if (ref.runId.toLongOrNull() == null) {
            logger.warn("azure connector: non-numeric build id — refusing outbound call")
            return null
        }
        val auth = "Basic " + Base64.getEncoder().encodeToString(":$pat".encodeToByteArray())

        val buildJson =
            getJson("$base/$project/_apis/build/builds/${ref.runId}?api-version=$apiVersion", auth)
                ?: return null
        val timelineJson =
            getJson(
                "$base/$project/_apis/build/builds/${ref.runId}/timeline?api-version=$apiVersion",
                auth,
            )

        val queueTime = buildJson.instantMs("queueTime")
        val startedAt = buildJson.instantMs("startTime")
        val finishedAt = buildJson.instantMs("finishTime")
        // A completed build must have a fetchable timeline. If the build finished but the timeline
        // call failed (transient 5xx after retries, or a parse miss), returning a finished CiRun with
        // no spans would make the enricher store a permanent, silently-empty OK. Return null instead
        // so the poll loop retries it like any other transient failure (→ eventually FAILED, honest).
        if (finishedAt != null && timelineJson == null) {
            logger.warn("azure connector: build {} finished but timeline fetch failed — retrying", ref.runId)
            return null
        }
        val spans = timelineJson?.let { parseSpans(it) }.orEmpty()
        return CiRun(
            spans = spans,
            queuedMs = if (queueTime != null && startedAt != null) (startedAt - queueTime).coerceAtLeast(0) else null,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )
    }

    override fun buildLink(ref: CiRunRef, config: ConnectorConfig): String? {
        val base = (config.baseUrl ?: ref.collectionUri)?.trimEnd('/') ?: return null
        val project = config.project ?: ref.project ?: return null
        if (!base.startsWith("https://", ignoreCase = true)) return null
        return "$base/$project/_build/results?buildId=${ref.runId}"
    }

    override fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent? =
        runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
            if (!root.str("eventType").equals("build.complete", ignoreCase = true)) return null
            val resource = root["resource"] as? JsonObject ?: return null
            // str() already reads a numeric or string JsonPrimitive via contentOrNull.
            val buildId = resource.str("id") ?: return null
            // The collection/project come from config or the ingested payload — never from the hook
            // body's URLs (a hook must not be able to redirect the outbound fetch host).
            CiEvent.RunCompleted(CiRunRef(provider = id, runId = buildId))
        }.getOrNull()

    private suspend fun getJson(url: String, auth: String): JsonObject? = runCatching {
        val response = client.get(url) { header("Authorization", auth) }
        if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            logger.warn("azure connector: {} returned {}", url.substringBefore('?'), response.status.value)
            return null
        }
        Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
    }.getOrElse {
        logger.warn("azure connector: fetch failed: {}", it::class.java.simpleName)
        null
    }

    private fun parseSpans(timeline: JsonObject): List<CiSpan> {
        val records = timeline["records"] as? JsonArray ?: return emptyList()
        return records.mapNotNull { element ->
            val record = element as? JsonObject ?: return@mapNotNull null
            val recordId = record.str("id") ?: return@mapNotNull null
            CiSpan(
                id = recordId,
                kind = spanKind(record.str("type")),
                name = record.str("name") ?: "",
                startMs = record.instantMs("startTime"),
                finishMs = record.instantMs("finishTime"),
                result = spanResult(record.str("result")),
                workerName = record.str("workerName"),
                parentId = record.str("parentId"),
            )
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("dev.buildhound.server.connector.Azure")

        // isAllowedHost is shared with the other connectors (ConnectorNet.kt) — one SSRF guard, no drift.

        fun spanKind(type: String?): SpanKind = when (type?.lowercase()) {
            "stage" -> SpanKind.STAGE
            "job", "phase" -> SpanKind.JOB
            else -> SpanKind.STEP
        }

        fun spanResult(result: String?): SpanResult? = when (result?.lowercase()) {
            null -> null
            "succeeded", "succeededwithissues" -> SpanResult.SUCCEEDED
            "failed" -> SpanResult.FAILED
            "canceled", "cancelled" -> SpanResult.CANCELED
            "skipped" -> SpanResult.SKIPPED
            else -> SpanResult.UNKNOWN
        }

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.instantMs(key: String): Long? =
            str(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        /** Parses an Azure `_build/results?buildId=` URL into `(collectionUri, project)`; null on mismatch. */
        fun parseBuildUrl(buildUrl: String?): Pair<String, String>? {
            val url = buildUrl ?: return null
            if (
                !url.startsWith("https://", ignoreCase = true) &&
                    !url.startsWith("http://", ignoreCase = true)
            )
                return null
            val beforeBuild = url.substringBefore("/_build/results", missingDelimiterValue = "")
            if (beforeBuild.isEmpty() || beforeBuild == url) return null
            val project = beforeBuild.substringAfterLast('/')
            val collection = beforeBuild.substringBeforeLast('/')
            if (project.isEmpty() || collection.isEmpty() || !collection.contains("://")) return null
            return collection to project
        }
    }

    /** Parses the collection URI + project out of an ingested `ci.buildUrl` (SPI hook). */
    override fun refFrom(provider: String, runId: String, buildUrl: String?): CiRunRef {
        val parsed = parseBuildUrl(buildUrl)
        return CiRunRef(provider = provider, runId = runId, collectionUri = parsed?.first, project = parsed?.second)
    }
}
