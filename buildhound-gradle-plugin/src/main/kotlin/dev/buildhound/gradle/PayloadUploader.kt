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
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        // A redirect must never re-send the token to another origin.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Uploads the current build's payload; spools it on any failure. */
    fun uploadOrSpool(buildId: String, payloadJson: String) {
        val body = gzip(payloadJson.encodeToByteArray())
        if (post(body)) {
            logger.lifecycle("[buildhound] payload uploaded ({} bytes gzip)", body.size)
        } else {
            spool(buildId, body)
        }
    }

    /** Retries previously spooled payloads, oldest first, bounded per build. */
    fun drainSpool(maxFiles: Int = 10) {
        val spooled = runCatching {
            spoolDir.listFiles { file -> file.name.endsWith(".json.gz") }?.sortedBy { it.lastModified() }
        }.getOrNull().orEmpty()
        if (spooled.isEmpty()) return
        var sent = 0
        for (file in spooled.take(maxFiles)) {
            val body = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (!post(body)) break // server still unreachable; keep the rest for next time
            runCatching { file.delete() }
            sent++
        }
        if (sent > 0) logger.lifecycle("[buildhound] drained {} spooled payload(s)", sent)
    }

    private fun post(gzipBody: ByteArray): Boolean = runCatching {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/builds"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .apply { token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(gzipBody))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    }.onFailure {
        // Class name only: connection failures can embed hosts/paths, never a token though.
        logger.info("[buildhound] upload attempt failed: {}", it::class.java.simpleName)
    }.getOrDefault(false)

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

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }
    }
}
