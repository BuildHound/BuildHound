# 059 — Delivery-health page: DORA proxies + the retry tax

## Source

- Research finding **F9** (`docs/research/ingest-corpus-analysis.md` §4 — "Delivery-health
  page: DORA proxies + the retry tax"). Sources: Develocity 360's build-observability-as-DORA-
  foundation pitch + the IEEE flakiness study (Parry et al. 2022 — "rerun the failing build" is
  the most common flakiness response), from the `docs/research/processed/` corpus.
- Spec [§1 non-goals](../build-telemetry-spec.md) (`spec:13`): **Git/DORA analytics is a v1
  non-goal.** This plan stays inside that boundary — it collects nothing new, mines no git
  history or deployment data, and labels every metric a *proxy over already-ingested build data*.
  The coarse fleet `successRate` KPI already ships (plan 032); this refines it per branch/pipeline.
- Read-side rollup discipline: plan [010](implemented/010-query-api-rollups.md) (on-read,
  tenant-scoped), plan [032](implemented/032-bottlenecks-landing-page.md) (`BottleneckCalculator`
  two-store parity, fleet `successRate` `KpiDelta`, dashboard landing conventions, no-migration
  default), plan [036](implemented/036-flaky-detection.md) (`store.flaky`, `FlakyRecord`,
  `#/flaky`), plan [028](implemented/028-azure-devops-connector.md) (`ci_runs`, `queuedMs`,
  `GradleShare.percent`, `CiSpanStore.findRun`), plans [041](implemented/041-ci-connectors-gha-gitlab.md)
  + [027](implemented/027-ci-env-breadth.md) (`ci.attributes["runAttempt"]`),
  plan [033](implemented/033-lost-build-accounting.md) (INTERRUPTED synthetic-duration exclusion),
  plan [025](implemented/025-regression-engine-v1.md) (`RegressionEngine.requestedTasksSignature`/
  `median`). Sibling read-side slice: plan [057](057-tag-cohort-comparison.md).

## Scope

**In (server + dashboard only — no plugin, no commons, no payload/golden change; zero new collection):**

- A pure **`DeliveryHealthCalculator`** (mirrors `BottleneckCalculator`/`FlakyDetector`) over
  **build-only rows**, computing three DORA *proxies* from data already in the `builds` table:
  - **Change-failure-rate** per (branch, pipeline): `FAILED / (SUCCESS + FAILED)`, INTERRUPTED
    excluded (plan 033), a `MIN_SAMPLES` guard — the per-branch/pipeline refinement of plan-032's
    shipped fleet `successRate`.
  - **Time-to-green** per (branch, pipeline): recovery episodes (first FAILED → next SUCCESS on the
    same branch, ordered by `startedAt`); median/p90 recovery ms + episode count. A **CI-recovery**
    proxy, labeled honestly — **not** production MTTR.
  - **Rerun chains** (the retry tax): authoritative `ci.attributes["runAttempt"] > 1` (GHA) plus a
    temporally-guarded same-`(projectKey, sha, requestedTasksSig)`-after-FAILED heuristic (all providers).
- **Route-level best-effort enrichment** (outside the parity core): lead-time's `queuedMs`/
  `gradleSharePct` from `ciSpans` (plan 028), and flaky attribution from `store.flaky` (plan 036) —
  both degrade to null / omitted when absent.
- `BuildStore.deliveryHealth(projectId, days, nowMs): DeliveryHealthRollup` (build-only core, both
  stores, parity-tested) + `GET /v1/rollups/delivery-health?days=30` in `queryRoutes`, read-scope +
  tenant-scoped. A dashboard **`deliveryHealthView()`** + `#/delivery` route + "Delivery" nav link;
  the retry-tax card links to `#/flaky`.

**Out / deferred:**

- **Not `buildhound-report`** (the per-build, zero-network standalone artifact): every metric here
  is cross-build/fleet, which that artifact structurally cannot compute. The finding's own `#/flaky`
  join and every sibling analytical page (Bottlenecks/Flaky/Tasks) live in the server dashboard SPA,
  so this page does too. *(Deliberate deviation from the task's "buildhound-report" wording, for this
  reason.)*
- Real DORA (deployment frequency needs deploy events; production MTTR needs incident data) — outside
  the payload and the §13 non-goal proper.
- No new git-history mining, PR-graph, or deployment tracking.
- No schema/golden/commons change; no plugin/CC code; no new alert kind (retry-tax alerting is a
  later slice on plan 025's dispatcher).
- No migration by default (mirror plan 032; `builds_project_started_idx` covers the window scan).

## Design

**Modules touched:** `buildhound-server` only. New server-owned `@Serializable` DTOs (commons stays
wire-contract-only, plan 010), one `BuildStore` method, one route, one dashboard page.

**Build-only rows + pure core.** `DeliveryBuildRow(branch, pipelineName, provider, outcome,
startedAtMs, finishedAtMs, sha, projectKey, requestedTasksSig, runAttempt: Int?)` is the raw shape
both stores fetch over the window: hot columns `started_at`/`finished_at`/`outcome`/`branch`
(`V1__core.sql:36-43`) + jsonb extracts `ci.provider`, `ci.pipelineName`, `vcs.sha`, `projectKey`,
`requestedTasks` (→ `RegressionEngine.requestedTasksSignature`), and `ci.attributes.runAttempt`
parsed **`toIntOrNull()`** (never-fail). `DeliveryHealthCalculator.compute(rows, period)` returns the
core `DeliveryHealthRollup(period, changeFailureRate: List<CfrRow>, timeToGreen: List<RecoveryRow>,
leadTime: List<LeadTimeRow>, retryTax: RetryTaxSummary, connectorDataAvailable=false)` with all
connector/flaky fields null. Both `InMemoryBuildStore` and `PostgresBuildStore` fetch the same rows
and defer to this one calculator (plan-026/032 parity discipline); `LeadTimeRow.medianDurationMs`
is build-only, its `medianQueuedMs?`/`medianGradleSharePct?` filled later.

**Retry-tax detection.** `runAttempt > 1` ⇒ a `RUN_ATTEMPT` rerun (GHA, authoritative). Heuristic
(all providers, labeled `SAME_KEY_CANDIDATE`): per `(projectKey, sha, requestedTasksSig)` group
sorted by `startedAt`, a build is a candidate rerun only when a prior same-key build is **FAILED**
*and* `startedAt > prior.finishedAt` (sequential, non-overlapping) — so concurrent JDK-matrix legs
and PR-vs-push builds on one sha (which overlap in time) are excluded, never miscounted as reruns.
`RetryTaxSummary` carries chain count, rerun-build ids, `wastedCiMinutesLowerBound` (Σ rerun
`duration_ms` — a **lower bound**, Gradle wall-clock only), and the signal split.

**Route enrichment (best-effort, = the degradation boundary).** `queryRoutes(store, verdicts,
tokens, ciSpans)` already holds everything needed (`Routes.kt:339`). The route calls
`store.deliveryHealth`, then (a) fills `LeadTimeRow` queue/share medians and upgrades wasted minutes
to pipeline wall-clock via `ciSpans.findRun` + `GradleShare.percent` over the bounded window build
ids (mirrors plan-028's builds+`ci_runs` composition, `Routes.kt:384-393`); (b) intersects
`store.flaky(projectId, days).affectedBuildIds` with rerun build ids to populate a
`flakyRerunTax: List<FlakyRerunCandidate>` — the "you spent N CI hours rerunning these tests" view,
a ranked **candidate**, not a confirmed cause. Any enrichment failure omits that sub-panel;
`connectorDataAvailable` flips true only when a `ci_runs` row was found. No `CiSpanStore` is threaded
into the store constructor — the parity core stays build-only.

**Dashboard.** `deliveryHealthView()` + `#/delivery` in `dashboard.js` (`#/` unchanged), "Delivery"
nav link in `index.html`, CSP style hash auto-recomputes (`DashboardRoutes.kt:29`). Renders: a CFR
table (semantic goodness coloring, plan 032); a time-to-green table captioned "CI recovery, not
production MTTR"; a lead-time table whose queue/share columns show "—" + a "connect a CI connector"
note when `connectorDataAvailable=false`; a retry-tax card (chain count, wasted CI-minutes lower
bound, signal split, flaky-rerun candidates linking to `#/flaky`). All payload strings reach the DOM
via `el()`/`textContent`; degrades to honest empty states on no data / enrichment error.

## Test strategy

- **`DeliveryHealthCalculatorTest` (pure unit):** CFR excludes INTERRUPTED, honors `MIN_SAMPLES`;
  recovery episode = FAILED→next-SUCCESS same branch (an open episode still red at window end is
  counted separately, not as a recovery); `runAttempt>1` ⇒ `RUN_ATTEMPT`; heuristic fires only when
  the prior same-key build is FAILED **and** `startedAt > prior.finishedAt` — concurrent same-sha
  matrix legs and PR-vs-push overlap yield **no** rerun; `runAttempt` garbage string → null, no throw;
  wasted-minutes sum; deterministic ordering.
- **`DeliveryHealthRouteTest` (`testApplication`):** 401 no token, 403 ingest-scope, tenant isolation
  (foreign token sees none), `days` clamp `[1,365]`; happy path returns CFR/time-to-green/retry-tax;
  `connectorDataAvailable=false` with no `ci_runs` (queue/share null, page still renders); flaky-rerun
  candidates populate when a rerun build id ∈ a seeded `FlakyRecord.affectedBuildIds`.
- **Testcontainers (`PostgresStoresIntegrationTest`):** seed multi-branch builds incl. a
  FAILED→SUCCESS recovery, a GHA rerun (`runAttempt=2`), and a concurrent same-sha matrix pair
  (overlapping times) that must **not** count as reruns; assert Postgres `deliveryHealth` core equals
  `InMemoryBuildStore` byte-for-byte; a seeded `ci_runs` row fills `queuedMs` at the route.
- **Dashboard smoke (`DashboardScriptTest`):** `#/delivery` renders all four panels + the connector-
  absent degraded panel + the `#/flaky` link, CSP-safe, no external request.
- **Golden files:** none — commons/payload untouched; new server DTOs round-trip-pinned.

## Risks

- **Spec §13 Git/DORA non-goal (framing, load-bearing).** Stays inside the non-goal: zero new
  collection, no git-history/PR-graph/deployment mining, every metric a labeled *proxy* over
  already-ingested data. CFR is explicitly the per-branch/pipeline refinement of plan-032's shipped
  fleet `successRate` (pre-empts a duplication flag), not a new signal.
- **`runAttempt` GHA-only; others heuristic (narrowing 2).** `ci.attributes["runAttempt"]` (string →
  `toIntOrNull`) is authoritative but populated only for GitHub Actions; every other provider uses
  the same-key-after-FAILED chain, surfaced as a labeled **candidate**, never a confirmed rerun. The
  false-positive guard (prior same-key build FAILED **and** `startedAt > prior.finishedAt`) excludes
  concurrent matrix legs and PR-vs-push builds on one sha.
- **Lead-time connector dependency (narrowing 3).** `queuedMs`/`gradleSharePct` exist only for
  connector-enriched builds (plan 028/041); they are route-level best-effort enrichment, null with a
  `connectorDataAvailable=false` "connect a CI connector" panel when absent. Build-duration lead-time
  always renders. The parity boundary is the degradation boundary: the build-only core is
  parity-tested, the connector layer degrades.
- **Time-to-green ≠ MTTR (narrowing 4).** Labeled a **CI-recovery** proxy in the DTO doc, the
  dashboard caption, and the exit criteria — never production MTTR.
- **Retry-tax pricing honesty.** Wasted CI minutes are a **lower bound** (`duration_ms` is Gradle
  wall-clock only, excluding checkout/setup; upgraded to pipeline wall-clock from `ci_runs` when
  connector-enriched). Only the subset of rerun builds whose id ∈ a plan-036 `FlakyRecord.
  affectedBuildIds` is the "flaky rerun tax," and it stays a ranked candidate (plan-057 discipline) —
  not all reruns are flakiness.
- **Multi-tenancy.** The route is `authenticatedProject(tokens, ::allowsRead)` + every query
  `WHERE project_id = ?`; a Testcontainers test proves cross-tenant isolation.
- **Privacy (spec §3.7).** Branch/pipeline/sha and test-class names are already-stored, already-
  scrubbed plaintext; `userId` stays the `u_…` HMAC (unused here). CI-minutes derive from durations.
  No new field, path, URL, or PII.
- **Never-fail (server analogue).** Read-side rollup: storage outage → 503 via `respondQuery`; a
  detector or enrichment error degrades to an empty/omitted sub-panel, never a 500 that blanks the
  dashboard.
- **Isolated-projects / CC.** N/A — server + dashboard only; no plugin or config-time code (as with
  plans 032/036/057).
- **Additive-schema / migration race.** No commons/payload/golden change; new server DTOs only. No
  migration by default; if review adds a `(project_id, branch, started_at)` index it claims the next
  free `V{n}` at merge (Flyway race, per plan 032).

## Implementation notes (delta from plan, same-PR record)

- **`DeliveryBuildRow` carries `buildId`** (absent from the sketch above): the plan's own
  requirements need it — `RetryTaxSummary.rerunBuildIds`, the flaky-`affectedBuildIds` intersection,
  and the route's `ciSpans.findRun` enrichment are all keyed by build id.
- **Store→route enrichment handoff rides on `@Transient` DTO fields** (`LeadTimeRow.enrichmentSamples`,
  `RetryTaxSummary.wastedMsLowerBound`/`rerunSamples`, bounded by calculator caps): the plan said the
  route enriches "over the bounded window build ids" without saying how the route learns them.
  Chosen over widening the `BuildStore.deliveryHealth` signature (connector coupling would leak into
  the parity core) or a second store round-trip. Pinned never to reach the wire.
- **The Testcontainers coverage lives in its own `DeliveryHealthStoresIntegrationTest`** (not appended
  to `PostgresStoresIntegrationTest` as the test-strategy bullet said) — the per-feature-file
  convention every sibling since plan 036 uses (`RerunCauseStoresIntegrationTest`,
  `TagCohortStoresIntegrationTest`, …). Same assertions as planned, incl. driving the route-level
  `enrichDeliveryHealth` against a real `PostgresCiSpanStore` for the seeded-`ci_runs` case.
- `MIN_SAMPLES = 3` (the plan left the value open; matches `RegressionEngine.MIN_BASELINE`'s
  small-sample floor rather than `BottleneckCalculator`'s 2 — a claimed failure *rate* needs more
  than two builds).

## Exit criteria

- `GET /v1/rollups/delivery-health?days=30` returns per-(branch, pipeline) CFR, time-to-green
  (labeled CI-recovery), and a retry-tax summary; read-scope + tenant-isolated (foreign token blocked).
- A GHA build with `runAttempt=2` is a `RUN_ATTEMPT` rerun; a sequential same-key-after-FAILED build
  is a `SAME_KEY_CANDIDATE`; concurrent same-sha matrix legs are **not** reruns — all pinned by tests.
- Lead-time queue/gradle-share populate for connector-enriched builds and degrade to an honest
  "connect a CI connector" panel otherwise; build-duration lead-time always renders.
- Retry-tax wasted CI-minutes render as a labeled lower bound; the flaky-rerun candidate list links
  to `#/flaky` and is a ranked candidate, not a confirmed cause.
- The `#/delivery` page renders CFR/time-to-green/lead-time/retry-tax with semantic coloring, a
  "Delivery" nav link, CSP-safe, degrading to honest empty states.
- No commons/payload/golden change; new server DTOs round-trip-pinned; Postgres↔in-memory core parity
  pinned (Testcontainers). Clean-context code and security/privacy reviews completed, findings addressed.
- `./gradlew build` green.
