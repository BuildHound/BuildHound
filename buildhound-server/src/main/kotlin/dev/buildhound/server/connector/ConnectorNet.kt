package dev.buildhound.server.connector

import java.net.URI
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shared connector networking + JSON helpers (plan 041). [isAllowedHost] is the one security-critical
 * function every connector shares: the outbound base URL must be `https` **and** its host must be in
 * the per-config allowlist, so an ingested build URL can select *which* configured org is dialled but
 * never introduce a new outbound host. Single-sourced here so the three connectors cannot drift.
 */
internal fun isAllowedHost(baseUrl: String, allowed: Set<String>): Boolean {
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host ?: return false
    return allowed.any { it.equals(host, ignoreCase = true) }
}

/** A string/number JSON field as text (a numeric id returns its literal); null if absent or complex. */
internal fun JsonObject.strField(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** An ISO-8601 timestamp (either `…Z` or a numeric offset) to epoch millis; null on absence/parse miss. */
internal fun JsonObject.offsetMs(key: String): Long? =
    strField(key)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
