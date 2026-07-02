# 010 — Query API + basic rollups (dashboard backend)

## Source

Roadmap Phase 1: "basic rollups. Dashboard: builds list, build detail, duration &
hit-rate trend charts with pipeline/branch/mode filters." Spec §5 (query API),
§6 (dashboard content). This chunk is the backend; the dashboard pages are chunk 011.

## Scope

**In:**

- `GET /v1/builds` — newest-first build summaries from the hot columns
  (buildId, startedAt, duration, outcome, mode, branch, hitRate), filters
  `branch`/`mode`/`outcome`, `limit` (default 50, max 200) + `offset`.
- `GET /v1/builds/{buildId}` — the full stored payload (build detail + timeline data;
  the dashboard renders it with the same data the HTML artifact embeds).
- `GET /v1/trends` — daily buckets over the last `days` (default 30, max 365):
  build count, failure count, avg + max duration, avg hit rate; same filters.
  **Rollups are computed on read** (SQL `date_trunc` group-by over the hot columns,
  which are indexed by `(project_id, started_at)`) — materialized/continuous
  aggregates arrive when data volume demands them; recorded divergence from the
  roadmap's "basic rollups" one-liner, the read-model shape is identical.
- All three routes Bearer-authenticated with the same token → project scoping as
  ingest; summaries/trends are server-owned DTOs (commons stays the plugin↔server
  wire contract only).
- `BuildStore` gains `list(projectId, filter, limit, offset)` and
  `trends(projectId, filter, days)`; both store implementations.

**Out (later):** dashboard HTML (chunk 011), pipeline filter (needs ci.pipelineName
as a hot column — additive migration later), p50/p95 percentiles, retention, rate
limiting (still a pre-pilot blocker), CSV export.

## Test strategy

- `testApplication` + in-memory: list ordering/filter/limit-cap/pagination, detail
  404 vs cross-tenant 404, trends bucketing + failure counts, auth on all three
  routes (401).
- Testcontainers: same assertions against real SQL (group-by correctness, filter
  parameterization) with seeded builds across three days and two branches.

## Risks

- SQL injection: filters are bound parameters only; `days`/`limit` are clamped ints.
- Tenant isolation: every query keyed by project_id from the token; cross-tenant
  reads must be impossible (tested).
- jsonb detail reads are per-build (no unbounded scans).

## Exit criteria

`./gradlew :buildhound-server:test` green; list/detail/trends behave identically on
in-memory and Postgres; a curl against the compose stack returns real data.
