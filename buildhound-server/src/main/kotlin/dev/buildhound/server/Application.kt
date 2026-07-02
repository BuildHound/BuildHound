package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
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
class ServerStores(val builds: BuildStore, val tokens: TokenStore)

/**
 * Per-token request ceilings (spec §8), per minute; 0 disables a limiter.
 * State is deliberately instance-local — see plan 013 / architecture §5.
 */
data class RateLimits(val ingestPerMinute: Int = 60, val queryPerMinute: Int = 120)

fun rateLimitsFromEnvironment(env: Map<String, String>): RateLimits = RateLimits(
    ingestPerMinute = env["BUILDHOUND_INGEST_RPM"]?.toIntOrNull() ?: 60,
    queryPerMinute = env["BUILDHOUND_QUERY_RPM"]?.toIntOrNull() ?: 120,
)

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
fun storesFromEnvironment(env: Map<String, String>): ServerStores {
    val logger = LoggerFactory.getLogger("dev.buildhound.server.Application")
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
        ServerStores(PostgresBuildStore(dataSource), PostgresTokenStore(dataSource))
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
    if (rateLimits.ingestPerMinute > 0 || rateLimits.queryPerMinute > 0) {
        install(RateLimit) {
            if (rateLimits.ingestPerMinute > 0) register(INGEST_LIMIT) { perTokenLimiter(rateLimits.ingestPerMinute) }
            if (rateLimits.queryPerMinute > 0) register(QUERY_LIMIT) { perTokenLimiter(rateLimits.queryPerMinute) }
        }
    }

    routing {
        healthRoutes()
        dashboardRoutes()
        maybeRateLimited(rateLimits.ingestPerMinute > 0, INGEST_LIMIT) { ingestRoutes(stores.builds, stores.tokens) }
        maybeRateLimited(rateLimits.queryPerMinute > 0, QUERY_LIMIT) { queryRoutes(stores.builds, stores.tokens) }
    }
}

/**
 * Buckets are keyed by the token's SHA-256 (the auth-lookup hash — the plaintext
 * never enters the limiter's key map). Credential-less or non-Bearer requests
 * share a per-remote-host bucket, so invalid-token floods are throttled before
 * they reach token resolution.
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
