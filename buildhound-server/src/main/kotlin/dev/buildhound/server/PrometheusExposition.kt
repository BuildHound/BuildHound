package dev.buildhound.server

import java.util.Locale

/**
 * Renders a [MetricsSnapshot] as Prometheus text exposition format 0.0.4 (plan 070, spec §5 F20) — pure,
 * no Ktor/DB. Labels carry only the operator-chosen, escaped `project` key plus a fixed low-cardinality
 * `outcome` enum (architecture §6 privacy: no paths, no hashed ids, no branch names).
 *
 * Omit-not-zero (F17): a `null` field in [MetricsSnapshot] emits **no line at all** for that metric —
 * never a spurious `0`, which a dashboard would misread as "broken" rather than "no data yet". The
 * result is always a syntactically valid (if short) exposition body, even for a brand-new project with
 * zero builds, so the scrape target never reads "down" — [windowDaysMetric] alone guarantees a non-empty,
 * always-present line.
 *
 * `buildhound_builds` and `buildhound_flaky_tests` are typed `gauge`, not `counter`: both are windowed
 * (non-monotonic) values that fall as builds/records age out of the window, and Prometheus `rate()`/
 * `increase()` treat a drop in a `counter` as a process restart — mistyping these would corrupt those
 * functions. The Grafana recipe under `deploy/grafana/` must not apply `rate()`/`increase()` to them.
 */
object PrometheusExposition {

    fun render(projectKey: String, snapshot: MetricsSnapshot): String {
        val label = "project=\"${escapeLabelValue(projectKey)}\""
        val sb = StringBuilder()

        snapshot.p50DurationMs?.let {
            sb.gauge("buildhound_build_duration_p50_seconds", "Build duration, p50 over the scrape window, in seconds.", label, msToSeconds(it))
        }
        snapshot.p95DurationMs?.let {
            sb.gauge("buildhound_build_duration_p95_seconds", "Build duration, p95 over the scrape window, in seconds.", label, msToSeconds(it))
        }
        snapshot.cacheHitRate?.let {
            sb.gauge("buildhound_cache_hit_rate", "Cacheable-task hit rate over the scrape window (0..1).", label, it)
        }
        snapshot.successRate?.let {
            sb.gauge("buildhound_build_success_rate", "Build success rate over the scrape window (0..1).", label, it)
        }
        if (snapshot.buildCountsByOutcome.isNotEmpty()) {
            sb.metric("buildhound_builds", "Build count in the scrape window, by outcome.", "gauge") {
                for ((outcome, count) in snapshot.buildCountsByOutcome.toSortedMap()) {
                    append("buildhound_builds{").append(label).append(",outcome=\"").append(escapeLabelValue(outcome))
                        .append("\"} ").append(count).append('\n')
                }
            }
        }
        snapshot.flakyTestCount?.let {
            sb.gauge("buildhound_flaky_tests", "Distinct flaky test units detected in the scrape window.", label, it.toDouble())
        }
        snapshot.avoidedMs?.let {
            sb.gauge("buildhound_avoided_seconds", "Cache/up-to-date time avoided over the scrape window, in seconds.", label, msToSeconds(it))
        }
        // Always present (plan 070 exit criteria): the window is request config, not a KPI, so it is
        // never omitted — and it doubles as a non-empty anchor line for an otherwise all-omitted project.
        sb.gauge("buildhound_scrape_window_days", "The window (days) these KPIs were computed over.", label, snapshot.windowDays.toDouble())

        return sb.toString()
    }

    private fun StringBuilder.gauge(name: String, help: String, label: String, value: Double) {
        metric(name, help, "gauge") {
            append(name).append('{').append(label).append("} ").append(formatDouble(value)).append('\n')
        }
    }

    private fun StringBuilder.metric(name: String, help: String, type: String, body: StringBuilder.() -> Unit) {
        append("# HELP ").append(name).append(' ').append(help).append('\n')
        append("# TYPE ").append(name).append(' ').append(type).append('\n')
        body()
    }

    private fun msToSeconds(ms: Long): Double = ms / 1000.0

    /**
     * Prometheus label-value escaping (exposition format spec): backslash and double-quote are
     * backslash-escaped, newline becomes a literal `\n` — order matters (backslash first, or the
     * escaped quote's backslash would itself be re-escaped).
     */
    internal fun escapeLabelValue(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Whole numbers render without a trailing `.0`; fractional values use a fixed, locale-safe scale. */
    private fun formatDouble(value: Double): String {
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        if (rounded == Math.floor(rounded) && !rounded.isInfinite()) return rounded.toLong().toString()
        return String.format(Locale.ROOT, "%.6f", rounded).trimEnd('0').trimEnd('.')
    }
}
