package dev.buildhound.server.connector

import dev.buildhound.server.MILLIS_PER_SECOND
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * GitLab CI connector (plan 041): pulls the pipeline + jobs REST APIs and normalizes them to a [CiRun]
 * tree (STAGE → JOB — GitLab has no per-step timing, so stages are synthesized from `job.stage`).
 * Poll-only in v1 — [TIMELINE_PULL][Capability.TIMELINE_PULL] + [DEEP_LINKS][Capability.DEEP_LINKS],
 * **no webhook**. Two GETs: `{apiBase}/projects/{encoded path}/pipelines/{id}` for timing/status and
 * `…/jobs` for the tree. The project path comes from the ingested `ci.buildUrl` (the plugin ingests
 * `CI_JOB_URL`, a `…/-/jobs/{id}` URL) and the pipeline id is the correlation `runId` (`CI_PIPELINE_ID`);
 * the outbound API host is config-supplied (`gitlab.com/api/v4` by default). SSRF guard: the API host
 * must be `https` **and** in [ConnectorConfig.allowedHosts]. The token is a `PRIVATE-TOKEN` header,
 * never logged; every failure returns null.
 */
class GitLabConnector(
    private val client: HttpClient,
) : CiConnector {

    override val id: String = "gitlab"
    override val capabilities: Set<Capability> = setOf(Capability.TIMELINE_PULL, Capability.DEEP_LINKS)

    override suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun? {
        val apiBase = config.baseUrl?.trimEnd('/') ?: return null
        val projectPath = config.project ?: ref.project ?: return null
        val token = (config.credential as? Credential.Pat)?.token ?: return null
        if (!isAllowedHost(apiBase, config.allowedHosts)) {
            logger.warn("gitlab connector: refusing outbound host not in the allowlist")
            return null
        }
        // runId is ingested (attacker-controlled). A GitLab pipeline id is numeric — reject anything
        // else so it cannot inject an extra path/query segment into the outbound URL (path pivot within
        // the allowlisted host). The host itself already comes only from config, never the build URL.
        if (ref.runId.toLongOrNull() == null) {
            logger.warn("gitlab connector: non-numeric pipeline id — refusing outbound call")
            return null
        }
        // GitLab wants the namespaced path url-encoded; a slug path only ever needs `/` → %2F.
        val encoded = projectPath.replace("/", "%2F")
        val pipelineUrl = "$apiBase/projects/$encoded/pipelines/${ref.runId}"
        val jobsUrl = "$apiBase/projects/$encoded/pipelines/${ref.runId}/jobs?per_page=100"

        val pipelineJson = getJsonObject(pipelineUrl, token) ?: return null
        val jobsJson = getJsonArray(jobsUrl, token)
        val terminal = pipelineJson.strField("status")?.lowercase() in TERMINAL_STATUSES
        // A finished pipeline must have fetchable jobs; a jobs failure after retries would store a
        // permanent empty OK — return null so the poll retries instead.
        if (terminal && jobsJson == null) {
            logger.warn("gitlab connector: pipeline {} finished but jobs fetch failed — retrying", ref.runId)
            return null
        }
        val spans = jobsJson?.let { parseJobs(it) }.orEmpty()
        return CiRun(
            spans = spans,
            queuedMs = pipelineJson.secondsToMs("queued_duration"),
            startedAt = pipelineJson.offsetMs("started_at"),
            finishedAt = pipelineJson.offsetMs("finished_at"),
        )
    }

    override fun buildLink(ref: CiRunRef, config: ConnectorConfig): String? {
        val host = ref.collectionUri?.takeIf { it.isNotBlank() } ?: return null
        val projectPath = ref.project ?: return null
        return "https://$host/$projectPath/-/pipelines/${ref.runId}"
    }

    /** No webhook capability in v1 — GitLab enrichment is poll-only. */
    override fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent? = null

    private suspend fun getJsonObject(url: String, token: String): JsonObject? =
        get(url, token) as? JsonObject

    private suspend fun getJsonArray(url: String, token: String): JsonArray? =
        get(url, token) as? JsonArray

    private suspend fun get(url: String, token: String) = runCatching {
        val response = client.get(url) { header("PRIVATE-TOKEN", token) }
        if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            logger.warn("gitlab connector: {} returned {}", url.substringBefore('?'), response.status.value)
            return@runCatching null
        }
        Json.parseToJsonElement(response.bodyAsText())
    }.getOrElse {
        logger.warn("gitlab connector: fetch failed: {}", it::class.java.simpleName)
        null
    }

    /** Groups jobs into their stages (first-appearance order) → a STAGE span parenting its JOB spans. */
    private fun parseJobs(jobs: JsonArray): List<CiSpan> {
        val byStage = LinkedHashMap<String, MutableList<JsonObject>>()
        jobs.forEach { element ->
            val job = element as? JsonObject ?: return@forEach
            val stage = job.strField("stage") ?: "(default)"
            byStage.getOrPut(stage) { mutableListOf() }.add(job)
        }
        val spans = mutableListOf<CiSpan>()
        byStage.forEach { (stage, stageJobs) ->
            val stageId = "stage:$stage"
            val jobSpans = stageJobs.mapNotNull { job ->
                val jobId = job.strField("id") ?: return@mapNotNull null
                CiSpan(
                    id = jobId,
                    kind = SpanKind.JOB,
                    name = job.strField("name") ?: "",
                    startMs = job.offsetMs("started_at"),
                    finishMs = job.offsetMs("finished_at"),
                    result = statusResult(job.strField("status")),
                    workerName = (job["runner"] as? JsonObject)?.strField("description"),
                    parentId = stageId,
                )
            }
            spans +=
                CiSpan(
                    id = stageId,
                    kind = SpanKind.STAGE,
                    name = stage,
                    startMs = jobSpans.mapNotNull { it.startMs }.minOrNull(),
                    // A stage is only finished once every one of its jobs is; if any is still
                    // running
                    // (result==null), report no finish so a consumer can't read a premature stage
                    // end.
                    finishMs =
                        if (jobSpans.any { it.result == null }) null
                        else jobSpans.mapNotNull { it.finishMs }.maxOrNull(),
                    result = aggregate(jobSpans.map { it.result }),
                )
            spans += jobSpans
        }
        return spans
    }

    override fun refFrom(provider: String, runId: String, buildUrl: String?): CiRunRef {
        val parsed = parsePipelineUrl(buildUrl)
        return CiRunRef(
            provider = provider,
            runId = runId,
            collectionUri = parsed?.host,
            project = parsed?.projectPath,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger("dev.buildhound.server.connector.GitLab")

        val TERMINAL_STATUSES = setOf("success", "failed", "canceled", "cancelled", "skipped")

        /** GitLab pipeline/job `status` → [SpanResult]; an in-progress status maps to null (no result yet). */
        fun statusResult(status: String?): SpanResult? = when (status?.lowercase()) {
            null -> null
            "success" -> SpanResult.SUCCEEDED
            "failed" -> SpanResult.FAILED
            "canceled", "cancelled" -> SpanResult.CANCELED
            "skipped", "manual" -> SpanResult.SKIPPED
            "created", "waiting_for_resource", "preparing", "pending", "running", "scheduled" -> null
            else -> SpanResult.UNKNOWN
        }

        /** A stage's result = the worst of its jobs (failed ≻ canceled ≻ running ≻ succeeded). */
        fun aggregate(results: List<SpanResult?>): SpanResult? =
            when {
                results.any { it == SpanResult.FAILED } -> SpanResult.FAILED
                results.any { it == SpanResult.CANCELED } -> SpanResult.CANCELED
                results.any { it == null } ->
                    null // a job still running ⇒ the stage is not concluded
                results.isNotEmpty() &&
                    results.all { it == SpanResult.SUCCEEDED || it == SpanResult.SKIPPED } ->
                    SpanResult.SUCCEEDED
                else -> SpanResult.UNKNOWN
            }

        /** A numeric-seconds JSON field (GitLab `queued_duration`) → epoch-agnostic millis; null if absent. */
        fun JsonObject.secondsToMs(key: String): Long? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?.let { (it * MILLIS_PER_SECOND).toLong() }

        data class Parsed(val host: String, val projectPath: String)

        /**
         * Parses the `{namespace}/{project}` path out of any GitLab web URL. The plugin ingests
         * `ci.buildUrl = CI_JOB_URL` (`…/{path}/-/jobs/{id}`), so we split on the `/-/` separator that
         * precedes every GitLab route — the project path is everything before it. The pipeline id is
         * **not** taken from this URL; it is the correlation `runId` (`CI_PIPELINE_ID`). Null on mismatch.
         */
        fun parsePipelineUrl(buildUrl: String?): Parsed? {
            val url = buildUrl ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null
            val marker = "/-/"
            val path = uri.path
            if (!path.contains(marker)) return null
            val projectPath = path.substringBefore(marker).trim('/')
            if (projectPath.isEmpty()) return null
            return Parsed(host = host, projectPath = projectPath)
        }
    }
}
