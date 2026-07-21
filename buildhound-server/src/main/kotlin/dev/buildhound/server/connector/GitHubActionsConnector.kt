package dev.buildhound.server.connector

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * GitHub Actions connector (plan 041): pulls the workflow-run + jobs REST APIs and normalizes them to
 * a [CiRun] tree (JOB → STEP). Poll-only in v1 — capabilities are [TIMELINE_PULL][Capability.TIMELINE_PULL]
 * and [DEEP_LINKS][Capability.DEEP_LINKS], **no webhook** (so [parseWebhook] is a no-op). Two GETs:
 * `{apiBase}/repos/{owner}/{repo}/actions/runs/{runId}` for run timing/conclusion, and `…/jobs` (or
 * `…/attempts/{n}/jobs` for a re-run) for the tree. The `owner/repo` and re-run attempt are parsed from
 * the ingested `ci.buildUrl` (never the outbound host, which comes from config — `api.github.com` by
 * default). SSRF guard: the API host must be `https` **and** in [ConnectorConfig.allowedHosts]. The PAT
 * is a `Bearer` token, never logged; every failure returns null (the enricher records it, never crashes).
 */
class GitHubActionsConnector(
    private val client: HttpClient,
    private val apiVersion: String = "2022-11-28",
) : CiConnector {

    override val id: String = "github-actions"
    override val capabilities: Set<Capability> = setOf(Capability.TIMELINE_PULL, Capability.DEEP_LINKS)

    @Suppress("CyclomaticComplexMethod") // Provider response validation is intentionally fail-closed.
    override suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun? {
        val apiBase = config.baseUrl?.trimEnd('/') ?: return null
        val repo = config.project ?: ref.project ?: return null // "owner/repo"
        val token = (config.credential as? Credential.Pat)?.token ?: return null
        if (!isAllowedHost(apiBase, config.allowedHosts)) {
            logger.warn("github connector: refusing outbound host not in the allowlist")
            return null
        }
        // runId is ingested (attacker-controlled). A GitHub run id is numeric — reject anything else so
        // it cannot inject an extra path/query segment into the outbound URL (path pivot within the
        // allowlisted host). The host itself already comes only from config, never the build URL.
        if (ref.runId.toLongOrNull() == null) {
            logger.warn("github connector: non-numeric run id — refusing outbound call")
            return null
        }
        // A re-run (attempt > 1) has its own jobs; attempt 1 (or unknown) uses the base run.
        val attempt = ref.attempt?.takeIf { it > 1 }
        val runBase = "$apiBase/repos/$repo/actions/runs/${ref.runId}"
        val runUrl = if (attempt != null) "$runBase/attempts/$attempt" else runBase
        val jobsUrl =
            if (attempt != null) "$runBase/attempts/$attempt/jobs?per_page=100"
            else "$runBase/jobs?per_page=100"

        val runJson = getJson(runUrl, token) ?: return null
        val jobsJson = getJson(jobsUrl, token)
        val conclusion = runJson.strField("conclusion") // null ⇒ still in progress
        // A concluded run must have fetchable jobs; if the jobs call failed after retries, storing a
        // finished-but-empty CiRun would be a permanent silent OK. Return null so the poll retries it.
        if (conclusion != null && jobsJson == null) {
            logger.warn("github connector: run {} concluded but jobs fetch failed — retrying", ref.runId)
            return null
        }
        val spans = jobsJson?.let { parseJobs(it) }.orEmpty()
        val created = runJson.offsetMs("created_at")
        val runStarted = runJson.offsetMs("run_started_at")
        return CiRun(
            spans = spans,
            queuedMs = if (created != null && runStarted != null) (runStarted - created).coerceAtLeast(0) else null,
            startedAt = runStarted,
            // Only a concluded run has an honest finish. Prefer the latest job finish (the true pipeline
            // end), but a concluded run can have zero timestamped jobs (startup_failure, or cancelled
            // before any job started → jobs:[]). Fall back to the run envelope's own finish so the
            // enricher never sees a concluded run as finishedAt==null and stores it PENDING forever.
            finishedAt = if (conclusion != null) {
                spans.mapNotNull { it.finishMs }.maxOrNull() ?: runJson.offsetMs("updated_at") ?: runStarted
            } else {
                null
            },
        )
    }

    override fun buildLink(ref: CiRunRef, config: ConnectorConfig): String? {
        val host = ref.collectionUri?.takeIf { it.isNotBlank() } ?: return null
        val repo = ref.project ?: return null
        val base = "https://$host/$repo/actions/runs/${ref.runId}"
        return if ((ref.attempt ?: 1) > 1) "$base/attempts/${ref.attempt}" else base
    }

    /** No webhook capability in v1 — GitHub enrichment is poll-only. */
    override fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent? = null

    private suspend fun getJson(url: String, token: String): JsonObject? = runCatching {
        val response = client.get(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", apiVersion)
        }
        if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            logger.warn("github connector: {} returned {}", url.substringBefore('?'), response.status.value)
            return null
        }
        Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
    }.getOrElse {
        logger.warn("github connector: fetch failed: {}", it::class.java.simpleName)
        null
    }

    private fun parseJobs(envelope: JsonObject): List<CiSpan> {
        val jobs = envelope["jobs"] as? JsonArray ?: return emptyList()
        val total = envelope.strField("total_count")?.toIntOrNull()
        if (total != null && total > jobs.size) {
            // v1 fetches only the first page (100 jobs); a huge matrix is truncated. Log so it is not
            // mistaken for a complete tree — a follow-up can paginate. Never fails ingest.
            logger.warn("github connector: run has {} jobs but only {} fetched (first page)", total, jobs.size)
        }
        val spans = mutableListOf<CiSpan>()
        jobs.forEach { element ->
            val job = element as? JsonObject ?: return@forEach
            val jobId = job.strField("id") ?: return@forEach
            spans += CiSpan(
                id = jobId,
                kind = SpanKind.JOB,
                name = job.strField("name") ?: "",
                startMs = job.offsetMs("started_at"),
                finishMs = job.offsetMs("completed_at"),
                result = conclusionResult(job.strField("conclusion")),
                workerName = job.strField("runner_name"),
            )
            val steps = job["steps"] as? JsonArray ?: return@forEach
            steps.forEachIndexed { index, stepElement ->
                val step = stepElement as? JsonObject ?: return@forEachIndexed
                // The real API always sends `number`; fall back to the loop index (not the name) so two
                // unnamed/duplicate steps can never collide on the same span id within a job.
                val number = step.strField("number") ?: index.toString()
                spans += CiSpan(
                    id = "$jobId#$number",
                    kind = SpanKind.STEP,
                    name = step.strField("name") ?: "",
                    startMs = step.offsetMs("started_at"),
                    finishMs = step.offsetMs("completed_at"),
                    result = conclusionResult(step.strField("conclusion")),
                    parentId = jobId,
                )
            }
        }
        return spans
    }

    override fun refFrom(provider: String, runId: String, buildUrl: String?): CiRunRef {
        val parsed = parseRunUrl(buildUrl)
        return CiRunRef(
            provider = provider,
            runId = runId,
            collectionUri = parsed?.host,
            project = parsed?.repo,
            attempt = parsed?.attempt,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger("dev.buildhound.server.connector.GitHub")

        /** GHA `conclusion` (null while running) → [SpanResult]; `status` alone never sets a result. */
        fun conclusionResult(conclusion: String?): SpanResult? = when (conclusion?.lowercase()) {
            null -> null
            "success" -> SpanResult.SUCCEEDED
            "failure", "timed_out", "startup_failure" -> SpanResult.FAILED
            "cancelled", "canceled" -> SpanResult.CANCELED
            "skipped", "neutral" -> SpanResult.SKIPPED
            else -> SpanResult.UNKNOWN // action_required, stale, …
        }

        data class Parsed(val host: String, val repo: String, val attempt: Int?)

        /** Parses `https://{host}/{owner}/{repo}/actions/runs/{id}[/attempts/{n}]`; null on any mismatch. */
        fun parseRunUrl(buildUrl: String?): Parsed? {
            val url = buildUrl ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null
            val segments = uri.path.split('/').filter { it.isNotEmpty() }
            // [owner, repo, "actions", "runs", runId, ("attempts", n)?]
            if (segments.size < MIN_RUN_PATH_SEGMENTS ||
                segments[2] != "actions" ||
                segments[RUNS_SEGMENT] != "runs"
            ) {
                return null
            }
            val attempt =
                if (segments.size >= ATTEMPT_PATH_SEGMENTS && segments[ATTEMPTS_SEGMENT] == "attempts") {
                    segments[ATTEMPT_NUMBER_SEGMENT].toIntOrNull()
                } else {
                    null
                }
            return Parsed(host = host, repo = "${segments[0]}/${segments[1]}", attempt = attempt)
        }

        private const val MIN_RUN_PATH_SEGMENTS = 5
        private const val RUNS_SEGMENT = 3
        private const val ATTEMPT_PATH_SEGMENTS = 7
        private const val ATTEMPTS_SEGMENT = 5
        private const val ATTEMPT_NUMBER_SEGMENT = 6
    }
}
