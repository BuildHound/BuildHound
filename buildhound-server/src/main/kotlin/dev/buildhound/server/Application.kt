package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.server.connector.AzureDevOpsConnector
import dev.buildhound.server.connector.CiSpanStore
import dev.buildhound.server.connector.ConnectorConfigStore
import dev.buildhound.server.connector.ConnectorEnricher
import dev.buildhound.server.connector.ConnectorHttpClient
import dev.buildhound.server.connector.ConnectorRegistry
import dev.buildhound.server.connector.EnrichmentQueue
import dev.buildhound.server.connector.EnvConnectorConfigStore
import dev.buildhound.server.connector.InMemoryCiSpanStore
import dev.buildhound.server.connector.PostgresCiSpanStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimitProviderConfig
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory

/** Everything the module needs; assembled from env in [main], hand-built in tests. */
class ServerStores(
    val builds: BuildStore,
    val tokens: TokenStore,
    val metrics: MetricStore = InMemoryMetricStore(),
    val verdicts: VerdictStore = InMemoryVerdictStore(),
    val settings: SettingsStore = InMemorySettingsStore(),
    val alerts: AlertDispatcher = RecordingAlertDispatcher(),
    val dashboardBaseUrl: String? = null,
    // Addon foundation (plan 039): generic jsonb store + the server-side allowlist of registered
    // addon ids. Empty by default — every `/v1/addons/{id}` id 404s until a consumer (plan 040)
    // registers one; core boots and serves builds with zero addons active.
    val addons: AddonStore = InMemoryAddonStore(),
    val registeredAddons: Set<String> = emptySet(),
    // Test-sharding addon (plan 040): idempotent LPT shard-plan memo.
    val shardPlans: ShardPlanStore = InMemoryShardPlanStore(),
    // CI-connector framework (plan 028). Defaults are the honest no-op: no registered connector →
    // nothing enriches, ci-run reads 404, ingest is unchanged. Production wires the Azure connector.
    val ciSpans: CiSpanStore = InMemoryCiSpanStore(),
    val connectors: ConnectorRegistry = ConnectorRegistry(emptyList()),
    val connectorConfigs: ConnectorConfigStore = EnvConnectorConfigStore(emptyMap()),
    enrichment: EnrichmentQueue? = null,
) {
    /** Bounded single-worker enrichment queue; built from the connector wiring unless injected (tests). */
    val enrichment: EnrichmentQueue =
        enrichment ?: EnrichmentQueue(
            ConnectorEnricher(
                connectors, connectorConfigs, ciSpans,
                // Expected-build fallback (plan 033): a completed-but-never-ingested CI run is recorded
                // as an idempotent, tenant-scoped INTERRUPTED build so it stops vanishing.
                recordInterrupted = { projectId, provider, runId, run ->
                    InterruptedBuild.recordIfMissing(builds, projectId, provider, runId, run)
                },
            ),
        )
}

/**
 * Request ceilings (spec §8), per minute; 0 disables a limiter. The per-host limiter
 * is the outer coarse layer: per-token buckets alone can't stop a rotating-token
 * flood (every garbage token would mint its own fresh bucket and still reach token
 * resolution), so the host layer caps what any single source can do — including
 * bucket-minting itself. State is deliberately instance-local — plan 013 / arch §5.
 */
data class RateLimits(
    val ingestPerMinute: Int = 60,
    val queryPerMinute: Int = 120,
    val perHostPerMinute: Int = 600,
)

fun rateLimitsFromEnvironment(env: Map<String, String>): RateLimits {
    // Invalid values (non-numeric, negative) fall back to the default with a warning —
    // a typo must never silently disable limiting; only an explicit 0 does.
    fun rpm(key: String, default: Int): Int {
        val raw = env[key] ?: return default
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed < 0) {
            applicationLogger.warn("{}='{}' is not a non-negative integer — using default {}", key, raw, default)
            return default
        }
        return parsed
    }
    return RateLimits(
        ingestPerMinute = rpm("BUILDHOUND_INGEST_RPM", 60),
        queryPerMinute = rpm("BUILDHOUND_QUERY_RPM", 120),
        perHostPerMinute = rpm("BUILDHOUND_HOST_RPM", 600),
    )
}

fun main() {
    val env = System.getenv()
    val port = env["BUILDHOUND_PORT"]?.toIntOrNull() ?: 8080
    val stores = storesFromEnvironment(env)
    val rateLimits = rateLimitsFromEnvironment(env)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { buildHoundModule(stores, rateLimits) }
        .start(wait = true)
}

/**
 * `BUILDHOUND_DB_URL/DB_USER/DB_PASSWORD` select Postgres (Flyway-migrated on boot);
 * without them the server runs in-memory (dev/smoke only — data is lost on restart).
 * Bootstrap tenancy (plan 009): `BUILDHOUND_BOOTSTRAP_PROJECT` + `_TOKEN` create the
 * pilot project + token hash idempotently. Without any token source, ingest is
 * 401-everything — fail closed.
 */
private val applicationLogger = LoggerFactory.getLogger("dev.buildhound.server.Application")

fun storesFromEnvironment(env: Map<String, String>): ServerStores {
    val logger = applicationLogger
    val dbUrl = env["BUILDHOUND_DB_URL"]
    val stores = if (dbUrl != null) {
        val dataSource = createDataSource(
            jdbcUrl = dbUrl,
            user = env["BUILDHOUND_DB_USER"] ?: "buildhound",
            password = requireNotNull(env["BUILDHOUND_DB_PASSWORD"]) {
                "BUILDHOUND_DB_PASSWORD is required when BUILDHOUND_DB_URL is set"
            },
        )
        migrate(dataSource)
        logger.info("storage: postgres ({})", dbUrl.substringBefore('?'))
        ServerStores(
            builds = PostgresBuildStore(dataSource),
            tokens = PostgresTokenStore(dataSource),
            metrics = PostgresMetricStore(dataSource),
            verdicts = PostgresVerdictStore(dataSource),
            settings = PostgresSettingsStore(dataSource),
            // The server's only outbound caller — real HTTP in prod; https-only enforced in the dispatcher.
            alerts = HttpAlertDispatcher(),
            dashboardBaseUrl = env["BUILDHOUND_DASHBOARD_URL"],
            // CI connector framework (plan 028): ship the Azure connector; credentials + host allowlist
            // come from env only (UNCONFIGURED and inert until a PAT is set).
            ciSpans = PostgresCiSpanStore(dataSource),
            connectors = ConnectorRegistry(listOf(AzureDevOpsConnector(ConnectorHttpClient.create()))),
            connectorConfigs = EnvConnectorConfigStore(env),
            // Addon foundation (plan 039): jsonb store present, allowlist empty until a consumer ships.
            addons = PostgresAddonStore(dataSource),
            // Test-sharding addon (plan 040): idempotent shard-plan memo.
            shardPlans = PostgresShardPlanStore(dataSource),
        )
    } else {
        logger.warn("storage: IN-MEMORY (no BUILDHOUND_DB_URL) — data is lost on restart")
        ServerStores(InMemoryBuildStore(), InMemoryTokenStore())
    }

    // BUILDHOUND_DEV_TOKEN is an in-memory-only convenience: in DB mode a stray dev
    // env var must never silently persist a (likely weak) credential (review finding).
    val devToken = env["BUILDHOUND_DEV_TOKEN"].takeIf { dbUrl == null }
    val bootstrapToken = env["BUILDHOUND_BOOTSTRAP_TOKEN"] ?: devToken
    val bootstrapProject = env["BUILDHOUND_BOOTSTRAP_PROJECT"] ?: "dev".takeIf { devToken != null }
    if (bootstrapProject != null && !bootstrapToken.isNullOrBlank()) {
        stores.tokens.ensureProjectWithToken(bootstrapProject, sha256Hex(bootstrapToken))
        logger.info("bootstrap project '{}' ready (token hash stored)", bootstrapProject)
    } else if (dbUrl != null) {
        logger.info("no bootstrap configured — relying on tokens already present in the database")
    } else {
        logger.warn("no token configured — ingest will reject all requests")
    }
    return stores
}

private val HOST_LIMIT = RateLimitName("per-host")
private val INGEST_LIMIT = RateLimitName("ingest")
private val QUERY_LIMIT = RateLimitName("query")

fun Application.buildHoundModule(
    stores: ServerStores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore()),
    rateLimits: RateLimits = RateLimits(),
) {
    install(CallLogging)
    install(ContentNegotiation) {
        json(BuildHoundJson.payload)
    }
    val hostOn = rateLimits.perHostPerMinute > 0
    val ingestOn = rateLimits.ingestPerMinute > 0
    val queryOn = rateLimits.queryPerMinute > 0
    if (hostOn || ingestOn || queryOn) {
        // NOTE: the limiter's clock is NOT injectable here on purpose. Ktor 3.2.2's
        // key-eviction coroutine compares the limiter's refillAt against real wall
        // time (io.ktor.util.date.getTimeMillis, hardwired), so a fake clock makes
        // eviction fire instantly and silently disables throttling.
        install(RateLimit) {
            if (hostOn) register(HOST_LIMIT) {
                rateLimiter(limit = rateLimits.perHostPerMinute, refillPeriod = 60.seconds)
                requestKey { call -> "h:" + call.request.origin.remoteHost }
            }
            if (ingestOn) register(INGEST_LIMIT) { perTokenLimiter(rateLimits.ingestPerMinute) }
            if (queryOn) register(QUERY_LIMIT) { perTokenLimiter(rateLimits.queryPerMinute) }
        }
    }
    // "Limiting off" must be distinguishable from "limiting on" in the logs.
    applicationLogger.info(
        "rate limits/min: ingest={} query={} per-host={} (0 = disabled)",
        rateLimits.ingestPerMinute, rateLimits.queryPerMinute, rateLimits.perHostPerMinute,
    )

    // Post-ingest regression evaluation (plan 025); never blocks or fails ingest.
    val evaluator = VerdictEvaluator(
        builds = stores.builds,
        metrics = stores.metrics,
        verdicts = stores.verdicts,
        settings = stores.settings,
        alerts = stores.alerts,
        dashboardBaseUrl = stores.dashboardBaseUrl,
    )
    // Post-ingest flaky-alert hook (plan 036); edge-triggered, never blocks or fails ingest.
    val flakyAlerter = FlakyAlerter(
        builds = stores.builds,
        settings = stores.settings,
        alerts = stores.alerts,
        dashboardBaseUrl = stores.dashboardBaseUrl,
    )

    routing {
        healthRoutes()
        dashboardRoutes()
        // Nested limiters compose (each rateLimit() collects its ancestors' providers):
        // the host layer sees every /v1 request first, then the per-token layer.
        maybeRateLimited(hostOn, HOST_LIMIT) {
            maybeRateLimited(ingestOn, INGEST_LIMIT) {
                ingestRoutes(stores.builds, stores.tokens, evaluator, flakyAlerter, stores.enrichment)
                metricsRoutes(stores.builds, stores.metrics, stores.tokens)
                connectorHookRoutes(stores.builds, stores.tokens, stores.connectors, stores.enrichment)
                // Test-sharding plan (plan 040): a CI POST with ingest scope — shares the ingest limiter.
                testShardingRoutes(stores.builds, stores.shardPlans, stores.tokens)
            }
            maybeRateLimited(queryOn, QUERY_LIMIT) {
                queryRoutes(stores.builds, stores.verdicts, stores.tokens, stores.ciSpans)
                settingsRoutes(stores.settings, stores.tokens)
                // Addon API namespace (plan 039): shares the query limiter — no new flood vector.
                addonRoutes(stores.addons, stores.tokens, stores.registeredAddons)
            }
        }
    }
}

/**
 * Buckets are keyed by the token's SHA-256 (the auth-lookup hash — the plaintext
 * never enters the limiter's key map). Credential-less or non-Bearer requests
 * share a per-remote-host bucket. A rotating-token flood mints a fresh bucket per
 * request and is NOT stopped by this layer — that's the outer [HOST_LIMIT]'s job.
 */
private fun RateLimitProviderConfig.perTokenLimiter(perMinute: Int) {
    rateLimiter(limit = perMinute, refillPeriod = 60.seconds)
    requestKey { call ->
        call.bearerToken()?.let { "t:" + sha256Hex(it) } ?: ("h:" + call.request.origin.remoteHost)
    }
}

private fun Route.maybeRateLimited(enabled: Boolean, name: RateLimitName, build: Route.() -> Unit) {
    if (enabled) rateLimit(name) { build() } else build()
}
