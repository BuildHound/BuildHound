package dev.buildhound.gradle

import java.io.Serializable

/**
 * Where one `Test` task writes its JUnit XML, captured at configuration time (plan 024).
 * Serializable so it rides the [TaskEventCollector] service parameter map like [TaskMetadata]
 * (plan 016) — replayed verbatim on a configuration-cache hit, when the `whenReady` callback
 * that built it never runs.
 */
data class TestResultLocations(
    val junitXmlDir: String,
    val module: String?,
) : Serializable
