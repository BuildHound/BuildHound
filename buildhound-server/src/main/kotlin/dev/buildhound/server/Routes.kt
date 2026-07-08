package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.PayloadCapper
import dev.buildhound.commons.payload.PayloadCaps
import dev.buildhound.server.connector.CiEvent
import dev.buildhound.server.connector.CiRunView
import dev.buildhound.server.connector.CiSpanStore
import dev.buildhound.server.connector.ConnectorConfig
import dev.buildhound.server.connector.ConnectorRegistry
import dev.buildhound.server.connector.EnrichmentQueue
import dev.buildhound.server.connector.GradleShare
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.util.getOrFail
import io.ktor.utils.io.readAvailable
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.io.ByteArrayInputStream
import java.sql.SQLException
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class IngestResponse(val buildId: String, val status: String)

@Serializable
data class ApiError(val error: String)

/** Compressed request cap; the decompressed cap is the zip-bomb guard (plan 009). */
const val MAX_COMPRESSED_BYTES: Int = 32 * 1024 * 1024
const val MAX_DECOMPRESSED_BYTES: Int = 64 * 1024 * 1024

fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthResponse(status = "ok"))
    }
}

/**
 * `POST /v1/builds` (spec §5): Bearer-token authenticated, gzip-aware, idempotent on
 * (project, buildId). The token — not the payload's `projectKey` — determines the
 * tenant. Fails closed: no resolvable token, no ingest.
 */
fun Route.ingestRoutes(
    store: BuildStore,
    tokens: TokenStore,
    evaluator: VerdictEvaluator,
    flakyAlerter: FlakyAlerter,
    enrichment: EnrichmentQueue,
) {
    route("/v1") {
        post("/builds") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsIngest) ?: return@post

            // Bounded read: the body must never be fully buffered before the cap check
            // (authenticated OOM DoS otherwise — review finding, plan 009).
            val raw = call.receiveBounded(MAX_COMPRESSED_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("payload too large"))
            val json = if (call.request.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true) {
                gunzipBounded(raw)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid or oversized gzip body"))
            } else {
                raw
            }

            val payload = runCatching {
                BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), json.decodeToString())
            }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid payload"))
            }
            if (payload.schemaVersion > BuildPayload.SCHEMA_VERSION) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ApiError("unsupported schemaVersion ${payload.schemaVersion}"),
                )
            }

            payload.projectKey?.takeIf { it != project.key }?.let {
                ingestLogger.warn("payload projectKey '{}' differs from token project '{}'", it, project.key)
            }

            // Defensive clamp (plan 019): a compliant plugin makes this a no-op; a hostile or
            // buggy client is bounded, not rejected — the telemetry survives, and only counts
            // (never keys/values) log. The byte ceilings above stay the outer wall.
            val capped = PayloadCapper.cap(payload)
            if (capped.caps != payload.caps) {
                ingestLogger.warn(
                    "clamped over-cap payload from '{}': post-cap totals {} tag(s), {} value(s), {} task(s) dropped",
                    project.key, capped.caps?.droppedTags, capped.caps?.droppedValues, capped.caps?.droppedTasks,
                )
            }

            // Data-shaped failures (SQLSTATE 22xxx, e.g. \u0000 in jsonb) are permanent →
            // 400 so the plugin drops them; anything else is a storage outage → 503 so
            // the plugin spools and retries (its 4xx/5xx classification relies on this).
            val stored = try {
                store.save(project.id, capped)
            } catch (e: SQLException) {
                val permanent = e.sqlState?.startsWith("22") == true
                return@post if (permanent) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("payload not storable"))
                } else {
                    ingestLogger.warn("storage unavailable: {}", e::class.java.simpleName)
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("storage unavailable"))
                }
            }
            // Post-save regression evaluation (plan 025): only on a fresh store, wrapped so it can
            // never block or fail ingest (its own runCatching). The alert HTTP call inside is async.
            if (stored) evaluator.evaluate(project.id, project.key, capped)
            // Flaky-alert hook (plan 036): edge-triggered, best-effort, never fails ingest.
            if (stored) flakyAlerter.evaluate(project.id, project.key, capped)

            // CI-connector enrichment (plan 028): fire-and-forget; no-ops unless a registered connector
            // handles ci.provider. buildUrl is the ingested (attacker-controlled) source parsed for the
            // collection/project — the connector's host allowlist is the SSRF guard, not this call.
            if (stored) {
                enrichment.submit(project.id, capped.buildId, capped.ci?.provider, capped.ci?.runId, capped.ci?.buildUrl)
            }

            call.respond(
                HttpStatusCode.Accepted,
                IngestResponse(buildId = payload.buildId, status = if (stored) "accepted" else "duplicate"),
            )
        }
    }
}

/**
 * `POST /v1/connectors/azure-devops/hook` (plan 028): an Azure `build.complete` service hook can push
 * completion so enrichment re-fetches a now-finished timeline instead of waiting on the poll budget.
 * Ingest-scoped and tenant-scoped — the tenant is the token's project, **never** the hook body. The
 * body only names the provider run id; we correlate it to our build via `{provider, runId}` and reuse
 * that build's ingested `ci.buildUrl` (the hook can't redirect the outbound host). Junk → 400.
 */
fun Route.connectorHookRoutes(
    builds: BuildStore,
    tokens: TokenStore,
    connectors: ConnectorRegistry,
    enrichment: EnrichmentQueue,
) {
    route("/v1/connectors") {
        post("/azure-devops/hook") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsIngest) ?: return@post
            val body = call.receiveBounded(256 * 1024)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("hook body too large"))
            // Provider is fixed by the route path for v1; when plan 041 adds GHA/GitLab hooks this
            // becomes a `/{provider}/hook` path parameter resolved through the same registry.
            val connector = connectors.byId("azure-devops")
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("connector not available"))
            val event = connector.parseWebhook(emptyMap(), body.decodeToString(), ConnectorConfig())
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("unrecognized hook body"))
            when (event) {
                is CiEvent.RunCompleted -> {
                    // Resolve the provider run id to OUR build id (tenant-scoped); reuse its buildUrl.
                    val resolved = call.runQuery {
                        val buildId = builds.resolveBuildId(project.id, event.ref.provider, event.ref.runId)
                        buildId to buildId?.let { builds.findById(project.id, it)?.ci?.buildUrl }
                    } ?: return@post
                    val (buildId, buildUrl) = resolved.value
                    if (buildId != null) {
                        enrichment.submit(project.id, buildId, event.ref.provider, event.ref.runId, buildUrl)
                    } else {
                        // Expected-build fallback (plan 033): the run completed on the Timeline but no
                        // payload ever arrived (the build died on an ephemeral agent). Confirm + record
                        // an INTERRUPTED build asynchronously so it stops vanishing.
                        enrichment.submitExpectedBuildCheck(project.id, event.ref.provider, event.ref.runId, buildUrl)
                    }
                }
            }
            call.respond(HttpStatusCode.Accepted, IngestResponse(buildId = event.ref.runId, status = "accepted"))
        }
    }
}

/** Metric caps enforced in code (spec §5): a foreign CLI is rejected loudly (422), not clamped. */
const val MAX_METRICS_PER_RUN: Int = 100
const val MAX_METRIC_NAME_CHARS: Int = 150
const val MAX_METRIC_VALUE_CHARS: Int = 300
const val MAX_METRIC_SCOPE_CHARS: Int = 64

@Serializable
data class MetricCorrelation(val buildId: String? = null, val provider: String? = null, val runId: String? = null)

@Serializable
data class MetricSubmission(
    val correlation: MetricCorrelation = MetricCorrelation(),
    val scope: String,
    val name: String,
    val value: Double? = null,
    val text: String? = null,
    val unit: String? = null,
)

@Serializable
data class MetricAck(val status: String, val correlatedBuildId: String? = null)

/**
 * `POST /v1/metrics` (spec §5): ingest-scoped custom measures from the metric CLI, correlated to a
 * build by an explicit buildId or a {provider, runId}. Caps are enforced in code and rejected 422.
 */
fun Route.metricsRoutes(builds: BuildStore, metrics: MetricStore, tokens: TokenStore) {
    route("/v1") {
        post("/metrics") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsIngest) ?: return@post
            val body = call.receiveBounded(256 * 1024)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("metric too large"))
            val submission = runCatching {
                BuildHoundJson.payload.decodeFromString(MetricSubmission.serializer(), body.decodeToString())
            }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid metric")) }

            validateMetric(submission)?.let {
                return@post call.respond(HttpStatusCode.UnprocessableEntity, ApiError(it))
            }

            val correlation = submission.correlation
            // Explicit buildId is a direct correlation (no provider/runId stored); otherwise resolve
            // {provider,runId} to the newest matching build now (or null — joined lazily at verdict time).
            val byBuildId = correlation.buildId != null
            val resolvedBuildId = correlation.buildId
                ?: builds.resolveBuildId(project.id, correlation.provider, correlation.runId)

            // Cardinality cap: a *new* measure beyond the per-run limit is rejected loudly.
            val runKeys = call.runQuery {
                metrics.correlationKeys(project.id, correlation.buildId, correlation.provider, correlation.runId)
            } ?: return@post
            val measureKey = "${submission.scope} ${submission.name}"
            if (measureKey !in runKeys.value && runKeys.value.size >= MAX_METRICS_PER_RUN) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ApiError("too many measures for this run (max $MAX_METRICS_PER_RUN)"),
                )
            }

            call.runQuery {
                metrics.upsert(
                    project.id,
                    MetricRecord(
                        scope = submission.scope, name = submission.name,
                        value = submission.value, text = submission.text, unit = submission.unit,
                        buildId = resolvedBuildId,
                        provider = if (byBuildId) null else correlation.provider,
                        runId = if (byBuildId) null else correlation.runId,
                    ),
                )
            } ?: return@post
            call.respond(HttpStatusCode.Accepted, MetricAck(status = "accepted", correlatedBuildId = resolvedBuildId))
        }
    }
}

private fun validateMetric(s: MetricSubmission): String? = when {
    s.scope.isBlank() || s.scope.length > MAX_METRIC_SCOPE_CHARS -> "scope must be 1..$MAX_METRIC_SCOPE_CHARS chars"
    s.name.isBlank() || s.name.length > MAX_METRIC_NAME_CHARS -> "name must be 1..$MAX_METRIC_NAME_CHARS chars"
    s.value == null && s.text == null -> "a value or text is required"
    s.value != null && s.text != null -> "provide either value or text, not both"
    (s.text?.length ?: 0) > MAX_METRIC_VALUE_CHARS -> "text must be <= $MAX_METRIC_VALUE_CHARS chars"
    (s.unit?.length ?: 0) > MAX_METRIC_VALUE_CHARS -> "unit must be <= $MAX_METRIC_VALUE_CHARS chars"
    s.correlation.buildId == null && s.correlation.provider == null && s.correlation.runId == null ->
        "a correlation (buildId, or provider + runId) is required"
    else -> null
}

/** `GET/PUT /v1/settings` (spec §5): read with read-scope; write requires the all-scope admin token. */
fun Route.settingsRoutes(settings: SettingsStore, tokens: TokenStore) {
    route("/v1") {
        get("/settings") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            call.runQuery { settings.get(project.id) ?: ProjectSettings() }?.let { call.respond(it.value) }
        }
        put("/settings") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsAll) ?: return@put
            val body = call.receiveBounded(256 * 1024)
                ?: return@put call.respond(HttpStatusCode.PayloadTooLarge, ApiError("settings too large"))
            val cfg = runCatching {
                BuildHoundJson.payload.decodeFromString(ProjectSettings.serializer(), body.decodeToString())
            }.getOrElse { return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid settings")) }
            validateSettings(cfg)?.let { return@put call.respond(HttpStatusCode.UnprocessableEntity, ApiError(it)) }
            call.runQuery { settings.put(project.id, cfg) } ?: return@put
            call.respond(cfg)
        }
    }
}

/**
 * `GET/PUT /v1/admin/retention` (plan 042): per-tenant retention windows. Admin-scoped — a `read`
 * token gets 403, a missing token 401. Invalid windows → 400. Tenant comes from the token, never the
 * body, so an admin token can only ever change its own project's retention.
 */
fun Route.adminRoutes(settings: SettingsStore, tokens: TokenStore) {
    route("/v1/admin") {
        get("/retention") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsAdmin) ?: return@get
            call.runQuery { settings.retention(project.id) }?.let { call.respond(it.value) }
        }
        put("/retention") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsAdmin) ?: return@put
            val body = call.receiveBounded(16 * 1024)
                ?: return@put call.respond(HttpStatusCode.PayloadTooLarge, ApiError("retention config too large"))
            val cfg = runCatching {
                BuildHoundJson.payload.decodeFromString(RetentionConfig.serializer(), body.decodeToString())
            }.getOrElse { return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid retention config")) }
            cfg.validationError()?.let { return@put call.respond(HttpStatusCode.BadRequest, ApiError(it)) }
            call.runQuery { settings.setRetention(project.id, cfg) } ?: return@put
            call.respond(cfg)
        }
    }
}

private fun validateSettings(s: ProjectSettings): String? = when {
    s.baselineN < RegressionEngine.MIN_BASELINE -> "baselineN must be >= ${RegressionEngine.MIN_BASELINE}"
    s.warnZ <= 0.0 || s.failZ <= 0.0 -> "z thresholds must be positive"
    s.failZ < s.warnZ -> "failZ must be >= warnZ"
    s.defaultBranch.isBlank() -> "defaultBranch is required"
    // Alert URLs are the outbound-allowlist source; the dispatcher additionally enforces https.
    s.alertChannels.any { it.url.isBlank() || it.kind.lowercase() !in setOf("slack", "teams", "webhook") } ->
        "each alert channel needs a non-blank url and a kind of slack|teams|webhook"
    else -> null
}

/**
 * Query API (plan 010): same token, same tenant scoping as ingest. Rollups are
 * computed on read over the indexed hot columns; materialized aggregates come when
 * volume demands them.
 */
fun Route.queryRoutes(store: BuildStore, verdicts: VerdictStore, tokens: TokenStore, ciSpans: CiSpanStore) {
    route("/v1") {
        get("/builds") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val filter = call.buildFilterOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("invalid mode/outcome filter"))
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 10_000)
            val result = call.runQuery {
                store.count(project.id, filter) to store.list(project.id, filter, limit, offset)
            } ?: return@get
            val (total, builds) = result.value
            // Filter-aware total for the list's count-summary header (plan 018): additive,
            // the body stays a plain array so existing consumers are unaffected. count and
            // list run on separate pooled connections, so a concurrent ingest can make the
            // header and page momentarily disagree by one — cosmetic, accepted at pilot scale.
            call.response.header("X-Total-Count", total.toString())
            call.respond(builds)
        }

        get("/builds/{buildId}") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val buildId = call.parameters.getOrFail("buildId")
            val payload = call.runQuery { store.findById(project.id, buildId) } ?: return@get
            payload.value
                ?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("unknown build"))
        }

        // Per-build regression verdict (plan 025, spec §5): pollable by the CI verdict gate.
        // Tenant-scoped, so a foreign or unknown build reads as 404 (never a cross-tenant peek).
        get("/builds/{buildId}/verdict") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val buildId = call.parameters.getOrFail("buildId")
            val verdict = call.runQuery { verdicts.find(project.id, buildId) } ?: return@get
            verdict.value
                ?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("no verdict for this build"))
        }

        // Normalized CI span tree + derived queue time and Gradle share (plan 028, spec §5).
        // Read-scope, tenant-scoped: a foreign/unknown build (or one never enriched) reads as 404.
        get("/builds/{buildId}/ci-run") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val buildId = call.parameters.getOrFail("buildId")
            val view = call.runQuery {
                val stored = ciSpans.findRun(project.id, buildId) ?: return@runQuery null
                // The Gradle build's wall-clock comes from the ingested build; null when it is gone.
                val buildDurationMs = store.findById(project.id, buildId)?.let { it.finishedAt - it.startedAt }
                CiRunView(
                    status = stored.status,
                    queuedMs = stored.run?.queuedMs,
                    spans = stored.run?.spans.orEmpty(),
                    gradleSharePct = GradleShare.percent(buildDurationMs, stored.run),
                )
            } ?: return@get
            view.value
                ?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("no ci run for this build"))
        }

        // Compare two builds' inputs to explain B's cache misses vs A (plan 022, spec §5).
        // Tenant-scoped: both lookups use the token's project, so a foreign build reads as 404.
        get("/builds/{a}/compare/{b}") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val idA = call.parameters.getOrFail("a")
            val idB = call.parameters.getOrFail("b")
            if (idA == idB) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("cannot compare a build with itself"))
            }
            val result = call.runQuery {
                val a = store.findById(project.id, idA)
                val b = store.findById(project.id, idB)
                if (a == null || b == null) null else BuildComparator.compare(a, b)
            } ?: return@get
            result.value
                ?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("unknown build"))
        }

        // Agent-facing diagnosis synthesis (plan 071, research F21): dominant phase, hit-rate-vs-target,
        // top hotspots, and deltas vs the comparable baseline — one call over already-collected signals,
        // no new store method. Tenant-scoped like /verdict and /compare: a foreign/unknown build reads
        // as 404, never a cross-tenant peek. A build with no evaluated verdict still 200s with deltas null.
        get("/builds/{buildId}/diagnosis") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val buildId = call.parameters.getOrFail("buildId")
            val result = call.runQuery {
                val payload = store.findById(project.id, buildId) ?: return@runQuery null
                val verdict = verdicts.find(project.id, buildId)
                BuildDiagnoser.diagnose(payload, verdict)
            } ?: return@get
            result.value
                ?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("unknown build"))
        }

        get("/trends") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val filter = call.buildFilterOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("invalid mode/outcome filter"))
            val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 30).coerceIn(1, 365)
            call.respondQuery { store.trends(project.id, filter, days, System.currentTimeMillis()) }
        }

        // Android artifact-size trends (plan 031, spec §6): daily avg/max per (module, variant, type).
        // Read scope, tenant-scoped, same filter + days handling as /trends (benchmark excluded by default).
        get("/artifacts/trends") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val filter = call.buildFilterOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("invalid mode/outcome filter"))
            call.respondQuery { store.artifactTrends(project.id, filter, call.daysParam(), System.currentTimeMillis()) }
        }

        // Server-side rollups over the normalized task_executions table (plan 026). Read-scope,
        // tenant-scoped, days clamped like /trends, top-25 result caps enforced in the store.
        get("/rollups/project-cost") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            call.respondQuery { store.projectCost(project.id, days, System.currentTimeMillis()) }
        }
        get("/rollups/task-duration") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            call.respondQuery { store.taskDuration(project.id, days, System.currentTimeMillis()) }
        }
        get("/rollups/negative-avoidance") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            call.respondQuery { store.negativeAvoidance(project.id, days, System.currentTimeMillis()) }
        }

        // Owning-plugin cost rollup (plan 058, research F8 Layer 1): FQCN-prefix rollup over
        // already-collected task types — zero new collection. Read-scope, tenant-scoped, days clamped
        // like its taskDuration/projectCost/negativeAvoidance siblings (benchmark builds included).
        get("/rollups/plugin-cost") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            call.respondQuery { store.pluginCost(project.id, days, System.currentTimeMillis()) }
        }

        // Benchmark series (plan 030, spec §7): mode=BENCHMARK builds grouped by (scenario, isolation)
        // with percentiles. Read-scope, tenant-scoped; optional scenario/isolationMode/branch narrowing.
        // Benchmark builds are excluded from the fleet /trends + /builds views (buildFilterOrNull); this
        // is the dedicated view for them. Unknown filter values simply return empty groups.
        get("/benchmark/series") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            val params = call.request.queryParameters
            call.respondQuery {
                store.benchmarkSeries(
                    project.id,
                    scenario = params["scenario"],
                    isolationMode = params["isolationMode"],
                    branch = params["branch"],
                    days = days,
                    nowMs = System.currentTimeMillis(),
                )
            }
        }

        // "What got worse" landing rollup (plan 032, spec §6): this window vs the prior equal window.
        // Read-scope, tenant-scoped; period clamped to [1, 90] (two windows = up to 180 days scanned).
        get("/rollups/bottlenecks") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val period = call.periodParam()
            call.respondQuery { store.bottlenecks(project.id, period, System.currentTimeMillis()) }
        }

        // Toolchain adoption (plan 032, spec §6): per-dimension version distribution + hashed distinct
        // users + "who is behind". Read-scope, tenant-scoped, days clamped like /trends. AGP/KGP/KSP
        // report available=false until the plugin collects them.
        get("/rollups/toolchain") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            call.respondQuery { store.toolchainAdoption(project.id, days, System.currentTimeMillis()) }
        }

        // Rerun-cause taxonomy (plan 061, research F11): per-bucket coverage of executed task-hours
        // (overlapping — see RerunCauseBucketRow), a build-level cascade rate, and an optional
        // build-logic-invalidation-storm candidate. Read-scope, tenant-scoped, days clamped like
        // /trends; benchmark builds excluded (the bottlenecks/toolchain fleet-view convention).
        get("/rollups/rerun-causes") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            call.respondQuery { store.rerunCauses(project.id, days, System.currentTimeMillis()) }
        }

        // Flaky-test detection (plan 036, spec §5): two-signal per-(module, class) records over the
        // window, ranked by flake rate. Read-scope, tenant-scoped, days clamped like /trends.
        get("/flaky") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val days = call.daysParam()
            call.respondQuery { store.flaky(project.id, days, System.currentTimeMillis()) }
        }

        // Tag-cohort comparison (plan 057, research F7): split a trend by a tag's distinct values —
        // per-cohort daily series plus a median-delta/%change/robust-z verdict vs the largest (most
        // stable) cohort. Read-scope, tenant-scoped, days clamped like /trends; benchmark builds
        // excluded by default (buildFilterOrNull, plan 030). An unknown tag key reads as an empty
        // comparison (no cohorts), never a 404 — mirroring /trends' unmatched-filter behavior.
        get("/trends/cohorts") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val filter = call.buildFilterOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("invalid mode/outcome/tag filter"))
            val tagKey = call.request.queryParameters["tag"]
            if (tagKey.isNullOrBlank()) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("tag query parameter is required"))
            }
            val days = call.daysParam()
            call.respondQuery {
                CohortComparator.compare(tagKey, store.tagCohortTrends(project.id, tagKey, filter, days, System.currentTimeMillis()))
            }
        }

        // Distinct tag keys + capped top-N values each (plan 057): populates the dashboard's
        // split-by-tag picker. Read-scope, tenant-scoped, days clamped like /trends.
        get("/tags") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            call.respondQuery { store.tagKeys(project.id, call.daysParam(), System.currentTimeMillis()) }
        }
    }
}

/**
 * `GET /v1/metrics/prometheus` (plan 070, research F20): Prometheus text-exposition (format 0.0.4) of
 * one project's KPIs — p50/p95 build duration, cache hit rate, success rate, windowed build counts,
 * flaky-unit count, avoided time. `metrics`-scoped (also `read`/`all`, mirroring the dedicated
 * `ADDON`/`ADMIN` scopes: a leaked CI ingest token must not scrape metrics, and a leaked scrape token
 * must not read history). Serves **only** `principal.project` — there is no global unauthenticated
 * `/metrics`, which would cross-leak every tenant's KPIs (F20's load-bearing multi-tenancy caveat).
 * Bypasses the JSON `ContentNegotiation` plugin via `respondText` (the wire format is plain text, not
 * JSON — same pattern the dashboard's static assets use with `respondBytes`). Always 200 with valid (if
 * line-omitted, per [PrometheusExposition]'s omit-not-zero rule) exposition on success, so a scrape
 * target never reads "down"; a storage outage is 503 through the shared [runQuery] classifier, never a
 * bare 500.
 */
fun Route.metricsEgressRoutes(store: BuildStore, tokens: TokenStore) {
    route("/v1/metrics") {
        get("/prometheus") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsMetrics) ?: return@get
            val days = call.daysParam()
            val snapshot = call.runQuery { store.metricsSnapshot(project.id, days, System.currentTimeMillis()) } ?: return@get
            call.respondText(
                PrometheusExposition.render(project.key, snapshot.value),
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
        }
    }
}

/**
 * Addon API namespace (plan 039): `/v1/addons/{addonId}/…`, walled off from ingest/read tokens by a
 * dedicated `ADDON` scope. `{addonId}` is validated against [registeredAddons] — a **server-side
 * allowlist**, so it never names a table or route dynamically; an unregistered id is a flat 404
 * (empty allowlist ⇒ every id 404 until a consumer registers one). Storage is a generic tenant-scoped
 * jsonb key/value scaffold; plan 040 (sharding) adds the first concrete sub-route as a consumer.
 */
fun Route.addonRoutes(addons: AddonStore, tokens: TokenStore, registeredAddons: Set<String>) {
    route("/v1/addons/{addonId}") {
        get("/data") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsAddon) ?: return@get
            val addonId = call.registeredAddonId(registeredAddons) ?: return@get
            call.respondQuery { addons.all(project.id, addonId) }
        }
        get("/data/{key}") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsAddon) ?: return@get
            val addonId = call.registeredAddonId(registeredAddons) ?: return@get
            val key = call.addonKeyOrNull() ?: return@get
            val result = call.runQuery { addons.get(project.id, addonId, key) } ?: return@get
            result.value?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("no such key"))
        }
        put("/data/{key}") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsAddon) ?: return@put
            val addonId = call.registeredAddonId(registeredAddons) ?: return@put
            val key = call.addonKeyOrNull() ?: return@put
            val body = call.receiveBounded(64 * 1024)
                ?: return@put call.respond(HttpStatusCode.PayloadTooLarge, ApiError("addon value too large"))
            val json = runCatching { BuildHoundJson.payload.parseToJsonElement(body.decodeToString()) }
                .getOrElse { return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid json body")) }
            // Mirror ingest's classification: a data-shaped failure (SQLSTATE 22xxx, e.g. a NUL byte
            // in jsonb) is a permanent client error -> 400; anything else is a storage outage -> 503.
            try {
                addons.put(project.id, addonId, key, json)
            } catch (e: SQLException) {
                return@put if (e.sqlState?.startsWith("22") == true) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("value not storable"))
                } else {
                    ingestLogger.warn("storage unavailable: {}", e::class.java.simpleName)
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("storage unavailable"))
                }
            }
            call.respond(HttpStatusCode.OK, AddonAck(status = "stored"))
        }
    }
}

@Serializable
data class AddonAck(val status: String)

/** 404s (returns null) when `{addonId}` is not on the server-side allowlist — never a dynamic name. */
private suspend fun ApplicationCall.registeredAddonId(registered: Set<String>): String? {
    val addonId = parameters.getOrFail("addonId")
    if (addonId !in registered) {
        respond(HttpStatusCode.NotFound, ApiError("unknown addon"))
        return null
    }
    return addonId
}

/** Validates the addon KV key: a bounded identifier (bound in SQL anyway; this rejects junk early). */
private suspend fun ApplicationCall.addonKeyOrNull(): String? {
    val key = parameters.getOrFail("key")
    val ascii = { c: Char -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._-" }
    if (key.isEmpty() || key.length > 200 || !key.all(ascii)) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid addon key"))
        return null
    }
    return key
}

@Serializable
data class ShardPlanRequest(val reference: String, val index: Int, val total: Int, val suites: List<String> = emptyList())

@Serializable
data class ShardPlanResponse(
    val shardPlanId: String,
    val index: Int,
    val classes: List<String>,
    /** Every class the plan assigns to *some* shard — lets the last shard catch any drift-unassigned suite. */
    val assigned: List<String> = emptyList(),
)

/** Trailing window (days) the shard balancer's p90 timings are computed over (plan 040). */
private const val SHARD_TIMING_DAYS: Int = 30

/** Upper bound on shard count — `LptBalancer` allocates `total` lists, so a hostile `total` is a DoS. */
private const val MAX_SHARDS: Int = 1000

/**
 * Test-sharding addon capability (plan 040): `POST /v1/addons/test-sharding/plan`. A CI token
 * (ingest scope) posts its `{reference, index, total, suites}`; the server returns this shard's class
 * list from an **idempotent** per-(project, reference, total) LPT plan balanced over 30-day p90 CI
 * class timings. The first job for a reference fixes the plan; later jobs read it — so inter-job
 * discovery drift can't reshuffle shards. Tenant is the token's project, never the body.
 */
fun Route.testShardingRoutes(builds: BuildStore, shardPlans: ShardPlanStore, tokens: TokenStore) {
    post("/v1/addons/test-sharding/plan") {
        val project = call.authenticatedProject(tokens, TokenScope::allowsIngest) ?: return@post
        val body = call.receiveBounded(1024 * 1024)
            ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("plan request too large"))
        val req = runCatching { BuildHoundJson.payload.decodeFromString(ShardPlanRequest.serializer(), body.decodeToString()) }
            .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid plan request")) }
        if (req.reference.isBlank() || req.total < 1 || req.total > MAX_SHARDS || req.index < 1 || req.index > req.total) {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError("reference must be non-blank and 1 <= index <= total <= $MAX_SHARDS"))
        }
        val result = call.runQuery {
            shardPlans.planOrCompute(project.id, req.reference, req.total) {
                val p90 = builds.classTimings(project.id, SHARD_TIMING_DAYS, System.currentTimeMillis())
                    .mapValues { LptBalancer.p90(it.value) }
                LptBalancer.plan(req.suites, req.total, p90)
            }
        } ?: return@post
        val plan = result.value
        val classes = plan.getOrElse(req.index - 1) { emptyList() }
        val shardPlanId = sha256Hex("${project.id}|${req.reference}|${req.total}").substring(0, 12)
        call.respond(ShardPlanResponse(shardPlanId = shardPlanId, index = req.index, classes = classes, assigned = plan.flatten()))
    }
}

/** Window size in days, defaulting to 30 and clamped to [1, 365] like /trends. */
internal fun ApplicationCall.daysParam(): Int =
    (request.queryParameters["days"]?.toIntOrNull() ?: 30).coerceIn(1, 365)

/**
 * Bottlenecks comparison window in days, default 7, clamped to [1, 90] (plan 032). The store scans two
 * back-to-back windows, so 90 bounds the read at 180 days — the fleet-scale cap that keeps it honest.
 */
private fun ApplicationCall.periodParam(): Int =
    (request.queryParameters["period"]?.toIntOrNull() ?: 7).coerceIn(1, 90)

private class QueryResult<T>(val value: T)

/** Same outage classification as ingest: storage failures are 503, never a bare 500. */
private suspend fun <T> ApplicationCall.runQuery(block: () -> T): QueryResult<T>? =
    try {
        QueryResult(block())
    } catch (e: SQLException) {
        ingestLogger.warn("storage unavailable: {}", e::class.java.simpleName)
        respond(HttpStatusCode.ServiceUnavailable, ApiError("storage unavailable"))
        null
    }

private suspend inline fun <reified T : Any> ApplicationCall.respondQuery(noinline block: () -> T) {
    runQuery(block)?.let { respond(it.value) }
}

/**
 * 401s and returns null when the bearer token is missing/unknown; 403 when the
 * token's scope does not permit the operation (spec §5: ingest vs read scopes).
 */
private suspend fun ApplicationCall.authenticatedProject(
    tokens: TokenStore,
    scopeCheck: (String) -> Boolean,
): ProjectRef? {
    val token = bearerToken()
    if (token == null) {
        respond(HttpStatusCode.Unauthorized, ApiError("missing bearer token"))
        return null
    }
    val principal = tokens.resolve(sha256Hex(token))
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, ApiError("unknown token"))
        return null
    }
    if (!scopeCheck(principal.scope)) {
        respond(HttpStatusCode.Forbidden, ApiError("token scope does not permit this operation"))
        return null
    }
    return principal.project
}

/** Filter values are allowlisted against the schema enum names — never free text. */
private fun ApplicationCall.buildFilterOrNull(): BuildFilter? {
    val mode = request.queryParameters["mode"]?.uppercase()
    if (mode != null && mode !in BuildMode.entries.map { it.name }) return null
    val outcome = request.queryParameters["outcome"]?.uppercase()
    if (outcome != null && outcome !in BuildOutcome.entries.map { it.name }) return null
    // Benchmark builds pollute fleet p50/p95 trends (plan 030), so exclude them by default — unless
    // the caller explicitly asks for mode=benchmark or passes includeBenchmark=true.
    val includeBenchmark = request.queryParameters["includeBenchmark"].toBoolean()
    val excludeModes = if (mode == BuildMode.BENCHMARK.name || includeBenchmark) emptySet() else setOf(BuildMode.BENCHMARK.name)
    val tags = buildTagFilterOrNull() ?: return null
    return BuildFilter(branch = request.queryParameters["branch"], mode = mode, outcome = outcome, excludeModes = excludeModes, tags = tags)
}

/**
 * Parses `tag.<key>=<value>` params into the [BuildFilter.tags] equality map (plan 057); null when
 * a key/value exceeds the same char caps ingest enforces ([PayloadCaps] — single source of truth,
 * commons untouched) — the route rejects with 400 rather than silently truncating a filter, unlike
 * the ingest-side clamp. Also caps the number of distinct `tag.` params at [PayloadCaps.maxTags],
 * mirroring the same cap ingest's [dev.buildhound.commons.payload.PayloadCapper] enforces on a
 * payload's tag map — symmetric ceilings on both the write and the read/query path.
 */
private fun ApplicationCall.buildTagFilterOrNull(): Map<String, String>? {
    val caps = PayloadCaps.DEFAULT
    val tags = mutableMapOf<String, String>()
    for (name in request.queryParameters.names()) {
        if (!name.startsWith("tag.")) continue
        val key = name.removePrefix("tag.")
        val value = request.queryParameters[name] ?: continue
        if (key.isEmpty() || key.length > caps.maxKeyChars || value.length > caps.maxValueChars) return null
        tags[key] = value
    }
    if (tags.size > caps.maxTags) return null
    return tags
}

private val ingestLogger = LoggerFactory.getLogger("dev.buildhound.server.Ingest")

/** Reads at most [limit] bytes; null when Content-Length or the stream exceeds it. */
internal suspend fun ApplicationCall.receiveBounded(limit: Int): ByteArray? {
    request.header("Content-Length")?.toLongOrNull()?.let { declared ->
        if (declared > limit) return null
    }
    val channel = receiveChannel()
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        out.write(buffer, 0, read)
        if (out.size() > limit) return null
    }
    return out.toByteArray()
}

/** Bounded gunzip: null on corrupt input or when the decompressed size exceeds the cap. */
internal fun gunzipBounded(compressed: ByteArray, limit: Int = MAX_DECOMPRESSED_BYTES): ByteArray? =
    runCatching {
        GZIPInputStream(ByteArrayInputStream(compressed)).use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) return@use null
            }
            out.toByteArray()
        }
    }.getOrNull()
