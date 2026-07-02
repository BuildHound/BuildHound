package dev.buildhound.server

import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest

/** Tokens are compared by SHA-256 hex only — the plaintext never reaches a store or log. */
fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

fun ApplicationCall.bearerToken(): String? {
    val header = request.headers["Authorization"] ?: return null
    val prefix = "Bearer "
    if (!header.startsWith(prefix, ignoreCase = true)) return null
    return header.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}
