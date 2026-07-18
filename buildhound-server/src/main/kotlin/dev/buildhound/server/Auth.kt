package dev.buildhound.server

import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

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

/**
 * Starts the unactivated-token sweep on a daemon thread (plan 098), only from `main()` so
 * `testApplication` never spawns it — tests call [TokenStore.deleteExpiredUnactivatedTokens] directly.
 * [sweepMinutes] `<= 0` disables it entirely (logged, distinguishable), mirroring
 * [startRetentionSweeper]'s single-thread scheduled-executor pattern.
 */
fun startTokenSweeper(tokens: TokenStore, sweepMinutes: Long) {
    val logger = LoggerFactory.getLogger("dev.buildhound.server.Auth")
    if (sweepMinutes <= 0) {
        logger.info("token sweep: disabled (BUILDHOUND_TOKEN_SWEEP_MINUTES=0)")
        return
    }
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "buildhound-token-sweep").apply { isDaemon = true }
    }
    executor.scheduleAtFixedRate(
        {
            runCatching { tokens.deleteExpiredUnactivatedTokens() }
                .onSuccess { count -> if (count > 0) logger.info("token sweep: deleted {} unactivated token(s)", count) }
                .onFailure { logger.warn("token sweep failed", it) }
        },
        sweepMinutes, sweepMinutes, TimeUnit.MINUTES,
    )
    logger.info("token sweep: every {}m", sweepMinutes)
}
