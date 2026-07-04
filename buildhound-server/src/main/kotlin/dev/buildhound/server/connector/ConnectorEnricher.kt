package dev.buildhound.server.connector

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * The connector framework's worker logic (plan 028): resolve config → poll the provider until the
 * build has finished → persist the normalized tree. Every failure path degrades to a stored
 * [CiRunStatus] + a `warn` log and **never throws** — a connector must not fail ingest (the plugin's
 * never-fail rule, mirrored server-side).
 *
 * Completion is read from the normalized model (`CiRun.finishedAt`), not a provider-specific status,
 * so the SPI stays minimal: while `finishedAt` is null the build is still running and we back off and
 * retry; after the budget we store whatever partial tree we have as `PENDING`.
 *
 * `sleep` and `backoff` are injected so tests drain deterministically without real waits.
 */
class ConnectorEnricher(
    private val registry: ConnectorRegistry,
    private val configs: ConnectorConfigStore,
    private val spans: CiSpanStore,
    private val maxPollAttempts: Int = 6,
    private val backoff: (attempt: Int) -> Long = { attempt -> minOf(5_000L shl (attempt - 1), 120_000L) },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val logger = LoggerFactory.getLogger("dev.buildhound.server.connector.Enrich")

    /** True when a registered connector can pull a timeline for this provider (ingest guard). */
    fun canEnrich(provider: String?): Boolean = registry.canEnrich(provider)

    suspend fun enrich(
        projectId: String,
        buildId: String,
        provider: String,
        runId: String,
        buildUrl: String?,
    ): CiRunStatus {
        val connector = registry.byProvider(provider)
        if (!connector.capabilities.contains(Capability.TIMELINE_PULL)) return CiRunStatus.UNCONFIGURED

        val config = configs.forProject(projectId, provider)
        if (config?.credential == null) {
            spans.saveRun(projectId, buildId, provider, runId, run = null, status = CiRunStatus.UNCONFIGURED)
            return CiRunStatus.UNCONFIGURED
        }

        val ref = connector.refFrom(provider, runId, buildUrl)
        return runCatching { poll(connector, ref, config, projectId, buildId, provider, runId) }
            .getOrElse {
                // Last-resort net for a failure outside the per-attempt loop (e.g. saveRun throws) —
                // still degraded, never propagated.
                logger.warn("enrichment failed for build {}: {}", buildId, it::class.java.simpleName)
                spans.saveRun(projectId, buildId, provider, runId, run = null, status = CiRunStatus.FAILED)
                CiRunStatus.FAILED
            }
    }

    private suspend fun poll(
        connector: CiConnector,
        ref: CiRunRef,
        config: ConnectorConfig,
        projectId: String,
        buildId: String,
        provider: String,
        runId: String,
    ): CiRunStatus {
        var last: CiRun? = null
        var attempt = 1
        while (attempt <= maxPollAttempts) {
            // A per-attempt exception is transient, treated exactly like a null/incomplete result:
            // log and retry with backoff. One bad attempt (attempt 1 of N) must NOT abort the whole
            // poll — that would give a thrown failure zero retries while a null gets the full budget.
            val run = runCatching { connector.fetchRun(ref, config) }.getOrElse {
                logger.warn("enrichment attempt {} for build {} failed: {}", attempt, buildId, it::class.java.simpleName)
                null
            }
            if (run != null) {
                last = run
                if (run.finishedAt != null) {
                    spans.saveRun(projectId, buildId, provider, runId, run, CiRunStatus.OK)
                    return CiRunStatus.OK
                }
            }
            if (attempt < maxPollAttempts) sleep(backoff(attempt))
            attempt++
        }
        // Budget exhausted: a still-running build keeps its partial tree as PENDING; a run we could
        // never fetch at all is FAILED. Neither ever surfaced as an ingest error.
        return if (last != null) {
            spans.saveRun(projectId, buildId, provider, runId, last, CiRunStatus.PENDING)
            CiRunStatus.PENDING
        } else {
            spans.saveRun(projectId, buildId, provider, runId, run = null, status = CiRunStatus.FAILED)
            CiRunStatus.FAILED
        }
    }
}
