package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/** Everything the module needs; assembled from env in [main], hand-built in tests. */
class ServerStores(val builds: BuildStore, val tokens: TokenStore)

fun main() {
    val port = System.getenv("BUILDHOUND_PORT")?.toIntOrNull() ?: 8080
    val stores = storesFromEnvironment(System.getenv())
    embeddedServer(Netty, port = port, host = "0.0.0.0") { buildHoundModule(stores) }
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

fun Application.buildHoundModule(stores: ServerStores = ServerStores(InMemoryBuildStore(), InMemoryTokenStore())) {
    install(CallLogging)
    install(ContentNegotiation) {
        json(BuildHoundJson.payload)
    }

    routing {
        healthRoutes()
        ingestRoutes(stores.builds, stores.tokens)
    }
}
