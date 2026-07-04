package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import org.slf4j.LoggerFactory

/**
 * Post-ingest regression evaluation (plan 025). Runs after a successful `store.save`, wrapped so it
 * can never block or fail ingest (the never-block contract): any failure leaves the verdict simply
 * absent. Loads settings + the rolling baseline window, judges the build via [RegressionEngine],
 * persists the verdict, and dispatches an alert on a *fresh* FAIL only (no repeat-spam).
 */
class VerdictEvaluator(
    private val builds: BuildStore,
    private val metrics: MetricStore,
    private val verdicts: VerdictStore,
    private val settings: SettingsStore,
    private val alerts: AlertDispatcher,
    private val dashboardBaseUrl: String?,
) {
    fun evaluate(projectId: String, projectKey: String, payload: BuildPayload) {
        runCatching {
            val cfg = settings.get(projectId) ?: ProjectSettings()
            val sig = RegressionEngine.requestedTasksSignature(payload.requestedTasks)
            val query = BaselineQuery(payload.ci?.pipelineName, sig, payload.mode.name)
            val branchClass = RegressionEngine.branchClass(payload.vcs?.branch, cfg.defaultBranch)
            val baselineKey = listOf(query.pipelineName ?: "", sig, branchClass, query.mode).joinToString("|")

            // Baseline is always the default-branch window (PR-vs-baseline); the candidate is excluded.
            val window = builds.baselineWindow(projectId, cfg.defaultBranch, query, payload.buildId, cfg.baselineN)
            val baselines = mapOf(
                "durationMs" to window.map { it.durationMs.toDouble() },
                "cacheableHitRate" to window.mapNotNull { it.hitRate },
            )
            // Lazily attach any metrics that arrived before this build did (null build_id).
            metrics.correlate(projectId, payload.ci?.provider, payload.ci?.runId, payload.buildId)
            // Built-in metrics + numeric custom metrics correlated to this build (v1: custom metrics
            // are budget-checked; their rolling baselines arrive with the rollup family, plan 026).
            val custom = metrics.forBuild(projectId, payload.buildId)
                .mapNotNull { record -> record.value?.let { MetricInput(record.name, it, MetricDirection.HIGHER_BAD) } }
            val inputs = RegressionEngine.builtInMetrics(payload) + custom

            val verdict = RegressionEngine.evaluate(inputs, baselines, cfg, baselineKey)
            val prior = verdicts.latestStatusForKey(projectId, baselineKey, payload.buildId)
            verdicts.save(projectId, payload.buildId, verdict)

            if (verdict.status == VerdictStatus.FAIL.name && prior != VerdictStatus.FAIL.name) {
                alerts.dispatch(
                    cfg.alertChannels,
                    VerdictAlert(projectKey, payload.buildId, baselineKey, verdict, dashboardBaseUrl),
                )
            }
        }.onFailure {
            logger.warn("verdict evaluation failed (ingest unaffected): {}", it::class.java.simpleName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("dev.buildhound.server.Verdict")
    }
}
