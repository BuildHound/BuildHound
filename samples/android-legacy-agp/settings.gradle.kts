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

buildhound {
    // Per-build HTML report under build/buildhound/ (on by default; explicit for the demo).
    htmlReport {
        enabled = true
    }
    // Local BuildHound stack from deploy/compose.yaml:
    //   docker compose -f ../../deploy/compose.yaml up --build
    server {
        url = "http://localhost:8080"
        // The committed local-dev token from deploy/compose.yaml (project "pilot").
        // Real deployments must supply the token via the environment only.
        token = providers.environmentVariable("BUILDHOUND_TOKEN")
            .orElse("buildhound-local-dev-token")
    }
    // Builds of this sample are LOCAL mode (no CI env). Uploads go to localhost only,
    // so the ~/.buildhound/optin marker is not required for the demo.
    localBuilds {
        enabled = true
        requireOptInFile = false
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
