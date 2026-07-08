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

// NOTE (deliberately suboptimal): no `dependencyResolutionManagement { repositoriesMode = ... }`.
// Repositories are declared per-subproject in the root `subprojects {}` block instead — the classic
// anti-pattern that couples projects and blocks the configuration cache / isolated projects. Using
// FAIL_ON_PROJECT_REPOS here (like the nowinandroid sample) would *reject* that, so it is omitted.

rootProject.name = "springboot-legacy"

// 50 modules with 2–3 levels of nesting and a real inter-module DAG
// (apps -> services:<svc>:{api,domain,persistence,web} -> libs:*). Generated (plan 051).
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
