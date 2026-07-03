# Plan 036 — Flaky test detection (two signals) + flaky page

**Status: planned — roadmap phase 4** · 2026-07-03

## 1. Source

- Roadmap [phase 4 item 1](../build-telemetry-roadmap.md): "Flaky detection (Tuist's two
  signals: intra-run retry divergence + cross-run divergence keyed on (commit, module, test))
  → flaky page", gating the quarantine addon (plan 037).
- Spec [§5 "Flaky detection (v1.x, gates quarantine)"](../build-telemetry-spec.md) and
  [§6 dashboard "Tests (… flaky page v1.x)"](../build-telemetry-spec.md); locked decision #3
  (quarantine gated on proven flaky-detection precision).
- Research: [plugin-ecosystem-gap-analysis.md §2.3](../research/plugin-ecosystem-gap-analysis.md)
  (the two Tuist signals, comparison key = `(commit sha, module path, test identity)`, "no
  schema change needed; the server-side algorithm is the new work") and
  [test-distribution-addon.md §2.6](../research/test-distribution-addon.md) (the pinned
  `modulePath + "/" + classFqcn` join key).

## 2. Scope

**In** — purely server-side detection over data already ingested by plan 024, plus a dashboard
page. No plugin change; no schema change.

- Two flaky signals computed server-side:
  1. **Intra-run retry divergence**: a `failedOrRetried` case in one build whose `outcomes`
     sequence contains a `FAILED` followed by a `PASSED` (Test Retry plugin fail-then-pass).
  2. **Cross-run divergence**: the same join key `(sha, modulePath, classFqcn)` reaching both a
     passed and a failed outcome across two or more builds. Class-level rollups suffice to
     *detect* it; the failing build's per-case detail names the offending case.
- A `FlakyStore` capability over the plan-024 test tables: incremental detection on ingest plus a
  windowed query, exposed as `GET /v1/flaky` (read scope) returning per-(module, class[, case])
  records: flake rate, first/last seen, sample count, affected build ids, and which signal fired.
- A **Flaky** dashboard page (new nav entry + hash route) rendering flake rate, first/last seen,
  affected modules, and the signal that fired, reusing the v0 SPA conventions.
- **Alert integration via plan 025**: a new "flaky" alert kind dispatched through plan 025's
  existing webhook channels when a class newly crosses the flake-rate threshold.
- **Precision-validation method** (below, §4 step 8) so the pilot can prove precision *before*
  plan 037 unquarantines anything — this plan is the gate.

**Out** (and where it lives):

- Test collection / ingestion of the `tests` payload block and the tests page — **plan 024**
  (hard dependency; this plan reads what 024 writes).
- Alert channel plumbing (Slack/Teams/generic webhook dispatch, budgets, verdict endpoint) —
  **plan 025**; this plan only adds a new alert *kind* to that dispatcher.
- Quarantine closed loop (`excludeTestsMatching` / `ignoreFailures` skipped/muted modes) —
  **plan 037** (gated on this plan's precision validation; locked decision #3).
- Score decay / half-life weighting — deferred; v1 uses a fixed trailing window (§6 records why).
- Any plugin-side change: this plan touches `buildhound-server` only.

## 3. Design

Everything lands in `buildhound-server`. The current server exposes builds via the persistence
boundary `BuildStore` (`buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt:47`),
with `InMemoryBuildStore` and `PostgresBuildStore`
(`buildhound-server/src/main/kotlin/dev/buildhound/server/PostgresStores.kt:35`). Query routes
authenticate with `TokenScope::allowsRead` and compute rollups on read
(`buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt:107`). The dashboard is a
zero-dependency hash-router SPA (`buildhound-server/src/main/resources/web/dashboard.js:257`) whose
nav lives in `index.html:39` and whose CSP is hash-pinned over the embedded resources
(`buildhound-server/src/main/kotlin/dev/buildhound/server/DashboardRoutes.kt:29`).

**Dependency on plan 024's shape.** The `main` schema
(`buildhound-commons/.../payload/BuildPayload.kt:12`) has no `tests` field yet; plan 024 adds the
spec §4 `tests` block (per-class rollup + `failedOrRetried[]` carrying an `outcomes` sequence) and
the `test_classes`/`test_case_events` tables (spec §5). This plan consumes those — if 024's
field/column names differ at implementation time, update §3/§4 here in the same PR and say why.

**Join key (single source of truth).** The comparison key is `"${module ?: ""}/$classFqcn"`, the
same key pinned for sharding
([test-distribution-addon.md §2.6](../research/test-distribution-addon.md)). To avoid Tuist's
degeneration bug (client and server keyed the string differently, silently breaking balancing), the
key is defined **once** in `buildhound-commons` by **plan 024** as `TestUnitKey.of(module, classFqcn)`
(null module → empty-string prefix), with `TestClassResult.unitKey()` delegating to it and the
pinning test living beside that single definition. This plan **references** `TestUnitKey.of(...)`
verbatim — it never redefines the object or introduces a competing key — and it is the same helper
called by plan-024 ingestion and plan-040 sharding. Cross-run detection keys by
`(projectId, sha, unitKey)`; case identity (`unitKey + "#" + caseName`) is the reporting grain when
the failing side supplies a per-case name.

**New types / files:**

- `FlakyDetector.kt` — pure Kotlin (Gradle/Ktor-free, plain unit tests, mirroring the
  `DerivedMetricsCalculator` precedent). Given a window of per-build test outcomes keyed by
  `(sha, unitKey)`, emits `FlakyRecord`s: cross-run `flakeRate = divergentBuilds / sampleCount`;
  a retry-only case is flaky with rate from retried-vs-total appearances. `minSamples` (default 3)
  and `minFlakeRate` (default 0.05) suppress one-offs — thresholds in code, not docs.
- `FlakyRecord` (serializable): `module`, `className`, `caseName?`, `signal`
  (`RETRY | CROSS_RUN | BOTH`), `flakeRate`, `sampleCount`, `firstSeenMs`, `lastSeenMs`,
  `affectedBuildIds` (capped), and `affectedModules` for the roll-up view.
- `FlakyStore.kt` — interface + `InMemoryFlakyStore` and `PostgresFlakyStore`, mirroring the
  `BuildStore` split. `recordBuildTests(projectId, buildId, sha, testData)` (idempotent on
  `(project, buildId)`) and `flaky(projectId, days)`.
- `GET /v1/flaky` in `queryRoutes` (`Routes.kt:107`): read-scope, tenant-filtered, `?days=` window
  coerced like `/trends`, returned via `respondQuery`.
- Migration `V{n}__flaky.sql` (claim the next free version integer at implementation time — plans
  025/026/028/031/036/037/039 all add migrations, so the merge order determines numbering; renumber
  deterministically to the next free `V{n}` when merging): `test_class_outcomes` (project_id, build_id, sha, module, class_fqcn,
  passed/failed counts, first_seen; unique on `(project_id, build_id, module, class_fqcn)`) with an
  index on `(project_id, sha, module, class_fqcn)` for the cross-run join — detection reads this
  narrow table, never re-parses jsonb. If plan 024 already lands `test_classes`, reuse it and add
  only the missing index (reconcile in §4).
- Dashboard: `flakyView()` + `#/flaky` route in `dashboard.js`, a **Flaky** nav link in
  `index.html`; any new CSS class is re-hashed into the CSP automatically (`DashboardRoutes.kt:29`).

**Alert kind (plan 025).** Add a `FLAKY` variant to plan 025's alert model; the detector calls that
dispatcher when a class first crosses `minFlakeRate` in the window (edge-triggered, not per-build,
to avoid storms). Alert body: module, class, rate, signal, sample count, first/last seen, dashboard
deep-link. No new channel code here.

**Data flow:** ingest post-processing (the plan-024/025 normalize step) → `recordBuildTests` upserts
per-class outcomes → `FlakyDetector` re-evaluates the touched `(sha, unitKey)` groups → new-flaky
transitions fire a plan-025 alert → `GET /v1/flaky` and the dashboard read the same store.

## 4. Implementation steps

1. **Reference the commons join key.** Use `TestUnitKey.of(module, classFqcn)` — defined once by
   plan 024 in `buildhound-commons` as `"${module ?: ""}/$classFqcn"` with its pinning test — for
   every cross-run/case key here; do not redefine it or add a competing helper. Pure helper — no
   schema field, golden files untouched.
2. **Migration `V{n}__flaky.sql`** (claim the next free version integer at implementation time —
   plans 025/026/028/031/036/037/039 all add migrations, so the merge order determines numbering;
   renumber deterministically to the next free `V{n}` when merging) in `.../resources/db/migration/`:
   the `test_class_outcomes` table +
   cross-run index from §3 (or, if plan 024 lands `test_classes`, only the missing index, noted in
   the migration comment). Every column tenant-scoped by `project_id` (architecture §5).
3. **`FlakyDetector.kt`** (pure): two-signal algorithm, `minSamples`/`minFlakeRate` thresholds,
   `RETRY | CROSS_RUN | BOTH` classification, flake-rate math, deterministic ordering (rate desc,
   then key).
4. **`FlakyStore.kt`**: interface + in-memory + Postgres. `PostgresFlakyStore` uses bound parameters
   only, tenant-filtered (copy the injection-safe pattern from `PostgresStores.kt:92`);
   `recordBuildTests` is idempotent via `ON CONFLICT DO NOTHING`, matching the builds table.
5. **Wire the store** into `ServerStores` (`Application.kt:22`) and construct it in
   `storesFromEnvironment` (`Application.kt:74`).
6. **`GET /v1/flaky`** in `queryRoutes` (`Routes.kt:107`): read-scope
   `authenticatedProject(tokens, TokenScope::allowsRead)`, `days` coerced `1..365`, via
   `respondQuery` so storage outages stay 503.
7. **Ingest hook**: in the plan-024/025 post-processing step call `recordBuildTests`, re-run
   `FlakyDetector` over the touched `(sha, unitKey)` groups, and on a new-flaky transition invoke
   plan 025's dispatcher with a `FLAKY` alert. Guard the hook so a detection failure never fails
   ingest (log + continue — the server-side analogue of the plugin's never-fail rule).
8. **Precision-validation method** (the gate for plan 037), runnable on the pilot. Export the
   flagged set (`GET /v1/flaky?days=30`); a human labels each flagged class *truly flaky*
   (reproducible nondeterminism / known-flaky ticket) or *false positive* (a regression later fixed,
   i.e. divergence explained by an intervening commit, or a shared infra outage). **Precision =
   truly-flaky / flagged**, required **≥ 0.90** before plan 037 ships and recorded in its PR; recall
   against the pilot's known-flaky ticket list is reported as a secondary, non-gating signal.
   Confounder controls baked into detection keep precision honest: cross-run divergence requires the
   **same sha** (a fix between runs is not counted), excludes builds whose overall `outcome` was
   `FAILED` for an unrelated task (filter to the test-task signal), and requires `minSamples`.
9. **Dashboard**: `flakyView()` + `#/flaky` route (all payload text via `textContent`,
   `dashboard.js:27`), a **Flaky** nav link (`index.html:39`), and a table (module · class · flake
   rate · signal · first/last seen · samples); reuse `filterControls`/`query` for branch filtering.
10. **Docs**: move spec §5 flaky bullet and §6 tests-page note from "v1.x/planned" to shipped; add
    an architecture decision-log row recording the commons-pinned `(sha, module, class)` join key
    (shared with sharding) and the 0.90 pilot-precision gate for quarantine; update roadmap phase-4
    item-1 status.

## 5. Test strategy

- **Unit (`FlakyDetectorTest`, pure, no containers):** retry signal fires on
  `outcomes=["FAILED","PASSED"]` and not on `["FAILED","FAILED"]`; cross-run fires when the same
  `(sha, unitKey)` is green in build A and red in build B and **not** when the shas differ; `BOTH`
  when both signals fire; `minSamples`/`minFlakeRate` suppress a single divergence; flake-rate math;
  deterministic ordering. Confounder cases: unrelated build-level `FAILED` is excluded;
  same-sha-different-branch handled per key definition.
- **Join-key pinning (commons):** the exact `"${module ?: ""}/$classFqcn"` format is pinned by
  plan 024's `TestUnitKey` test — this plan adds none of its own; it relies on that single
  definition so client and server can never drift (the Tuist bug).
- **Route test (`FlakyRoutesTest`, `testApplication`):** `GET /v1/flaky` is 401 without a token,
  403 with an ingest-only token, 200 with read scope; window coercion; empty-tenant returns `[]`.
  Mirrors `ApplicationTest`'s seeded-tenant fixture (`ApplicationTest.kt:44`).
- **Testcontainers (`PostgresFlakyStoreIntegrationTest`):** real round trip — upsert per-class
  outcomes across two same-sha builds, assert the cross-run record; idempotent re-ingest of the
  same build id adds nothing; tenant isolation (a second project sees none of the first's flaky
  records). Follows `PostgresStoresIntegrationTest.kt:22` (`disabledWithoutDocker`).
- **Failure-injection (phase guardrail):** the ingest hook swallows a thrown detector/store error
  and still returns `202` for the build (assert the build persists even when flaky detection
  throws) — the server-side never-fail analogue.
- **Dashboard (`DashboardScriptTest` / smoke):** the `#/flaky` route renders without inline script;
  the new nav link is present; CSP still has no `unsafe-*` (the hash recompute in
  `DashboardRoutes.kt` covers any added style).
- **Golden files:** none edited. If plan 024's payload golden already contains a `tests` block,
  this plan adds no new schema field, so no new golden is required; a fixture JSON for the detector
  lives in server test resources, not the commons golden set.

## 6. Risks

- **Schema compatibility:** zero schema change — detection is server-side over plan-024 data; no
  cross-module addition (the `TestUnitKey` helper is defined by plan 024, this plan only references
  it). Contract tests unaffected.
- **Dependency ordering:** hard-blocked on plan 024 (test ingestion + tables), soft-coupled to plan
  025 (alert dispatcher). If 024's table/field names differ, §3/§4 are updated in the same PR; the
  detector's pure core is insulated from storage names by the `FlakyStore` boundary.
- **Precision / false positives (central risk):** a false-flaky that is really a regression would,
  once plan 037 lands, wrongly quarantine a broken test. Mitigations: same-sha requirement for
  cross-run, exclusion of unrelated build-level failures, `minSamples`/`minFlakeRate` thresholds, and
  the **≥ 0.90 pilot precision gate** (§4 step 8) that plan 037 cannot bypass. No decay/half-life in
  v1 — it would add a tuning knob before we have ground truth; the fixed window is easier to
  validate, revisit once the pilot has labelled data.
- **Cardinality:** flaky records are bounded (one per divergent class), but the window query must
  use the `(project_id, sha, module, class_fqcn)` index and cap rows, and the detector caps
  `affectedBuildIds` per record (budget in code) — a class-name-churning project can't blow memory.
- **Isolated projects:** irrelevant — no plugin/config-cache code here (that is plan 024's concern).
- **Security / privacy (spec §3.7):** class FQCNs, module paths, and case names are already-ingested
  scrubber-cleared plaintext (spec §3.5) — no new PII or absolute paths. The endpoint is read-scoped
  and tenant-filtered like every query route; SQL uses bound parameters only. Alert payloads reuse
  plan 025's channel (already redacted/rate-limited); the deep-link is the dashboard URL, no secrets.
  Logs carry ids/counts, never raw messages or tokens.

## 7. Exit criteria

- `GET /v1/flaky?days=30` returns, for the pilot, per-(module, class) flaky records with flake rate,
  first/last seen, sample count, affected build ids, and the firing signal; 401/403 without read
  scope; `[]` for a tenant with no divergence.
- A build carrying `outcomes=["FAILED","PASSED"]` produces a `RETRY` flaky record; two same-sha
  builds with a class green in one and red in the other produce a `CROSS_RUN` record; differing shas
  produce none.
- The **Flaky** dashboard page lists those records with a working nav link and no CSP regression.
- A newly-flaky class fires exactly one plan-025 `FLAKY` alert (edge-triggered, not per build).
- A thrown error inside the flaky ingest hook does not fail ingest (build still `202` and stored).
- The precision-validation method is documented and produces a precision number on the pilot;
  plan 037 references this plan's ≥ 0.90 gate. `./gradlew :buildhound-server:test` green (plus the
  Testcontainers leg where Docker is present).
