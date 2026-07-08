package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Prometheus text-exposition shape, unit conversion, escaping, and the omit-not-zero rule (plan 070). */
class PrometheusExpositionTest {

    @Test
    fun `every present KPI gets a HELP, TYPE, and sample line with the project label`() {
        val snapshot = MetricsSnapshot(
            windowDays = 30,
            p50DurationMs = 5_000,
            p95DurationMs = 9_500,
            cacheHitRate = 0.5,
            successRate = 0.9,
            buildCountsByOutcome = mapOf("SUCCESS" to 9, "FAILED" to 1),
            flakyTestCount = 2,
            avoidedMs = 12_000,
        )
        val body = PrometheusExposition.render("acme", snapshot)

        assertTrue(body.contains("# HELP buildhound_build_duration_p50_seconds"))
        assertTrue(body.contains("# TYPE buildhound_build_duration_p50_seconds gauge"))
        assertTrue(body.contains("buildhound_build_duration_p50_seconds{project=\"acme\"} 5"), body)
        assertTrue(body.contains("buildhound_build_duration_p95_seconds{project=\"acme\"} 9.5"), body)
        assertTrue(body.contains("buildhound_cache_hit_rate{project=\"acme\"} 0.5"), body)
        assertTrue(body.contains("buildhound_build_success_rate{project=\"acme\"} 0.9"), body)
        assertTrue(body.contains("buildhound_flaky_tests{project=\"acme\"} 2"), body)
        assertTrue(body.contains("buildhound_avoided_seconds{project=\"acme\"} 12"), body)
        assertTrue(body.contains("buildhound_scrape_window_days{project=\"acme\"} 30"), body)
    }

    @Test
    fun `the build counter carries one sample per outcome under a single HELP+TYPE`() {
        val snapshot = MetricsSnapshot(windowDays = 7, buildCountsByOutcome = mapOf("SUCCESS" to 9, "FAILED" to 1))
        val body = PrometheusExposition.render("acme", snapshot)

        assertEquals(1, Regex("# TYPE buildhound_builds ").findAll(body).count(), "one TYPE line for the whole family")
        assertTrue(body.contains("buildhound_builds{project=\"acme\",outcome=\"FAILED\"} 1"), body)
        assertTrue(body.contains("buildhound_builds{project=\"acme\",outcome=\"SUCCESS\"} 9"), body)
    }

    @Test
    fun `windowed non-monotonic gauges are typed gauge, never counter`() {
        val snapshot = MetricsSnapshot(windowDays = 7, buildCountsByOutcome = mapOf("SUCCESS" to 1), flakyTestCount = 1)
        val body = PrometheusExposition.render("acme", snapshot)
        assertTrue(body.contains("# TYPE buildhound_builds gauge"))
        assertTrue(body.contains("# TYPE buildhound_flaky_tests gauge"))
        assertFalse(body.contains("counter"), "a windowed, non-monotonic value must never be typed counter")
    }

    @Test
    fun `omitted KPIs emit no line at all, never a zero`() {
        val empty = MetricsSnapshot(windowDays = 30)
        val body = PrometheusExposition.render("acme", empty)

        assertFalse(body.contains("buildhound_build_duration_p50_seconds"))
        assertFalse(body.contains("buildhound_build_duration_p95_seconds"))
        assertFalse(body.contains("buildhound_cache_hit_rate"))
        assertFalse(body.contains("buildhound_build_success_rate"))
        assertFalse(body.contains("buildhound_builds"))
        assertFalse(body.contains("buildhound_flaky_tests"))
        assertFalse(body.contains("buildhound_avoided_seconds"))
        // The window line always survives — a non-empty, valid body even for a brand-new project.
        assertTrue(body.contains("buildhound_scrape_window_days{project=\"acme\"} 30"), body)
    }

    @Test
    fun `a project key containing a backslash, quote, and newline is escaped in the label value`() {
        val snapshot = MetricsSnapshot(windowDays = 30, successRate = 1.0)
        val body = PrometheusExposition.render("weird\\\"name\n", snapshot)
        assertTrue(body.contains("project=\"weird\\\\\\\"name\\n\""), body)
    }

    @Test
    fun `escapeLabelValue backslash-escapes backslash and quote and newline-escapes newline`() {
        assertEquals("a\\\\b\\\"c\\nd", PrometheusExposition.escapeLabelValue("a\\b\"c\nd"))
    }

    @Test
    fun `durations convert milliseconds to seconds`() {
        val snapshot = MetricsSnapshot(windowDays = 30, p50DurationMs = 1_234, avoidedMs = 500)
        val body = PrometheusExposition.render("acme", snapshot)
        assertTrue(body.contains("buildhound_build_duration_p50_seconds{project=\"acme\"} 1.234"), body)
        assertTrue(body.contains("buildhound_avoided_seconds{project=\"acme\"} 0.5"), body)
    }
}
