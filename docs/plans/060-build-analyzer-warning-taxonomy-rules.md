# 060 — Build Analyzer warning taxonomy as server rules

## Source

- Research finding **F10**, [`docs/research/ingest-corpus-analysis.md`](../research/ingest-corpus-analysis.md)
  §4 — "Build Analyzer warning taxonomy as server rules." Source article(s):
  Android Studio **Build Analyzer** (its fixed warning taxonomy + remediation copy) and the
  Gradle/Android **Profile-your-build** docs (`docs/research/processed/` corpus, whence the
  "dynamic debug values" signal — *not* Build Analyzer's own taxonomy).
- Spec [§6](../build-telemetry-spec.md) — the **Bottlenecks** landing page and its ranked families.
- Builds on plan [032](implemented/032-bottlenecks-landing-page.md) (Bottlenecks page +
  `BottleneckCalculator`/`BottlenecksRollup` + the two-store parity discipline), plan
  [026](implemented/026-server-rollups-project-cost.md) (`TaskRow`/`RollupCalculator`, on-read
  rollups), and reads the plan [016](implemented/016-task-type-cacheable-capture.md) task
  `type` dictionary. Sibling: **F11** (a full `executionReasons` taxonomy) is not planned yet;
  when it lands it should share this plan's reason-pattern module.

## Scope

**In (server + dashboard only — no plugin, no commons, no payload/golden change, no internal-adapters):**

- A new pure **`WarningCalculator`** computing a ranked **Warnings** family over the current
  window from data already in the payload — three rules:
  - **`ALWAYS_RUN`** — a task group that EXECUTED in ~all builds with an `executionReasons` string
    matching the always-run patterns ("has not declared any outputs" / "upToDateWhen is false").
  - **`NON_INCREMENTAL_AP`** — an annotation-processing / Java-compile group persistently
    `incremental=false` among EXECUTED occurrences on **incremental** builds (a *proxy candidate*).
  - **`DYNAMIC_DEBUG_VALUES`** — an AGP manifest/BuildConfig group **never UP-TO-DATE** across the
    window (dynamic `buildConfigField`/`resValue`, e.g. a timestamp, in debug builds).
- A new read route `GET /v1/rollups/warnings?period=<days>` (token + tenant scoped, read-scope
  gated), returning `WarningsRollup`; rendered as a **"Warnings" section on the existing
  Bottlenecks page** (`#/`), fetched best-effort so it never blanks the page.
- Remediation copy lives **client-side**, keyed by category, modeled on the official strings and
  phrased as *candidates* ("likely / investigate"), each row carrying its evidence.

**Out (explicitly deferred / not this slice):**

- **`task-setup-conflict`** — Build Analyzer's fourth family has **no feasible rule** from current
  telemetry (no output-property data). Dropped, not forgotten (finding narrowing).
- **CC posture** warnings (owned by F1 / F17 `environment.invocation`) and **Jetifier** cost
  (F8's config-phase plugin inventory) — the rest of Build Analyzer's taxonomy, other findings.
- Naming the **specific** offending annotation processor (impossible — see Risks) and any *new*
  collection to enable task-setup-conflict.
- A dedicated `#/warnings` route or a per-build (single-build) warning view — this is the
  fleet/historical rollup only.

## Design

**Modules touched:** `buildhound-server` only — one new pure calculator, one DTO set, one
`BuildStore` method (both stores), one route, one dashboard section. **No `buildhound-commons`,
no payload-schema, no golden change, and no migration** (see below); every input already ships in
schema v1 and is core (not internal-adapters).

**No new collection, no schema bump (say it loudly).** Every classification input already exists on
`TaskExecution` (schema v1): `outcome` (`TaskOutcome`), `type` (plan 016), `incremental: Boolean`
(populated from the Tooling `TaskExecutionResult.isIncremental`,
[TaskEventCollector.kt:105](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TaskEventCollector.kt)),
and `executionReasons: List<String>` (from `TaskExecutionResult.executionReasons`,
[:106](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TaskEventCollector.kt) —
already **scrubbed plaintext**, [PayloadScrubber.kt:40-44](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/PayloadScrubber.kt)).
The only additive surface is the **server-owned** `WarningsRollup`/`WarningRow` DTOs.

**Read straight from jsonb — no `task_executions` column, no migration (the load-bearing choice).**
`task_executions` (plan 026) does **not** carry `incremental`/`executionReasons`, and the Warnings
rules need both. Rather than add columns, this plan reads the two fields from the payload jsonb
directly — the exact posture `toolchainAdoption` already uses
([BuildStore.kt:550](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt),
[PostgresStores.kt:728](../../buildhound-server/src/main/kotlin/dev/buildhound/server/PostgresStores.kt)).
Two reasons this beats a column: (1) plan 032 **deliberately added zero migrations** to sidestep the
unpinned-`V{n}` Flyway race (025/026/028/031/036/037/039/040/057 each grab "next free" at merge) —
a new column re-enters it; (2) a new nullable column is NULL for every pre-migration row, so the
Warnings family would be **blind to history** until the window refills — which directly undercuts
F10's whole value ("fleet-wide + historical is strictly stronger than the single-build view").
jsonb reads the source of truth with zero blind spot. Cost accepted: a heavier windowed scan
(`jsonb_array_elements`), the same tradeoff plan 032 already took for its jsonb fallback at pilot
scale — which is why warnings get their **own** route, keeping `/rollups/bottlenecks` fast.

- **`WarningCalculator` (pure, the parity oracle).** `compute(rows: List<WarningTaskRow>, builds:
  List<BuildKpiRow>, period): WarningsRollup`. `WarningTaskRow` is the plan-032 `TaskRow` plus
  `incremental: Boolean` and `executionReasons: List<String>`. Grouping key is `type ?: name`
  (plan-032 convention). Per group over the window it counts **distinct builds** it appeared in
  (`buildsObserved`) vs distinct builds where the rule fired (`buildsAffected`), `share =
  affected/observed`, and `totalMs` = attributable EXECUTED ms (the ranking metric). A rule fires
  only above `MIN_BUILDS` and a per-rule `share` threshold ("~100%"). Output rows are candidates,
  ranked `totalMs` desc then key.
  - **ALWAYS_RUN:** occurrence EXECUTED **and** a reason matches the always-run patterns
    (case-insensitive substring: `has not declared any outputs`, `uptodatewhen`/`up to date ... is
    false`), with an **unclassified fallback** and version-tolerance (reasons are human output, not
    an API — F11 discipline). `evidenceReason` = a representative matched string.
  - **NON_INCREMENTAL_AP:** group is an AP/Java-compile task, matched **primarily by task NAME**
    (`kapt*`, `ksp*`, `compile*JavaWithJavac`) with `type` FQCN as corroboration
    (`org.gradle.api.tasks.compile.JavaCompile`, `*KaptTask`, `*KaptGenerateStubs*`). Among EXECUTED
    occurrences on **non-clean** builds, `share` = fraction with `incremental == false`.
  - **DYNAMIC_DEBUG_VALUES:** AGP manifest/BuildConfig group, matched primarily by NAME
    (`process*Manifest`, `generate*BuildConfig`) with AGP-FQCN corroboration; fires when the group
    **never** hit `UP_TO_DATE` across the builds it appeared in (executed-every-time).
  - **Clean-build exclusion** (NON_INCREMENTAL_AP only): a build whose avoided share
    (`UP_TO_DATE`+`FROM_CACHE` over cache-relevant tasks, computed from its own rows; `BuildKpiRow.
    hitRate` corroborates) ≈ 0 is a full rebuild where non-incremental is expected — excluded from
    the denominator so the rule flags genuinely-incremental builds only.
  - **`typeDataAvailable: Boolean`** (mirrors plan 032 `byTypeAvailable`): false when no row carries
    a `type` (isolated projects — plan-016 dictionary empty). Rules stay name-driven so they still
    fire under IP; the flag lets the UI say the classification is name-only.
- **DTOs (server-owned `@Serializable`, commons untouched — plan 010 rule).**
  `WarningRow(category: String, key: String, module: String? = null, buildsObserved: Int,
  buildsAffected: Int, share: Double, totalMs: Long, evidenceReason: String? = null)` and
  `WarningsRollup(period: Int, warnings: List<WarningRow>, typeDataAvailable: Boolean)`. `category`
  is the fixed enum name; the client maps it to copy.
- **Store method.** `BuildStore.warnings(projectId, period, nowMs): WarningsRollup`.
  `InMemoryBuildStore` extends `taskRowsOf`
  ([BuildStore.kt:633](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt))
  to the richer `WarningTaskRow` and defers to `WarningCalculator`; `PostgresBuildStore` runs the
  `jsonb_array_elements(b.payload->'tasks')` window scan (bound params, fixed jsonb paths, benchmark
  excluded, `mode <> 'BENCHMARK'`) and defers to the **same** calculator — byte-for-byte parity
  (plan 026/032 discipline).
- **Route.** `GET /v1/rollups/warnings` appended to `queryRoutes`
  ([Routes.kt:476-479](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt)),
  copied verbatim from `/rollups/bottlenecks`: `authenticatedProject(tokens, TokenScope::allowsRead)`,
  tenant-scoped, `respondQuery` 503-on-outage, `periodParam()` clamp.
- **Dashboard.** `bottlenecksView()` gains a **Warnings** section that fetches `/v1/rollups/warnings`
  best-effort (a fetch error omits the section, never blanks the page — the artifact-panel pattern).
  Rows render category → remediation copy (client-side map, official strings, "likely/investigate"),
  the evidence (`buildsAffected/buildsObserved`, `totalMs`), and a name-only note when
  `typeDataAvailable` is false. All strings via `textContent`; category → an allowlisted CSS class
  (plan 012 discipline). CSP style hash self-updates from served bytes.

## Test strategy

- **`WarningCalculatorTest` (pure unit):** each rule fires above threshold and stays silent below
  (`MIN_BUILDS`, share); ALWAYS_RUN matches the patterns case-insensitively and buckets an unknown
  reason as unclassified (no fire); NON_INCREMENTAL_AP excludes clean builds (avoided≈0) and matches
  by name when `type` is null; DYNAMIC_DEBUG_VALUES fires only when the group never hits UP_TO_DATE;
  ranking is `totalMs` desc; `typeDataAvailable=false` when every row's type is null; empty window →
  empty `warnings`, no divide-by-zero.
- **`WarningRoutesTest` (testApplication, in-memory):** 401 no token, 403 with an ingest-only token,
  tenant isolation (a second project's builds never appear), `period` clamp, happy path returns
  ranked candidates; a foreign read token is blocked.
- **Testcontainers (`WarningStoresIntegrationTest`, cf. `BottleneckStoresIntegrationTest`):** seed a
  window with always-run reasons, a persistently non-incremental `kapt` group (plus one clean build
  to prove exclusion), and an AGP BuildConfig group never UP-TO-DATE; assert the Postgres
  `jsonb_array_elements` scan equals the in-memory oracle **byte-for-byte**, and the window boundary
  excludes older builds.
- **Server DTO round-trip:** pin the `WarningsRollup` JSON shape. **No commons schema change →
  existing golden files untouched.**
- **Dashboard smoke** (existing JS harness): the Warnings section renders candidates + evidence with
  no console error and no external request (CSP-safe); a `typeDataAvailable:false` payload shows the
  name-only note; a fetch error leaves the rest of the Bottlenecks page intact.

## Risks

- **Candidate framing, never a confirmed fix (the load-bearing narrowing).** Every row is a ranked
  *candidate* with its evidence (`buildsAffected/buildsObserved` share + `totalMs`); copy is phrased
  "likely/investigate." **NON_INCREMENTAL_AP** in particular is a proxy: it flags a persistently
  non-incremental task but **cannot name the offending processor** (that data isn't collected) and
  **excludes clean builds** (avoided-share≈0) so a full rebuild doesn't masquerade as a regression.
  Stated in the copy and enforced by the calculator test.
- **Reason strings are version-tolerant output, not an API.** Match always-run reasons as
  case-insensitive substrings with an unclassified fallback; the exact Gradle 9.6.1 wording is not
  verifiable from here (the goldens only show ordinary input-changed/output-removed reasons), so this
  is validated against representative server fixtures. `PayloadCapper` truncates/drops reasons under
  pressure (plan 019) → the rule degrades to a **silent under-report**, never a wrong fire.
- **Additive-only, no schema/golden edit.** No `buildhound-commons` type, no payload field, no golden
  file, and **no migration** — the only additive surface is the server DTO. `schemaVersion` stays 1.
  The jsonb-over-column choice is what keeps this true and avoids the plan-032 Flyway-version race.
- **Isolated projects.** `type` is null under IP (plan-016 dictionary empty), but `incremental`
  survives (execution-time Tooling result) and reasons survive. Rules classify **by task name
  first**, so ALWAYS_RUN and DYNAMIC_DEBUG_VALUES fire unchanged and NON_INCREMENTAL_AP degrades to
  name-matching rather than vanishing; `typeDataAvailable=false` tells the UI the classification is
  name-only. Honest degrade, not a blank.
- **Multi-tenancy.** One new read route, `authenticatedProject(…, ::allowsRead)` + `WHERE project_id
  = ?` on every scan; a Testcontainers/testApplication test proves cross-tenant isolation. No global
  or unauthenticated surface added.
- **Privacy (spec §3.7).** `evidenceReason` echoes an `executionReasons` string that is **already
  scrubbed** at ingest (PayloadScrubber) and already stored — same exposure class, no new field.
  Task types/names/modules are declared data. No absolute paths, env dumps, or secrets enter any
  warning; the scan selects fixed jsonb paths only.
- **Security / injection.** The Postgres scan uses bound params and compile-time-literal jsonb paths
  (`payload->'tasks'`, `->>'outcome'` …) — no free text, no interpolation; `period`/top-N clamped.
- **Never-fail (server-side analogue).** No plugin or commons-collector code runs — CC-safety and the
  never-fail-build contract are untouched (this slice is pure read-side). Sparse/empty windows roll
  up to empty candidates, a storage outage returns 503 via `respondQuery`, and the dashboard section
  degrades to omitted on error — no exception path reaches the user.

## Exit criteria

- `GET /v1/rollups/warnings?period=7` returns ranked `ALWAYS_RUN` / `NON_INCREMENTAL_AP` /
  `DYNAMIC_DEBUG_VALUES` candidates with per-row evidence (share + attributable ms), tenant-scoped
  and read-scope-gated; a foreign token is blocked; `typeDataAvailable` is false when no build
  carried task types.
- NON_INCREMENTAL_AP excludes clean/full-rebuild builds and never names a processor; ALWAYS_RUN
  matches reasons version-tolerantly and stays silent on an unclassified reason — pinned by the pure
  calculator test.
- The Bottlenecks page shows a Warnings section with candidate copy modeled on the official strings,
  a name-only note under IP data, and graceful omission on fetch error.
- In-memory and Postgres stores agree byte-for-byte (Testcontainers parity); no commons/payload/golden
  change, no migration; server DTO round-trip pinned. Clean-context code and security/privacy reviews
  completed with findings addressed.
- `./gradlew build` green.
