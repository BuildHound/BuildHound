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
| `buildhound-internal-adapters/` | Bundled-with-core module — the one quarantined use of internal Gradle APIs (cache origin/keys + critical-path/avoided-time, deprecation + WARN-log warnings). Dormant until a `buildhound { internalAdapters { } }` toggle is set (plan 074) |
| `buildhound-addon-test-sharding/` | Opt-in addon (`dev.buildhound.test-sharding`): server-balanced test sharding across CI shards |
| `buildhound-mcp/` | Opt-in read-only MCP server exposing the query API over stdio JSON-RPC (agent tooling) |
| `buildhound-ci-assets/` | JVM-free CI assets: GitHub Action, GitLab + Azure Pipelines templates, metric CLI, overhead/profiler harnesses |
| `deploy/` | `compose.yaml`: server + TimescaleDB for local/self-host |
| `docs/` | Spec, roadmap, research, the living [architecture doc](docs/architecture.md), the [OpenAPI contract](docs/api/openapi.yaml), and [implementation plans](docs/plans/) |

## Quick start

```bash
# Build everything (unit + TestKit functional tests)
./gradlew build
```

Then wire up the two halves — the **ingest server** (the webservice) and the **plugin** — and
watch a build's telemetry land. The fastest path is the ready-made
[`samples/nowinandroid`](samples/) harness (see [end-to-end below](#try-it-end-to-end)); two more
sample builds under [`samples/`](samples/) cover different shapes (a big, deliberately
mis-configured Spring Boot build and an older-AGP Android build).

### 1. Run the ingest server (webservice)

```bash
# Full stack: server + TimescaleDB (closest to production)
docker compose -f deploy/compose.yaml up --build

# …or a quick, DB-less loop straight from Gradle (in-memory storage, lost on restart)
./gradlew :buildhound-server:run
```

Either way it comes up on `http://localhost:8080`:

```bash
curl http://localhost:8080/health   # liveness
open  http://localhost:8080/        # dashboard (paste a read token on first visit)
open  http://localhost:8080/docs    # API reference (from docs/api/openapi.yaml)
```

The Compose stack **bootstraps a project and an ingest token** on first boot, so you can send
builds immediately — see [local development credentials](#local-development-credentials). The
dashboard's queries are authenticated: paste the local-dev token into its token bar on first visit.

### 2. Apply the plugin and point it at the server

BuildHound is a **settings plugin**, so it goes in `settings.gradle.kts`. Once published you
apply it by version; during development consume it from this repo as an included build (no
publish, no `mavenLocal` — a plugin change is recompiled on the next build):

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("/path/to/Gradle-build-monitoring")   // dev: consume the plugin from source
}
plugins {
    id("dev.buildhound")                               // dev (version supplied by the included build)
    // id("dev.buildhound") version "0.1.0-SNAPSHOT"   // once published
}

buildhound {
    tags.put("team", "mobile")                         // low-cardinality dimensions on every build

    server {
        url = "http://localhost:8080"
        // Ingest token — env-var first, never hardcode a real one. The fallback here is the
        // committed LOCAL-DEV token from deploy/compose.yaml; override in the environment.
        token = providers.environmentVariable("BUILDHOUND_TOKEN")
            .orElse("buildhound-local-dev-token")
    }

    htmlReport { enabled = true }                      // standalone per-build HTML report (on by default)

    // Local (non-CI) builds don't upload unless you opt in. For a local demo, enable and drop the
    // ~/.buildhound/optin marker requirement; leave the marker required for real local uploads.
    localBuilds {
        enabled = true
        requireOptInFile = false
    }
}
```

Leaving `server {}` unset (or `url` empty) runs the plugin **offline** — it still writes the HTML
report but uploads nothing.

### Configuration reference

Everything lives in the single `buildhound { }` block (there is one plugin — `dev.buildhound` — and
one config block). Every knob has a safe default, so an empty `buildhound { }` is a valid, useful
configuration. A fully-populated reference at defaults ships in
[`samples/nowinandroid/settings.gradle.kts`](samples/nowinandroid/settings.gradle.kts).

Most booleans/strings also accept a `buildhound.<key>` Gradle property or a `BUILDHOUND_<KEY>`
environment variable (`<KEY>` = the key uppercased with `.`→`_`); an explicit DSL value always wins
over an override, which wins over the built-in default (plan 027).

| Option | Default | Override key | What it does |
|---|---|---|---|
| `enabled` | `true` | `buildhound.enabled` | Master switch; telemetry is skipped entirely when false |
| `mode` | `AUTO` | `buildhound.mode` | `AUTO` detects CI vs LOCAL; force with `CI` / `LOCAL` / `DISABLED` |
| `tags.put(k, v)` | none | — | Low-cardinality dimensions attached to every build |
| `identity { pseudonymize }` | `true` | `buildhound.identity.pseudonymize` | Salted-HMAC pseudonyms for hostname/user (spec §3.7); `false` sends plaintext |
| `htmlReport { enabled }` | `true` | `buildhound.htmlReport.enabled` | Per-build standalone HTML report under `build/buildhound/` |
| `server { url }` | unset (offline) | `buildhound.server.url` | Ingest base URL; unset ⇒ offline (report only, no upload) |
| `server { token }` | unset | **env/provider only** | Ingest token — wire from an env-var provider; never overridable (would serialize into the CC entry) |
| `localBuilds { enabled }` | `true` | `buildhound.localBuilds.enabled` | Allow local (non-CI) builds to upload |
| `localBuilds { requireOptInFile }` | `true` | `buildhound.localBuilds.requireOptInFile` | Local uploads additionally require the `~/.buildhound/optin` marker (spec §3.7) |
| `upload { uploadInBackground }` | `false` | `buildhound.upload.uploadInBackground` | Local builds spool and let the next build drain, instead of uploading inline |
| `fingerprints { systemProperties/envVars/gradleProperties }` | none | — | Extra build inputs fingerprinted as salted hashes for cache-miss comparison |
| `kotlinReports { bundle }` | `true` | — | Bundle the Kotlin build report (phase times, incremental effectiveness) |
| `tests { collect }` | `true` | — | Parse per-class JUnit test results into the payload |
| `processProbe { enabled }` | `true` | `buildhound.processProbe.enabled` | End-of-build JVM process snapshot (heap, GC) |
| `internalAdapters { collectCacheOrigins }` | `false` | `buildhound.internalAdapters.collectCacheOrigins` | Per-task cache origin/keys + critical-path / avoided-time. **Reads internal Gradle APIs** |
| `internalAdapters { collectDeprecations }` | `false` | `buildhound.internalAdapters.collectDeprecations` | Capture Gradle deprecation warnings. **Reads internal Gradle APIs** |
| `internalAdapters { collectLogWarnings }` | `false` | `buildhound.internalAdapters.collectLogWarnings` | Capture `WARN`-level log lines (`logger.warn`). **Reads internal Gradle APIs** |
| `internalAdapters { perFileHashes }` | `false` | `buildhound.internalAdapters.perFileHashes` | Reserved for a v1.x follow-up; no effect yet |

> **`internalAdapters { }` and internal Gradle APIs.** These four toggles drive capture that reads
> *internal* Gradle APIs (build-operation types, `LoggingOutputInternal`) which carry no compatibility
> guarantee. The code ships bundled with the core plugin but is **dormant** — off by default, and on a
> daemon where no build has enabled a toggle, no internal API is touched. Flipping one is the per-feature
> consent; the plugin then logs a one-time notice that a Gradle upgrade may silently stop that signal.
> Capture never fails the build (every path is reflection-guarded). See
> [architecture §7](docs/architecture.md#7-decision-log) (plan 074) for why this is quarantined in its
> own module rather than living in core — including the one known limitation (a configuration-cache-hit
> warm-daemon edge, deferred to plan 075) where a later all-off build in a daemon that already opted in
> can still capture until the next CC miss.

> **Disabling JUnit XML reports (`reports.junitXml.required = false`).** Gradle's own performance
> guide suggests turning this off "if you use a Build Scan" — but BuildHound's test collection (the
> `tests { collect }` row above) parses that XML, so following that advice silently blanks all test
> telemetry with zero signal. BuildHound detects the flag and reports an honest "test telemetry
> unavailable — JUnit XML disabled on `<taskPath>`" note instead of a false-empty result, but the
> underlying per-class/per-case data is genuinely gone for that task. If you want the same perf win,
> disable **`htmlReport { enabled = false }`** instead and leave `junitXml.required` at its default
> (`true`) — that skips the report BuildHound doesn't need without losing test telemetry.

### Local development credentials

The Compose stack ships committed **local-development-only** credentials so the harness works out
of the box. They live in [`deploy/compose.yaml`](deploy/compose.yaml); for anything shared or real,
mint a high-entropy token (`openssl rand -hex 32`) and pass it through the environment — never in
code, DSL, logs, or an image layer (see [docs/self-hosting.md](docs/self-hosting.md)).

| What | Value | Source (env var in `deploy/compose.yaml`) |
|---|---|---|
| Server URL | `http://localhost:8080` | `server.ports` |
| Bootstrap project | `pilot` | `BUILDHOUND_BOOTSTRAP_PROJECT` |
| Ingest token | `buildhound-local-dev-token` | `BUILDHOUND_BOOTSTRAP_TOKEN` |
| DB user / password | `buildhound` / `buildhound-local-dev` | `POSTGRES_USER` / `POSTGRES_PASSWORD` |

The bootstrap token is `all`-scope. Token scopes and operator-grade provisioning are in
[docs/self-hosting.md §3](docs/self-hosting.md#3-token-provisioning); to run BuildHound for a team
that doc also covers the env-var contract, retention, and backup/restore.

### Try it end-to-end

[`samples/nowinandroid`](samples/) is already wired to apply the in-development plugin and send to
`http://localhost:8080` with the local-dev token — the quickest way to see the full loop:

```bash
docker compose -f deploy/compose.yaml up --build        # 1. start the server (repo root)
cd samples/nowinandroid && ./gradlew :core:common:assemble   # 2. run any build in the sample
# 3. open http://localhost:8080/ (paste the local-dev token) to see the build;
#    the HTML report is under samples/nowinandroid/build/buildhound/
```

Prerequisites and the full dev loop (including the Android SDK setup the sample needs) are in
**[samples/README.md](samples/README.md)**. Two more harnesses live alongside it, wired the same way
and sending to the same local server/token:

- [`samples/springboot-legacy`](samples/springboot-legacy/) — a 50-module Spring Boot build with a
  **deliberately sub-optimal** Gradle config (config cache / build cache / parallelism all off,
  cross-project config, per-project repos). Its telemetry looks "bad" on purpose. No Android SDK needed:
  `cd samples/springboot-legacy && ./gradlew build`.
- [`samples/android-legacy-agp`](samples/android-legacy-agp/) — a ~10-module Android build on an
  **older AGP (8.5.2)** / Gradle 8.14.5 (the bottom of the plugin's supported window):
  `cd samples/android-legacy-agp && ./gradlew :app:assembleDebug` (needs the Android SDK).

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
  test-sharding addon, the bundled internal-adapters capture (behind `internalAdapters {}`, off by
  default), and an opt-in read-only MCP server.

Two plans remain open, both blocked: [035](docs/plans/035-cc-miss-reason-capture.md) and
[037](docs/plans/037-test-quarantine-addon.md).

> **Known gap:** Android artifact-size capture (plan 031) shipped but is non-functional under
> AGP 9.x (a settings-plugin/AGP classloader boundary) and its test is disabled pending a
> rework — see the [architecture decision log](docs/architecture.md#7-decision-log).

## Contributing / workflow

Development follows a plan-first, review-heavy workflow described in
[CLAUDE.md](CLAUDE.md); architecture rules and best practices live in
[docs/architecture.md](docs/architecture.md).
