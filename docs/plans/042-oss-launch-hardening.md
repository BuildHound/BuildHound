# Plan 042 — OSS-launch hardening: self-host docs, API docs, MCP surface, retention UI

**Status: planned — roadmap phase 4** · 2026-07-03

## 1. Source

- Roadmap [phase 4 item 5](../build-telemetry-roadmap.md): "OSS-launch hardening: self-host
  docs, API docs, optional MCP surface, retention config UI, Marketplace extension for Azure
  if adoption warrants." Phase-4 exit: "an outside team can self-host."
- Spec [§5](../build-telemetry-spec.md): one Docker image + compose for self-host; per-project
  retention overrides; **retention defaults** (task/kotlin/ci-span raw 90d → build-level 13mo →
  daily aggregates indefinite; nightly downsample + purge jobs); `GET /v1/…` query API
  "public, documented, versioned (agent/MCP-friendly)"; Admin page "retention, salt rotation".
- Spec [§8](../build-telemetry-spec.md): self-host compose documented à la Tuist; Apache-2.0;
  public docs site; hashed tokens; schema validated + size-capped.
- Architecture [§4 OCI rules](../architecture.md) (multi-stage, non-root, digest-pinned bases,
  SBOM/signing planned, small context) and [§5 server](../architecture.md) (persistence
  boundary, tenancy, on-read rollups) — this plan is the container-hardening review the roadmap
  requires against §4, and the decision log [§7](../architecture.md).
- Research [comparison-to-spec.md §3](../research/comparison-to-spec.md) (retention/downsampling,
  admin APIs, and salt issuance named as unbuilt product-layer work; pilot risk concentrates
  here) and [plugin-ecosystem-gap-analysis.md §3](../research/plugin-ecosystem-gap-analysis.md)
  (eBay's **User Query** summarizer → the query API doubles as the MCP tool surface).

This plan is the **last** phase-4 slice and documents/depends on the surfaces shipped by
016–041. Named cross-plan dependencies are stated in §2.

## 2. Scope

**In:**

1. **Self-host documentation** — `docs/self-hosting.md`: the compose quick-start (from
   `deploy/compose.yaml`), a generic single-container OCI deployment (env-var contract, external
   managed Postgres/TimescaleDB), TimescaleDB sizing guidance keyed off the retention windows,
   backup/restore (`pg_dump`/`pg_restore`, volume snapshot), and **token provisioning** (bootstrap
   env vars vs. the new admin write route; `openssl rand -hex 32` guidance already in compose).
2. **API documentation** — a hand-authored, checked-in `docs/api/openapi.yaml` (OpenAPI 3.1)
   covering every shipped `/v1/*` route, the Bearer auth scheme + token scopes, the versioning
   policy (`/v1` prefix = major; additive within a major, mirroring the payload contract rule),
   error shapes, and rate-limit headers. Served read-only at `GET /openapi.yaml` and rendered by
   a self-contained (zero-CDN) `GET /docs` viewer page. A test keeps the spec in sync with the
   route table.
3. **Optional MCP surface** — an opt-in, separately-shipped `buildhound-mcp` module: a stdio MCP
   server exposing **read-only** query tools (`list_builds`, `get_build`, `trends`, and the
   rollups from plan 026 if present) that call the existing read-scoped query API over HTTP with
   a `read` token. No new server endpoints, no write tools. Scope is evaluated and bounded in §3.
4. **Retention configuration UI + enforcement** — per-tenant retention windows (spec §5 defaults,
   overridable): a `project_settings`-backed retention config, an **admin-scoped** read/write
   route, a nightly purge job (build-level + raw task rows past their window; daily aggregates
   kept), and an Admin/Retention dashboard page. Introduces a new `admin` token scope.
5. **Azure DevOps Marketplace extension — decision, not build.** A short ADR-style section in
   `docs/self-hosting.md` (or `docs/decisions/`) recording the go/no-go and criteria; a
   decision-log row. Default posture: **defer** unless pilot adoption warrants it.
6. **Container-hardening review (arch §4)** — digest-pin the Dockerfile base images, add a
   non-blocking CI image-scan (+ SBOM) job, and record the residual items. Findings that are
   cheap land here; larger ones are recorded with owners.

**Out (named where they live):**

- New query/rollup *endpoints* themselves — owned by plan 010 (shipped), plan 025 (verdict,
  metrics), plan 026 (rollups), plan 039 (`/v1/addons/*`). This plan **documents** whatever
  exists at implementation time and adds only the retention admin route.
- Salt issuance / rotation and full tenant/token CRUD admin — spec §6 Admin; **not** in this
  plan beyond the retention slice and token provisioning docs. A follow-up admin plan owns them
  (noted; this plan adds the `admin` scope they will reuse).
- CI-provider correlation docs / composite actions — plan 041 (GHA + GitLab connectors + provider
  docs). This plan's self-host doc links to them, does not restate them.
- TimescaleDB continuous aggregates / hypertable conversion — deferred (plan 010/026 posture:
  materialize when volume demands). Retention here purges raw rows on a schedule; it does **not**
  require hypertables.
- Any MCP *write* tool, or an MCP server bundled into `buildhound-server` — explicitly excluded
  (§3 MCP scope decision).
- Regression/alert config UI — plan 025 seeds settings from env; a settings UI is a follow-up.

## 3. Design

**Modules touched:** `buildhound-server` (retention settings store + admin route + purge job +
openapi/docs routes + a new scope), a new `buildhound-mcp` module (opt-in, not wired into the
server image), `docs/` (self-hosting, api), `deploy/compose.yaml`, `buildhound-server/Dockerfile`,
`.github/workflows/ci.yml`, `docs/architecture.md`. **No plugin or `buildhound-commons` schema
change** — the wire payload is untouched, so golden/contract tests are unaffected.

**Current behavior (verified).**
- Token scopes are `ingest` / `read` / `all` only, gated by `authenticatedProject(tokens,
  scopeCheck)` per route — **no `admin` scope, no settings table**; `V1__core.sql` has only
  `projects` / `api_tokens` / `builds`
  ([BuildStore.kt:63-70](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt),
  [Routes.kt:157-176](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt),
  [V1__core.sql](../../buildhound-server/src/main/resources/db/migration/V1__core.sql)).
- **No retention, OpenAPI, MCP, or docs viewer exist anywhere** (grep-confirmed); retention is
  unbounded — plans 025 §2 and 026 §52 both defer "retention/admin UI" to *this* plan.
- Dashboard is two embedded static assets under a strict CSP (`script-src 'self'`, style pinned
  by hash), token kept client-side in `sessionStorage`
  ([DashboardRoutes.kt:20-67](../../buildhound-server/src/main/kotlin/dev/buildhound/server/DashboardRoutes.kt),
  [dashboard.js:1-46](../../buildhound-server/src/main/resources/web/dashboard.js)). The Admin
  page reuses this exactly: an **admin** token in `sessionStorage`, writes via the
  Bearer-authenticated route, every payload string via `textContent`.
- Dockerfile bases are **tag-pinned, not digest-pinned** (`eclipse-temurin:21-jdk-jammy` /
  `21-jre-jammy`, [Dockerfile:10,33](../../buildhound-server/Dockerfile)); arch §4.4 requires
  digest-pinning before release. The `server-image` CI job builds + health-smoke-tests but runs
  **no scan and produces no SBOM** ([ci.yml:64-96](../../.github/workflows/ci.yml); arch §4.7
  lists SBOM/signing as planned). `deploy/compose.yaml` DB image is `latest-pg16`, tag-pinned
  ([compose.yaml:49-50](../../deploy/compose.yaml)).

**Retention data model.** New migration `V<n>__project_settings.sql` (next free number at
implementation time — 025/026/039 also add migrations; pick the next after whatever has merged):

```sql
CREATE TABLE project_settings (
    project_id            uuid PRIMARY KEY REFERENCES projects (id),
    retention_raw_days    integer NOT NULL DEFAULT 90,    -- spec §5 default
    retention_build_days  integer NOT NULL DEFAULT 395,   -- ~13 months
    updated_at            timestamptz NOT NULL DEFAULT now()
);
```

`SettingsStore` (interface + in-memory + Postgres): `retention(projectId)` returns the row or the
spec defaults; `setRetention(projectId, RetentionConfig)` upserts with validation (each window
clamped to `[1, 3650]`, `build_days >= raw_days`). If plan 025's per-project settings table has
already landed, **extend that table** with the retention columns instead of adding a second one —
recorded as an implementation-time reconciliation in this plan's divergence note.

**Retention enforcement job.** A single-instance-safe scheduled purge (daemon thread started in
`main()`, not in `buildHoundModule` so `testApplication` never spawns it; interval from
`BUILDHOUND_RETENTION_SWEEP_HOURS`, default 24, `0` disables). Per project: delete `builds` rows
older than `retention_build_days`; delete raw `task_executions` rows (plan 026) older than
`retention_raw_days` when that table exists. Deletes are batched + `LIMIT`ed to bound lock time,
tenant-scoped, and wrapped so a storage error logs and the sweep retries next cycle — it never
crashes the server (server-side analogue of the never-fail rule). The N-replica caveat (arch §5:
instance-local state) is documented: run the sweep on one instance, or make it advisory-lock
guarded — recorded, single-instance pilot runs it unconditionally.

**Admin scope + route.** `TokenScope.ADMIN` added with `allowsAdmin(scope) = scope == ADMIN ||
scope == ALL` (mirrors the existing helpers, [BuildStore.kt:63-70](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt)).
Routes under `/v1/admin`, gated by `authenticatedProject(tokens, TokenScope::allowsAdmin)` and
covered by the existing per-host + query rate-limiters
([Application.kt:149-152](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt)):
- `GET /v1/admin/retention` → current `RetentionConfig`.
- `PUT /v1/admin/retention` → validated upsert (400 on invalid windows).
A `read` token must **not** reach these (403) — asserted by test.

**Admin/Retention dashboard page (`#/admin`).** New route in `dashboard.js`
([dashboard.js:257-274](../../buildhound-server/src/main/resources/web/dashboard.js)): a second
token bar labelled "Admin token" (separate `sessionStorage` key), a form reading/writing the two
retention windows via the admin route, honest empty/validation states. Nav link in `index.html`
([index.html:39-42](../../buildhound-server/src/main/resources/web/index.html)); the CSP style
hash recomputes from served bytes so no CSP edit is needed
([DashboardRoutes.kt:29-40](../../buildhound-server/src/main/kotlin/dev/buildhound/server/DashboardRoutes.kt)).

**OpenAPI + docs viewer.** `docs/api/openapi.yaml` is the source of truth, also embedded as a
server resource. `GET /openapi.yaml` serves it (public, `Cache-Control: no-cache`); `GET /docs`
serves a **zero-CDN** static viewer (a small hand-rolled HTML page that `fetch`es the spec and
renders the route list; no Swagger-UI CDN — same CSP posture as the dashboard). A
`OpenApiContractTest` parses the yaml and asserts every `route(...)`/`get`/`post`/`put` path
registered in the module appears in it (and vice-versa), so the doc can't silently drift from the
router — the discipline comparison-to-spec.md §4 item 12 demands for docs.

**MCP surface — scope decision (evaluated per roadmap).** Ship an **opt-in, read-only** MCP
server as a **separate module** `buildhound-mcp`, **not** merged into the ingest image. *Why
separate:* the ingest image is a hardened, non-root, network-facing service (arch §4); bolting a
stdio agent tool into it widens its surface for zero self-host benefit — MCP is a local
developer/agent convenience, distributed as its own artifact. *Tools (read-only only):*
`list_builds`, `get_build`, `trends`, plus `project_cost` / `task_duration` / `negative_avoidance`
**iff** plan 026 has landed — each a thin wrapper over the matching `GET /v1/*` call, using a
`read`-scoped `BUILDHOUND_TOKEN` against a configured `BUILDHOUND_URL`; no mutation or admin tools
(a leaked agent token must not change retention or reach another tenant). *Dependency:* the
official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) added to `gradle/libs.versions.toml`
— **look up the latest released version on Maven Central at implementation time**, do not pin from
memory; if the SDK is immature, fall back to a minimal hand-rolled JSON-RPC-over-stdio server (the
read-only surface is small) and record which path was taken. Reuses the query API + tenancy + rate
limits unchanged; adds no server code.

**Container hardening (arch §4 review).** Digest-pin both Dockerfile bases (resolve the current
`eclipse-temurin:21-jdk-jammy` / `21-jre-jammy` digests **at implementation time** and pin
`@sha256:…`); digest-pin the compose DB image similarly. Add a non-blocking CI job that scans the
built image (Trivy or Grype) and generates an SBOM (Syft), uploading both as artifacts —
satisfying the arch §4.7 "planned SBOM" item without gating merges yet. Cosign signing stays
deferred to a real release pipeline (recorded).

## 4. Implementation steps

1. **server** — add `TokenScope.ADMIN` + `allowsAdmin()`
   ([BuildStore.kt:63-70](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt));
   `RetentionConfig(rawDays, buildDays)` DTO (`@Serializable`, server-owned) with a `validated()`
   that clamps/orders the windows and returns the spec defaults on absence.
2. **server** — `V<next>__project_settings.sql` migration (table above), or extend plan 025's
   settings table if merged (record which in the divergence note); `SettingsStore` interface +
   `InMemorySettingsStore` + `PostgresSettingsStore` (`retention` / `setRetention`, upsert with
   `ON CONFLICT (project_id) DO UPDATE`). Add it to `ServerStores`
   ([Application.kt:22](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt)).
3. **server** — `adminRoutes(settings, tokens)`: `GET`/`PUT /v1/admin/retention`, admin-scope
   gated via `authenticatedProject(tokens, TokenScope::allowsAdmin)`, mounted inside the existing
   nested rate-limiters in `buildHoundModule`
   ([Application.kt:144-153](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt)),
   storage outages → 503 via the `respondQuery`/`runQuery` pattern
   ([Routes.kt:139-151](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt)).
4. **server** — `RetentionSweeper`: `BuildStore.purgeOlderThan(projectId, buildCutoffMs,
   rawCutoffMs)` (per-project, batched `DELETE … LIMIT`, tenant-scoped) on both store impls; a
   daemon-thread scheduler started only in `main()`
   ([Application.kt:56-63](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt))
   reading `BUILDHOUND_RETENTION_SWEEP_HOURS` (default 24, 0 disables), iterating every project's
   settings, catching + logging all errors. Log a one-line "retention sweep: purged N builds…"
   summary, and log "disabled" when 0 (distinguishable, like the rate-limit log).
5. **docs (api)** — author `docs/api/openapi.yaml` (3.1) for `/health`, `POST /v1/builds`,
   `GET /v1/builds`, `GET /v1/builds/{id}`, `GET /v1/trends`, plus whatever of 025/026/039's
   routes have merged; Bearer scheme, the `ingest`/`read`/`admin`/`all` scopes, versioning
   policy, `ApiError` shape, 401/403/413/422/503 responses, rate-limit note. Copy it into
   `buildhound-server/src/main/resources/api/openapi.yaml` (build-time copy or committed twin).
6. **server** — `GET /openapi.yaml` (serves the embedded spec) and `GET /docs` (zero-CDN viewer
   HTML that fetches + renders it) added to `dashboardRoutes` with the same CSP/nosniff headers
   ([DashboardRoutes.kt:49-67](../../buildhound-server/src/main/kotlin/dev/buildhound/server/DashboardRoutes.kt)).
7. **dashboard** — `#/admin` retention page + admin token bar in `dashboard.js`/`index.html`; nav
   link; extend `dashboard-smoke.js` for the render + a validation-rejection path.
8. **buildhound-mcp** — new module + `settings.gradle.kts` include; `application` main exposing
   the read-only tools over stdio; `BUILDHOUND_URL` + read `BUILDHOUND_TOKEN` config; MCP SDK
   coordinate added to `libs.versions.toml` (latest version looked up at implementation time),
   with the hand-rolled fallback if needed. A `README.md` shows the agent client config. **Not**
   added to the server image or `deploy/compose.yaml`.
9. **Dockerfile** — replace both `FROM` tags with digest-pinned refs (digests resolved at
   implementation time); mirror on the compose DB image ([compose.yaml:49-50](../../deploy/compose.yaml)).
10. **CI** — add a non-blocking `image-scan` job (`continue-on-error: true` or a separate
    workflow) after `server-image`: Trivy/Grype scan + Syft SBOM, uploaded as artifacts
    ([ci.yml:64-96](../../.github/workflows/ci.yml)).
11. **docs (self-host)** — write `docs/self-hosting.md`: compose quick-start, generic-OCI
    deployment + full env-var table, TimescaleDB sizing vs. retention windows, backup/restore,
    token provisioning (bootstrap vs. admin route), retention-config walkthrough, and the
    **Azure Marketplace decision** section (default: defer; criteria listed). Link it from
    `README.md` and the docs index.
12. **docs (spec/architecture)** — spec §5 Admin/retention marked delivered (defaults now
    enforced, per-project overridable via the admin route); spec §5 query API note that OpenAPI +
    MCP surfaces exist. `docs/architecture.md`: §4 base images now digest-pinned + scan/SBOM in
    CI; §5 gains the retention-sweep + `admin` scope; **decision-log rows** for (a) MCP shipped as
    a separate read-only module, not in the ingest image, (b) retention enforcement as a
    single-instance scheduled purge with the N-replica caveat, (c) the Azure Marketplace go/no-go,
    (d) base-image digest-pinning.
13. Re-read this plan against the diff; record any divergence here in the same PR (esp. the
    migration number and whether the settings table was shared with plan 025).

## 5. Test strategy

- **Server unit (`testApplication`, in-memory):** `GET/PUT /v1/admin/retention` returns 401
  without a token, 403 with a `read`-only token, 200/validated round-trip with an `admin`/`all`
  token; invalid windows (`rawDays<1`, `buildDays<rawDays`, `>3650`) → 400; a second project's
  settings are never visible (tenant scoping). `/openapi.yaml` + `/docs` return 200 with the CSP
  + `nosniff` headers and no `unsafe-inline`.
- **`OpenApiContractTest`:** the set of paths in `openapi.yaml` equals the set of `/v1` + top-level
  routes registered by `buildHoundModule` (drift guard, both directions).
- **`RetentionSweeper` unit:** given seeded builds/task rows and a cutoff, purge deletes only rows
  past the window, only for the target tenant, and leaves within-window rows; a store that throws
  is caught (sweep logs, does not propagate); `0`-hours config = disabled (no thread).
- **Testcontainers (`PostgresStoresIntegrationTest`):** `PostgresSettingsStore` upsert idempotency
  + defaults-on-absence; `purgeOlderThan` deletes `builds` and `task_executions` (when present)
  past the windows and nothing newer; batched delete completes for a large fixture.
- **Dashboard smoke (`DashboardScriptTest` via `dashboard-smoke.js`):** `#/admin` renders the
  retention form from a canned `GET`, submits a `PUT`, and shows the validation-error branch —
  no throw, no inline script.
- **Container:** the existing image smoke test still passes with digest-pinned bases; the scan/SBOM
  job produces artifacts (non-blocking). No new golden/contract tests — the wire schema is
  untouched, so the additive-schema guardrail is trivially satisfied.
- **MCP:** a unit test drives each tool against a stubbed query API (or `testApplication`) and
  asserts it issues a read-scoped GET and returns the mapped result; a test asserts **no** tool
  performs a write/admin call.

## 6. Risks

- **New `admin` scope = privilege escalation surface.** Retention writes and (later) tenant admin
  ride this scope; a leaked admin token can purge data. Mitigations: `admin` is a *distinct*
  scope (a CI `ingest` or dashboard `read` token can never reach admin routes — asserted),
  admin routes are rate-limited like queries, and the dashboard keeps the admin token in a
  separate `sessionStorage` slot cleared from the live DOM (dashboard.js pattern). Purge is the
  only write action for now; full admin CRUD is explicitly out of scope.
- **Destructive retention job.** A wrong window or a bug deletes real data irreversibly.
  Mitigations: windows validated + clamped (`build_days >= raw_days`, `[1,3650]`); the sweep is
  tenant-scoped and batched; the default is the spec's conservative 90d/13mo; `SWEEP_HOURS=0`
  fully disables it; and the docs make backup/restore a first-class step *before* enabling
  aggressive windows. Daily aggregates are never purged by this job.
- **Multi-replica double-purge / instance-local scheduler** (arch §5). N replicas each run the
  sweep → redundant deletes (idempotent, but wasteful) and N× effective work. Documented; pilot
  runs one instance; an advisory-lock guard is the noted upgrade path, not built now.
- **Docs/OpenAPI drift** — the failure comparison-to-spec.md §4 item 12 calls the ecosystem's most
  universal defect. Mitigated by `OpenApiContractTest` (route table ↔ spec) and by the
  "self-host doc updates in the same PR" discipline; the spec/architecture edits are part of this
  plan's steps, not a follow-up.
- **MCP token/tenant leakage.** The MCP server holds a `read` token; if it also had ingest/admin
  reach, a compromised agent could write. Bounded by construction: read-only tools only, one
  `read`-scoped token, no admin/write tools, separate artifact from the server. No new server
  endpoint, so no new authz surface on the ingest service.
- **Zero-CDN doc viewer.** A Swagger-UI-from-CDN page would violate the same CSP posture the
  dashboard enforces (locked decision #4 spirit for served pages). The hand-rolled viewer keeps
  `script-src 'self'`; risk is only cosmetic (less polished than Swagger UI) — acceptable.
- **CC / isolated projects:** not applicable — no plugin or `buildhound-commons` collector code
  changes; the MCP module runs outside any Gradle build; the schema is untouched.
- **Base-image digest pinning staleness.** Digest pins go stale (miss patches). The non-blocking
  scan job surfaces CVEs; a Renovate/scheduled bump (comparison-to-spec.md §3 item 14) is the
  refresh mechanism — noted, not required for the exit criteria.

## 6b. Implementation-time reconciliations (recorded per step 13)

- **Settings table shared with plan 025 (as planned).** Plan 025's `project_settings` table already
  existed, so retention was added as **`V10__retention.sql` ALTER** (two columns) rather than a second
  table; `SettingsStore` gained `retention()`/`setRetention()` that touch only the retention columns
  (regression `put()` and retention writes never clobber each other — parity-tested).
- **MCP: hand-rolled, not the SDK.** The MCP Kotlin SDK was verified on Maven Central at 0.14.0 (still
  0.x, pulls a Ktor/coroutines stack). For six read-only GET proxies the hand-rolled JSON-RPC-over-stdio
  server (`kotlinx-serialization` + JDK `HttpClient`) was chosen — the plan's sanctioned fallback.
  Recorded in `buildhound-mcp/README.md` and the architecture decision log.
- **Digests pinned at implementation time** (`docker buildx imagetools inspect`): temurin 21-jdk-jammy
  `sha256:9d8dcf99…`, 21-jre-jammy `sha256:d63bd8d9…`, timescaledb latest-pg16 `sha256:ba149561…`.
- **Docs viewer is a bespoke route-list renderer**, not Swagger-UI: the strict served-page CSP forbids a
  CDN, so `/docs` fetches `/openapi.yaml` and renders the path table with a tiny in-page parser.

## 7. Exit criteria

- `./gradlew :buildhound-server:test` and `./gradlew build` green, including the admin-route,
  retention-sweep, OpenAPI-contract, and dashboard-smoke additions; `buildhound-mcp` builds and
  its tool tests pass.
- An outside operator can stand up the stack from `docs/self-hosting.md` alone (compose **or**
  generic OCI): provision a token, ingest a build, read it back, and set a retention window —
  the phase-4 "an outside team can self-host" exit is met by documentation that matches running
  behavior.
- `GET /openapi.yaml` serves a spec whose paths exactly match the live route table (contract test
  green); `GET /docs` renders it with no external requests and no `unsafe-inline`.
- `PUT /v1/admin/retention` (admin scope) persists per-tenant windows; a `read` token gets 403;
  the nightly sweep purges builds/raw task rows past their window and nothing newer
  (Testcontainers-verified), never crashing the server.
- The `buildhound-mcp` server exposes only read-only query tools over stdio against the query API;
  no write/admin tool exists (test-asserted).
- Dockerfile (and compose DB) bases are digest-pinned; a non-blocking CI job emits an image scan
  report + SBOM. `docs/architecture.md` §4/§5 + decision log carry the MCP, retention, base-image,
  and Azure-Marketplace rows; the Azure Marketplace go/no-go is recorded (default: deferred).
- No `buildhound-commons` schema change; golden files and the schema contract tests are unmodified
  in the diff.
