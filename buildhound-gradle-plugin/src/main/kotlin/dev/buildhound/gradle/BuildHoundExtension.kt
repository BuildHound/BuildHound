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

    val kotlinReports: KotlinReportsSpec = objects.newInstance(KotlinReportsSpec::class.java)

    fun kotlinReports(action: Action<KotlinReportsSpec>) = action.execute(kotlinReports)

    val tests: TestsSpec = objects.newInstance(TestsSpec::class.java)

    fun tests(action: Action<TestsSpec>) = action.execute(tests)

    val upload: UploadSpec = objects.newInstance(UploadSpec::class.java)

    fun upload(action: Action<UploadSpec>) = action.execute(upload)

    val processProbe: ProcessProbeSpec = objects.newInstance(ProcessProbeSpec::class.java)

    fun processProbe(action: Action<ProcessProbeSpec>) = action.execute(processProbe)

    val internalAdapters: InternalAdaptersSpec = objects.newInstance(InternalAdaptersSpec::class.java)

    fun internalAdapters(action: Action<InternalAdaptersSpec>) = action.execute(internalAdapters)
}

/**
 * `internalAdapters { ... }` (spec §3.4, plans 038/044/074): capture that reads **internal Gradle
 * APIs** (build-operation types, `LoggingOutputInternal`) that carry no compatibility guarantee. It
 * ships bundled with the core plugin but every toggle is **off by default and dormant** — no internal
 * API is touched until you enable a specific one. Flipping a toggle here *is* the per-feature consent
 * (plan 074 replaced the old "apply a second `dev.buildhound.internal-adapters` plugin" consent). When
 * a toggle is on the plugin logs a one-time notice that a Gradle upgrade may silently stop that signal;
 * capture never fails the build (every path is reflection-guarded).
 */
abstract class InternalAdaptersSpec {
    /**
     * Capture per-task cache origin/keys + the critical-path / cache-avoided-time derived metrics
     * (plan 038), via build-operation listeners. Off by default.
     */
    abstract val collectCacheOrigins: Property<Boolean>

    /** Capture Gradle deprecation warnings (summary + advice, never the stack trace, plan 044). Off by default. */
    abstract val collectDeprecations: Property<Boolean>

    /** Capture `WARN`-level log lines (`logger.warn`, some compiler output, plan 044). Off by default. */
    abstract val collectLogWarnings: Property<Boolean>

    /** Reserved for a v1.x per-input-file hash follow-up (plan 038); no effect yet, warns if set. */
    abstract val perFileHashes: Property<Boolean>
}

/**
 * `processProbe { ... }` (spec §3.4/§3.6, plan 029): one end-of-build snapshot of the daemon /
 * Kotlin / worker JVMs (heap used-vs-configured, GC time, RSS) via bounded `jps`/`jstat`/`jinfo`/`ps`
 * execs. Read-only, never fails or hangs the build; yields `processes: []` when JDK tools are absent.
 */
abstract class ProcessProbeSpec {
    /** Collect the JVM process snapshot (default true). */
    abstract val enabled: Property<Boolean>
}

/**
 * `upload { ... }` (spec §3.4/§3.9, plan 027). `uploadInBackground` opts a **local** build out of
 * blocking on the inline upload attempt — the payload is spooled and the next build's drain sends
 * it. CI/benchmark are unaffected (short-lived agents must upload inline). No background thread is
 * introduced (spec §3.9, plan 020): "background" here means "don't block this build on the send".
 */
abstract class UploadSpec {
    /** Local builds spool instead of attempting an inline upload (default false). */
    abstract val uploadInBackground: Property<Boolean>
}

/**
 * `tests { ... }` (spec §3.4/§3.5, plan 024): collect per-class test rollups + failure/retry
 * detail by parsing each `Test` task's JUnit XML output. Read-only — no `Test` task is mutated;
 * zero overhead when no test task runs (there is simply no results directory to read).
 */
abstract class TestsSpec {
    /** Parse test results into the payload (default true). */
    abstract val collect: Property<Boolean>
}

/**
 * `kotlinReports { ... }` (spec §3.4, plan 023): bundle the KGP json build report (compiler
 * phase times, incremental effectiveness, rebuild reasons) into the payload. Read-only — the
 * plugin never mutates KGP's own `kotlin.build.report.*` properties; when bundling is on and the
 * report wiring is absent on a Kotlin build, it prints a one-time copy-paste hint.
 */
abstract class KotlinReportsSpec {
    /** Bundle the KGP json build report into the payload (default true). */
    abstract val bundle: Property<Boolean>
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
