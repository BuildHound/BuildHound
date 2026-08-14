/*
 * BuildHound sample-pilot benchmark init script (plan 105).
 *
 * CI-only redirection of a samples/<pilot> build's telemetry to a real ingest server, so the samples keep
 * their committed local-development configuration (`server.url = "http://localhost:8080"` plus the
 * local-dev token) and no pipeline needs to edit them. Used by
 * .github/workflows/nightly-benchmark.yml, which installs this file into the init.d of the Gradle
 * user home the profiler is told to use:
 *
 *   cp <this script> "$BENCHMARK_GRADLE_USER_HOME/init.d/"
 *   gradle-profiler --benchmark --gradle-user-home "$BENCHMARK_GRADLE_USER_HOME" ...
 *
 * Both halves are load-bearing (plan 109). gradle-profiler does NOT use ~/.gradle — it runs every
 * measured build against a dedicated Gradle user home — and Gradle reads init.d only from the home
 * actually in use, so an install into ~/.gradle/init.d applies to nothing the profiler runs. That
 * mistake published four nightlies' worth of correctly-labelled telemetry to the samples' committed
 * localhost URL. A hand-run `./gradlew` takes `-I <this script>` instead (samples/README.md), which
 * is also how SampleBenchmarkInitScriptFunctionalTest applies it.
 *
 * Sibling of .github/buildhound-dogfood.init.gradle.kts (plan 093), and deliberately NOT the same
 * script — the two inject at different points and cannot be merged:
 *
 *  - The dogfood script *applies* the plugin from mavenLocal into the root build, which has no
 *    `buildhound { }` block at all. The samples already apply the plugin themselves, from source
 *    via includeBuild("../.."), so applying a second copy here would mean two classloaders' worth
 *    of the same plugin id.
 *  - The dogfood script overrides in `beforeSettings`, which runs BEFORE a settings.gradle.kts is
 *    evaluated. That is correct for a build with no DSL of its own, but against a sample it loses:
 *    the sample's own `server { url = ... }` literal is applied afterwards and wins.
 *
 * Hence `settingsEvaluated`: the extension already exists and is already configured, and the write
 * lands before anything reads it. The plugin wires `spec.parameters.serverUrl.set(extension.server.url)`
 * into its Flow-action parameters (BuildHoundSettingsPlugin) — a lazy Property→Property link resolved
 * when the Flow action runs at build finish, so a later set still propagates. NOTE the distinction:
 * this is a *Flow action's* parameters, not a BuildService's — BuildService parameters freeze on first
 * instantiation (plan 044), and this "a later set still propagates" reasoning must not be transplanted
 * onto a `collector.parameters.*` write.
 *
 * Token discipline (architecture §6): url/token come from providers.environmentVariable(...) ONLY —
 * tracked configuration-cache inputs, so a changed env value invalidates the entry rather than being
 * replayed. (The *resolved* value does end up inside the CC entry as part of the finalized Flow
 * parameters — the discipline buys correct invalidation and keeps the value out of the build script,
 * not secrecy of the entry itself. This pipeline starts every job from a cold Gradle user home and
 * never archives `.gradle/`.)
 *
 * Env contract, sample-scoped on purpose (same reasoning as the dogfood script's _DOGFOOD_ names):
 * BUILDHOUND_SERVER_URL / BUILDHOUND_TOKEN are the plugin's plan-027 convention fallbacks, read by
 * ANY instrumented build whose DSL leaves them unset. Using those names in CI job env would arm
 * uploads in every other build on the runner.
 *
 *   BUILDHOUND_SAMPLE_SERVER_URL   ingest base URL
 *   BUILDHOUND_SAMPLE_TOKEN        ingest-scoped token
 *
 * BOTH are required: with either unset or blank the upload is disabled entirely ("no server
 * configured"), never an unauthenticated POST to a half-configured target.
 *
 * Failure posture: never fail a build (CLAUDE.md hard constraint). Every step is reflection-based
 * and guarded — a build without the plugin, or a plugin whose extension shape changed, degrades to
 * a warn log and unchanged telemetry.
 */

import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property

settingsEvaluated {
    val settings: Settings = this
    val log = Logging.getLogger("buildhound-sample-benchmark-init")
    // findByName, not getByName: a build in the composite that does not apply BuildHound (e.g. the
    // included plugin build itself) simply has no extension, and that is not an error.
    val buildhound = settings.extensions.findByName("buildhound")
    if (buildhound == null) {
        log.info("[buildhound] sample benchmark init: no buildhound extension on this settings; nothing to redirect")
    } else {
        runCatching {
            val server = buildhound.javaClass.getMethod("getServer").invoke(buildhound)
            @Suppress("UNCHECKED_CAST")
            val url = server.javaClass.getMethod("getUrl").invoke(server) as Property<String>
            @Suppress("UNCHECKED_CAST")
            val token = server.javaClass.getMethod("getToken").invoke(server) as Property<String>
            // gradle-profiler drives more Gradle invocations than the ones it measures, and every
            // one of them inherits the job's BUILDHOUND_BENCHMARK_* env — so without this gate they
            // all publish as benchmark rows that the server cannot tell apart from a measurement
            // (one BenchmarkPoint per payload, and `iteration` is null on all of them because
            // gradle-profiler exports no BUILDHOUND_BENCHMARK_ITERATION). In a `clean` cell that is
            // 5 scaffolding rows out of 9, and the cell's `min` becomes the `:help` build.
            //
            // Measured directly against gradle-profiler 0.24.0 rather than assumed — the requested
            // task names separate them cleanly:
            //   build inspection ("Inspecting the build")      -> [:help]
            //   cleanup before each build (`cleanup-tasks`)    -> [clean]
            //   the measured build                             -> the scenario's own tasks
            // An invocation whose requested tasks are ALL scaffolding is therefore not published.
            // The leaf name is compared so that `:app:clean` is still recognised; an empty task list
            // (an invocation that runs nothing) counts as scaffolding too.
            val scaffoldingTasks = setOf("help", "clean")
            val requestedTasks = settings.startParameter.taskNames
            val isScaffolding =
                requestedTasks.isEmpty() ||
                    requestedTasks.all { it.substringAfterLast(':') in scaffoldingTasks }
            if (isScaffolding) {
                // CLEAR, rather than merely declining to redirect. Leaving the sample's committed
                // localhost URL in place would arm an upload that cannot succeed, and the workflow's
                // publication check reads a failed POST as a broken cell — so "skip the redirect"
                // would trade a data-quality bug for a false alarm on every scaffolding build.
                //
                // An absent PROVIDER, not `set(null)`. `Property.set(null)` is Gradle's *unset*: it
                // discards the explicit value and reverts to the convention — and
                // BuildHoundSettingsPlugin gives `server.url` one (from `buildhound.server.url` /
                // BUILDHOUND_SERVER_URL via ConfigOverrides) while `server.token` is excluded by
                // construction. So unsetting both restores the URL and drops the token: the exact
                // "URL without token" state this script's token gate exists to prevent, which
                // `UploadGate` arms and `PayloadUploader` sends with no Authorization header.
                // Reproduced with `set(null)` before this line was written — the fixture received an
                // unauthenticated payload — and covered by
                // `scaffolding clear does not fall back to a convention url`.
                val absent = settings.providers.provider { null as String? }
                url.set(absent)
                token.set(absent)
                log.lifecycle(
                    "[buildhound] sample benchmark init: $requestedTasks is gradle-profiler " +
                        "scaffolding, not a measured build — upload disabled for this invocation",
                )
                return@runCatching
            }
            // Blank is treated as absent: a CI expression that collapsed to '' must mean "do not
            // publish", not "publish to the empty URL".
            val urlEnv = settings.providers.environmentVariable("BUILDHOUND_SAMPLE_SERVER_URL")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tokenEnv = settings.providers.environmentVariable("BUILDHOUND_SAMPLE_TOKEN")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            // The URL is gated on the TOKEN, deliberately: UploadGate keys on the URL alone and
            // PayloadUploader simply omits the Authorization header when the token is absent, so a
            // URL-without-token override would POST this build's telemetry unauthenticated to
            // whatever host was named. Setting only one of the two env vars — a one-line slip in
            // the documented manual invocation — must therefore disable the upload, not weaken it.
            url.set(tokenEnv.flatMap { urlEnv })
            token.set(tokenEnv)
            // set(provider), not set(value): with either var absent the properties end up
            // undefined, which is exactly UploadGate's "no server configured" skip. This
            // intentionally REPLACES the sample's localhost demo values; the sample file itself
            // stays untouched for local development.
            if (urlEnv.isPresent && tokenEnv.isPresent) {
                log.lifecycle("[buildhound] sample benchmark init: telemetry redirected to the BUILDHOUND_SAMPLE_* ingest target")
            } else {
                // warn, not info: the common way to reach this state is a stray copy of this script
                // left in ~/.gradle/init.d after mirroring the CI step locally, which would
                // otherwise silently disable the sample's telemetry on every later build — this
                // override also defeats the plugin's BUILDHOUND_SERVER_URL convention fallback.
                log.warn(
                    "[buildhound] sample benchmark init: BUILDHOUND_SAMPLE_SERVER_URL and " +
                        "BUILDHOUND_SAMPLE_TOKEN are not both set — this build's telemetry upload is " +
                        "DISABLED (this script overrides the project's own server configuration)",
                )
            }
        }.onFailure {
            log.warn("[buildhound] sample benchmark init failed (build unaffected): {}", it.toString())
        }
    }
}
