# BuildHound

An open-source build-telemetry stack for Gradle — monitor build performance on CI over
time. A Gradle settings plugin collects build/task/cache telemetry from every build, a
self-hostable multi-tenant service ingests it, and each build additionally produces a
fully standalone HTML report artifact. Apache-2.0. Home: [buildhound.dev](https://buildhound.dev).

## Repository layout

| Path | What it is |
|---|---|
| `buildhound-commons/` | Kotlin Multiplatform shared module: payload schema v1 + `CiEnvironmentProvider` / addon SPIs — the contract everything builds against |
| `buildhound-gradle-plugin/` | Settings plugin (`dev.buildhound`): collectors (BuildService), Flow-API finalizer, `buildhound {}` DSL, uploader |
| `buildhound-server/` | Ktor ingestion + query service (`POST /v1/builds`, rollups, regression engine, CI connectors, dashboard), shipped as an OCI image |
| `buildhound-report/` | Standalone HTML build-report artifact (zero network access, enforced by test) |
| `buildhound-internal-adapters/` | Opt-in module (`dev.buildhound.internal-adapters`) — the one sanctioned use of internal Gradle APIs: cache origin/keys + critical-path/avoided-time |
| `buildhound-addon-test-sharding/` | Opt-in addon (`dev.buildhound.test-sharding`): server-balanced test sharding across CI shards |
| `buildhound-mcp/` | Opt-in read-only MCP server exposing the query API over stdio JSON-RPC (agent tooling) |
| `buildhound-ci-assets/` | JVM-free CI assets: GitHub Action, GitLab + Azure Pipelines templates, metric CLI, overhead/profiler harnesses |
| `deploy/` | `compose.yaml`: server + TimescaleDB for local/self-host |
| `docs/` | Spec, roadmap, research, the living [architecture doc](docs/architecture.md), the [OpenAPI contract](docs/api/openapi.yaml), and [implementation plans](docs/plans/) |

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

Roadmap phases 0–4 are implemented (see the [roadmap](docs/build-telemetry-roadmap.md) and
[implemented plans](docs/plans/implemented/)). Highlights:

- **Plugin** — configuration-cache- and isolated-projects-safe collectors validated by TestKit
  (incl. CC reuse): task/cache/type telemetry, JUnit test results, Kotlin build-report metrics,
  input fingerprints, an end-of-build JVM process probe, lost-build (`INTERRUPTED`) accounting,
  broad CI/environment detection, and a measured overhead budget.
- **Server** — Postgres + TimescaleDB persistence, multi-tenant with per-token + per-host rate
  limiting, eBay-style project/task rollups, a regression engine with baselines and alerts,
  flaky-test detection, per-build comparison, CI connectors (Azure DevOps, GitHub Actions,
  GitLab) that enrich builds with pipeline timelines, retention with an `admin` scope, an
  OpenAPI-contracted API, and a zero-CDN dashboard + docs viewer.
- **Artifacts & extension points** — the standalone HTML report, an addon SPI with the
  test-sharding addon, the internal-adapters module, and an opt-in read-only MCP server.

Two plans remain open, both blocked: [035](docs/plans/035-cc-miss-reason-capture.md) and
[037](docs/plans/037-test-quarantine-addon.md).

> **Known gap:** Android artifact-size capture (plan 031) shipped but is non-functional under
> AGP 9.x (a settings-plugin/AGP classloader boundary) and its test is disabled pending a
> rework — see the [architecture decision log](docs/architecture.md#7-decision-log).

## Contributing / workflow

Development follows a plan-first, review-heavy workflow described in
[CLAUDE.md](CLAUDE.md); architecture rules and best practices live in
[docs/architecture.md](docs/architecture.md).
