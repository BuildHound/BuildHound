pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "build-telemetry-platform"

include(":btp-commons")
include(":btp-gradle-plugin")
include(":btp-server")
include(":btp-report")
// btp-ci-assets is intentionally not a Gradle module: it holds CI templates and
// shell assets that must be consumable without a JVM (see docs/architecture.md).
