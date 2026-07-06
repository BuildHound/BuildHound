# Plan 044 — test telemetry lost in composite (included-build) builds

**Status: planned** · 2026-07-06

## 1. Source

Defect found exercising the plan-043 nowinandroid dev harness. The harness applies the plugin
via `includeBuild("../..")` (a composite build). On every sample build the payload carries
`tests: []` even when Gradle demonstrably ran tests (its own JUnit XML shows `tests="1"`), and
every task's `type`/`cacheable` is null. Silent data loss: the finalizer never sees the
config-time data, so `/v1/builds` and the HTML report get no test telemetry from the harness.
Relates to plan 016 (task dictionary), plan 024 (test collection), architecture §2 rule 12 +
the 2026-07-03 test-telemetry decision-log row.

## 2. Root cause

Both datasets ride the plan-016 "mailbox": `settings.gradle.taskGraph.whenReady` fills two
`AtomicReference` holders; the collector `BuildService`'s param providers read them; the
finalizer reads them back via `collector.snapshotLocations()` / the collector's `metadata`.

BuildService parameters are **frozen when the service is first instantiated**. In a composite
build the included builds (`build-logic`, and BuildHound itself) execute their tasks *first*;
those task-finish events reach the root build's listener and instantiate the collector **before
the root build's `taskGraph.whenReady` runs**. Proven by ordered diagnostics:

```
collector metadata read: size=0                              ← params frozen empty here
whenReady ran: allTasks=13, metaHolder=13, testTasks=1        ← holder filled, too late
```

`snapshotLocations()` is not itself cached, yet still returned empty at finalizer time — so the
param is frozen-empty, not merely stale. The mailbox's assumption ("the param provider is
realized after configuration") holds for the classpath-applied path (TestKit, published-plugin
usage) but is false for a composite build. CC/parallel are ruled out — the loss reproduces with
`--no-configuration-cache --no-parallel`.

## 3. Scope

**In (test telemetry only):**
- A durable **sidecar file** for the Test-task JUnit XML locations, written at config time and
  read by the finalizer — replacing the frozen service param as the delivery channel for test
  locations. Modeled on the plan-031 artifact sidecar and the identity-salt file.
- File lives under **`.gradle/buildhound/`** (like `identity.salt`), *not* `build/` — so it
  survives `clean` and its lifecycle tracks the CC entry: present iff config ran or a CC entry
  exists. This closes the composite **+ CC-hit** path (run the same test task twice → run 2 is
  `cc=HIT`, config skipped, but the file persists from run 1; the CC key pins `requestedTasks`
  and the task graph, so the persisted locations match the current run).
- Finalizer prefers the file; falls back to `collector.snapshotLocations()` only if the file is
  absent/unreadable (belt-and-suspenders; the classpath path keeps working unchanged).
- Docs same PR: architecture §2 rule 12 amended (test locations now via the durable file, not
  the frozen param) + a decision-log row. `samples/README.md` gains a `0 test(s)` troubleshooting
  bullet (the reporter's original confusion — `--rerun-tasks` + the JVM-vs-Android test-task
  names). *(Divergence: the plan first said "remove a troubleshooting note"; no such note existed
  — the bug was undiscovered — so a helpful one is added instead.)*

**Out:**
- The task `type`/`cacheable` **dictionary** (plan 016) has the *same* root cause but is consumed
  on the collector's hot `onFinish` path (a per-event file read is the wrong shape) and is
  cosmetic (affects `type` display + `cacheableHitRate` only in the composite dev harness, not
  the classpath path). **Deferred to a tracked follow-up** (plan 045) to keep this fix off the
  hot path and small; the composite functional test here does not assert `type`.
- No schema change — payload shape is unchanged; this is a delivery-path fix.
- Isolated-projects degradation (type/tests null under IP) is unchanged: the file is written in
  the same IP-gated `whenReady` block, so IP still degrades to empty by design.

## 4. Design

- `TestLocationSidecar` (new): writes `Map<String, TestResultLocations>` as JSON-lines to
  `<rootDir>/.gradle/buildhound/test-locations.jsonl` and reads it back, both defensively
  (never throws; a miss → empty), mirroring `ArtifactRecordIo`. `TestResultLocations` gains a
  kotlinx `@Serializable` form (it is already `java.io.Serializable` for the param) or a small
  record IO — whichever keeps it additive and dependency-free.
- `BuildHoundSettingsPlugin`: inside the existing `whenReady` `runCatching`, after building the
  test-locations map (unchanged), also `TestLocationSidecar.write(rootDir, map)`. The existing
  holder→param wiring stays (fallback + no behavior change on the classpath path). Write is a
  side effect, not a CC input read, so it does not invalidate the next entry (contrast the salt,
  which is *read* at execution for exactly that reason).
- `TelemetryFinalizerAction`: `val locations = TestLocationSidecar.read(rootDir).ifEmpty {
  collector.snapshotLocations() }`, then the existing `TestResultCollector.collect(...)` is
  unchanged. The file is **not** deleted (must survive the next CC-hit); it is overwritten each
  config run.
- Never-fail preserved: all sidecar IO inside `runCatching`; any failure degrades to the current
  empty behavior.

## 5. Test strategy

- **Functional (TestKit), new — the regression gate:** a root build that `includeBuild`s a
  *second build whose task executes during the root's configuration* (a build-logic/plugin-style
  included build — a plain `includeBuild` nobody depends on runs nothing early and would pass
  green on `main`, a fake gate). Apply the plugin at the root, run a `Test` task, assert the
  payload's `tests` is non-empty. **Must be confirmed red on `main` before the fix.**
- **CC-hit coverage:** run the composite test task twice in the same TestKit runner; assert run 2
  is a CC hit **and** still reports tests (the `.gradle` file persistence path).
- Unit: `TestLocationSidecar` round-trip + malformed/missing-file degradation to empty.
- Existing `TestCollectionFunctionalTest`, CC-reuse, and settings-plugin functional tests stay
  green (classpath path and CC key unchanged).
- Manual: `cd samples/nowinandroid && ./gradlew :core:common:test --rerun-tasks` → `>0 test(s)`;
  run again → `cc=HIT` and still `>0 test(s)`; dashboard shows the run.

## 6. Risks

- **CC correctness (highest):** the sidecar must not become a CC input. It is *written* at config
  time (side effect) and *read* at execution time (finalizer) — neither is a config-cache input.
  The salt file proves the boundary (it is read at execution precisely to avoid being an input).
  Assert CC store+reuse in the functional test.
- **Stale/mismatched file on CC-hit:** guarded by the CC key — `requestedTasks` and the task
  graph are part of the key (plan 016), so a hit implies the same Test tasks and dirs. A `clean`
  wipes `build/` but not `.gradle`, and the re-executed tasks rewrite their XML into the same
  dirs the file names.
- **Concurrent/other builds:** file is under the build's own `.gradle`; one build per rootDir.
- Security/privacy: no new data collected, no new field/endpoint, no PII. The file holds task
  paths + absolute JUnit XML dirs already present in the payload's scrub domain; it is local,
  under `.gradle` (git-ignored), never uploaded. Pure delivery fix.

## 7. Exit criteria

- New composite functional test passes and is confirmed **red on `main`**; the CC-hit variant
  passes.
- `cd samples/nowinandroid && ./gradlew :core:common:test --rerun-tasks` reports `>0 test(s)`,
  and a second back-to-back run is `cc=HIT` and still `>0 test(s)`.
- Full `./gradlew build` green (unit + functional), CC still reused where asserted.
- Architecture §2 rule 12 + decision log updated; follow-up plan 045 (task-`type` dictionary in
  composite) filed; `samples/README.md` `0 test(s)` troubleshooting bullet added. Code &
  architecture review (kotlin-gradle-reviewer) and the §3.2 security/privacy review both clean or
  findings resolved.
