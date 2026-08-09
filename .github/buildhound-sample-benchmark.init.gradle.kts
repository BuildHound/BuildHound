/*
 * BuildHound sample-pilot benchmark init script (plan 105).
 *
 * CI-only redirection of a samples/<pilot> build's telemetry to a real ingest server, so the samples keep
 * their committed local-development configuration (`server.url = "http://localhost:8080"` plus the
 * local-dev token) and no pipeline needs to edit them. Used by
 * .github/workflows/nightly-benchmark.yml:
 *
 *   gradle-profiler ... (scenario gradle-args: -I <this script>)
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
 * Hence `settingsEvaluated`: the extension already exists and is already configured, and this is
 * the last write before the plugin's BuildService parameters are realized (the plugin wires
 * `parameters.serverUrl.set(extension.server.url)` — a lazy Property→Property link, so a later set
 * still propagates).
 *
 * Token discipline (architecture §6): url/token come from providers.environmentVariable(...) ONLY —
 * tracked configuration-cache inputs whose values never land in the serialized CC entry.
 *
 * Env contract, sample-scoped on purpose (same reasoning as the dogfood script's _DOGFOOD_ names):
 * BUILDHOUND_SERVER_URL / BUILDHOUND_TOKEN are the plugin's plan-027 convention fallbacks, read by
 * ANY instrumented build whose DSL leaves them unset. Using those names in CI job env would arm
 * uploads in every other build on the runner.
 *
 *   BUILDHOUND_SAMPLE_SERVER_URL   ingest base URL; unset/empty => upload skipped ("no server
 *                                  configured"), never an unauthenticated POST
 *   BUILDHOUND_SAMPLE_TOKEN        ingest-scoped token
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
            // set(provider) — not set(value): an absent env var leaves the property undefined, which
            // is exactly the "no server configured" skip. This intentionally REPLACES the sample's
            // localhost demo value; the sample file itself stays untouched for local development.
            url.set(settings.providers.environmentVariable("BUILDHOUND_SAMPLE_SERVER_URL"))
            token.set(settings.providers.environmentVariable("BUILDHOUND_SAMPLE_TOKEN"))
            log.lifecycle("[buildhound] sample benchmark init: server url/token taken from BUILDHOUND_SAMPLE_* env")
        }.onFailure {
            log.warn("[buildhound] sample benchmark init failed (build unaffected): {}", it.toString())
        }
    }
}
