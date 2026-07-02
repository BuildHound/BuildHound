package io.example.btp.commons.payload

import kotlinx.serialization.json.Json

/**
 * The one Json configuration both plugin and server must use for the wire format.
 * `ignoreUnknownKeys` is the additive-schema guarantee: an old server must accept
 * payloads from a newer plugin (spec cross-phase guardrails).
 */
object BtpJson {
    val payload: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}
