# 001 — BuildHound rebrand (naming decision #6)

## Source

Roadmap Phase 0 leftover: "Pick the name + plugin id + Maven coordinates (decision #6)".
Owner decision (2026-07-02): product name **BuildHound**, domain **buildhound.dev**.

## Scope

**In:**

- Maven group: `io.example.btp` → `dev.buildhound` (reverse of buildhound.dev).
- Plugin id: `io.example.buildtelemetry` → `dev.buildhound`.
- Packages: `io.example.btp.*` → `dev.buildhound.*` (commons, gradle, server, report).
- Gradle modules/directories: `btp-*` → `buildhound-*`; root project name → `buildhound`.
- DSL: `buildTelemetry {}` → `buildhound {}`; extension class `BuildHoundExtension`;
  plugin class `BuildHoundSettingsPlugin`.
- Env-var prefix: `BTP_*` → `BUILDHOUND_*` (generic CI provider, server port/DB, CI
  template token vars). Pre-release, so no compatibility shim.
- Deploy/OCI: image name `buildhound-server`, compose project/db/volume names,
  non-root user, Dockerfile paths + OCI labels (`url` → https://buildhound.dev).
- CI assets: `buildhound-gradle-steps.yml`, `bin/buildhound-metric`.
- Report: resource path `dev/buildhound/report/`, placeholder `/*__BUILDHOUND_DATA__*/`,
  template title.
- Docs: README, CLAUDE.md, spec, roadmap, architecture (incl. decision-log entry).

**Out:**

- `docs/build-telemetry-research.md` and `docs/plans/000-*` stay untouched — they are
  point-in-time records that predate the naming decision.
- Wire format: schema v1 JSON carries no branding; golden files unchanged (guardrail:
  golden files are never edited).
- No publishing setup (Plugin Portal / Maven Central) — separate feature.

## Design

Pure rename, no behavior change. Class renames: `BtpJson` → `BuildHoundJson`,
`btpModule` → `buildHoundModule`, build-service name `buildhoundTaskEventCollector`,
log prefix `[buildhound]`. Casing convention: **BuildHound** in prose/class names,
`buildhound` for ids/modules/DSL, `BUILDHOUND_` for env vars.

## Test strategy

Existing suite must stay green: `./gradlew build` (unit + TestKit functional with CC) —
functional tests assert the new plugin id, DSL name, and log prefix. Golden-file test
proves the wire format is untouched. Docker image builds and passes the CI smoke test.

## Risks

- Renamed report resource path must match `ReportAssets` lookup (test covers it).
- Dockerfile layer-cache COPY paths must follow the module rename or image build breaks.
- Typesafe project accessors (`projects.btpCommons` → `projects.buildhoundCommons`).

## Exit criteria

`grep -ri "io.example\|btp" ` over code/config returns nothing except the excluded
historical docs; `./gradlew build` green; docker image builds; PR reviews pass.
