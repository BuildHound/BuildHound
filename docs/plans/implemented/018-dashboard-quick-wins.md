# Plan 018 — Dashboard v0 quick wins: empty states, work-avoidance ledger, count-summary headers

**Status: planned — roadmap phase 2a** · 2026-07-03

## 1. Source

- [Roadmap phase 2a](../build-telemetry-roadmap.md), bullet "Dashboard v0 quick wins from the
  UX research" (the timeline named in the same bullet is plan 017's).
- [dashboard-ux-research.md](../research/dashboard-ux-research.md) §4.1.4 (empty/degraded
  states are MVP scope), §4.1.2+§4.1.5 (work-avoidance ledger with explicit zeros),
  §4.1.6 (count-summary sentences), §5 ("empty states are not mentioned" is the one
  by-omission gap in plan 012; token entry should read as first-run, not error).
- [research/README.md](../research/README.md) §4 item 6.
- Spec [§6 Dashboard](../build-telemetry-spec.md); extends
  [implemented/012-dashboard.md](012-dashboard.md).

## 2. Scope

**In:**

1. **Contextual empty states** (Tuist pattern) on all three dashboard views, including a
   deliberate first-run state when no token is stored, and a get-started pointer (plugin
   snippet + buildhound.dev docs link) when the project has zero ingested builds.
2. **Work-avoidance ledger** on build detail (Develocity pattern): count + percentage +
   duration triples covering the task-outcome enum 1:1, every row rendered with explicit
   zeros. Must be honest pre-plan-016: no invented cacheability data.
3. **Bold natural-language count-summary sentences** as section headers on builds list,
   build detail, and trends — plus the minimal server support the list sentence needs
   (filter-aware total count exposed as a response header).

**Out:** task timeline and any task-table changes such as dimmed no-op rows (plan 017) ·
`type`/`cacheable` population and the honest hit-rate denominator (plan 016) · KPI strip
with delta chips and the Overview/Bottlenecks landing page (plan 032) · clickable
category pivots and hash-encoded filter state (deferred with the pipeline filter,
plan 012 trade-off list) · comparisons page (plan 022). No plugin code, no schema change.

## 3. Design

All three wins are client-side except one additive server header. Current behavior,
verified in source:

- Empty states today are one-line muted strings: `"No builds."`
  (`buildhound-server/src/main/resources/web/dashboard.js:105`) and `"No data in range."`
  (`dashboard.js:223`), identical whether the project is empty or a filter excluded
  everything. With no stored token, `route()` fires a request that 401s, unhides the
  token bar, and renders red error text ("token required", `dashboard.js:38-48`) — the
  first thing every pilot user sees is an error state.
- The detail view's "cache summary" is outcome-count chips built only from outcomes
  present in the build (`dashboard.js:146-156`) — no zeros, no percentages, no durations.
- No view has a heading sentence; detail leads with `projectKey — buildId`
  (`dashboard.js:128`), trends shows builds/failures/days chips (`dashboard.js:224-230`).
- `GET /v1/builds` returns a bare `BuildSummary` array page
  (`buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt:109-116`); no total
  exists for a filter — `BuildStore.count(projectId)` ignores filters and is only used by
  tests (`buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt:91-92`).
- `TaskOutcome` = EXECUTED, UP_TO_DATE, FROM_CACHE, SKIPPED, NO_SOURCE, FAILED
  (`buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt:110`);
  all six are already in the dashboard badge-class allowlist (`dashboard.js:21`).
  `TaskExecution.durationMs`/`module` are populated on every task
  (`buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TaskEventCollector.kt:30`);
  `cacheable` is schema-present but always null until plan 016 (`BuildPayload.kt:102`).

**Empty states.** `route()` renders a first-run panel (token bar visible, muted
explanatory copy, no fetch, no error styling) when `sessionStorage` has no token. After a
fetch, an empty result at `offset === 0` splits on whether filters are active: filtered →
"No builds match this filter" + a clear-filters button; unfiltered → the get-started
state: heading, one line of copy, a `<pre>` snippet (settings.gradle.kts `plugins { id("dev.buildhound") … }`
plus a `buildhound { server { url/token } }` block using `location.origin` for the URL and
`providers.environmentVariable("BUILDHOUND_TOKEN")` for the token — never a literal), and
a docs link. Trends reuses the same shared renderer with range-aware copy. All content is
static strings or `location.origin` via `textContent` — the plan-012 no-innerHTML rule holds.

**Work-avoidance ledger.** Replaces the outcome-chip block in `detailView` with a small
table computed by a pure function over `build.tasks`: rows *All tasks* → *Avoided* (child
rows *From cache*, *Up to date*) → *Executed* (child rows *Cacheable*, *Not cacheable*,
*Unknown cacheability*, split on `task.cacheable` true/false/null) → *Failed* → *Skipped*
→ *No source*. Columns: count, percentage of all tasks (one decimal), summed `durationMs`
(right-aligned, existing `ms()` formatter, labeled *task time* since parallel sums exceed
wall clock). Every row always renders — "From cache 0 (0.0%) 0 ms" — so layouts stay
comparable across builds. Pre-016 honesty falls out of the data: all executed tasks have
`cacheable == null` and land under *Unknown cacheability* (exactly Develocity's row for
this case); when 016 lands, the split fills in with zero layout change. The existing
hit-rate chip (`derived.cacheableHitRate`, different denominator per
`DerivedMetricsCalculator.kt:32-37`) stays as-is; ledger percentages are explicitly
share-of-all-tasks.

**Count-summary sentences.** Bold sentence as the first element of each view. Detail and
trends need only data already fetched: "18 tasks in 4 modules — 42.3 s total task time"
(module count from `task.module`), and the trends chips become "84 builds with 3 failures
across 12 active days in the last 30 days". The builds list needs a filter-aware total:
extend `BuildStore` with `count(projectId, filter)` (default `BuildFilter()` keeps
existing test callers source-compatible), implemented in `InMemoryBuildStore` and
`PostgresBuildStore` (same WHERE-clause builder as `list`, `PostgresStores.kt:101-107`),
and exposed as an `X-Total-Count` response header on `GET /v1/builds` — additive, keeps
the documented array body shape. Sentence: "312 builds on main" with filter qualifiers
appended from the active filter values (user-typed branch reaches the DOM via
`textContent` only). Opportunistic bonus: the pager uses the total, removing plan 012's
recorded dead-click on exact multiples of 50 (`dashboard.js:112`).

## 4. Implementation steps

1. `BuildStore.kt`: change `count` to `count(projectId: String, filter: BuildFilter = BuildFilter())`;
   update `InMemoryBuildStore` (reuse `matching()`) and `PostgresStores.kt` (shared
   filter-clause builder + bound parameters, as `list` does).
2. `Routes.kt` `queryRoutes`: in `GET /v1/builds`, compute the filtered count inside the
   same `runQuery` block as `list` and set `X-Total-Count` before responding.
3. `web/dashboard.js`: add an `apiList(path)` variant returning `{ items, total }` from
   the response header (tolerating a missing/non-numeric header → `null` total, so the
   smoke stub and any cached older server degrade to today's behavior).
4. `web/dashboard.js` + `web/index.html`: first-run token state in `route()`; shared
   `emptyState(kind, filterActive, onClear)` renderer with get-started snippet; wire into
   `buildsView` (offset-0 empty) and `trendsView`; CSS for `.empty`, `.snippet`,
   `.summary-sentence`, `.ledger` (the CSP style hash recomputes from served bytes at
   startup, `DashboardRoutes.kt:29-40` — no manual hash step).
5. `web/dashboard.js`: pure `avoidanceLedger(tasks)` → ordered rows with counts,
   percentages, summed durations; render as the table described above in `detailView`,
   replacing the chip block at `dashboard.js:146-156`; keep badge classes for row labels.
6. `web/dashboard.js`: count-summary sentences on all three views; builds-list pager
   switches to `offset + 50 < total` when `total` is known.
7. `src/test/resources/web/dashboard-smoke.js`: give stubbed responses a `headers.get`
   stub; add scenarios — unfiltered empty list (asserts get-started copy + no token
   literal in the snippet), filtered empty list (asserts clear-filters path re-fetches),
   detail ledger (asserts explicit-zero rows and the unknown-cacheability bucket),
   missing `X-Total-Count` tolerated, first-run no-token render performs no fetch.
8. Server tests: `ApplicationTest` — `X-Total-Count` present, filter-aware, and
   tenant-scoped on `GET /v1/builds`; `PostgresStoresIntegrationTest` — filtered count
   agrees with filtered list length. `DashboardRoutesTest` CSP pins are unaffected but
   re-run (the style hash changes with the CSS edit).
9. Docs: none beyond this plan. No schema change → no golden files (contract untouched);
   no architectural decision made or reversed → no `docs/architecture.md` decision-log
   entry. If implementation diverges, update this file in the same PR.

## 5. Test strategy

- **Node smoke harness** (`DashboardScriptTest` running `dashboard-smoke.js`) is the
  primary UI regression net — all step-7 scenarios execute in a real JS engine, keeping
  the plan-006 lesson (string assertions miss SyntaxErrors).
- **testApplication**: header presence/absence per filter and tenant; existing 401/403
  and CSP tests must stay green unchanged (the pages remain public and data-free).
- **Testcontainers**: Postgres filtered-count parity with `list` under branch/mode/outcome
  filters, including the no-match → 0 case.
- **Failure-injection (server-side analogue)**: smoke scenarios for API failure (existing
  error path), header missing, and empty `tasks` array on detail (ledger renders all-zero
  rows instead of dividing by zero — percentage falls back to 0.0% when `allTasks == 0`).
- No golden files: `buildhound-commons` is untouched.

## 6. Risks

- **XSS surface**: new DOM content is static copy, `location.origin`, and user-typed
  filter values — all through the `el()`/`textContent` helpers; no `innerHTML` anywhere.
  CSP (`script-src 'self'`, hash-pinned styles) stays the backstop; the hash self-updates.
- **Secrets**: the get-started snippet must show the env-var provider pattern
  (architecture §6), never a token value; smoke test asserts the rendered snippet
  contains `BUILDHOUND_TOKEN` as an env-var reference and not the session token.
- **API compatibility**: `X-Total-Count` is additive; the body stays a plain array, so
  existing consumers and the versioned query-API posture are unaffected. Rejected
  alternative: wrapping the list in an envelope object (breaking).
- **Honesty pre-016**: the ledger must not imply cacheability knowledge — the
  unknown-cacheability bucket is the truthful rendering until `cacheable` is populated;
  its percentages are labeled share-of-all-tasks to avoid clashing with the (still
  denominator-dirty) hit-rate chip that plan 016 fixes.
- **Cost**: the extra `count(*)` per list request duplicates the list WHERE at pilot
  scale — acceptable; revisit with the materialized-rollup note from plan 010.
- **CC / isolated projects / plugin failure paths**: not applicable — no plugin or
  commons code changes in this plan.

## 7. Exit criteria

- A fresh compose-stack user opening `/` with no token sees a styled first-run panel (no
  red error, no failed request); after pasting a token into an empty project they see the
  get-started empty state with the plugin snippet, and the same state (range-worded) on
  trends; an active filter with zero matches offers clear-filters instead.
- Build detail shows the work-avoidance ledger with all six outcome categories and the
  cacheability sub-split rendered, zeros explicit, durations right-aligned — verified by
  a smoke-harness scenario and by eyeball against a real ingested build.
- All three views lead with a bold count-summary sentence; the builds-list sentence shows
  the filter-aware total from `X-Total-Count`, and paging past the last page is no longer
  offered on exact multiples of 50.
- `./gradlew :buildhound-server:test` green, including the new smoke scenarios,
  header tests, and Postgres count parity; CSP tests still pin no `unsafe-*` sources.

## 8. Divergences from the plan (recorded during implementation)

- **Plans 016 and 017 landed before this one**, so two "pre-016 honesty" premises are now
  moot (in a good way): the ledger's `Executed → Cacheable / Not cacheable / Unknown`
  split shows *real* data because `task.cacheable` is populated (plan 016) — the split
  code handles all three cases unchanged, so no rework was needed and the
  unknown-cacheability bucket now fills only for genuinely unknown tasks. And the detail
  view already carries the plan-017 timeline; the ledger sits between the summary chips and
  that timeline, with the task table last.
- **`PostgresBuildStore.count` override cannot carry the interface default** (Kotlin forbids
  default values on overrides), so the one concrete-typed test caller
  (`PostgresStoresIntegrationTest`) passes `BuildFilter()` explicitly. Interface-typed
  callers (`ServerStores.builds`) stay source-compatible via the interface default, as the
  plan intended.
- **`apiList` parses `X-Total-Count` with `Number(raw)` + `Number.isFinite`, not
  `parseInt`** — the node smoke harness's `vm` context intentionally exposes only an
  allow-list of globals and does not include `parseInt`; `Number(...)` is already in scope
  and gives the same tolerant-null behaviour for a missing/blank/non-numeric header.
- **Trends stat chips became the count-summary sentence** (as the plan described) — the
  old `builds / failures / days` chip row is fully replaced, not kept alongside.
