package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.TestUnitKey
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Post-ingest flaky-alert hook (plan 036): after a test-carrying build stores, re-run detection and
 * fire a plan-025 `FLAKY` alert for any class that is **newly** flaky. Edge-triggered per
 * (project, class) via an instance-local set — the same instance-local posture as the rate limiter
 * (architecture §5); a restart re-alerts each still-flaky class once, which is acceptable and avoids a
 * new stateful table. Wrapped so a detection/alert failure can never fail or block ingest (the
 * server-side never-fail rule).
 */
class FlakyAlerter(
    private val builds: BuildStore,
    private val settings: SettingsStore,
    private val alerts: AlertDispatcher,
    private val dashboardBaseUrl: String?,
    private val windowDays: Int = 30,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    // (projectId | unitKey) already alerted this process → the edge-trigger, no per-build storms.
    private val alerted = ConcurrentHashMap.newKeySet<String>()

    fun evaluate(projectId: String, projectKey: String, payload: BuildPayload) {
        if (payload.tests.isEmpty()) return // only builds that carried tests can change flakiness
        runCatching {
            val cfg = settings.get(projectId) ?: ProjectSettings()
            if (cfg.alertChannels.isEmpty()) return@runCatching
            for (record in builds.flaky(projectId, windowDays, nowMs())) {
                val key = "$projectId|" + TestUnitKey.of(record.module, record.className)
                // add() is true only the first time → one alert per class per process (edge-triggered).
                if (alerted.add(key)) {
                    alerts.dispatch(cfg.alertChannels, FlakyAlert(projectKey, record, dashboardBaseUrl))
                }
            }
        }.onFailure { logger.warn("flaky evaluation failed (ingest unaffected): {}", it::class.java.simpleName) }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("dev.buildhound.server.Flaky")
    }
}
