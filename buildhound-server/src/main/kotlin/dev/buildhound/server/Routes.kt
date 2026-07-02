package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.readAvailable
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
fun Route.ingestRoutes(store: BuildStore, tokens: TokenStore) {
    route("/v1") {
        post("/builds") {
            val token = call.bearerToken()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("missing bearer token"))
            val project = tokens.resolveProject(sha256Hex(token))
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unknown token"))

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

            // Data-shaped failures (SQLSTATE 22xxx, e.g. \u0000 in jsonb) are permanent →
            // 400 so the plugin drops them; anything else is a storage outage → 503 so
            // the plugin spools and retries (its 4xx/5xx classification relies on this).
            val stored = try {
                store.save(project.id, payload)
            } catch (e: SQLException) {
                val permanent = e.sqlState?.startsWith("22") == true
                return@post if (permanent) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("payload not storable"))
                } else {
                    ingestLogger.warn("storage unavailable: {}", e::class.java.simpleName)
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("storage unavailable"))
                }
            }
            call.respond(
                HttpStatusCode.Accepted,
                IngestResponse(buildId = payload.buildId, status = if (stored) "accepted" else "duplicate"),
            )
        }
    }
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
