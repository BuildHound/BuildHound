package dev.buildhound.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * `buildhound { ... }` DSL (spec §3.4). Scaffold exposes the core knobs; nested blocks
 * (server, filters, upload, ...) are added feature by feature per the roadmap.
 */
abstract class BuildHoundExtension @Inject constructor(objects: ObjectFactory) {
    /** Master switch; telemetry is skipped entirely when false. */
    abstract val enabled: Property<Boolean>

    abstract val mode: Property<TelemetryMode>

    /** Low-cardinality dimensions attached to every build, e.g. `tags.put("team", "mobile")`. */
    abstract val tags: MapProperty<String, String>

    val identity: IdentitySpec = objects.newInstance(IdentitySpec::class.java)

    fun identity(action: Action<IdentitySpec>) = action.execute(identity)

    val htmlReport: HtmlReportSpec = objects.newInstance(HtmlReportSpec::class.java)

    fun htmlReport(action: Action<HtmlReportSpec>) = action.execute(htmlReport)

    val server: ServerSpec = objects.newInstance(ServerSpec::class.java)

    fun server(action: Action<ServerSpec>) = action.execute(server)

    val localBuilds: LocalBuildsSpec = objects.newInstance(LocalBuildsSpec::class.java)

    fun localBuilds(action: Action<LocalBuildsSpec>) = action.execute(localBuilds)
}

/** `server { ... }` (spec §3.4). Unset url = offline/artifact-only. */
abstract class ServerSpec {
    /** Base URL, e.g. `https://buildhound.example.com`. */
    abstract val url: Property<String>

    /** Ingest token. Wire from an environment variable provider, never hardcode. */
    abstract val token: Property<String>
}

/**
 * `localBuilds { ... }` (spec §3.4): local-mode uploads are opt-in — by default they
 * additionally require the `~/.buildhound/optin` marker file (spec §3.7).
 */
abstract class LocalBuildsSpec {
    abstract val enabled: Property<Boolean>
    abstract val requireOptInFile: Property<Boolean>
}

/** `htmlReport { ... }` (spec §3.4/§3.8). Output dir is fixed next to the payload for now (plan 006). */
abstract class HtmlReportSpec {
    abstract val enabled: Property<Boolean>
}

/**
 * `identity { ... }` (spec §3.7). With `pseudonymize = true` (default) hostname/user are
 * salted-HMAC pseudonyms; `false` sends plaintext (team choice). `strict` (send nothing)
 * arrives with payload assembly, additively.
 */
abstract class IdentitySpec {
    abstract val pseudonymize: Property<Boolean>
}

enum class TelemetryMode { AUTO, CI, LOCAL, DISABLED }
