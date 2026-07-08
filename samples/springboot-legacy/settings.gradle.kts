pluginManagement {
    // BuildHound lives in this repository two levels up; the included build supplies the
    // `dev.buildhound` settings plugin without a published artifact (same wiring as
    // samples/nowinandroid — see samples/README.md).
    includeBuild("../..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.buildhound")
}

// A deliberately EXHAUSTIVE `buildhound { }` block: every available option, shown at its default
// value, so this doubles as a config reference (see README.md "Configuration reference" and
// docs/build-telemetry-spec.md §3.4). Two settings deviate from the defaults for the local demo and
// say so inline — `server { }` (unset default = offline) and `localBuilds.requireOptInFile`.
buildhound {
    // Master switch — telemetry is skipped entirely when false. Default: true.
    enabled = true

    // Telemetry mode: AUTO detects CI vs LOCAL; force with CI / LOCAL, or DISABLED. Default: AUTO.
    // (Uncomment the import at the top of the block or use the fully-qualified enum to set it.)
    // mode = dev.buildhound.gradle.TelemetryMode.AUTO

    // Low-cardinality dimensions attached to every build. Default: none.
    // tags.put("team", "backend")

    // Salted-HMAC pseudonyms for hostname/user (spec §3.7). Default: true.
    identity {
        pseudonymize = true
    }

    // Per-build standalone HTML report under build/buildhound/. Default: true.
    htmlReport {
        enabled = true
    }

    // Ingest server. UNSET by default → the plugin runs offline (writes the HTML report, uploads
    // nothing). DEMO DEVIATION: pointed at the local BuildHound stack from deploy/compose.yaml
    //   docker compose -f ../../deploy/compose.yaml up --build
    server {
        url = "http://localhost:8080"
        // The committed local-dev token from deploy/compose.yaml (project "pilot"). Real deployments
        // must supply the token via the environment only — never hardcode a real one.
        token = providers.environmentVariable("BUILDHOUND_TOKEN")
            .orElse("buildhound-local-dev-token")
    }

    // Local (non-CI) builds. `enabled` default: true. `requireOptInFile` default: true (a real local
    // upload additionally needs the ~/.buildhound/optin marker, spec §3.7). DEMO DEVIATION: relaxed to
    // false so the sample uploads to localhost without the marker.
    localBuilds {
        enabled = true
        requireOptInFile = false
    }

    // Extra build inputs to fingerprint as salted hashes for cache-miss comparison (spec §3.4). The
    // built-in JDK/OS/locale/parallelism keys are always captured; these add named entries. Default: none.
    fingerprints {
        // systemProperties("some.property")
        // envVars("SOME_ENV")
        // gradleProperties("someGradleProp")
    }

    // Bundle the Kotlin build report (compiler phase times, incremental effectiveness). Default: true.
    kotlinReports {
        bundle = true
    }

    // Parse per-class JUnit test results into the payload. Default: true.
    tests {
        collect = true
    }

    // Local builds spool the payload and let the next build drain it, instead of an inline upload
    // (CI/benchmark always upload inline). Default: false.
    upload {
        uploadInBackground = false
    }

    // End-of-build JVM process snapshot (daemon/Kotlin/worker heap + GC). Default: true.
    processProbe {
        enabled = true
    }

    // Internal-adapters capture (plans 038/044/074). Reads INTERNAL Gradle APIs, so every toggle is
    // OFF BY DEFAULT and dormant — on a daemon where no build has enabled a toggle, no internal API is
    // touched. Flipping one is the per-feature consent and logs a one-time "reads internal Gradle APIs"
    // notice. (One known CC-hit warm-daemon edge is deferred to plan 075 — see docs/architecture.md §7.)
    internalAdapters {
        collectCacheOrigins = false // per-task cache origin/keys + critical-path / avoided-time
        collectDeprecations = false // Gradle deprecation warnings (summary + advice)
        collectLogWarnings = false // WARN-level log lines (logger.warn)
        perFileHashes = false // reserved for a v1.x follow-up; no effect yet
    }
}

// NOTE (deliberately suboptimal): no `dependencyResolutionManagement { repositoriesMode = ... }`.
// Repositories are declared per-subproject in the root `subprojects {}` block instead — the classic
// anti-pattern that couples projects and blocks the configuration cache / isolated projects. Using
// FAIL_ON_PROJECT_REPOS here (like the nowinandroid sample) would *reject* that, so it is omitted.

rootProject.name = "springboot-legacy"

// 50 modules with 2–3 levels of nesting and a real inter-module DAG
// (apps -> services:<svc>:{api,domain,persistence,web} -> libs:*). Generated (plan 073).
// 6 libs + 10 services x 4 submodules + 4 Spring Boot apps.
include(":libs:common")
include(":libs:util")
include(":libs:validation")
include(":libs:serialization")
include(":libs:config")
include(":libs:security")
include(":services:orders:api")
include(":services:orders:domain")
include(":services:orders:persistence")
include(":services:orders:web")
include(":services:payments:api")
include(":services:payments:domain")
include(":services:payments:persistence")
include(":services:payments:web")
include(":services:inventory:api")
include(":services:inventory:domain")
include(":services:inventory:persistence")
include(":services:inventory:web")
include(":services:shipping:api")
include(":services:shipping:domain")
include(":services:shipping:persistence")
include(":services:shipping:web")
include(":services:catalog:api")
include(":services:catalog:domain")
include(":services:catalog:persistence")
include(":services:catalog:web")
include(":services:users:api")
include(":services:users:domain")
include(":services:users:persistence")
include(":services:users:web")
include(":services:notifications:api")
include(":services:notifications:domain")
include(":services:notifications:persistence")
include(":services:notifications:web")
include(":services:pricing:api")
include(":services:pricing:domain")
include(":services:pricing:persistence")
include(":services:pricing:web")
include(":services:reviews:api")
include(":services:reviews:domain")
include(":services:reviews:persistence")
include(":services:reviews:web")
include(":services:search:api")
include(":services:search:domain")
include(":services:search:persistence")
include(":services:search:web")
include(":apps:gateway")
include(":apps:admin")
include(":apps:batch")
include(":apps:scheduler")

// The BuildHound plugin ships JVM 21 bytecode and raises this sample's floor to 21.
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    """
    This sample requires JDK 21+ (BuildHound plugin floor) but it is currently using JDK ${JavaVersion.current()}.
    Java Home: [${System.getProperty("java.home")}]
    """.trimIndent()
}
