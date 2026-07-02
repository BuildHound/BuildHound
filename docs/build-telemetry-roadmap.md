# Build Telemetry Platform — MVP-first Roadmap

Phases are scoped so every phase ends in something usable in production on the pilot project. Effort assumes one senior engineer part-time alongside client work; treat sizes as relative (S < M < L < XL), not calendar promises.

## Phase 0 — Foundations (S)

Pick the name + plugin id + Maven coordinates (decision #6 lands here). Create repos/modules (`commons`, `gradle-plugin`, `server`, `ci-assets`), Apache-2.0, CI for the project itself (plugin TestKit matrix skeleton, server Testcontainers, publishing dry-run). Define payload schema v1 in `btp-commons` with golden-file tests — the schema is the contract everything else builds against, so it is finished first.

**Exit:** empty plugin applies cleanly on a Gradle 8/9 matrix with config cache; schema serializes/deserializes golden files.

## Phase 1 — MVP: see every build (L)

*Plugin:* settings plugin, TaskEventCollector, EnvironmentCollector, CI env SPI with Azure + GitHub + generic providers, config DSL (server/mode/tags/filters/identity), Flow finalizer, payload assembly + derived metrics (hit rate, avoided time, critical path), gzip upload with spool/retry, foreground-on-CI. *Server:* tenancy + tokens, `POST /v1/builds`, Postgres+Timescale core tables, basic rollups. *Dashboard:* builds list, build detail (tasks table + timeline + cache summary), duration & hit-rate trend charts with pipeline/branch/mode filters. *Standalone HTML artifact* (same renderer as build detail, embedded data, zero network). Deploy server for the pilot project; run on real CI + opt-in local builds.

**Exit:** two weeks of pilot data visible; artifact opens from the CI run; local opt-in works pseudonymized; zero build failures caused by the plugin.

## Phase 2 — Depth + first intelligence (L)

Kotlin build-report bundling (json dir) + Kotlin dashboard panel (incremental %, rebuild reasons, slowest compilations). Test collection per locked granularity + tests page. Regression engine v1: rolling default-branch baselines, PR-vs-baseline verdict endpoint, budgets, Slack/Teams alerts. Metric CLI + `POST /v1/metrics` + custom metrics on build detail. Azure YAML template polished (token injection, artifact publish, optional verdict gate).

**Exit:** a deliberately slowed PR on the pilot gets flagged against baseline; a Kotlin incremental-compilation regression is visible with its rebuild reason; a non-Gradle step metric (e.g. signing duration) appears on the build page.

## Phase 3 — Context + benchmark mode (M)

AzureDevOpsConnector (Timeline pull; optional service-hook receiver) → CI span tree + queue time on build detail, "Gradle share of pipeline" stat. Process probe + configured-vs-used memory view. Benchmark mode: gradle-profiler scheduled pipeline template, `mode=benchmark` series rendered separately, scenario docs. Bottlenecks/overview landing page. APK/AAB size capture + trend. Toolchain-version adoption view.

**Exit:** nightly benchmark series running on the pilot; queue time and non-Gradle steps visible without any pipeline changes beyond the token; bottlenecks page answers "what got worse this week".

## Phase 4 — v1.x differentiators (XL, order flexible)

1. **Flaky detection** (same-sha divergence + retry signals) → flaky page; after precision validated on pilot: quarantine API + plugin `excludeTestsMatching` closed loop (locked gate #3).
2. **Cache origin local/remote + task-input fingerprints** via the isolated internal-adapters module → build comparison with input diffs (the Develocity-killer feature; also unlocks "why did this miss").
3. **GitHub Actions + GitLab CI connectors** implementing the backend SPI; composite action / CI include templates; public "write your own connector/provider" docs with the 30-line recipe.
4. Hardening for OSS launch: self-host docs (compose, backup, retention tuning), API docs, optional MCP surface for agents, Marketplace extension for Azure if adoption warrants, per-project retention config UI.

**Exit:** OSS-ready: an outside team can self-host, connect an unsupported CI via the SPI, and get the full loop without talking to you.

## Cross-phase guardrails

Schema changes only additive within a major version (contract tests enforce). The plugin must never fail a build — every phase adds failure-injection tests. Isolated Projects tracked as a non-blocking CI job from Phase 1. Each phase ends with a short retro against the research doc's risk register (§6) before the next begins.

## Suggested first implementation steps (when you say go)

1. Decide name/coordinates → Phase 0 scaffolding.
2. Spike (1–2 days): TaskEventCollector + Flow finalizer printing a schema-v1 JSON on a KMP sample with CC on — validates the riskiest assumptions before any server code.
3. Stand up Ktor ingest + Timescale via compose; wire the spike's output end-to-end; only then start the dashboard.
