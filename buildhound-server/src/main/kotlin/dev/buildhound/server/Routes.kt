package dev.buildhound.server

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.PayloadCapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.util.getOrFail
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
            call.respond(
                HttpStatusCode.Accepted,
                IngestResponse(buildId = payload.buildId, status = if (stored) "accepted" else "duplicate"),
            )
        }
    }
}

/**
 * Query API (plan 010): same token, same tenant scoping as ingest. Rollups are
 * computed on read over the indexed hot columns; materialized aggregates come when
 * volume demands them.
 */
fun Route.queryRoutes(store: BuildStore, tokens: TokenStore) {
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

        get("/trends") {
            val project = call.authenticatedProject(tokens, TokenScope::allowsRead) ?: return@get
            val filter = call.buildFilterOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("invalid mode/outcome filter"))
            val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 30).coerceIn(1, 365)
            call.respondQuery { store.trends(project.id, filter, days, System.currentTimeMillis()) }
        }
    }
}

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
    return BuildFilter(branch = request.queryParameters["branch"], mode = mode, outcome = outcome)
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
