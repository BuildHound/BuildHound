package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class IngestResponse(val buildId: String, val status: String)

@Serializable
data class ApiError(val error: String)

fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthResponse(status = "ok"))
    }
}

/**
 * `POST /v1/builds` (spec §5): token-authenticated, gzip, idempotent on buildId.
 * Scaffold covers payload validation + idempotency; auth, gzip, and async
 * post-processing land with phase 1.
 */
fun Route.ingestRoutes(store: BuildStore) {
    route("/v1") {
        post("/builds") {
            val payload = runCatching { call.receive<BuildPayload>() }.getOrElse { cause ->
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid payload: ${cause.message}"))
                return@post
            }
            if (payload.schemaVersion > BuildPayload.SCHEMA_VERSION) {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ApiError("unsupported schemaVersion ${payload.schemaVersion}"),
                )
                return@post
            }

            val stored = store.save(payload)
            call.respond(
                HttpStatusCode.Accepted,
                IngestResponse(buildId = payload.buildId, status = if (stored) "accepted" else "duplicate"),
            )
        }
    }
}
