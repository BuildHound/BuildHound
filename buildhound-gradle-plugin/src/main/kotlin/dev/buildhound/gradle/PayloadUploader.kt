package dev.buildhound.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPOutputStream
import org.gradle.api.logging.Logging

/**
 * Synchronous gzip upload with a spool fallback (spec §5): one attempt per payload,
 * failures land in `build/buildhound/spool/` and are retried (oldest first) by the
 * next build's finalizer. The server dedupes on buildId, so retries are idempotent.
 * Never throws out of [uploadOrSpool]/[drainSpool]; token never appears in logs.
 */
internal class PayloadUploader(
    private val baseUrl: String,
    private val token: String?,
    private val spoolDir: File,
    private val timeout: Duration = Duration.ofSeconds(15),
) : AutoCloseable {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        // A redirect must never re-send the token to another origin.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        // Loopback http is normal for compose/dev; anything else cleartexts the token.
        val host = runCatching { URI.create(baseUrl).host }.getOrNull()
        if (baseUrl.startsWith("http://", ignoreCase = true) &&
            host !in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        ) {
            logger.warn("[buildhound] server url uses plaintext http — the ingest token and telemetry are unencrypted")
        }
    }

    private enum class Outcome { SENT, REJECTED, UNREACHABLE }

    override fun close() {
        runCatching { client.close() }
    }

    /**
     * Uploads the current build's payload. Transient failures (transport, 5xx, 408/429)
     * spool for the next build; permanent rejections (other 4xx: bad token, bad payload)
     * are dropped with a warning — retrying them can never succeed.
     */
    /** Spool without attempting a send (uploadInBackground, plan 027) — the next build's drain sends it. */
    fun spoolDirectly(buildId: String, payloadJson: String) {
        spool(buildId, gzip(payloadJson.encodeToByteArray()))
    }

    fun uploadOrSpool(buildId: String, payloadJson: String) {
        val body = gzip(payloadJson.encodeToByteArray())
        when (post(body)) {
            Outcome.SENT -> logger.lifecycle("[buildhound] payload uploaded ({} bytes gzip)", body.size)
            Outcome.UNREACHABLE -> spool(buildId, body)
            Outcome.REJECTED ->
                logger.warn("[buildhound] server rejected the payload (4xx) — dropped, check token/config")
        }
    }

    /**
     * Retries previously spooled payloads, oldest first, bounded per build. A rejected
     * (4xx) file is deleted and must not block younger files — only an unreachable
     * server stops the drain.
     */
    fun drainSpool(maxFiles: Int = 10) {
        val spooled = runCatching {
            spoolDir.listFiles { file -> file.name.endsWith(".json.gz") }?.sortedBy { it.lastModified() }
        }.getOrNull().orEmpty()
        if (spooled.isEmpty()) return
        var sent = 0
        for (file in spooled.take(maxFiles)) {
            if (file.length() > MAX_SPOOL_FILE_BYTES) {
                runCatching { file.delete() } // never load an absurd file into daemon heap
                continue
            }
            val body = runCatching { file.readBytes() }.getOrNull() ?: continue
            when (post(body)) {
                Outcome.SENT -> {
                    runCatching { file.delete() }
                    sent++
                }
                Outcome.REJECTED -> {
                    runCatching { file.delete() }
                    logger.warn("[buildhound] server rejected spooled payload {} — dropped", file.name)
                }
                Outcome.UNREACHABLE -> return // keep the rest for next time
            }
        }
        if (sent > 0) logger.lifecycle("[buildhound] drained {} spooled payload(s)", sent)
    }

    private fun post(gzipBody: ByteArray): Outcome = runCatching {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/builds"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .apply { token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(gzipBody))
            .build()
        when (val code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) {
            in 200..299 -> Outcome.SENT
            408, 429 -> Outcome.UNREACHABLE // retryable server states
            in 400..499 -> Outcome.REJECTED
            else -> {
                logger.info("[buildhound] upload attempt got {}", code)
                Outcome.UNREACHABLE
            }
        }
    }.onFailure {
        // Class name only: connection failures can embed hosts/paths, never a token though.
        logger.info("[buildhound] upload attempt failed: {}", it::class.java.simpleName)
    }.getOrDefault(Outcome.UNREACHABLE)

    private fun spool(buildId: String, gzipBody: ByteArray) {
        runCatching {
            spoolDir.mkdirs()
            File(spoolDir, "$buildId.json.gz").writeBytes(gzipBody)
            trimSpool()
            logger.warn(
                "[buildhound] upload failed; payload spooled to {} (retried on the next build)",
                spoolDir.toRelativeStringOrSelf(),
            )
        }.onFailure {
            logger.warn("[buildhound] upload failed and spooling failed: {}", it::class.java.simpleName)
        }
    }

    /** The spool must never grow unbounded on a long-dead server. */
    private fun trimSpool(maxFiles: Int = 20) {
        val files = spoolDir.listFiles { file -> file.name.endsWith(".json.gz") } ?: return
        files.sortedBy { it.lastModified() }
            .dropLast(maxFiles)
            .forEach { runCatching { it.delete() } }
    }

    private fun File.toRelativeStringOrSelf(): String =
        runCatching { relativeToOrSelf(File(".").absoluteFile.parentFile) }.getOrNull()?.path ?: path

    internal companion object {
        val logger = Logging.getLogger(PayloadUploader::class.java)
        const val MAX_SPOOL_FILE_BYTES: Long = 8L * 1024 * 1024

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }
    }
}
