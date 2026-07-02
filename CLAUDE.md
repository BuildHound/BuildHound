# CLAUDE.md

Build Telemetry Platform (working name **BTP**): an open-source Develocity alternative —
a Gradle settings plugin that collects build/task/cache/test telemetry, a multi-tenant
Ktor ingestion service shipped as an OCI image, a standalone per-build HTML artifact, and
CI assets. Apache-2.0.

## Source-of-truth documents

| Document | Role |
|---|---|
| `docs/build-telemetry-spec.md` | What we are building (v0.1, locked decisions included) |
| `docs/build-telemetry-roadmap.md` | Phase ordering and exit criteria |
| `docs/build-telemetry-research.md` | Why — landscape, APIs, risk register (§6) |
| `docs/architecture.md` | How we build it well — **living** architecture + best practices (Gradle plugin, KMP, OCI). Update it whenever development produces a better insight |
| `docs/plans/` | One committed plan per feature (see workflow below) |

## Modules

- `btp-commons` — KMP shared module: payload schema v1 (kotlinx-serialization) +
  `CiEnvironmentProvider` SPI. The contract everything builds against; golden-file tests
  pin every schema version.
- `btp-gradle-plugin` — settings plugin (`io.example.buildtelemetry`, placeholder id).
  Collector `BuildService`, Flow-API finalizer, `buildTelemetry {}` DSL. TestKit tests in
  the `functionalTest` source set. Must stay configuration-cache safe.
- `btp-server` — Ktor ingest service (`POST /v1/builds`, `/health`), storage behind
  `BuildStore`. OCI image via `btp-server/Dockerfile`, local stack via
  `deploy/compose.yaml` (TimescaleDB). JVM 21.
- `btp-report` — standalone HTML artifact (zero network — enforced by test), embedded
  into the plugin at build time.
- `btp-ci-assets` — deliberately **not** a Gradle module: Azure YAML template, shell
  metric CLI.

## Commands

```bash
./gradlew build                          # everything: compile, unit + functional tests
./gradlew :btp-gradle-plugin:functionalTest   # TestKit tests only
./gradlew :btp-server:test               # server tests only
./gradlew :btp-server:run                # run the server locally (port 8080)
docker build -f btp-server/Dockerfile -t btp-server .   # OCI image (from repo root!)
docker compose -f deploy/compose.yaml up --build        # server + TimescaleDB
```

The repo keeps `org.gradle.configuration-cache=true`; never disable it to make something
pass — fix the CC violation instead.

## Development workflow (required)

Every feature follows **plan → commit → implement → review → merge**:

### 1. Plan first, commit the plan

Before writing implementation code, derive a short plan from the spec/roadmap (or the
feature request) and write it to `docs/plans/NNN-short-title.md` — structure described in
`docs/plans/README.md`. **Commit the plan before implementation starts** (its own commit,
e.g. `plan: task event collector (spec §3.2)`). This keeps design intent reviewable and
separates "what we intended" from "what we built". If implementation diverges, update the
plan file in the same PR and say why.

### 2. Implement

Follow the committed plan and the binding rules in `docs/architecture.md`. Hard
constraints that always apply:

- The plugin must never fail a build; every failure path degrades to a `warn` log.
- Configuration-cache compatibility for all plugin code.
- Schema changes are additive only; add golden files, never edit them.
- No internal Gradle APIs (v1).
- Tokens/secrets only via providers/env — never in code, DSL literals, logs, or images.

### 3. Review with clean-context agents

After implementation, run **two separate reviews, each in a fresh context** (subagents
with no memory of the implementation conversation — they must judge the code on its own
merits, not on the author's intentions):

1. **Code & architecture review** — correctness, test coverage, simplicity, and
   conformance to `docs/architecture.md` (Gradle plugin rules §2, KMP rules §3, OCI rules
   §4, server rules §5). Also: does the code match the committed plan?
2. **Security & privacy review** — a single dedicated review that examines the change
   from *both* perspectives:
   - *Security:* token handling, injection surfaces, dependency risk, container
     hardening, authz on new endpoints, rate limiting, secrets in logs/layers.
   - *Privacy:* what new data is collected? Is it in the spec? Pseudonymization intact
     (spec §3.7), no absolute paths/env dumps/PII in payloads, scrubber coverage,
     retention implications, local-build opt-in respected.

Findings are fixed (or explicitly accepted with a note in the PR) before merge. When a
review invalidates an architectural assumption, update `docs/architecture.md` — including
its decision log — in the same PR.

### 4. Keep the architecture document alive

`docs/architecture.md` is a living document: improve it whenever a better practice is
found during development, a decision is made/reversed (decision log §7), or a phase retro
(roadmap guardrails) produces lessons. Best-practice updates are normal PR content, not a
special event.

## Conventions

- JVM 21 floor for **all** modules (owner decision, see architecture decision log); the
  plugin therefore requires Gradle running on JDK 21+.
- Placeholder coordinates (`io.example.btp`, plugin id `io.example.buildtelemetry`) until
  naming decision #6 — do not brand anything yet.
- Version catalog (`gradle/libs.versions.toml`) is the only place versions live.
- **When adding a dependency, check what the latest released version is and use that** —
  query Maven Central (`https://repo.maven.apache.org/maven2/<group-path>/<artifact>/maven-metadata.xml`)
  or the Gradle Plugin Portal; never copy a version from memory, an old example, or
  another project. Same applies to tool upgrades (Gradle wrapper, base images).
- Commit messages: imperative mood, scoped prefix when useful
  (`plugin:`, `server:`, `commons:`, `docs:`, `plan:`).
