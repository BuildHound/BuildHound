# Plan 026 — eBay-style server rollups: Project Cost family, by-type durations, top-25, negative avoidance

**Status: planned — roadmap phase 2b** · 2026-07-03

## 1. Source

- Roadmap [phase 2b, "eBay-style server rollups"](../build-telemetry-roadmap.md): per-module
  Project Cost family (`buildCostScalar`, `buildImpactedUsers` over hashed ids), task duration
  by name and by type (unblocked by plan 016), top-25 rankings, negative-avoidance-savings.
- Research: [plugin-ecosystem-gap-analysis.md §3](../research/plugin-ecosystem-gap-analysis.md)
  (the four eBay summarizers, the `buildCostScalar` int-truncation quirk, by-type blocked on
  `TaskExecution.type`) and [repos/ArtifactTransformReport.md](../research/repos/ArtifactTransformReport.md)
  §"Techniques worth borrowing" 1/3/5 (library-computes/CLI-renders, negative avoidance,
  distribution measures) — the third independent surfacing of negative avoidance.
- Spec: [§4](../build-telemetry-spec.md) (`derived` block, task shape), [§5](../build-telemetry-spec.md)
  (query API, `tasks` hypertable + continuous aggregates), [§6](../build-telemetry-spec.md)
  (Tasks explorer: "by type/module: duration, miss-rate×duration ranking"), [§3.7](../build-telemetry-spec.md)
  (pseudonymized `userId`).

## 2. Scope

**In:**

- A normalized `task_executions` table (spec §5's planned `tasks` hypertable), populated on
  ingest in the same transaction as the `builds` row, so per-module/per-type/per-name rollups
  are indexed SQL instead of jsonb scans.
- Server rollup queries + read-scoped endpoints, all tenant-filtered, computed on read (plan 010
  posture):
  - `GET /v1/rollups/project-cost` — per-module Project Cost family.
  - `GET /v1/rollups/task-duration` — duration/count aggregates grouped by task **name** and by
    task **type**, top-25 each.
  - `GET /v1/rollups/negative-avoidance` — tasks/types where avoidance cost more than execution.
- `NegativeAvoidance` as a first-class, honest **build-local** derived signal:
  `DerivedMetricsCalculator` gains a pure `negativeAvoidanceMs(tasks)` helper (commons), and the
  server-rules rollup aggregates it across builds.
- Dashboard **Tasks explorer** page (`#/tasks`) rendering the three rollups; nav link.
- Spec §5/§6 amendments; architecture decision-log row for the normalized-table decision.

**Out (named plans own these):**

- Task `type`/`cacheable` population itself — **plan 016** (hard dependency; by-type is null
  until it lands).
- True per-task `avoidanceSavings` / `avoidedMs` from cache-origin timings — **plan 038**
  (hardcoded null today, [DerivedMetricsCalculator.kt:20](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/DerivedMetricsCalculator.kt));
  this plan defines negative avoidance against data that exists (see §3), not against 038's field.
- Build comparison endpoint / input fingerprints — **plan 022**.
- CC miss-reason rollup (the fourth eBay summarizer) — **plan 035** (capture is the hard part).
- Regression baselines/verdicts, budgets, alerts — **plan 025**.
- Dashboard empty-state/ledger/count-header conventions — **plan 018** (this page reuses them).
- Cardinality/size caps in the assembler/scrubber — **plan 019** (ingest-side); this plan caps
  its own rollup **result** sizes (top-25, LIMIT).
- TimescaleDB continuous aggregates / retention downsampling — deferred (plan 010 posture:
  materialize when volume demands; on-read group-by until then).

## 3. Design

**Modules touched:** `buildhound-server` (new migration, store methods, rollup DTOs, routes,
dashboard JS/HTML); `buildhound-commons` (one pure helper on `DerivedMetricsCalculator`). No
plugin change and **no payload-schema change** — every input already ships.

**Current behavior (verified).** The server has three tables — `projects`, `api_tokens`,
`builds` — with the full schema-v1 document in `builds.payload` (jsonb) and only envelope columns
extracted ([V1__core.sql:32-48](../../buildhound-server/src/main/resources/db/migration/V1__core.sql));
there is **no `tasks` table and no rollup job**, and nothing reads into the jsonb payload
([BuildStore.kt:47-60](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt),
[PostgresStores.kt:136-175](../../buildhound-server/src/main/kotlin/dev/buildhound/server/PostgresStores.kt)).
Each payload task already carries `path`, `module` (derived `taskPath.substringBeforeLast(':')`,
[TaskEventCollector.kt:30](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TaskEventCollector.kt)),
`startMs`, `durationMs`, `outcome`, and — once plan 016 lands — `type`/`cacheable`
([BuildPayload.kt:94-107](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt)).
`environment.userId` is the pseudonymized `u_…` HMAC value (spec §3.7; golden file pins `u_9e1b44c0`).

**Why a normalized table, not jsonb scans.** Project Cost and by-type rankings aggregate over
every task of every build in a window; repeated `jsonb_array_elements` scans of the whole `builds`
table would be O(builds × tasks) per request with no usable index. A `task_executions` table
written on ingest keys the hot dimensions (module, type, name, outcome) and *is* spec §5's declared
`tasks` hypertable — landed now, TimescaleDB conversion deferred like `builds`
([V1__core.sql:30-31](../../buildhound-server/src/main/resources/db/migration/V1__core.sql)). Row
count is bounded by the per-payload task cap plan 019 enforces at assembly.

**Migration `V{n}__task_executions.sql`** (claim the next free version integer at implementation
time — plans 025/026/028/031/036/037/039 all add migrations, so the merge order determines
numbering; renumber deterministically to the next free `V{n}` when merging) (additive; older
deploys migrate forward on boot,
[Application.kt:85](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt)):

```sql
CREATE TABLE task_executions (
    project_id   uuid   NOT NULL REFERENCES projects (id),
    build_id     text   NOT NULL,
    started_at   timestamptz NOT NULL,   -- copied from the build, the time key for windows
    user_id      text,                   -- pseudonymized u_… from environment.userId
    path         text   NOT NULL,
    module       text,
    name         text   NOT NULL,        -- last path segment (task name)
    type         text,                   -- FQCN once plan 016 populates it, else NULL
    outcome      text   NOT NULL,
    cacheable    boolean,
    duration_ms  bigint NOT NULL
);
CREATE INDEX task_exec_project_started_idx ON task_executions (project_id, started_at DESC);
CREATE INDEX task_exec_project_module_idx  ON task_executions (project_id, module);
CREATE INDEX task_exec_project_type_idx    ON task_executions (project_id, type);
```

No `UNIQUE`/PK on task rows: dedupe stays at the `builds` level. `save()` becomes transactional
— insert the `builds` row (unchanged idempotent `ON CONFLICT DO NOTHING`), and **only when it
actually inserted** (return value 1) also batch-insert the task rows in the same connection/tx.
A duplicate build inserts no task rows, preserving idempotency. `user_id` and `started_at` are
denormalized onto each task row so `buildImpactedUsers` and windowing need no join back to
`builds`. `name` = `path.substringAfterLast(':')`.

**Project Cost family (server rollup).** Per module over a window (default 30 days), mirroring
eBay's `ProjectCostSummarizer`: `builds` (distinct builds containing the module),
`executedBuilds` (distinct builds where ≥1 of its tasks was `EXECUTED`), `buildImpactedUsers`
(`count(distinct user_id)` over those tasks — a plain set over the hashed `userId`, spec §3.7
intact, no de-pseudonymization), `serialTaskMs` (`sum(duration_ms)`), `buildAvgDurationMs`
(average wall duration of containing builds, joined on `build_id`), and `buildPercentage`
(containing builds / total in window). **`buildCostScalar = executedBuildAvgDurationMs ×
executedBuildPercentage.toInt()`** — the int-truncation of the percentage is copied verbatim from
eBay (their README hedges "may change") so the number matches the reference; the quirk is recorded
in a doc comment. It surfaces modules that are both frequently and expensively built.

**Task Duration (server rollup).** Two group-bys over the window: by **name** (`GROUP BY name`)
and by **type** (`GROUP BY type`, `type IS NOT NULL`), each returning count, total, avg, min,
max `duration_ms`, ordered by total desc, **`LIMIT 25`** (the top-25 requirement, capped in
SQL). By-type is empty until plan 016 populates `type`; the endpoint returns an explicit
`byTypeAvailable: false` marker (all rows have null type) rather than an empty array the UI
can't distinguish from "no builds", so the dashboard can say "populate task types (plan 016)".

**Negative avoidance (commons helper + server rollup).** Defined honestly against data that
exists today, **independent of plan 038's per-task savings**: a *negative-avoidance candidate* is
a task that was `UP_TO_DATE`/`FROM_CACHE` (avoidance attempted) yet whose `durationMs` exceeds the
window's **executed** median for the same type/name — i.e. the avoidance check cost more than
running the work. This is the signal all three research sources point at (ArtifactTransformReport's
negative `avoidanceSavings`, gradle-doctor's "cache slower than task", android-cache-fix's
disabled-task list).

- Commons: pure `DerivedMetricsCalculator.negativeAvoidanceMs(tasks): Long` — per avoided task,
  `durationMs − medianExecutedDurationOfSameGroup`; sum only positive deltas; group by `type`,
  fall back to `name` when `type` is null; 0 when nothing qualifies. Kept in commons so the server
  (and the HTML artifact later) compute the same number, matching the `DerivedMetrics` contract.
  **No payload field is added** — it is a rollup-time computation; the roadmap's "belongs in
  DerivedMetrics + server rules" is met by the shared helper plus the server aggregate.
- Server: `GET /v1/rollups/negative-avoidance` ranks per type/name across the window by
  `count`, `total excess ms`, `worst single excess ms` (eBay/ATR shape), `LIMIT 25`; baseline
  median via SQL `percentile_cont(0.5)`.

**Endpoints (server).** Three `GET /v1/rollups/*` routes added to `queryRoutes`
([Routes.kt:107-135](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt)),
each read-scope-authenticated via `authenticatedProject(tokens, TokenScope::allowsRead)`,
tenant-scoped by `project.id`, storage outages → 503 through `respondQuery`, `days` clamped
`[1,365]` like `/v1/trends`. Response DTOs are server-owned `@Serializable` classes (commons stays
the wire contract only — plan 010 rule). `BuildStore` gains `projectCost`/`taskDuration`/
`negativeAvoidance`; `InMemoryBuildStore` computes over stored payload task lists (keeps DB-less
dev + `testApplication` working) and `PostgresBuildStore` runs SQL over `task_executions`, agreeing
exactly.

**Dashboard Tasks explorer (`#/tasks`).** New route in `dashboard.js`
([dashboard.js:257-271](../../buildhound-server/src/main/resources/web/dashboard.js)) with three
sections — Project Cost table, Task Duration (name/type toggle; by-type shows the "enable plan 016"
empty state when `byTypeAvailable` is false), Negative Avoidance table. All strings reach the DOM
via the existing `el()`/`textContent` helper (payload untrusted,
[dashboard.js:27-32](../../buildhound-server/src/main/resources/web/dashboard.js)); badge classes
stay allowlisted ([dashboard.js:21-22](../../buildhound-server/src/main/resources/web/dashboard.js)).
Nav link in `index.html` ([index.html:38-44](../../buildhound-server/src/main/resources/web/index.html));
the CSP style hash recomputes from served bytes
([DashboardRoutes.kt:29-40](../../buildhound-server/src/main/kotlin/dev/buildhound/server/DashboardRoutes.kt)).

## 4. Implementation steps

1. **commons** — `DerivedMetricsCalculator`: add pure `negativeAvoidanceMs(tasks): Long` (and a
   small internal `medianExecutedDurationByGroup` helper), grouping by `type` with `name`
   fallback; unit-tested. No schema change, no `compute()` signature change → existing golden
   files and `DerivedMetrics` untouched.
2. **server** — `V{n}__task_executions.sql` migration as above (table + three indexes; claim the
   next free version integer at merge time, per §3).
3. **server** — `PostgresBuildStore.save`: wrap the build insert + task-row batch insert in one
   transaction on a single connection (`autoCommit=false`); insert task rows only when the build
   row was newly inserted; map `name`/`module`/`user_id`/`started_at` from the payload. Keep the
   SQLSTATE-22 permanent-vs-outage classification ([Routes.kt:82-93](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt))
   working (any data-shaped failure still surfaces as `SQLException` to the route).
4. **server** — `BuildStore` interface + `InMemoryBuildStore`: add `projectCost`,
   `taskDuration`, `negativeAvoidance` (in-memory computes over stored payload task lists,
   reusing the commons helper for the negative-avoidance math so both stores agree).
5. **server** — `PostgresBuildStore`: SQL implementations of the three rollups over
   `task_executions`, all `project_id` + `started_at >= cutoff` filtered, top-25 `LIMIT`,
   bound parameters only.
6. **server** — rollup DTOs (`ProjectCostRow`, `TaskDurationRow`, `TaskDurationRollup` with
   `byName`/`byType`/`byTypeAvailable`, `NegativeAvoidanceRow`) + the three
   `GET /v1/rollups/*` routes in `queryRoutes` (read scope, tenant scope, `days` clamp,
   `respondQuery` outage path).
7. **dashboard** — `#/tasks` route, three rendered sections + name/type toggle + empty states,
   in `dashboard.js`; nav link in `index.html`.
8. **dashboard tests** — extend `dashboard-smoke.js` (run by `DashboardScriptTest`) with canned
   `/v1/rollups/*` responses and a `#/tasks` render pass, including the `byTypeAvailable: false`
   empty-state branch and empty-rollup branch (no throw).
9. **server tests** — `ApplicationTest`: 401 no-token / 403 ingest-scope / tenant-scoping / happy
   path on each rollup route; `DerivedMetricsCalculatorTest`: negative-avoidance cases.
   `PostgresStoresIntegrationTest`: seed multi-build/multi-module/multi-user payloads, assert
   `save` writes task rows once (duplicate build adds none), and each rollup's numbers match the
   in-memory store over the same fixtures.
10. **docs, same PR** — spec §5 gains the three rollup endpoints and notes `task_executions` now
    exists (the planned `tasks` hypertable, TimescaleDB conversion still deferred); spec §6 Tasks
    explorer entry marked delivered. `docs/architecture.md` §5 + decision log: row recording the
    normalized-`task_executions`-on-ingest decision (why not jsonb scans; dedupe stays build-level;
    denormalized `user_id`/`started_at`) and that `buildCostScalar` copies eBay's int-truncation
    intentionally.
11. Re-read this plan against the diff; record any divergence here in the same PR.

## 5. Test strategy

- **Unit (commons):** `negativeAvoidanceMs` — an `UP_TO_DATE` task slower than the executed
  median of its type yields positive excess; an avoided task faster than the median yields 0;
  no executed baseline for a group → that group contributes 0 (never negative); `type`-null
  tasks group by `name`; empty list → 0.
- **Server unit (`testApplication`, in-memory):** each `/v1/rollups/*` route returns 401 without
  a token, 403 with an ingest-only token, and is tenant-scoped (a second project's builds never
  appear); `days` clamp honored; `byTypeAvailable=false` when no ingested task has a `type`.
- **Testcontainers (`PostgresStoresIntegrationTest`):** seed builds across ≥3 days, ≥2 modules,
  ≥2 distinct `userId`s, mixed outcomes and (post-016-shaped) types. Assert: `save` inserts task
  rows exactly once per build and zero on a duplicate `buildId`; Project Cost `buildImpactedUsers`
  equals the distinct hashed-id count; `buildCostScalar` matches the hand-computed int-truncated
  product; by-name and by-type top-25 ordering and totals match the in-memory store byte-for-byte;
  negative-avoidance ranking matches. Window exclusion (a build older than `days`) drops out.
- **Golden/contract:** none — no schema change (`buildhound-commons` payload models untouched);
  the guardrail contract tests stay green unmodified.
- **Failure injection (server-side analogue of the never-fail rule):** a payload whose `tasks`
  array is empty or whose `type` is all-null ingests and rolls up without error (rollups return
  empty/`byTypeAvailable=false`, not an exception); the smoke harness drives an empty-rollup
  `#/tasks` render without throwing.

## 6. Risks

- **Ingest write amplification / DoS surface:** every build now writes N task rows in the ingest
  transaction. Bounded by the per-payload task cap plan 019 enforces at assembly and the
  server's existing decompressed-size cap ([Routes.kt:34-35](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt));
  batch insert in one round trip. If pilot volume strains it, the fallback is a background
  post-processing job (spec §5's "async post-processing") — noted, not built now.
- **Idempotency:** task rows must be written iff the `builds` insert was new; a re-ingested build
  must add zero task rows. Enforced by gating on the insert's return value inside the same tx and
  asserted by the Testcontainers duplicate-build case. No PK on task rows means a partial-failure
  retry could double-insert — mitigated by the single transaction (all-or-nothing per build).
- **Schema/compatibility:** additive migration only; no payload-schema change, so contract/golden
  tests are untouched and old plugins/servers interoperate. An older server that upgrades runs the
  new `task_executions` migration on boot; historical builds already stored have no task rows, so rollups cover only builds
  ingested after upgrade — documented; a one-off backfill from jsonb is a follow-up, not required
  for the exit criteria.
- **Dependency on plan 016:** by-type rollups and part of negative avoidance grouping need
  `TaskExecution.type`. Until 016 lands, `type` columns are null: by-type returns
  `byTypeAvailable=false`, negative avoidance falls back to `name` grouping. Everything else
  (Project Cost, by-name durations) works on data shipping today. Plan sequenced after 016.
- **Privacy (dedicated review):** the only identity data touched is the already-pseudonymized
  `userId`; `buildImpactedUsers` is a `count(distinct)` over hashes and the API returns a *number*,
  never the ids — no de-pseudonymization, no new PII, spec §3.7 intact. Module/type/name are the
  same exposure class as task paths already stored. No absolute paths, env dumps, or tokens enter
  `task_executions` (task fields are already-scrubbed payload values). New endpoints are
  read-scope-gated and rate-limited by the existing query limiter ([Application.kt:135](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt)).
- **Security:** all rollup SQL uses bound parameters and fixed column group-bys (no free text);
  `days`/`limit` are clamped ints; top-25 caps bound result size so a large window can't return an
  unbounded ranking. No `XForwardedHeaders` assumption changes.
- **CC / isolated projects:** not applicable — server-only plan, no plugin or commons-collector
  code runs inside a build.

## 7. Exit criteria

- `./gradlew :buildhound-server:test` green, including the new route tests, Testcontainers rollup
  parity, and the extended dashboard smoke harness; `./gradlew build` green with the commons unit
  additions.
- Ingesting a build writes one `task_executions` row per task; re-ingesting the same `buildId`
  adds none (Testcontainers-verified).
- `GET /v1/rollups/project-cost` returns per-module rows with `buildImpactedUsers` (distinct
  hashed users) and a `buildCostScalar` matching eBay's int-truncated formula, tenant-scoped and
  read-scope-gated.
- `GET /v1/rollups/task-duration` returns top-25 by-name rankings today and top-25 by-type once
  plan 016 populates `type` (`byTypeAvailable` flips true); `GET /v1/rollups/negative-avoidance`
  returns count/total-excess/worst rows.
- The dashboard Tasks explorer renders all three rollups with honest empty/degraded states; no
  payload-schema change (golden files and contract tests unmodified in the diff).
- Spec §5/§6 updated; `docs/architecture.md` §5 + decision log carry the normalized-table row.

## 8. Divergences from plan (2026-07-04, implementation)

- **Migration is `V4__task_executions.sql`** (next free version after 025's V3).
- **The rollup math is a pure `RollupCalculator`** (server-owned, over a flat `TaskRow`) that the
  in-memory store calls directly and the Postgres SQL mirrors; a Testcontainers parity test pins
  byte-for-byte agreement. This is a stronger version of the plan's "both stores agree."
- **The negative-avoidance *rollup* uses the window executed median** (`percentile_cont(0.5)`, per
  §3), **not** the build-local commons `negativeAvoidanceMs`. The commons helper is still added +
  unit-tested (per §2) — it is the per-build artifact/DerivedMetrics signal — but a build-local sum
  can't produce the per-group (type/name) ranking the rollup needs, so the rollup computes its own
  window medians. Both are "avoided task slower than the executed baseline"; they differ only in
  baseline scope (window vs build). Recorded so the two definitions aren't conflated.
- **Deterministic tiebreakers** added to every rollup ordering (`… DESC, key/module ASC`) in both
  `RollupCalculator` and the SQL, so in-memory and Postgres return identical order under ties (the
  parity test would otherwise be flaky).
- **All three rollups cap at top-25** (`RollupCalculator.TOP_N`), including Project Cost (the plan
  named top-25 only for task-duration/negative-avoidance) — bounds result size uniformly; modules
  rarely exceed 25 and the ranking is by cost.
- Everything else as planned: commons `negativeAvoidanceMs`, transactional task-row insert (rows
  only on a fresh build), the three DTOs + routes (read-scope, tenant, days clamp), the `#/tasks`
  dashboard page (name/type toggle + `byTypeAvailable` empty state) + smoke cases, and the docs.

### Review-driven changes (2026-07-04, two clean-context reviews)

Security & privacy: **SHIP** (no Critical/High/Medium — parameterized SQL, read-scope + tenant
scoping, pseudonymized userId only ever counted, textContent rendering, bounded). Code &
architecture: **SHIP** (full parity traced sound, pinned by the Testcontainers test). Applied:

- **[code Low] `buildPercentage` rounded to 6 dp** in both `RollupCalculator` and the SQL
  (`round(count(*)::numeric / tot.n, 6)::float8`), so Kotlin double-division and Postgres float8
  division agree exactly — removes a latent parity-test-flakiness risk on raw IEEE-754 division.
- **[code Low] Parity fixtures strengthened** with a build carrying a **null module**, a **null
  user**, and an **even executed count** (median interpolation) — the branches the tiebreakers and
  `percentile_cont(0.5)` exist for now assert, not just reasoned.
- **[security Low] `taskDuration` column-selection comment** making explicit that `$column`/
  `$typeFilter` are boolean-derived fixed literals, never request input (SQL-injection guard).
- **[security Low, accepted] `path` is stored in `task_executions` but unused by v1 rollups** —
  kept for near-term per-path drill-down (it's the same scrubbed structural identifier already in
  `builds.payload`); noted rather than dropped. Retention (N1) lands with the deferred TimescaleDB
  conversion, inheriting the `builds` obligations.
