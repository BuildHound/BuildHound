# Plan 033 — Lost-build accounting: INTERRUPTED builds surface instead of vanishing

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- [build-telemetry-roadmap.md](../build-telemetry-roadmap.md) Phase 3: "Lost-build
  accounting: daemonitor-style INTERRUPTED detection (start-marker reconciliation or
  connector-side expected-build check) so OOM-killed builds surface"; exit criterion "a
  daemon-killed build appears as INTERRUPTED instead of vanishing".
- [build-telemetry-spec.md](../build-telemetry-spec.md) §3.2 (Finalizer, never-fail), §3.9
  (upload/spool/idempotency), §4 (payload `outcome`), §5 (`POST /v1/builds`, buildId
  idempotency), §6 (Builds list / detail).
- [architecture.md](../architecture.md) §2 (plugin rules — CC safety, never-fail-or-hang,
  rule 9 "file access in `apply()` is a CC fingerprint input"), §5 (server `BuildStore`
  boundary, route testability, instance-local jobs), §6 (privacy).
- Research: [research/repos/daemonitor.md](../research/repos/daemonitor.md) (daemon-PID
  disappearance → `INTERRUPTED`; the signal the in-process Flow finalizer can never see),
  [research/comparison-to-spec.md §4 item 3](../research/comparison-to-spec.md) ("Builds
  that die never report" — the two candidate mitigations this plan evaluates).

## 2. Scope

**In:**

- An **additive** `BuildOutcome.INTERRUPTED` schema value (+ a new golden file), giving a
  never-finalized build a first-class terminal state instead of absence.
- **Primary — start-marker reconciliation** (plugin): a build writes a tiny local
  build-started marker at execution start; the *next* build's finalizer finds an
  unreconciled marker (the prior build never finalized), synthesizes a minimal `INTERRUPTED`
  payload, and feeds it to the existing spool/upload path.
- Server acceptance of `INTERRUPTED` through the unchanged `POST /v1/builds`, storage,
  list/trends/detail rendering, and a query filter value.
- **Fallback — connector-side expected-build check** (server, opt-in, Azure only): reuse
  [plan 028](028-azure-devops-connector.md)'s `AzureDevOpsConnector.fetchRun` to detect "the
  Timeline shows a completed Gradle run but no payload arrived" and record a
  server-originated `INTERRUPTED` build — *upgrading* detection for the machine-evicted case
  the marker cannot catch (successor runs on a fresh agent).
- Dashboard `INTERRUPTED` badge + filter; honest-degraded detail for a marker-only build.
- Failure-injection tests (marker path never fails/hangs a build) + a decision-log entry.

**Out:**

- **Process-health** on interrupted builds (heap/RSS at death) — [plan 029](029-process-probe.md);
  a marker carries no probe data.
- **The connector framework itself** — [plan 028](028-azure-devops-connector.md); this plan
  only consumes its `fetchRun`/registry and adds one enrichment rule.
- **GHA/GitLab** expected-build checks — [plan 041](041-ci-connectors-gha-gitlab.md); the
  fallback is Azure-only until they land.
- **Regression/alerting** on interrupted builds — [plan 025](025-regression-engine-v1.md);
  here they are merely excluded from duration/hit-rate baselines.
- **Cardinality/size caps** — [plan 019](019-cardinality-size-caps.md); the synthetic
  payload has no tags/values/tasks, so it is below every cap by construction.

## 3. Design

**The gap (verified).** The finalizer is a `FlowAction` on `FlowProviders.buildWorkResult`
(`BuildHoundSettingsPlugin.kt:78-100`) and is the *only* place a payload is written or
uploaded (`TelemetryFinalizerAction.kt:93-174`). When the daemon dies mid-build (OOM kill,
`kill -9`, agent eviction) the Flow action never runs, so today the build leaves no
`build-payload.json`, no spool entry, no server row — it vanishes. Nothing records that a
build *started*. `BuildOutcome` is exactly `SUCCESS`/`FAILED` (`BuildPayload.kt:37`), and
server list/trends/detail + dashboard key off those two only.

**Primary vs fallback.** The start-marker works for every consumer (local, any CI, no
server, no connector) and covers the headline OOM case cheaply. Its blind spot is a build
whose successor never runs in the same workspace (ephemeral agent evicted). The connector
check closes exactly that blind spot, but only where an Azure connector is configured and
the Timeline is fetchable. So the marker is the always-on baseline; the connector check is
additive precision. Both converge on the same `INTERRUPTED` outcome and the same idempotent
ingest, so a build caught by both is deduped by buildId (`BuildStore.save`,
`BuildStore.kt:48-49`).

**Schema (additive).** Add `INTERRUPTED` to `BuildOutcome` (`BuildPayload.kt:36-37`). This
is additive within v1 (`BuildHoundJson` sets `ignoreUnknownKeys`/`explicitNulls=false`,
`BuildHoundJson.kt:11-16`); no new required field — an interrupted payload reuses existing
optional fields. A **new** golden file `build-payload-interrupted-v1.json` is added; the
existing `build-payload-v1.json` is never edited (arch §3 rule 2). An old server would 400
an unknown enum, but the value is only emitted once both sides ship, and a 400 spool-drops
rather than corrupts (`PayloadUploader.kt:58-60`).

**Marker (plugin).** A small `@Serializable StartMarker` (defined in commons so writer and
reader agree) under `build/buildhound/started/<buildId>.json`, carrying only `buildId`,
`startedAtMs`, `requestedTasks`, resolved `mode`, `projectKey`, and cheaply-available ci
(`provider`/`runId`) + vcs (`branch`/`sha`) — no tasks, no environment probe, no identity,
no token. It is written **at execution start, not in `apply()`**: arch §2 rule 9 makes any
config-phase file touch a CC fingerprint input (the plan-003 salt scar), so the marker is
created from execution-time code. Seam: the `TaskEventCollector` `BuildService` writes it
once on its first observed task event, `AtomicBoolean`-guarded, entirely inside
`runCatching` (failure → `info` log, dropped). BuildId is generated once and shared so the
marker and the eventual finalizer payload carry the same id (today the finalizer mints the
UUID at `TelemetryFinalizerAction.kt:112`; move that generation to a CC-safe value the
service and finalizer both read).

> **Implementation divergence (as built).** The marker carries `buildId`, `startedAtMs`,
> `requestedTasks`, `projectKey`, and resolved `mode` — but **not** `ci`/`vcs`. A build-service
> parameter value is snapshotted into the configuration-cache entry and replayed *stale* on a hit
> (the same mechanism by which `taskMetadata` goes empty under isolated projects), so wiring the
> ci/vcs value sources into the collector would bake last-build's ci/vcs and misreport them on every
> CC-enabled build — exactly the CI builds where it matters. Instead the buildId lives on the
> collector service (`by lazy`, shared with the finalizer via its existing `@ServiceReference`), and
> the marker's config-stable fields ride a single `MarkerContext` param; `mode` is resolved with no
> CI context (an `AUTO` build's marker is `LOCAL`). The connector expected-build fallback carries
> authoritative CI correlation for the CI case, and a local build has no ci context anyway, so
> nothing of value is lost. `assembleInterrupted(marker, projectRoots)` therefore builds ci/vcs = null.

**Reconciliation (plugin, finalizer).** At the top of a normal finalization: (1) delete this
build's own marker (it finalized → not interrupted); (2) scan `started/` for markers whose
buildId ≠ the current build's, and for each, synthesize an `INTERRUPTED` payload
(`finishedAt=startedAt`, empty tasks, `derived=null`, marker's mode/ci/vcs), scrub it, and
send it through the *same* `UploadGate`/`PayloadUploader` path, then delete the marker; (3)
bound the scan (≤20 oldest per build, TTL-prune ~14 days) so a dead server or a loop cannot
grow the dir — mirrors the spool caps (`PayloadUploader.kt:68-93,131-137`). Reconciliation
obeys the existing `UploadGate` (`UploadGate.kt`): an interrupted *local* build with no
opt-in marker is written locally and not uploaded, like any local build; interrupted
uploads that fail spool and retry idempotently.

**Server (mostly free).** `POST /v1/builds` decodes into `BuildPayload`
(`Routes.kt:64-67`); once the enum value exists the payload deserializes and `store.save`
persists it unchanged — `outcome` is stored as the enum name into a free-text column
(`PostgresStores.kt:51`; `V1__core.sql:38`), so **no migration** is needed for the primary
path. `buildFilterOrNull` (`Routes.kt:179-185`) already validates against
`BuildOutcome.entries`, so `interrupted` becomes an accepted filter for free. **Trends** must
not fold `INTERRUPTED` into `FAILED` and must exclude it from duration/hit-rate aggregates
(its duration is synthetic): add `WHERE outcome IN ('SUCCESS','FAILED')` (or `FILTER`) to
the aggregate math in both `InMemoryBuildStore.trends` and `PostgresBuildStore.trends`, and
surface an interrupted count via an additive `TrendPoint.interrupted: Int = 0`.

**Connector fallback (server, opt-in).** Extend plan 028's enrichment worker: after a
successful `fetchRun`, if no `builds` row exists for `(project, provider, runId)`, synthesize
and `store.save` an `INTERRUPTED` row with a deterministic id `interrupted:<provider>:<runId>`
(idempotent, cannot collide with a plugin UUID), `startedAt`/`finishedAt` from the `CiRun`,
and ci from the fetched run. Strictly additive: only adds rows for otherwise-invisible runs;
a run that did ingest is untouched. Gated on connector configuration. This plan adds only the
"expected build missing → synthesize INTERRUPTED" rule and its test; if plan 028 is unmerged
at implementation time, land the rule as a hook that is a no-op until the connector exists.

**Dashboard.** Add `"INTERRUPTED"` to `BADGE_CLASSES` (`dashboard.js:21`) and an
`interrupted` option to the filter controls (`dashboard.js:59-60`); `detailView` already
renders any outcome via `textContent`+`badgeClass`, so a marker-only build shows an
`INTERRUPTED` chip, zero tasks, and (new) a one-line amber "this build did not finish — the
daemon died before telemetry was written" note when tasks are empty and outcome is
interrupted. Add a `.INTERRUPTED` style rule to `index.html`; the CSP style-hash recomputes
from bytes (`DashboardRoutes.kt:29-40`), so no manual hash edit is needed.

**Modules touched:** `buildhound-commons` (enum + `StartMarker` + golden), plugin (marker
write in the collector, reconciliation + `assembleInterrupted` in/around the finalizer),
server (filter, trends, `TrendPoint.interrupted`, connector rule), dashboard, docs.

## 4. Implementation steps

1. **Commons — enum + marker + golden.** Add `INTERRUPTED` to `BuildOutcome`; add
   `@Serializable StartMarker` data class; create
   `buildhound-commons/src/jvmTest/resources/golden/build-payload-interrupted-v1.json`
   (schemaVersion 1, `outcome:"INTERRUPTED"`, empty tasks, `derived` absent, minimal ci/vcs).
   Never touch `build-payload-v1.json`.
2. **Commons — golden test.** Add a `GoldenPayloadTest` case: the new file deserializes,
   `outcome==INTERRUPTED`, round-trips losslessly; existing v1 asserts unchanged.
3. **Plugin — shared buildId + marker writer.** Give the collector service a CC-safe view of
   buildId/startedAt/mode/projectKey/requestedTasks/ci/vcs; on the first task event write
   `build/buildhound/started/<buildId>.json` once (`AtomicBoolean` guard) inside
   `runCatching` (failure → `info`, no throw). Move buildId generation off
   `TelemetryFinalizerAction.kt:112` into a shared CC-safe value.
4. **Plugin — assembler.** Add Gradle-free `PayloadAssembler.assembleInterrupted(marker,
   projectRoots)`: `StartMarker` → `BuildPayload` (interrupted, `finishedAt=startedAt`, empty
   tasks, null derived, ci/vcs from marker) then `PayloadScrubber.scrub`.
5. **Plugin — reconciliation.** In `TelemetryFinalizerAction.execute` (inside the existing
   outer `runCatching`), before writing this build's payload: delete this build's own marker;
   scan `started/` bounded+TTL; for each stale marker assemble+scrub+route through
   `UploadGate`/`PayloadUploader`, then delete it. Factor the scan/selection into a pure
   `MarkerReconciler` for unit testing.
6. **Server — filter + trends.** Confirm `buildFilterOrNull` accepts `interrupted` (free via
   `BuildOutcome.entries`). Add `TrendPoint.interrupted: Int = 0`; in both stores' `trends`,
   count interrupted separately and exclude it from duration/hit-rate aggregates.
7. **Server — connector fallback rule.** In plan 028's enrichment worker, after `fetchRun`,
   synthesize a deterministic-id `INTERRUPTED` row when no `builds` row exists for
   `(project,provider,runId)`; idempotent; gated on connector config; no-op until the
   connector lands if plan 028 is unmerged.
8. **Dashboard.** Add the `INTERRUPTED` badge class, the `interrupted` filter option, the
   empty-tasks amber note in `detailView`, and the `.INTERRUPTED` style rule in `index.html`.
   Extend the dashboard smoke/script test.
9. **Docs — spec.** Add the `INTERRUPTED` note to spec §3.9/§4 (`outcome` now has three
   values; lost builds surface via marker reconciliation) — same-PR patch per the living-doc
   rule ([plan 020](020-doc-repairs-spec-drift.md) owns the broader §3.9 rewrite).
10. **Docs — architecture decision log.** Add a §7 row: lost-build accounting via
    execution-time start-marker reconciliation (primary) + Azure connector expected-build
    check (fallback); `INTERRUPTED` additive; marker written from execution code (never
    `apply()`, rule 9) and never fails/hangs the build.

## 5. Test strategy

- **Commons golden (contract):** new file deserializes, round-trips, `outcome==INTERRUPTED`;
  `build-payload-v1.json` untouched and green.
- **Plugin unit (Gradle-free):** `PayloadAssemblerTest` — `assembleInterrupted` yields the
  expected payload (interrupted, `finishedAt==startedAt`, empty tasks, null derived, ci/vcs
  carried, scrubbed); `StartMarker` JSON round-trips.
- **Plugin unit — `MarkerReconciler`:** stale markers selected, the current build's marker
  excluded, bound + TTL honoured, a corrupt marker skipped (never throws).
- **Plugin functionalTest (TestKit):** (a) a normal build leaves **no** marker behind; (b)
  pre-seed a stale marker then build → an `INTERRUPTED` payload/spool entry appears for the
  seeded buildId and the marker is gone. Matrix {Gradle 8.14, 9.latest} × {CC on/off}; assert
  "Configuration cache entry reused" across two runs (marker IO must not invalidate CC).
- **Failure-injection (guardrail):** unwritable/read-only marker dir, corrupt marker, dir
  replaced by a file, reconciliation upload failure → build still succeeds, warn/info logged,
  no throw, no hang.
- **Server route (`testApplication`, no socket):** ingest `INTERRUPTED` → 202; appears in
  `/v1/builds`; filterable by `outcome=interrupted`; `/v1/trends` counts it under
  `interrupted` without inflating failures or skewing avg duration.
- **Server Testcontainers:** Postgres round-trip of an `INTERRUPTED` build; idempotent
  re-ingest is a no-op.
- **Connector fallback (`testApplication`, MockEngine — plan 028 harness):** a Timeline for a
  `(provider,runId)` with no ingested payload yields one deterministic-id `INTERRUPTED` row;
  one that *did* ingest yields none; repeated fetch is idempotent; no connector → nothing.
- **Dashboard:** script/smoke test asserts the badge class + filter option and the empty-task
  amber note; CSP still forbids inline script.

## 6. Risks

- **CC / isolated-projects (highest).** Marker IO at execution start is mandatory — a
  config-phase file touch would invalidate the next build's CC entry (arch §2 rule 9,
  plan-003 salt scar). Mitigation: IO lives only in the execution-time collector service,
  guarded + `runCatching`; a functional test asserts CC reuse. Under isolated projects the
  collector still sees task events, so the path is unaffected (no config-time task-graph
  dependency, unlike [plan 016](016-task-type-cacheable-capture.md)).
- **Never-fail / never-hang.** The whole marker+reconciliation path is best-effort inside
  `runCatching`; no new subprocess or blocking wait (arch §2 rules 3/11). Dir scans are
  bounded and TTL-pruned so a dead server cannot grow the dir.
- **Schema compatibility.** Additive enum value + new golden, old file never edited (arch §3
  rule 2); no new required field; old-server 400 spool-drops, does not corrupt.
- **Duplicate/racing records.** Marker + connector + a successor that finalizes could each
  describe the same dead build. Mitigation: buildId idempotency (`BuildStore.save`); the
  connector uses `interrupted:<provider>:<runId>`, the marker uses the dead build's real
  buildId — both are "extra visibility, never a lie" and neither can be mistaken for a
  finalized build (empty tasks, `INTERRUPTED`).
- **False positives.** Deleting this build's own marker as step 1 of finalization, plus
  reconciling only markers with buildId ≠ current, means a build that reaches finalization is
  never reported interrupted; TTL bounds any genuinely orphaned marker.
- **Security / privacy.** The marker holds only payload-sanctioned fields (buildId, times,
  requested tasks, mode, ci correlation, branch/sha) — no token (arch §6: spool/markers never
  hold the token), no identity, no absolute paths (scrubbed via the same `PayloadScrubber`
  before upload); it lives under `build/` in the spool's trust domain. The connector fallback
  stores only Azure-derived correlation, treating `workerName` with plan 028's server-only
  caution.
- **Trends poisoning.** Failing to exclude `INTERRUPTED` from duration/hit-rate math would
  skew baselines; the trends change + its explicit test are the mitigation, and the
  regression engine ([plan 025](025-regression-engine-v1.md)) must exclude it too (noted
  cross-plan dependency).

## 7. Exit criteria

- A daemon-killed build (simulated in TestKit via a pre-seeded stale marker) surfaces as
  `INTERRUPTED` on the next build — a payload is written and, when the upload gate permits,
  uploaded — instead of vanishing.
- `BuildOutcome.INTERRUPTED` exists; `build-payload-interrupted-v1.json` added; the golden
  contract test is green with `build-payload-v1.json` unmodified.
- A normal build leaves no marker and is never reported interrupted; CC entries are reused
  across consecutive builds.
- The server ingests, stores, lists, filters (`outcome=interrupted`), and renders
  `INTERRUPTED`; `/v1/trends` counts them separately and excludes them from duration/hit-rate.
- With an Azure connector configured, a completed-on-Timeline-but-never-ingested run produces
  exactly one idempotent server-originated `INTERRUPTED` build; without a connector, nothing.
- Every marker/reconciliation failure path degrades to a log and never fails or hangs the
  build.
- `docs/architecture.md` decision log carries the lost-build row and spec §3.9/§4 notes the
  third outcome, both in this PR.
