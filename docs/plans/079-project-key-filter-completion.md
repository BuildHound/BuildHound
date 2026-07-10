# 079 — projectKey filter completion (post-077 surfaces)

## Source

Follow-up to plan 077: while it was in flight, main landed dashboard surfaces without the
`projectKey` param, so with a project selected they silently render fleet-wide data.
Audited inventory below (Routes.kt + BuildStore.kt + PostgresStores.kt + dashboard.js).

## Scope

**In — server (optional `projectKey` param, validated via `projectKeyParamOrBadRequest`,
hot-column-only filtering, both stores in parity):**

| Endpoint | Store change |
|---|---|
| `/v1/rollups/plugin-cost` (058) | conditional builds join in `taskRowsInDaysWindow` |
| `/v1/rollups/change-blast-radius` (063) | conditional builds join in **both** reads (`build_changed_modules` + `task_executions`) |
| `/v1/rollups/rerun-causes` (061) | plumbing only — `taskRowsBetween`/`payloadsBetween` already accept it |
| `/v1/rollups/warnings` (060) | direct `AND b.project_key = ?` in the jsonb-scan WHERE (builds is the FROM table) |
| `/v1/rollups/cache-miss-diagnostics` (068) | in-SQL clause **pre-LIMIT**; in-memory filter **before** `.take(cap)` |
| `/v1/rollups/cache-roi` (067) | same pre-LIMIT rule |
| `/v1/rollups/cc-economics` (064) | same pre-LIMIT rule |
| `/v1/rollups/recommendations` (054) | same, via `windowPayloads(…, projectKey)` |
| `/v1/rollups/delivery-health` (059) | direct hot-column clause **plus** the second plumbing point: `enrichDeliveryHealth`'s internal `store.flaky(…)` call gets the same key |
| `/v1/tags` (057) | `tagKeys(…, projectKey)` — direct conditional clause |
| `/v1/trends/cohorts` (057) | **route-only**: wire `projectKeyParamOrBadRequest` into the existing `buildFilterOrNull(projectKey)` (store already honors `BuildFilter.projectKey`) |

Endpoints without dashboard call sites (recommendations, rerun-causes, cc-economics,
cache-miss-diagnostics) are still filtered server-side — API consistency; UI may consume
them later.

**In — dashboard:** thread the selection through the six live call sites
(change-blast-radius, plugin-cost, warnings, cache-roi, delivery-health, `/v1/tags` +
`/v1/trends/cohorts`), remove every "deliberately unfiltered" marker, and fix
`withProjectKey()` to handle paths without an existing query string (`/v1/tags` would
otherwise become `/v1/tags&projectKey=…`). Clear the module-level `selectedTagKey` when
the project selection changes (a kept key may not exist in the new project's tag set).

**Out:** per-build point reads (`/v1/builds/{id}`*, verdict, ci-run, compare, diagnosis,
parallelism, graph, per-build recommendations); settings/admin/addons (tenant-scoped
config, not build windows); `/v1/metrics/prometheus` (series identity is the tenant
label); the tests/compare build pickers (077 decision stands); baseline/regression keying.

## Design

- Nine `BuildStore` methods + `tagKeys` grow a trailing `projectKey: String? = null`
  (interface + both impls). Unfiltered SQL stays byte-identical (empty-clause or verbatim
  two-branch pattern, as in 077); filtered task-table reads join builds on
  `(project_id, build_id)` (unique → no fan-out); builds-table reads use the indexed
  `project_key` hot column — never `payload->>'projectKey'`.
- **Capped windows filter before the cap** (the sharp edge): Postgres puts the clause
  before `ORDER BY … LIMIT`, binding the LIMIT via the `flaky()` limitIndex pattern
  (conditional param shifts positional indexes); in-memory filters via
  `payloadsBetween(…, projectKey)` before `.take(cap)`. A post-decode filter would let a
  busy sibling repo starve the selected repo's window.
- Routes: every wiring goes through `projectKeyParamOrBadRequest` (≤256 → 400); openapi
  gains the `ProjectKey` `$ref` on the eleven ops (descriptions only, contract test green).
- Dashboard: existing `withProjectKey()`/`query()` helpers; `withProjectKey` gains
  `path.includes("?") ? "&" : "?"`.
- Semantics note (intended, test-visible): fleet-share gates and %-coverage denominators
  (warnings, rerun-causes, recommendations, cc-economics' reuse verdict) now compute over
  the selected repo's window. Pre-077 builds (`project_key IS NULL`) drop out of any
  filtered view — consistent with the landed 077 endpoints.

## Test strategy

- Route tests (extend the suites where each endpoint's tests live): filtered vs
  unfiltered for one task-table rollup (plugin-cost), one capped whole-payload rollup
  (cache-roi), delivery-health (including the flaky enrichment panel), tags +
  trends/cohorts; 400 on an over-long key for one newly-wired route (validation is shared).
- **Cap-starvation test** (the trap): seed cap-exceeding sibling-repo builds newer than
  the selected repo's; assert the filtered capped window still sees the selected repo's
  builds (fails if the filter lands post-LIMIT). Use a small injected cap if the constant
  is too large to seed — otherwise assert via ordering on a shrunk fixture set.
- Testcontainers parity: in-memory ↔ Postgres for every re-signed method, filtered and
  unfiltered, non-vacuous (sibling repo data must exist and be excluded).
- Dashboard smoke: canned `projectKey=`-variant responses for the newly-wrapped paths;
  assert delivery + tags views fetch filtered when selected and byte-identical paths when
  not; assert `withProjectKey("/v1/tags")` produces `?projectKey=`; assert
  `selectedTagKey` resets on project switch.

## Risks

- Interface ripple across ten methods — mechanical, but every override + test double must
  move together (compiler enforces).
- Positional-binding drift in the four capped queries — limitIndex pattern + parity tests.
- Denominator changes could surprise fixture-based assertions in existing suites — new
  params default to null, so existing tests are unaffected by construction.
- No migration, no schema change, no new endpoint (param additions only).

## Exit criteria

- With a project selected, every dashboard view renders that repo's data only; "All
  projects" and param-absent API calls are byte-identical to today.
- `./gradlew :buildhound-server:test` green incl. Testcontainers + smoke; full build green.
- openapi matches live routes; golden files and migrations untouched.
