package dev.buildhound.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

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

    val fingerprints: FingerprintsSpec = objects.newInstance(FingerprintsSpec::class.java)

    fun fingerprints(action: Action<FingerprintsSpec>) = action.execute(fingerprints)
}

/**
 * `fingerprints { ... }` (spec §3.4, plan 022): allowlist extra build inputs to fingerprint as
 * salted hashes for cache-miss comparison. Built-in JDK/locale/OS/timezone/parallelism keys are
 * always captured; these add named system properties, environment variables, and Gradle
 * properties. Values are salted 16-hex hashes — no plaintext leaves the machine.
 *
 * Per-`Test`-task system-property capture is deferred (plan 022 §8): it requires injecting an
 * action into Test tasks, which the isolated-projects-safe `GradleLifecycle` hook cannot carry a
 * build service into; it will land as a `dev.buildhound.fingerprints` add-on.
 */
abstract class FingerprintsSpec {
    /** Extra JVM system properties to fingerprint, recorded under `sysProps-<name>` keys. */
    abstract val systemProperties: SetProperty<String>

    /** Extra environment variables to fingerprint, recorded under `env-<name>` keys. */
    abstract val envVars: SetProperty<String>

    /** Extra Gradle properties to fingerprint, recorded under `gradleProp-<name>` keys. */
    abstract val gradleProperties: SetProperty<String>

    fun systemProperties(vararg names: String) = systemProperties.addAll(*names)

    fun envVars(vararg names: String) = envVars.addAll(*names)

    fun gradleProperties(vararg names: String) = gradleProperties.addAll(*names)
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
