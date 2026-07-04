package dev.buildhound.server.connector

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Bounded, in-process enrichment queue (plan 028). Single-worker by construction
 * (`Dispatchers.IO.limitedParallelism(1)`) so connector fetches never fan out and hammer a provider —
 * the same instance-local posture as the rate limiter (architecture §5); N replicas each run one
 * worker and `saveRun` idempotency keeps the stored result consistent.
 *
 * [submit] is fire-and-forget from the ingest fast path: it no-ops for a non-enrichable provider or a
 * missing run id, sheds load past [capacity], and can never throw into the caller. [drain] lets
 * `testApplication` await all in-flight work deterministically.
 *
 * `ci.provider`/`ci.runId`/`ci.buildUrl` are attacker-controlled ingest fields, so a hostile tenant
 * could flood fabricated `azure-devops` builds to monopolize the global queue and grind the shared
 * connector PAT against the victim org's API. [perProjectCap] bounds concurrent enrichments **per
 * project** (fairness + amplification cap); excess is dropped + logged, never queued.
 */
class EnrichmentQueue(
    private val enricher: ConnectorEnricher,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob()),
    private val capacity: Int = 512,
    private val perProjectCap: Int = 32,
) {
    private val active = ConcurrentLinkedQueue<Job>()
    private val inFlightByProject = ConcurrentHashMap<String, Int>()
    private val logger = LoggerFactory.getLogger("dev.buildhound.server.connector.EnrichmentQueue")

    fun submit(projectId: String, buildId: String, provider: String?, runId: String?, buildUrl: String?) {
        if (provider == null || runId == null || !enricher.canEnrich(provider)) return
        enqueue(projectId, label = buildId) { enricher.enrich(projectId, buildId, provider, runId, buildUrl) }
    }

    /**
     * Expected-build check (plan 033): a `build.complete` hook fired for a run with no ingested
     * payload. Same bounded/per-project-capped single worker as [submit]; it confirms the run
     * completed on the Timeline and records an INTERRUPTED build. Fire-and-forget, never throws.
     */
    fun submitExpectedBuildCheck(projectId: String, provider: String?, runId: String?, buildUrl: String?) {
        if (provider == null || runId == null || !enricher.canEnrich(provider)) return
        enqueue(projectId, label = "interrupted:$provider:$runId") {
            enricher.checkExpectedBuild(projectId, provider, runId, buildUrl)
        }
    }

    /** Shared load-shedding + per-project fairness guard + job tracking for both submit paths. */
    private fun enqueue(projectId: String, label: String, work: suspend () -> Unit) {
        active.removeAll { it.isCompleted }
        if (active.size >= capacity) {
            logger.warn("enrichment queue full ({}); dropping {}", capacity, label)
            return
        }
        // Reserve a per-project slot atomically; roll back and drop if the tenant is over its cap.
        if (inFlightByProject.merge(projectId, 1, Int::plus)!! > perProjectCap) {
            inFlightByProject.merge(projectId, -1) { old, delta -> (old + delta).takeIf { it > 0 } }
            logger.warn("enrichment per-project cap ({}) reached for project; dropping {}", perProjectCap, label)
            return
        }
        val job = scope.launch {
            try {
                runCatching { work() }
                    .onFailure { logger.warn("enrichment worker crashed for {}: {}", label, it::class.java.simpleName) }
            } finally {
                inFlightByProject.merge(projectId, -1) { old, delta -> (old + delta).takeIf { it > 0 } }
            }
        }
        active.add(job)
    }

    /** Await every submitted job — a test-only barrier so route tests read a settled ci-run. */
    suspend fun drain() {
        while (true) {
            val job = active.poll() ?: break
            job.join()
        }
    }
}
