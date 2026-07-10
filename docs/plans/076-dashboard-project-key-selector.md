# 076 — Dashboard project selector (payload `projectKey`)

## Source

Feature request (2026-07-10): when multiple different Gradle projects upload to the same
server, the dashboard mixes everything as if a single project uploads. Spec §6 (dashboard
pages), §4 (payload `projectKey`), §5 (tenancy — unchanged).

## Scope

**In:** a per-tenant selector over the payload's `projectKey` (the root project name the
plugin already sends). One tenant token, several repos → choose which repo's builds the
dashboard shows. Filter applies to: builds list/count, trends, artifact trends, all
`/v1/rollups/*` (project-cost, task-duration, negative-avoidance, bottlenecks, toolchain),
flaky, benchmark series. New enumeration endpoint. Project column in the builds table.

**Out:** cross-*tenant* switching (a read token stays bound to one tenant project — auth
model unchanged); baseline/regression keying (cross-repo baseline pollution is a known,
separate issue — follow-up candidate); shard-plan/classTimings (CI machinery, keyed by
reference); compare/detail/admin/settings/metrics routes (build-id- or tenant-keyed).
No schema change: `BuildPayload.projectKey` already exists (schema v1, additive rules hold;
golden files untouched).

**Terminology guard:** "project" in server code means *tenant* (`ProjectRef`,
`project_id`). This feature is the *payload* axis; it is named `projectKey` everywhere —
query param `projectKey`, endpoint `/v1/project-keys`, column `project_key`. UI label:
"Project".

## Design

- **DB — `V11__project_key.sql`** (append-only, V3 recipe): `ALTER TABLE builds ADD COLUMN
  project_key text` (nullable); one-shot backfill `SET project_key = payload->>'projectKey'`;
  index `builds_project_projectkey_started_idx (project_id, project_key, started_at DESC)`.
  Ingest (`insertBuild`, including the interrupted-build path) writes the column from
  `payload.projectKey`.
- **Stores** (both `PostgresBuildStore` and `InMemoryBuildStore`, parity by construction):
  - `BuildFilter` gains `projectKey: String? = null` → count/list/trends/artifactTrends
    pick it up through the existing filter plumbing (bound SQL param, never interpolated).
    `count` and `list` share the one filter object so `X-Total-Count` cannot drift.
  - Rollup/flaky/toolchain/benchmark store methods gain a trailing
    `projectKey: String? = null` param. Postgres: builds-table queries filter directly;
    `task_executions`- and `test_class_outcomes`-based queries add a join to `builds` on
    `(project_id, build_id)` **only when the filter is set** (unfiltered plans unchanged).
    In-memory: `payload.projectKey == projectKey` filter.
  - `BuildSummary` gains `projectKey: String? = null` (additive JSON field).
- **API** (all read-scope, tenant-scoped as today; openapi.yaml updated — path+method for
  the new endpoint, params documented for the rest):
  - `GET /v1/project-keys` → `[{ "projectKey": "...", "builds": n, "lastBuildAt": ms }]`,
    distinct non-null keys, newest-activity first (grouped scan of the new index).
  - Optional `projectKey` query param on `/v1/builds`, `/v1/trends`,
    `/v1/artifacts/trends`, `/v1/rollups/*`, `/v1/flaky`, `/v1/benchmark/series`.
    Validated: length ≤ 256, else 400; value only ever a bound parameter.
- **Dashboard** (`web/index.html` + `web/dashboard.js`, existing conventions: no innerHTML,
  `el()`/textContent, allowlisted classes, `renderSeq` guard, CSP untouched):
  - Header `<select>` populated from `/v1/project-keys` after a token exists; hidden unless
    ≥ 2 distinct keys. Selection in `sessionStorage["buildhound.projectKey"]` (mirrors the
    token slot; filters are not URL state today). Change → re-`route()`.
  - Every data view appends `projectKey=` to its query string when a selection is set
    (filter-pipeline views via `query()`, rollup views at their hardcoded query strings).
  - Builds table gains a "Project" column from `BuildSummary.projectKey` ("—" when null).
    Builds with null `projectKey` (pre-076 plugin) appear only under "All projects".

## Test strategy

- Route tests (`RollupRoutesTest` pattern): `/v1/project-keys` 401/403 + tenant isolation +
  ordering; `projectKey` filter on builds list **and** `X-Total-Count`; trends + one rollup
  + flaky filtered vs unfiltered; 400 on oversized param.
- `TestPayloads.build()` gains `projectKey` param (default null — existing fixtures
  unchanged).
- Testcontainers (`PostgresStoresIntegrationTest` et al.): in-memory/Postgres parity for
  the new filter on list/trends/rollups/flaky; backfill covered by inserting via jsonb then
  migrating? No — Flyway runs before inserts in tests; backfill correctness is asserted by
  ingesting a payload and reading the hot column, plus a manual note (self-hosted upgrades
  run the same UPDATE).
- Dashboard smoke (`dashboard-smoke.js` vm harness): canned `/v1/project-keys` response;
  selector renders with ≥2 keys, hidden with <2; selecting a project re-fetches with
  `projectKey=` (fetch stub keyed on exact paths); Project column asserted. Extend the DOM
  stub only if unavoidable.
- `OpenApiContractTest` keeps live routes ↔ spec in sync (new path added).

## Risks

- **Terminology collision** tenant-project vs payload-projectKey — mitigated by the naming
  guard above; reviewers should flag any `project=` param or "projects" endpoint naming.
- **Privacy:** no new data collected; `projectKey` is already in the payload, raw by
  design (spec §4 — not in §3.7's pseudonymization set), display stays tenant-scoped.
- **SQL injection surface:** new param is bound-only, length-capped, follows
  `filterSql()` discipline.
- **Query cost:** conditional join on `(project_id, build_id)` (unique index) — bounded;
  unfiltered paths byte-identical to today.
- **Backfill on large tables** is a one-shot full-table UPDATE (V3 precedent; acceptable
  at v0.1 scale, noted in migration comment).
- **Store parity drift** — every new filter lands in both stores + integration tests.

## Exit criteria

- Two repos ingesting under one tenant token are separable in every in-scope dashboard
  view via the header selector, and "All projects" reproduces today's behavior bit-for-bit.
- `./gradlew build` green (unit + functional + smoke); Testcontainers suite green.
- OpenAPI spec matches live routes; golden files untouched.
