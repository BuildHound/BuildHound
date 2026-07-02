# Build Telemetry Platform (working name: BTP)

An open-source build-telemetry stack for Gradle — monitor build performance on CI over
time. A Gradle settings plugin collects build/task/cache telemetry from every build, a
self-hostable multi-tenant service ingests it, and each build additionally produces a
fully standalone HTML report artifact. Apache-2.0.

> Naming, plugin id, and Maven coordinates are placeholders until decision #6
> (`io.example.buildtelemetry` / `io.example.btp`).

## Repository layout

| Path | What it is |
|---|---|
| `btp-commons/` | Kotlin Multiplatform shared module: payload schema v1 + `CiEnvironmentProvider` SPI |
| `btp-gradle-plugin/` | Settings plugin: task-event collector (BuildService), Flow-API finalizer, `buildTelemetry {}` DSL |
| `btp-server/` | Ktor ingestion service (`POST /v1/builds`), shipped as an OCI image |
| `btp-report/` | Standalone HTML build-report artifact (zero network access) |
| `btp-ci-assets/` | Azure Pipelines template, metric CLI — JVM-free CI assets |
| `deploy/` | `compose.yaml`: server + TimescaleDB for local/self-host |
| `docs/` | Research, specification, roadmap, and the living [architecture doc](docs/architecture.md) |

## Quick start

```bash
# Build everything (unit + TestKit functional tests)
./gradlew build

# Run the ingest server locally
./gradlew :btp-server:run
curl http://localhost:8080/health

# Or the full stack as containers
docker compose -f deploy/compose.yaml up --build
```

Apply the plugin in a test project's `settings.gradle.kts` (once published; during
development use an included build or `mavenLocal`):

```kotlin
plugins {
    id("io.example.buildtelemetry") version "0.1.0-SNAPSHOT"
}

buildTelemetry {
    tags.put("team", "mobile")
}
```

## Status

Phase 0 scaffold (see [roadmap](docs/build-telemetry-roadmap.md)): module structure,
schema v1 models with golden-file contract tests, a configuration-cache-safe settings
plugin validated by TestKit (including CC reuse), a Ktor ingest skeleton with idempotent
`POST /v1/builds`, and the OCI packaging. Phase 1 ("see every build") is next.

## Contributing / workflow

Development follows a plan-first, review-heavy workflow described in
[CLAUDE.md](CLAUDE.md); architecture rules and best practices live in
[docs/architecture.md](docs/architecture.md).
