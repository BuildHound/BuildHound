/*
 * BuildHound dogfooding init script (plan 093).
 *
 * CI-only injection of the just-published `dev.buildhound` plugin into this repository's own
 * build — the Develocity/CCUD CI-injection pattern. The `build` job runs two invocations:
 *
 *   1. gradle :buildhound-gradle-plugin:publishToMavenLocal
 *   2. gradle build -I .github/buildhound-dogfood.init.gradle.kts
 *
 * Local builds are untouched: no settings.gradle.kts change; a maintainer opts in by passing
 * the same -I flag after a publishToMavenLocal.
 *
 * Failure posture (plan 093 §3): this script must not redden a build. The classpath is only
 * declared when the plugin JAR is actually present in the local Maven repository, and the
 * apply/configure path below is reflection-based and fully guarded — a missing bootstrap
 * publish degrades to a warn log and a build without telemetry, never a failure. One accepted
 * exception (plan 093 §3, "residual accepted edge"): an artifact that is present on disk but
 * corrupt still fails resolution loudly — in CI that is the publication-drift signal. The
 * plugin itself never fails a build by design (CLAUDE.md hard constraint).
 *
 * Token discipline (architecture §6): server.url/server.token are wired from
 * providers.environmentVariable(...) ONLY. A DSL literal or gradleProperty would serialize
 * into the on-disk configuration-cache entry. Both are unset in plan 093, so UploadGate
 * skips with "no server configured" and the payload lands in the workflow artifact.
 */

import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

initscript {
    // Stage 1 is compiled separately from the body below, so the coordinates are repeated here.
    // 0.1.0-SNAPSHOT is the root build's default development version (root build.gradle.kts) —
    // exactly what the bootstrap publishToMavenLocal invocation publishes.
    val version = "0.1.0-SNAPSHOT"
    // Same location logic mavenLocal() uses for its common cases: the maven.repo.local system
    // property, falling back to ~/.m2/repository. Declaring the classpath only when the JAR is
    // really there keeps a missing bootstrap publish a graceful no-op (the body warns) instead
    // of an unresolvable-initscript-classpath build failure.
    val localRepo = System.getProperty("maven.repo.local")?.let { java.io.File(it) }
        ?: java.io.File(System.getProperty("user.home"), ".m2/repository")
    val pluginJar = java.io.File(
        localRepo,
        "dev/buildhound/buildhound-gradle-plugin/$version/buildhound-gradle-plugin-$version.jar",
    )
    if (pluginJar.isFile) {
        // mavenLocal only, on purpose: the dogfooded plugin must be the one built from this
        // commit, never a Portal/Central artifact.
        repositories {
            mavenLocal()
        }
        dependencies {
            classpath("dev.buildhound:buildhound-gradle-plugin:$version")
        }
    }
}

// Kotlin DSL SAM-with-receiver: an Action<Settings> lambda has NO parameter — `this` is the
// Settings instance. Bound to an explicit `settings` val so every access below is visibly
// against Settings, never the init script's implicit Gradle receiver (Gradle is itself
// PluginAware, so a mis-scoped pluginManager would silently target the wrong object).
beforeSettings {
    val settings: Settings = this
    val log = Logging.getLogger("buildhound-dogfood-init")
    // Everything below is reflection-based: the script must compile identically whether or not
    // stage 1 found the artifact, so the body never references a dev.buildhound type statically.
    val pluginClass = try {
        Class.forName("dev.buildhound.gradle.BuildHoundSettingsPlugin")
    } catch (missing: ClassNotFoundException) {
        log.warn(
            "[buildhound] dogfood init script: dev.buildhound is not in the local Maven repository; " +
                "telemetry skipped for this build (run `gradle :buildhound-gradle-plugin:publishToMavenLocal` first)",
        )
        null
    }
    if (pluginClass != null) {
        runCatching {
            settings.pluginManager.apply(pluginClass)
            val buildhound = settings.extensions.getByName("buildhound")
            val server = buildhound.javaClass.getMethod("getServer").invoke(buildhound)

            // Environment-variable providers only (never a literal / gradleProperty): tracked
            // CC inputs whose values stay out of the serialized configuration-cache entry.
            // Unset in plan 093 → UploadGate skips the upload with "no server configured".
            //
            // DOGFOOD-namespaced on purpose (093 §3.2 review): BUILDHOUND_SERVER_URL is the
            // plugin's documented plan-027 convention fallback for server.url, read by ANY
            // BuildHound-instrumented build whose DSL leaves it unset — including the many
            // functionalTest fixture builds nested inside `gradle build`. Job-level env under
            // the convention name would arm uploads in all of them (junk POSTs at the real
            // server; the token stays out via ConfigOverrides.EXCLUDED_KEY, but rate-limit
            // fallout could still cost the legit dogfood payload its delivery). The dogfood
            // contract therefore lives in its own namespace, which no convention fallback reads.
            @Suppress("UNCHECKED_CAST")
            val url = server.javaClass.getMethod("getUrl").invoke(server) as Property<String>
            url.set(settings.providers.environmentVariable("BUILDHOUND_DOGFOOD_SERVER_URL"))
            @Suppress("UNCHECKED_CAST")
            val token = server.javaClass.getMethod("getToken").invoke(server) as Property<String>
            token.set(settings.providers.environmentVariable("BUILDHOUND_DOGFOOD_TOKEN"))

            // Low-cardinality dimensions (spec §3.4): the CI job name and trigger. Read eagerly
            // through environment providers (tracked CC inputs, the ConfigOverrides pattern) and
            // only put when present — putting an absent provider would void the whole tag map.
            @Suppress("UNCHECKED_CAST")
            val tags = buildhound.javaClass.getMethod("getTags").invoke(buildhound) as MapProperty<String, String>
            settings.providers.environmentVariable("GITHUB_JOB").orNull?.let { tags.put("ci.job", it) }
            settings.providers.environmentVariable("GITHUB_EVENT_NAME").orNull?.let { tags.put("ci.trigger", it) }
        }.onFailure {
            log.warn("[buildhound] dogfood init script failed (build unaffected): {}", it.toString())
        }
    }
}
