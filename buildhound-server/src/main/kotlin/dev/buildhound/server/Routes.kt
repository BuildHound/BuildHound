package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable

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
fun Route.ingestRoutes(store: BuildStore, tokens: TokenStore) {
    route("/v1") {
        post("/builds") {
            val token = call.bearerToken()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("missing bearer token"))
            val project = tokens.resolveProject(sha256Hex(token))
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unknown token"))

            val raw = call.receive<ByteArray>()
            if (raw.size > MAX_COMPRESSED_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("payload too large"))
            }
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

            val stored = store.save(project.id, payload)
            call.respond(
                HttpStatusCode.Accepted,
                IngestResponse(buildId = payload.buildId, status = if (stored) "accepted" else "duplicate"),
            )
        }
    }
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
