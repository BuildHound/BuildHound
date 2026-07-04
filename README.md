# BuildHound

An open-source build-telemetry stack for Gradle — monitor build performance on CI over
time. A Gradle settings plugin collects build/task/cache telemetry from every build, a
self-hostable multi-tenant service ingests it, and each build additionally produces a
fully standalone HTML report artifact. Apache-2.0. Home: [buildhound.dev](https://buildhound.dev).

## Repository layout

| Path | What it is |
|---|---|
| `buildhound-commons/` | Kotlin Multiplatform shared module: payload schema v1 + `CiEnvironmentProvider` SPI |
| `buildhound-gradle-plugin/` | Settings plugin: task-event collector (BuildService), Flow-API finalizer, `buildhound {}` DSL |
| `buildhound-server/` | Ktor ingestion service (`POST /v1/builds`), shipped as an OCI image |
| `buildhound-report/` | Standalone HTML build-report artifact (zero network access) |
| `buildhound-ci-assets/` | Azure Pipelines template, metric CLI — JVM-free CI assets |
| `deploy/` | `compose.yaml`: server + TimescaleDB for local/self-host |
| `docs/` | Research, specification, roadmap, and the living [architecture doc](docs/architecture.md) |

## Quick start

```bash
# Build everything (unit + TestKit functional tests)
./gradlew build

# Run the ingest server locally
./gradlew :buildhound-server:run
curl http://localhost:8080/health

# Or the full stack as containers
docker compose -f deploy/compose.yaml up --build
```

To run BuildHound for a team, see **[docs/self-hosting.md](docs/self-hosting.md)** (compose + generic-OCI
deployment, the env-var contract, token provisioning, retention, and backup/restore). The API is
documented at `GET /docs` (rendered from [docs/api/openapi.yaml](docs/api/openapi.yaml)).

Apply the plugin in a test project's `settings.gradle.kts` (once published; during
development use an included build or `mavenLocal`):

```kotlin
plugins {
    id("dev.buildhound") version "0.1.0-SNAPSHOT"
}

buildhound {
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
