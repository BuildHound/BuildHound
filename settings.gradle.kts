pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK 26 build toolchain (plan 011). Environments that cannot
    // reach api.foojay.io set buildhound.toolchain=21 in their user gradle.properties.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // AGP (com.android.tools.build:gradle-api) is a compileOnly dependency of the plugin's
        // Android artifact-size collector (plan 031) and is published to Google's Maven, not Central.
        // Content-filtered so nothing else resolves here.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "buildhound"

include(":buildhound-commons")
include(":buildhound-gradle-plugin")
include(":buildhound-server")
include(":buildhound-report")
// Opt-in, separately-shipped module: the single sanctioned exception to the no-internal-Gradle-APIs
// rule (spec §3.1, plan 038). Never on the core plugin's classpath — applying it is the consent.
include(":buildhound-internal-adapters")
// buildhound-ci-assets is intentionally not a Gradle module: it holds CI templates and
// shell assets that must be consumable without a JVM (see docs/architecture.md).
