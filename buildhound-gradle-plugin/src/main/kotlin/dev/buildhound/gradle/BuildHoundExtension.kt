package dev.buildhound.gradle

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * `buildhound { ... }` DSL (spec §3.4). Scaffold exposes the core knobs; nested blocks
 * (server, filters, identity, upload, ...) are added feature by feature per the roadmap.
 */
abstract class BuildHoundExtension {
    /** Master switch; telemetry is skipped entirely when false. */
    abstract val enabled: Property<Boolean>

    /** Base URL of the BuildHound server, e.g. `https://buildhound.dev`. Unset = offline/artifact-only. */
    abstract val serverUrl: Property<String>

    /** Ingest token. Wire from an environment variable provider, never hardcode. */
    abstract val serverToken: Property<String>

    abstract val mode: Property<TelemetryMode>

    /** Low-cardinality dimensions attached to every build, e.g. `tags.put("team", "mobile")`. */
    abstract val tags: MapProperty<String, String>
}

enum class TelemetryMode { AUTO, CI, LOCAL, DISABLED }
