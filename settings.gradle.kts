pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "buildhound"

include(":buildhound-commons")
include(":buildhound-gradle-plugin")
include(":buildhound-server")
include(":buildhound-report")
// Bundled with the core plugin since plan 074 (one plugin, one config block): the core plugin has an
// `implementation` dependency on it, so it is always included. Its internal-Gradle-API code stays
// quarantined here and is dormant until a `buildhound { internalAdapters { } }` toggle is set — bundling
// is not blanket consent (spec §3.1, plan 074). The server OCI image copies its build.gradle.kts so this
// unconditional include still evaluates in the minimal Docker context (buildhound-server/Dockerfile).
include(":buildhound-internal-adapters")

// Opt-in, separately-shipped modules — included only when their directory is present. The server OCI
// image builds from a minimal context (buildhound-server/Dockerfile copies just the core modules), and
// Gradle 9 rejects an included project whose directory does not exist; a full checkout has all of them,
// so `./gradlew build` is unchanged. Each is off the core plugin's classpath — applying/shipping is the consent.
listOf(
    // Test-sharding addon (plan 040): a settings plugin that fetches a server-balanced shard plan and
    // filters Test tasks across CI shards. commons-only, applied alongside core.
    "buildhound-addon-test-sharding",
    // MCP server (plan 042): a stdio read-only query surface over /v1. Never bundled into the ingest image.
    "buildhound-mcp",
).forEach { module -> if (rootDir.resolve(module).isDirectory) include(":$module") }
// buildhound-ci-assets is intentionally not a Gradle module: it holds CI templates and
// shell assets that must be consumable without a JVM (see docs/architecture.md).
