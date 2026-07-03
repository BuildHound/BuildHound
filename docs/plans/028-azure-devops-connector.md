# Plan 028 — AzureDevOpsConnector: Timeline pull, CI span tree, queue time, Gradle share of pipeline

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- [build-telemetry-roadmap.md](../build-telemetry-roadmap.md) Phase 3, bullet 1:
  "AzureDevOpsConnector (Timeline pull, optional service hooks) → CI span tree + queue
  time, 'Gradle share of pipeline'".
- [build-telemetry-spec.md](../build-telemetry-spec.md) §5 "CI connector SPI (backend
  side)" (the `CiConnector` interface, `CiRun`/`CiSpan`/`queuedMs` normalized tree,
  `ci_spans` table, `NoopConnector` shipping in v1), §2 architecture diagram
  (`CiConnector SPI ◄─ AzureDevOpsConnector`), §6 dashboard "Build detail (… + CI span
  tree …)".
- [architecture.md](../architecture.md) §5 (server: `BuildStore` persistence boundary,
  multi-tenancy, `buildHoundModule` route testability), §6 (tokens env-only, hashed at
  rest), §4 (OCI env-only config).
- Research: [plugin-ecosystem-gap-analysis.md §6](../research/plugin-ecosystem-gap-analysis.md)
  (server connector namespace/registration discipline), §4.4 (Azure env mapping origin);
  [dashboard-ux-research.md §4.2.8](../research/dashboard-ux-research.md) (cross-linking /
  provenance links, CI span tree on build detail, honest degraded states).

## 2. Scope

**In:**

- A **backend connector framework** — the spec §5 `CiConnector` SPI as the first concrete
  interface, plus a `ConnectorRegistry`, `ConnectorConfig` (per-project, credentials from
  env/config), and a `NoopConnector` so the extension point is public from day one.
- `AzureDevOpsConnector`: pulls the Azure DevOps **Build Timeline REST API**
  (`GET {collectionUri}/{project}/_apis/build/builds/{buildId}/timeline`), normalizes its
  records (jobs/tasks with `startTime`/`finishTime`/`queueTime`/`result`/`workerName`)
  into the spec's `CiRun` tree of `CiSpan`s + `queuedMs`.
- **Enrichment trigger**: on ingest of a build whose `ci.provider == "azure-devops"` and
  `ci.runId != null`, enqueue a polite, backed-off connector fetch (tenant-scoped) that
  stores the resulting span tree.
- Optional **service-hook receiver** (`POST /v1/connectors/azure-devops/hook`) so an Azure
  `build.complete` service hook can push completion instead of relying only on poll.
- Span **storage** behind the persistence boundary (`CiSpanStore`), new `ci_spans` table +
  migration, tenant-scoped.
- Query surface: `GET /v1/builds/{id}/ci-run` returning the normalized tree + derived
  **queue time** and **Gradle share of pipeline** (Gradle build wall-clock ÷ pipeline
  wall-clock).
- Dashboard build-detail: a **CI span tree** section (Gantt-ish nested list) + queue-time
  and Gradle-share chips; amber "connector not configured / no timeline yet" degraded
  state.
- `deploy/compose.yaml` + docs: the connector PAT env var and per-project config knobs.
- Architecture decision-log entry for the connector framework + its outbound-HTTP posture.

**Out:**

- **GitHub Actions / GitLab connectors** — [plan 041](041-ci-connectors-gha-gitlab.md);
  this plan only makes the interface they plug into.
- **Lost-build / INTERRUPTED accounting** via the connector's expected-build check —
  [plan 033](033-lost-build-accounting.md) reuses this connector's `fetchRun`.
- **Regression/verdict/alerts** on span data — regression engine is
  [plan 025](025-regression-engine-v1.md); not extended here.
- **CI env-provider breadth / new payload fields** — [plan 027](027-ci-env-breadth.md).
  No plugin/`buildhound-commons` payload change is made here.
- OAuth app flow (spec's "later"): v1 auth is a **PAT via env/config only**.

## 3. Design

**Modules touched:** `buildhound-server` only (new `connector` package + one migration +
dashboard-detail additions). No `buildhound-commons`, no plugin, no schema-v1 change — the
connector reads the correlation keys already present in the ingested payload and writes to a
new server-side table, so golden files are untouched.

**Correlation keys (verified in source).** `CiInfo.provider`/`CiInfo.runId` are the only
keys needed: `BuildPayload.ci.provider == "azure-devops"` and `ci.runId` = the Azure
`BUILD_BUILDID` (`BuildPayload.kt:85-92`; `AzureDevOpsCiEnvironmentProvider` sets
`runId = env["BUILD_BUILDID"]`, `CiEnvironmentProviders.kt:14-22`). The collection URI +
project needed to call the REST API are **not** stored as columns today — they live only
inside `ci.buildUrl` (composed from `SYSTEM_COLLECTIONURI` + `SYSTEM_TEAMPROJECT`, same file
:34-39) and are not separately captured. The connector therefore derives collection URI +
project by parsing `ci.buildUrl` (`{collection}/{project}/_build/results?buildId=…`), and
per-project `ConnectorConfig` may override the organization/project base — never guessed
from attacker-controlled fields alone for the outbound call host (see Risks).

**New types (all in `dev.buildhound.server.connector`):**

- `interface CiConnector` (spec §5 verbatim shape): `id`, `capabilities: Set<Capability>`
  (`TIMELINE_PULL`, `WEBHOOK`, `DEEP_LINKS`), `suspend fun fetchRun(ref, config): CiRun?`,
  `fun parseWebhook(headers, body, config): CiEvent?`, `fun buildLink(ref, config): String?`.
- `data class CiRunRef(provider, runId, collectionUri?, project?)` — the correlation handle.
- `data class CiRun(spans: List<CiSpan>, queuedMs: Long?, startedAt, finishedAt)`;
  `data class CiSpan(kind: SpanKind /* STAGE|JOB|STEP */, name, startMs?, finishMs?,
  result: SpanResult?, workerName?, parentId?, id)`; `sealed CiEvent`.
- `class ConnectorConfig(baseUrl?, project?, credential: Credential)` where `Credential` is
  resolved from env only (`ConnectorConfigStore.forProject(projectId)` reads
  `BUILDHOUND_CONNECTOR_AZURE_PAT` in v1; a per-project table is a follow-up).
- `class ConnectorRegistry(connectors)` — `byId` / `byProvider` lookup; ships
  `AzureDevOpsConnector` + `NoopConnector`.
- `class NoopConnector` — `capabilities = emptySet()`, `fetchRun = null` — the honest
  default so a project with no connector still renders fully (spec §5 "strictly additive").

**Azure REST specifics.** Two calls, both GET, both Bearer/PAT-authed against
`{baseUrl}/{project}/_apis/build/builds/{buildId}` (the build, for `queueTime`/`startTime`/
`finishTime`) and `…/{buildId}/timeline?api-version=7.1` (records). Timeline `records[]` map
to `CiSpan` by `type` (`Stage`/`Phase`/`Job`/`Task`), nested via `parentId`; `queuedMs` =
`startTime − queueTime` on the top build record; span results map Azure
`succeeded|failed|canceled|skipped` → `SpanResult`. `AzureDevOpsConnector.buildLink` reuses
the existing `ci.buildUrl`.

**HTTP client.** Add Ktor client (CIO or OkHttp engine) — no HTTP client is in the catalog
today (`gradle/libs.versions.toml` has server-side Ktor only). Coordinate:
`io.ktor:ktor-client-core` + an engine + `ktor-client-content-negotiation`; **look up the
latest release at implementation time** (Maven Central metadata for the pinned `ktor`
version already in the catalog — keep it on the same `ktor.version`). A shared, timeout-
bounded client (connect + request + total deadline) with a small retry/backoff on 429/5xx.

**Enrichment flow (the framework's first instance).** Ingest stays synchronous for the
build envelope (`Routes.kt:48-100`, unchanged fast path). After a successful `store.save`,
if the payload's `ci` matches a registered connector with `TIMELINE_PULL`, submit a job to a
bounded, in-process `EnrichmentQueue` (single worker, per-instance — same "instance-local,
one pilot instance" posture as the rate limiter, arch §5). The worker: resolves
`ConnectorConfig`; if none, records `status = UNCONFIGURED` and stops (no outbound call,
no error); else `fetchRun` with **polite polling** — Azure timelines are complete only
after the build finishes, so retry with exponential backoff (e.g. 5s → capped ~2min, small
attempt budget) while the build record `status != completed`, then persist the tree. All
connector failures degrade to a logged `warn` + a stored `status = FAILED` — a connector
never fails ingest (mirrors the plugin's never-fail rule on the server side). The
service-hook receiver short-circuits the poll: on a valid `build.complete` hook it enqueues
the same job immediately.

**Storage.** New `CiSpanStore` interface (in-memory + Postgres impls, like `BuildStore`).
`saveRun(projectId, buildId, run, status)` upserts idempotently on `(project_id, build_id)`;
`findRun(projectId, buildId): StoredCiRun?`. Migration `V{n}__ci_spans.sql` (claim the next
free version integer at implementation time — plans 025/026/028/031/036/037/039 all add
migrations, so the merge order determines numbering; renumber deterministically to the next
free `V{n}` when merging): `ci_runs`
(project_id, build_id, provider, run_id, queued_ms, started_at, finished_at, status,
fetched_at, raw jsonb) + `ci_spans` (run row id, kind, name, start_ms, finish_ms, result,
worker_name, parent ordinal) — or a single `ci_runs` row storing the normalized tree as
jsonb (simpler, matches the spec's "store `extensions` as jsonb" precedent and keeps the
query a single read). Chosen: **jsonb tree in `ci_runs`** for v1; a flat `ci_spans`
hypertable is a rollups-era follow-up when span-level cross-build queries appear.

**Derived views.** `GET /v1/builds/{id}/ci-run` (read scope, tenant-filtered like all
query routes): returns `{ status, queuedMs, spans, gradleSharePct }`. **Queue time** =
`CiRun.queuedMs`. **Gradle share of pipeline** = ingested build wall-clock
(`finishedAt − startedAt` from the `builds` row) ÷ pipeline wall-clock
(`CiRun.finishedAt − CiRun.startedAt`), clamped to `[0,1]`, `null` when either is missing —
answers "how much of the pipeline was the Gradle build vs checkout/publish/sign steps".

**Dashboard.** Extend `detailView` (`dashboard.js:120-173`): after the task table, fetch
`/v1/builds/{id}/ci-run`; render a nested span list (stage → job → step) with per-span
duration bars and result badges (reuse `badgeClass` allowlist, extended with span results),
plus `queue time` and `Gradle share` chips. On `status != OK` or 404, render the amber
one-line "CI timeline not available (connector unconfigured / pending)" notice rather than
hiding the section (UX research §4.1.4 honesty rule). All span text via `textContent` only
(existing untrusted-string discipline).

## 4. Implementation steps

1. **Connector SPI + models.** New package `dev.buildhound.server.connector`:
   `CiConnector`, `Capability`, `CiRunRef`, `CiRun`, `CiSpan`, `SpanKind`, `SpanResult`,
   `CiEvent`, `ConnectorConfig`, `Credential`. Pure data + interface; `@Serializable` on
   the tree types so they round-trip to jsonb and out the query API.
2. **`NoopConnector` + `ConnectorRegistry`.** Registry keyed by `provider`; `NoopConnector`
   as the fallback. Unit-tested in isolation.
3. **HTTP client wiring.** Add `ktor-client-core`, an engine, and
   `ktor-client-content-negotiation` to `gradle/libs.versions.toml` (same `ktor` version
   ref; confirm the exact latest at implementation time) and to
   `buildhound-server/build.gradle.kts`. A `ConnectorHttpClient` factory with connect/
   request/total timeouts and 429/5xx backoff; injectable so tests supply a `MockEngine`.
4. **`AzureDevOpsConnector`.** Implement `fetchRun` (build + timeline calls, PAT auth,
   record→span mapping, `queuedMs`, result mapping), `buildLink`, and
   `parseWebhook` (validate `eventType == build.complete`, extract build id + collection/
   project, return a `CiEvent.RunCompleted`). Collection/project parsing helper over
   `ci.buildUrl` with a `ConnectorConfig` override; reject non-http(s) base URLs (reuse the
   commons `isHttpUrl` posture) so the outbound host is never `javascript:`/`file:`.
5. **`ConnectorConfigStore`.** v1: env-only PAT (`BUILDHOUND_CONNECTOR_AZURE_PAT`), plus an
   allowlist of permitted Azure host(s) from config so a payload can't redirect the fetch
   to an arbitrary host. Returns `null` (→ `UNCONFIGURED`) when no credential.
6. **`CiSpanStore` + migration `V{n}__ci_spans.sql`** (claim the next free version integer at
   implementation time — plans 025/026/028/031/036/037/039 all add migrations, so the merge
   order determines numbering; renumber deterministically to the next free `V{n}` when
   merging)**.** In-memory + Postgres impls;
   idempotent `saveRun`/`findRun` keyed on `(project_id, build_id)`; `status` enum
   (`OK|UNCONFIGURED|PENDING|FAILED`). Add both stores to `ServerStores`.
7. **`EnrichmentQueue`.** Bounded single-worker queue (per-instance); `submit(projectId,
   buildId, ci)`; backed-off `fetchRun` with the "poll until build completed" loop; persists
   via `CiSpanStore`; every failure path → `warn` log + `status=FAILED`, never propagated.
   Wire submission into `ingestRoutes` after a successful `save` (guarded by
   `ci.provider`/`runId`), and expose the queue in `buildHoundModule` so `testApplication`
   can drain it deterministically.
8. **Service-hook receiver.** `POST /v1/connectors/azure-devops/hook` — token-authed like
   ingest, tenant-scoped, size-capped body (reuse `receiveBounded`), `parseWebhook` →
   enqueue. Reject unknown/oversized bodies with 400; never trust the hook to name the
   tenant (tenant comes from the token).
9. **Query route.** `GET /v1/builds/{id}/ci-run` in `queryRoutes` (read scope): compute
   `queuedMs` + `gradleSharePct` from the stored run and the `builds` row; return status +
   spans. 404 when no run row exists.
10. **Dashboard detail.** Extend `dashboard.js` `detailView` with the span-tree section,
    queue-time/Gradle-share chips, span-result badge allowlist, and the amber degraded
    notice. Update the CSP style-hash pin if `index.html`'s `<style>` gains span-bar rules
    (`DashboardRoutes.kt` recomputes hashes from bytes — no manual hash edit needed).
11. **Config surface + docs.** Add the `BUILDHOUND_CONNECTOR_AZURE_PAT` + host-allowlist
    env vars (commented, dev-only note) to `deploy/compose.yaml`; document the connector +
    service-hook setup in `buildhound-ci-assets/README.md` (or a server self-host doc).
12. **Architecture decision log.** Add a §7 row: connector framework introduced
    (server-side `CiConnector` SPI, `NoopConnector` default, enrichment strictly additive,
    outbound HTTP env-only PAT + host allowlist, instance-local enrichment queue). Note the
    interface is the extension point [plan 041] and [plan 033] build on.

## 5. Test strategy

- **Unit (`connector`):** `AzureDevOpsConnectorTest` with a Ktor `MockEngine` — timeline
  JSON fixture → correct `CiRun` tree (stage/job/step nesting, `queuedMs`, result mapping);
  collection/project parsed from a real `_build/results?buildId=` URL; non-http base URL
  rejected; PAT sent as the auth header; `parseWebhook` accepts `build.complete` and rejects
  other event types / malformed bodies; `buildLink` echoes `ci.buildUrl`.
- **Unit:** `ConnectorRegistryTest` (provider lookup, Noop fallback);
  `GradleShareTest` (share = build/pipeline, clamp, null when either duration missing).
- **Failure-injection (phase guardrail):** `EnrichmentQueueTest` — `fetchRun` throwing /
  timing out / returning null / missing credential each yield the right `status`
  (`FAILED`/`UNCONFIGURED`) and **never** throw out of the ingest path; a build whose
  timeline is still `inProgress` retries then gives up without erroring.
- **Route (`testApplication`, no socket — arch §5):** ingest a `provider=azure-devops`
  payload, drain the queue, `GET /v1/builds/{id}/ci-run` returns spans + queue time +
  Gradle share; read-scope token required (403 for ingest-only); unknown build → 404;
  service-hook endpoint enqueues on a valid body and 400s on junk; cross-tenant read of
  another project's run is impossible (wrong-token 404, not leak).
- **Testcontainers:** `CiSpanStore` Postgres round-trip incl. idempotent `saveRun`
  (second save of the same `(project,build)` is a no-op/upsert), against plain Postgres so
  the migration's guarded TimescaleDB block still runs.
- **Golden files:** **none** — no schema-v1 change. Add a small connector-timeline JSON
  fixture under server test resources; the commons golden set is untouched (contract test
  unaffected).
- **Dashboard:** extend `DashboardScriptTest`/`DashboardRoutesTest` coverage for the new
  section's presence and the degraded-state copy path; assert no inline script (CSP).

## 6. Risks

- **SSRF / outbound host control (security).** `ci.buildUrl` is ingested data; deriving the
  Azure collection host from it lets a malicious payload point the server's authenticated
  fetch at an arbitrary URL. Mitigation: a **host allowlist** in `ConnectorConfig` (only
  configured Azure org hosts are dialable), scheme restricted to https, and the PAT sent
  only to allowlisted hosts — a payload can select *which* configured org, never introduce
  a new host. Documented in the decision-log row.
- **Credential handling (security/privacy).** PAT via env only
  (`BUILDHOUND_CONNECTOR_AZURE_PAT`), never in code/DSL/logs/image layers (arch §4/§6);
  never echoed in warnings; not stored in the span rows. Compose default is commented and
  dev-only.
- **Timeline data = potential PII.** Azure `workerName`/agent names and step names can name
  self-hosted machines. Mitigation: store `workerName` but treat it like `agentName`
  (plan 005 dropped it from the plugin payload for this reason) — keep it server-side only,
  do not surface raw in cross-tenant/exported views; revisit with pseudonymization if a
  need appears. No absolute paths/log text are pulled from the timeline.
- **Politeness / rate (reliability).** Backoff on 429/5xx and a bounded poll budget so a
  never-completing build can't hammer Azure; single-worker queue caps concurrency. Azure
  API version pinned (`api-version=7.1`) so a silent shape change surfaces as a parse
  failure → `status=FAILED`, not a crash.
- **CC hazards / isolated projects:** none — this is server-only; no Gradle plugin code, no
  configuration-cache or isolated-projects surface.
- **Schema compatibility:** additive by construction — no `buildhound-commons` change, new
  `ci_runs` table only, `GET …/ci-run` is a new route. Existing ingest/query untouched;
  a build with no connector renders exactly as today (spec §5 "renders fully").
- **Instance-local enrichment queue:** like the rate limiter (arch §5), N replicas each run
  their own worker → duplicate fetches, but `saveRun` is idempotent so the stored result is
  consistent; acceptable for the one-instance pilot, revisit on scale-out.

## 7. Exit criteria

- Ingesting an `azure-devops` build with a configured PAT + allowlisted host produces, after
  the enrichment worker runs, a stored `ci_runs` row; `GET /v1/builds/{id}/ci-run` returns
  the stage→job→step span tree, a non-null `queuedMs`, and a `gradleSharePct` in `[0,1]`.
- A build ingested with **no** connector configured returns `status=UNCONFIGURED` from the
  ci-run route and the build detail still renders fully with an amber notice — ingest
  latency and behavior are unchanged from today.
- A malformed/oversized service-hook body is rejected 400; a valid `build.complete` hook
  triggers enrichment; the tenant is taken from the token, never the hook body.
- A `fetchRun` that throws, times out, or hits a still-running build never fails ingest and
  is recorded as `FAILED`/pending (failure-injection test green).
- `./gradlew :buildhound-server:test` green including the new `MockEngine`, route, and
  Testcontainers tests; the commons golden-file contract test is unchanged (no schema edit).
- Build detail shows the CI span tree, queue time, and Gradle-share chips; `docs/architecture.md`
  decision log carries the connector-framework row; `deploy/compose.yaml` documents the PAT
  and host-allowlist env vars.
