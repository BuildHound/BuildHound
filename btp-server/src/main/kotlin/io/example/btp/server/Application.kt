package io.example.btp.server

import io.example.btp.commons.payload.BtpJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("BTP_PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::btpModule)
        .start(wait = true)
}

fun Application.btpModule() {
    install(CallLogging)
    install(ContentNegotiation) {
        json(BtpJson.payload)
    }

    // TODO(phase 1): Postgres + TimescaleDB via Flyway migrations, tenancy + token auth,
    // gzip request support, async post-processing (rollups, regression evaluation).
    val store: BuildStore = InMemoryBuildStore()

    routing {
        healthRoutes()
        ingestRoutes(store)
    }
}
