# Plan 032 — Bottlenecks / what-regressed landing page + toolchain adoption view

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- Roadmap [phase 3](../build-telemetry-roadmap.md): "Bottlenecks/'what regressed' landing page;
  toolchain-version adoption view", with the phase exit criterion "bottlenecks page answers
  'what got worse this week'".
- Spec [§6 Dashboard](../build-telemetry-spec.md): the **Overview/Bottlenecks** page ("what
  regressed in 7d: duration, hit rate, flaky count, budget breaches") and the dimension-slice
  framing; [§5](../build-telemetry-spec.md) query API + per-project settings (budgets).
- Research: [dashboard-ux-research.md §4.2.2](../research/dashboard-ux-research.md) (Overview/
  Bottlenecks as the Tuist-style fleet-first landing view — KPI cards with semantically-colored
  "since last period" delta chips, headline trend, "View more" drill-downs), §4.2.4 (dimension
  slicing / "Configuration Insights" by Gradle version, JDK, module), §2/§4.1.4-5 (plain-language
  numbers, explicit zeros, honest degraded states). Fleet-first pattern is Tuist's, §1.2/§3.
- Extends [implemented/012-dashboard.md](implemented/012-dashboard.md) and reuses the conventions
  from [018-dashboard-quick-wins.md](018-dashboard-quick-wins.md) (empty states, count-summary
  sentences) and the rollup/endpoint scaffolding from
  [026-server-rollups-project-cost.md](026-server-rollups-project-cost.md).

## 2. Scope

**In:**

- A server **bottlenecks rollup** computed on read (plan 010 posture), tenant-scoped, read-scope
  gated: `GET /v1/rollups/bottlenecks?period=7` returns a *this-period vs prior-period* comparison
  over four families, each as a ranked list with explicit deltas:
  - **Top regressed tasks/modules** — task name/module whose avg `duration_ms` grew most vs the
    prior equal-length window (absolute + percent; new/disappeared groups flagged, not divided).
  - **Slowest critical work** — the longest-running task groups this period (the "biggest offender
    first" ranking), a standing bottleneck list independent of regression direction.
  - **Negative-avoidance offenders** — reuses plan 026's `negativeAvoidanceMs` signal aggregated
    over this period (tasks where avoidance cost more than executing).
  - **Cache-miss hotspots** — cacheable task groups with the highest miss count × duration this
    period (Tasks-explorer §6 "miss-rate × duration ranking"), honest-degraded until plan 016
    populates `cacheable`.
  - Plus **headline KPIs** (build count, success rate, avg duration, cacheable hit rate) each with
    a prior-period delta, and, when plan 025 is present, a **budget-breach / trend-regression
    count** surfaced from its verdicts.
- A server **toolchain-adoption rollup**: `GET /v1/rollups/toolchain?days=30` — distribution of
  each toolchain dimension (`gradle`, `jdk`, and the schema-present-but-unpopulated `agp`/`kgp`/
  `ksp`) across the fleet: version → build count, share, distinct-user count (hashed), most-recent
  build; a "who is behind" view = users still on non-latest-observed versions. Honest degraded
  state for dimensions with no data.
- A dashboard **Bottlenecks page** made the **landing route** (`#/` → bottlenecks; existing
  `#/builds`/`#/trends`/`#/build/{id}` unchanged), Tuist-style: KPI card strip with semantic delta
  chips → the four ranked bottleneck sections → "View more" links into Builds/Trends/Tasks. A
  dashboard **Toolchain adoption section** (on the same page or `#/toolchain`) rendering the
  distribution and behind-list.
- Spec §6 amendment (Overview/Bottlenecks + toolchain view delivered); no architecture decision-log
  entry unless a new migration is required (see §3 — the default design adds none).

**Out (named plans own these):**

- Task `type`/`cacheable` population and the honest hit-rate denominator — **plan 016** (hard
  dependency for by-type regression grouping and cache-miss hotspots; degrades to name-grouping /
  "unavailable" without it).
- `task_executions` normalized table, `negativeAvoidanceMs` commons helper, Project-Cost / by-type
  / by-name rollups and the `/v1/rollups/*` route + `#/tasks` page scaffolding — **plan 026** (hard
  dependency; this plan adds `bottlenecks` and `toolchain` rollups to that same surface).
- Rolling baselines, PR-vs-baseline verdicts, budget thresholds and per-build alert dispatch —
  **plan 025** (this plan *reads* its budget-breach/verdict counts if present; the period-over-
  period comparison here is its own simpler window diff, not 025's median+MAD baseline).
- AGP/KGP/KSP version **collection** in the plugin — not planned anywhere yet; this plan renders
  those toolchain dimensions as honest "not collected yet" and files a follow-up note (§6). Only
  `gradle`/`jdk` carry data today.
- Empty-state / count-summary / ledger conventions — **plan 018** (reused, not re-specified).
- Lost-build (INTERRUPTED) accounting — **plan 033**; CI queue-time / span tree — **plan 028**.
- Comparisons page / input-fingerprint diff — **plan 022**. No plugin code, no schema change.

## 3. Design

**Modules touched:** `buildhound-server` only — new rollup DTOs, `BuildStore` methods, routes,
dashboard JS/HTML. **No plugin change, no `buildhound-commons` change, no payload-schema change**:
every input already ships. **No new migration in the default design** (see below).

**Current behavior (verified).** The server exposes exactly three read endpoints — `GET /v1/builds`
(list), `GET /v1/builds/{buildId}` (full payload), `GET /v1/trends` (daily buckets) — all computed
on read over the `builds` table, tenant-scoped, read-scope gated
([Routes.kt:107-135](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt),
[BuildStore.kt:47-60](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt)).
`builds` extracts only envelope hot columns; the full schema-v1 document is in `builds.payload`
jsonb ([V1__core.sql:32-48](../../buildhound-server/src/main/resources/db/migration/V1__core.sql)).
The dashboard is three hash routes (`#/builds` default, `#/trends`, `#/build/{id}`) in a single
zero-dependency `dashboard.js` under a strict CSP (`script-src 'self'`, hash-pinned style;
[DashboardRoutes.kt:29-40](../../buildhound-server/src/main/kotlin/dev/buildhound/server/DashboardRoutes.kt)),
default landing is the builds list ([dashboard.js:257-271](../../buildhound-server/src/main/resources/web/dashboard.js)).

**Toolchain data reality (verified — this is the load-bearing honesty constraint).** The plugin
populates only `toolchain.gradle` and `toolchain.jdk`
([PayloadAssembler.kt:67](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadAssembler.kt)
reads `EnvironmentValueSource.gradleVersion`/`jdkVersion`,
[EnvironmentValueSource.kt:68-69](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/EnvironmentValueSource.kt)).
`agp`/`kgp`/`ksp` exist in `ToolchainInfo`
([BuildPayload.kt:69-75](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt))
but are **always null**. The adoption view therefore ships real distributions for Gradle and JDK
and an explicit "not collected yet" degraded panel for AGP/KGP/KSP — never an empty chart the user
mistakes for "everyone on one version". `environment.userId` is the pseudonymized `u_…` HMAC
(spec §3.7); "who is behind" counts and lists distinct **hashed** ids only — no de-pseudonymization.

**Why period-over-period, not 025's baseline.** The exit criterion is "what got *worse this week*"
— a coarse, always-available fleet signal that must render before plan 025's regression engine
lands. This plan computes a simple **this-window vs prior-equal-window** diff over already-ingested
`builds`/`task_executions`; it is honest about small-sample noise (a min-build-count guard per
group, new/vanished groups flagged rather than shown as ∞/−100 %). When plan 025 *is* present, the
page additionally surfaces its budget-breach and trend-regression counts — additive, not a
replacement.

**Data source: task_executions (plan 026) with graceful degradation.** The task-level bottleneck
families (regressed tasks, slowest work, negative avoidance, cache-miss hotspots) aggregate over
the normalized `task_executions` table plan 026 introduces (indexed by
`(project_id, started_at)`, `(project_id, module)`, `(project_id, type)`). This plan therefore
sequences **after 026** and adds no schema of its own. If 026 has not landed, the store methods
fall back to jsonb-payload scans over `builds` for the current + prior window only (bounded by the
7/14-day window, acceptable at pilot scale) so the page still renders — a documented degraded path,
not a hard dependency failure. Toolchain distribution reads `builds.payload->'toolchain'` directly
(low cardinality; no new column needed — a jsonb group-by over the window is cheap and avoids a
migration; if pilot volume ever strains it, an extracted column is a follow-up).

**Migration decision.** Default design: **no migration**. Toolchain slicing is a windowed jsonb
group-by; task bottlenecks reuse 026's table. This deliberately sidesteps the Flyway
version-number race — plans 025/026/028/031/036/037/039/040 each add a migration and none pins its
version (each claims the next free `V{n}` at merge time). Adding zero migrations here keeps 032 orthogonal to
that race. *If* review decides an extracted `gradle_version`/`jdk_version` hot column is warranted,
it claims the next free `V{n}__toolchain_cols.sql` at implementation time (not a number pinned in
this plan) and adds a decision-log row; the plan is written to not require it.

**Endpoints (server).** Two `GET /v1/rollups/*` routes appended to `queryRoutes`
([Routes.kt:107-135](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt)),
matching plan 026's shape exactly: read-scope authenticated via
`authenticatedProject(tokens, TokenScope::allowsRead)`, tenant-scoped by `project.id`, storage
outages → 503 via `respondQuery`, window param clamped (`period` `[1,90]`, `days` `[1,365]`).
Response DTOs are server-owned `@Serializable` classes (commons stays wire-contract only — plan
010 rule): `BottlenecksRollup` (headline KPIs with `current`/`prior`/`deltaPct`, plus
`regressedTasks`/`slowestWork`/`negativeAvoidance`/`cacheMissHotspots` each `List<BottleneckRow>`
with `key`, `module?`, `currentMs`, `priorMs?`, `deltaMs`, `deltaPct?`, `isNew`, `count`), and a
`budgetBreaches: Int?`/`trendRegressions: Int?` populated only when plan 025's verdict store is
wired (null otherwise → UI omits the card); `ToolchainRollup` (per dimension: `available: Boolean`,
`versions: List<ToolchainVersionRow(version, builds, sharePct, distinctUsers, lastSeenMs)>`,
`behind: List<ToolchainVersionRow>` = non-latest-observed). `BuildStore` gains `bottlenecks(...)`
and `toolchainAdoption(...)`; `InMemoryBuildStore` computes over stored payload lists (keeps
DB-less dev + `testApplication` working) and `PostgresBuildStore` runs SQL agreeing exactly (the
plan-026 two-store parity discipline).

**Dashboard Bottlenecks landing page.** `route()` default changes from builds to a new
`bottlenecksView()` ([dashboard.js:257-271](../../buildhound-server/src/main/resources/web/dashboard.js)),
`#/` and `#/bottlenecks` both resolve to it; `#/builds`/`#/trends`/`#/build/{id}` unchanged. The
page renders: a KPI card strip (build count, success rate, avg duration, hit rate) each with a
**semantically-coloured delta chip** — failure/duration increases red, hit-rate/success increases
green (delta encodes goodness not sign, research §4.2.2); a period toggle (7 / 14 / 30 days); the
four ranked bottleneck tables with plain-language count-summary headers, explicit zeros, and
"View more" links into `#/tasks`/`#/trends`; and the toolchain-adoption section (Gradle/JDK
distribution bars + behind-list, AGP/KGP/KSP degraded panel). All payload-derived strings reach the
DOM via the existing `el()`/`textContent` helper (payload untrusted,
[dashboard.js:27-32](../../buildhound-server/src/main/resources/web/dashboard.js)); badge/delta CSS
classes stay allowlisted like [dashboard.js:21-22](../../buildhound-server/src/main/resources/web/dashboard.js);
new nav link in `index.html` ([index.html:38-44](../../buildhound-server/src/main/resources/web/index.html));
the CSP style hash recomputes from served bytes automatically
([DashboardRoutes.kt:29-40](../../buildhound-server/src/main/kotlin/dev/buildhound/server/DashboardRoutes.kt)).
Degraded/empty states reuse plan 018's renderer: no builds in window → get-started/empty copy; a
dimension with no data → an amber "unlock this" line (Gradle/JDK always present, AGP/KGP/KSP the
honest "populate task types style" note; cache-miss hotspots note plan 016 when `cacheable` is all
null).

## 4. Implementation steps

1. **server — DTOs.** Add `BottlenecksRollup`, `BottleneckRow`, `KpiDelta`, `ToolchainRollup`,
   `ToolchainVersionRow` as server-owned `@Serializable` classes (new `Rollups.kt` or alongside
   plan 026's rollup DTOs). Delta fields carry `current`/`prior`/`deltaPct` and `isNew`/vanished
   flags so the client never divides by zero or renders ∞.
2. **server — `BuildStore` interface.** Add `bottlenecks(projectId, period, nowMs)` and
   `toolchainAdoption(projectId, days, nowMs)`. Default the budget/verdict fields to null so the
   signature is independent of whether plan 025 is present.
3. **server — `InMemoryBuildStore`.** Implement both over stored payload lists: window-split the
   builds, aggregate task groups (by name; by type when non-null — 016), compute period deltas with
   the min-build-count guard, reuse plan 026's `negativeAvoidanceMs` and cache-miss (`cacheable` +
   `FROM_CACHE`/miss) logic; toolchain group-by over `payload.toolchain.{gradle,jdk,agp,kgp,ksp}`
   with distinct hashed `userId`. This is the parity oracle for the SQL path.
4. **server — `PostgresBuildStore`.** SQL implementations: task families over `task_executions`
   (two windowed CTEs joined on group key, `project_id` + `started_at` bounded, top-N `LIMIT`,
   bound params only); toolchain via `builds.payload->'toolchain'->>'…'` group-by with
   `count(distinct payload->'environment'->>'userId')`. Include the jsonb-scan fallback for the
   task families guarded on whether `task_executions` exists (or simply require 026 landed — plan
   sequenced after it; the fallback is the belt-and-braces path documented in §3).
5. **server — routes.** `GET /v1/rollups/bottlenecks` (`period` clamp `[1,90]`) and
   `GET /v1/rollups/toolchain` (`days` clamp `[1,365]`) in `queryRoutes`, read-scope + tenant
   scoped, `respondQuery` outage path — copy plan 026's route wiring verbatim.
6. **server — plan-025 hook (optional, guarded).** If the verdict/budget store from plan 025 is
   wired, populate `budgetBreaches`/`trendRegressions` counts for the window; otherwise leave null.
   Written so 032 compiles and passes with or without 025 merged.
7. **dashboard — `bottlenecksView()`** in `dashboard.js`: KPI card strip with semantic delta chips,
   period toggle, four ranked tables (count-summary headers, explicit zeros, View-more links),
   toolchain distribution + behind-list + AGP/KGP/KSP degraded panel. Make `#/` and `#/bottlenecks`
   resolve to it in `route()`; leave the other routes untouched.
8. **dashboard — `index.html`** nav: add a "Bottlenecks" link (first), keep Builds/Trends; add CSS
   for `.kpi`, `.delta-up`/`.delta-down` (semantic, not sign), `.behind`, reusing plan 018's
   `.summary-sentence`/`.empty`. The CSP style hash self-updates from served bytes.
9. **dashboard tests — `dashboard-smoke.js`** (the harness plan 018/026 introduce, run by
   `DashboardScriptTest`): canned `/v1/rollups/bottlenecks` + `/v1/rollups/toolchain` responses and
   a `#/bottlenecks` render pass, including: a regressed-task row, a `deltaPct` new-group (`isNew`)
   branch, an all-null-`cacheable` cache-miss degraded branch, and a toolchain response with
   AGP/KGP/KSP `available:false` (asserts the degraded panel renders, no throw), plus an empty-
   window branch (no builds → empty state, no divide-by-zero).
10. **server tests — `ApplicationTest`** (`testApplication`, in-memory): each new route returns 401
    no-token / 403 ingest-scope / tenant-scoped (a second project's builds never appear) / happy
    path; `period`/`days` clamps honored; toolchain `available:false` for AGP/KGP/KSP on real
    fixtures; the plan-025 counts null when the verdict store is absent.
    **`PostgresStoresIntegrationTest`**: seed builds across the current + prior window, ≥2 modules,
    ≥2 hashed users, mixed Gradle/JDK versions; assert bottleneck deltas, negative-avoidance and
    cache-miss rankings, and toolchain distribution/behind-list match the in-memory store
    byte-for-byte, and that a group present only in the prior window is flagged vanished (not −100 %
    silently), a group only in the current window is `isNew`.
11. **docs, same PR** — spec §6: mark Overview/Bottlenecks and the toolchain-adoption view
    delivered; note the period-over-period (not baseline) basis and the AGP/KGP/KSP-not-collected
    gap. `docs/architecture.md`: **no decision-log row** in the default (no-migration) design; add
    one only if the extracted-toolchain-column alternative is chosen (recording the choice + the
    Flyway-race renumber). File the AGP/KGP/KSP-collection follow-up note (§6).
12. Re-read this plan against the diff; record any divergence here in the same PR.

## 5. Test strategy

- **Server unit (`testApplication`, in-memory):** `/v1/rollups/bottlenecks` and `/v1/rollups/
  toolchain` — 401 without a token, 403 with an ingest-only token, tenant isolation, window clamps;
  headline KPI deltas correct sign and semantic direction; new/vanished group flags; the plan-025
  budget/verdict counts null when its store is unwired.
- **Node smoke harness (`DashboardScriptTest` → `dashboard-smoke.js`):** every step-9 scenario runs
  in a real JS engine (plan-006 lesson: string assertions miss `SyntaxError`s). Covers the render,
  the `isNew`/vanished branches, the all-null-`cacheable` and AGP/KGP/KSP degraded panels, the
  semantic delta-chip colouring, and the empty-window path.
- **Testcontainers (`PostgresStoresIntegrationTest`):** seed the two windows / modules / users /
  versions described in step 10; assert the Postgres rollups equal the in-memory oracle exactly and
  the window boundary excludes builds older than `2 × period`. If plan 026's `task_executions` is
  present, assert the task families read from it; else the jsonb fallback yields the same numbers.
- **Golden / contract:** none — `buildhound-commons` and the payload schema are untouched, so the
  additive-schema contract and golden-file tests stay green unmodified.
- **Failure injection (server-side never-fail analogue):** a project with a single build (no prior
  window) rolls up without error — every group is `isNew`, no delta division; a build whose
  `toolchain` is entirely null yields `available:false` on all dimensions, not an exception; an
  empty-window request returns empty rollups the UI renders as empty states.

## 6. Risks

- **Dependency ordering (plan 016 + 026):** by-type regression grouping, cache-miss hotspots, and
  negative avoidance need `TaskExecution.type`/`cacheable` (016) and the `task_executions` table +
  `negativeAvoidanceMs` helper (026). Without 016, task families group by name and cache-miss
  hotspots show the "populate task types (plan 016)" degraded panel. Without 026, the store falls
  back to windowed jsonb scans over `builds` (bounded by the window; acceptable at pilot scale).
  Plan sequenced after both; degradation is honest, never an error. Best-with-016+026, correct
  without.
- **Toolchain honesty (the headline privacy/UX trap):** only Gradle/JDK are collected; AGP/KGP/KSP
  are always null. The view **must** render those as an explicit "not collected yet" panel, never an
  empty distribution that reads as consensus. Enforced by a smoke scenario asserting the degraded
  panel. Follow-up filed: a future plugin plan to collect AGP/KGP/KSP versions (buildscript
  classpath / applied-plugin versions, CC-safe at configuration time) would light this up with zero
  server change.
- **Migration-number race:** the default design adds **no** migration, deliberately avoiding the
  migration-version collision that plans 025/026/028/031/036/037/039/040 could share — none pins a
  version, each claims the next free `V{n}` at merge time (Flyway fails on duplicate versions). If a
  toolchain hot column is later chosen, it
  claims the next free version at implementation time — not a number pinned here — and adds a
  decision-log row.
- **Small-sample noise:** period-over-period on a low-volume pilot can flag spurious "regressions".
  Mitigated by a per-group minimum-build-count guard and by flagging new/vanished groups instead of
  showing ∞/−100 %; the coarse window diff is explicitly not plan 025's statistical baseline and
  the page says so. When 025 lands, its budget/trend counts supersede the coarse signal for alerts.
- **Privacy (dedicated review):** the only identity data is the already-pseudonymized `userId`;
  `distinctUsers` and "who is behind" are `count(distinct)`/lists over **hashes** — the API returns
  numbers and hashed ids, never real users, no de-pseudonymization, spec §3.7 intact. Toolchain
  versions, task names/modules are the same exposure class as data already stored. No absolute
  paths, env dumps, or tokens enter any rollup (all values are already-scrubbed payload fields).
- **Security:** all rollup SQL uses bound parameters and fixed column/jsonb-path group-bys (no free
  text); `period`/`days`/top-N are clamped ints; both routes are read-scope-gated and covered by the
  existing query + per-host rate limiters
  ([Application.kt:149-152](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt)).
  No `XForwardedHeaders` assumption changes. The CSP stays `script-src 'self'` with hash-pinned
  styles; every new string is `textContent`, no `innerHTML`.
- **CC / isolated projects / plugin failure paths:** not applicable — server + dashboard only; no
  plugin or commons-collector code runs inside a build in this plan.

## 7. Exit criteria

- `./gradlew :buildhound-server:test` green, including the new route tests, the extended
  `dashboard-smoke.js` scenarios, and the Testcontainers two-window parity assertions; `./gradlew
  build` green (no commons change).
- `GET /v1/rollups/bottlenecks?period=7` returns headline KPIs with prior-period deltas and the four
  ranked families (regressed tasks/modules, slowest work, negative-avoidance offenders, cache-miss
  hotspots), tenant-scoped and read-scope-gated; new/vanished groups are flagged, never shown as ∞.
- `GET /v1/rollups/toolchain` returns real Gradle and JDK version distributions with hashed
  distinct-user counts and a "behind" list, and `available:false` for AGP/KGP/KSP (not an empty
  chart).
- Opening `/` lands on the Bottlenecks page: a KPI strip with semantically-coloured delta chips, the
  four bottleneck sections with explicit zeros and View-more drill-downs, and the toolchain-adoption
  section — answering "what got worse this week" for the pilot; a single-build or empty-window
  project renders honest empty/degraded states, not errors.
- No payload-schema change (golden files and contract tests unmodified in the diff); spec §6 updated;
  `docs/architecture.md` unchanged unless the optional extracted-column alternative was taken (then a
  decision-log row records it).
