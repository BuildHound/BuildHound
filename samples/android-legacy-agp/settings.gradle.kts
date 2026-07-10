import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    // BuildHound lives in this repository two levels up; the included build supplies the
    // `dev.buildhound` settings plugin without a published artifact (same wiring as
    // samples/nowinandroid — see samples/README.md).
    includeBuild("../..")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
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
    tags.put("team", "mobile")
    tags.put("project", "android-legacy-app")
    tags.put("type", "android")

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

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "android-legacy-agp"

// ~10 modules with nesting and a real DAG: app -> feature:* -> core:* -> library:*
include(":app")
include(":core:common")
include(":core:ui")
include(":core:data")
include(":core:network")
include(":feature:home")
include(":feature:profile")
include(":feature:settings")
include(":library:analytics")
include(":library:logging")

// The BuildHound plugin ships JVM 21 bytecode and raises this sample's floor to 21
// (AGP 8.5 itself supports running Gradle on JDK 17+).
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    """
    This sample requires JDK 21+ (BuildHound plugin floor) but it is currently using JDK ${JavaVersion.current()}.
    Java Home: [${System.getProperty("java.home")}]
    https://developer.android.com/build/jdks#jdk-config-in-studio
    """.trimIndent()
}
