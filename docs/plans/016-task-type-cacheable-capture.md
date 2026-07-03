# Plan 016 — Task type + cacheable capture, honest cacheableHitRate, configurationMs

**Status: planned — roadmap phase 2a** · 2026-07-03

## 1. Source

- [Roadmap phase 2a](../build-telemetry-roadmap.md), first bullet: type + cacheable via
  Talaiot's configuration-time `taskGraph.allTasks` dictionary with `BuildFeatures`-gated
  isolated-projects degradation; cacheable-only hit-rate denominator; `configurationMs`
  from the existing configuration-phase observer.
- [Spec §3.2](../build-telemetry-spec.md): "Task type/class captured at configuration
  time into the service parameter map (provider-lazy, CC-safe)". [Spec §4](../build-telemetry-spec.md):
  `tasks[].type`/`cacheable`/`nonCacheableReason`, `derived.configurationMs`.
- Research: [Talaiot](../research/repos/Talaiot.md) (dictionary pattern, `_Decorated`
  strip, empty-map IP degradation; also its warning to use typed events, which we already
  do), [comparison-to-spec §2.1/2.1a](../research/comparison-to-spec.md) (adopt verdict;
  observer yields configuration duration), [gap analysis](../research/plugin-ecosystem-gap-analysis.md)
  (§3 by-type rollups need the field; §"risks": define the IP degradation contract).

## 2. Scope

**In:**

- Configuration-time task dictionary (path → type + cacheable + nonCacheableReason) from
  `gradle.taskGraph.whenReady { it.allTasks }`, snapshotted into `TaskEventCollector`
  service parameters; empty under isolated projects via the already-injected
  `BuildFeatures` ([BuildHoundSettingsPlugin.kt:23](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)).
- Populate the existing (never-populated) schema fields `TaskExecution.type`,
  `cacheable`, `nonCacheableReason` ([BuildPayload.kt:98–103](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt)).
- `cacheableHitRate` recomputed over a cacheable-only denominator; null (not the old
  mixed number) when no cacheable flags exist in the payload.
- `configurationMs` measured by extending `DaemonState` (apply mark → task-graph-ready
  mark); `0` on a CC hit, null when unmeasurable. Threaded through
  `DerivedMetricsCalculator.compute`, which hardcodes null today
  ([DerivedMetricsCalculator.kt:23](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/DerivedMetricsCalculator.kt)).
- Scrubber coverage for `nonCacheableReason` — mandated in advance by the scrubber's own
  contract ([PayloadScrubber.kt:5–7](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/PayloadScrubber.kt)).
- New golden file pinning the populated shape; architecture decision-log rows.

**Out:** `avoidedMs`/`criticalPathMs` — stay hardcoded null until cache-origin/graph data
exist (plan 038). Runtime cacheability (`cacheIf {}` specs, actual cache controller
state) — internal API, plan 038. Timeline/dashboard consumers (plans 017, 018), by-type
server rollups (plan 026, unblocked by this plan), cardinality/size caps (plan 019), the
isolated-projects CI job that will exercise the degradation continuously (plan 021).
`worker` stays unpopulated. No new dependencies, no DSL changes, no schema change —
every field already exists with a null default, so old payloads and servers are unaffected.

## 3. Design

**Current behavior (verified):** `TaskEventCollector.onFinish` builds `TaskExecution`
without `type`/`cacheable`/`nonCacheableReason`
([TaskEventCollector.kt:28–36](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TaskEventCollector.kt));
the service is registered with `BuildServiceParameters.None` at apply
([BuildHoundSettingsPlugin.kt:45–50](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)).
`cacheableHitRate` counts (FROM_CACHE + UP_TO_DATE) / (those + EXECUTED) over **all**
tasks ([DerivedMetricsCalculator.kt:32–37](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/DerivedMetricsCalculator.kt)).
`DaemonState` records only booleans ([DaemonState.kt:24–33](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/DaemonState.kt)),
consumed once per build by the finalizer ([TelemetryFinalizerAction.kt:97](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TelemetryFinalizerAction.kt)).

**Dictionary.** In `apply()` (root build only — included builds already return early,
BuildHoundSettingsPlugin.kt:38–41), register `settings.gradle.taskGraph.whenReady`. The
callback runs at configuration time only, so capturing `Settings` in it is CC-safe
(Talaiot precedent). Body, wrapped in `runCatching` → single warn: if
`buildFeatures.isolatedProjects.active.getOrElse(false)`, leave the dictionary empty
(info log); otherwise walk `graph.allTasks` into `Map<String, TaskMetadata>` where
`TaskMetadata(type, cacheable, nonCacheableReason)` is a small `java.io.Serializable`
data class (precedent: `CollectedCi`). Per task: `type` = fully-qualified class name
with Gradle's `_Decorated` suffix stripped (FQN, diverging cosmetically from spec §4's
illustrative `"KotlinCompile"`, because it is the unambiguous grouping key plan 026
needs; UIs shorten for display); `cacheable` = a name-based superclass walk finds
`org.gradle.api.tasks.CacheableTask`; `nonCacheableReason` = the `because` text of
`org.gradle.work.DisableCachingByDefault` when present and not cacheable, else null.
Name-based reflection keeps the walk robust to decorated subclasses and non-`@Inherited`
annotations, and keeps the helper free of Gradle types.

**Delivery to the collector (spec §3.2's "service parameter map, provider-lazy").**
This plan introduces the `TaskEventCollector.Params` interface, replacing the current
`BuildServiceParameters.None`; 016 lands first and owns this `BuildService`. Params holds
`taskMetadata: MapProperty<String, TaskMetadata>`, set at registration to
`settings.providers.provider { holder.get() }` where `holder` is a plain per-apply
`AtomicReference` populated by the `whenReady` callback. **Forward note:** plan 024
shares this same `TaskEventCollector` `BuildService` and *extends* this Params interface
with `testResultLocations: MapProperty<String, TestResultLocations>` — it must extend the
interface introduced here, not re-derive from `None`. The merged shape once both land is
one `Params` interface carrying both maps. Evaluation
order makes this correct in all modes: no-CC — the service instantiates at the first
task event, after `whenReady`; CC store — parameters are finalized when the entry is
written, after configuration; CC hit — the stored value replays and neither closure
runs (the same mechanism that already makes the lazy `projectKey` provider work,
BuildHoundSettingsPlugin.kt:89). `onFinish` reads the map once into a lazy field and
fills the three fields per event. Included-build task paths are absent from the root
graph and stay null — documented limitation.

**Honest hit rate.** `DerivedMetricsCalculator.cacheableHitRate` becomes: a task is
*cache-relevant* iff `cacheable == true || outcome == FROM_CACHE` (a FROM_CACHE outcome
proves cacheability even when the static flag missed a `cacheIf {}`). Numerator =
cache-relevant tasks with outcome FROM_CACHE or UP_TO_DATE; denominator = cache-relevant
tasks with outcome in {EXECUTED, FROM_CACHE, UP_TO_DATE} (FAILED/SKIPPED/NO_SOURCE
excluded, as today). If no task in the payload carries a non-null `cacheable` flag
(isolated-projects degradation, legacy payloads), return **null** rather than the v0
mixed-denominator number — honest-nulls principle (plan 005); mixing two metric
definitions in one trend line is worse than a gap. Consumers already tolerate null:
finalizer log prints "n/a" (TelemetryFinalizerAction.kt:141), dashboard/report chips are
null-guarded, and the trend average drops nulls
([BuildStore.kt:116,128](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt)).

**configurationMs.** `DaemonState` gains monotonic timestamps: `configurationRan()`
(already called at apply, BuildHoundSettingsPlugin.kt:43) records a start nanotime; a new
`configurationCompleted()` called from the same `whenReady` callback (also under IP —
registering the callback is IP-safe, only `allTasks` is not) records the end.
`executionRan()` returns and resets both; duration is null unless both marks are present
and ordered. The finalizer derives the payload value: CC state HIT → `0` (configuration
was skipped; entry-load time is not measured, documented approximation), otherwise the
measured duration or null. It rides through a new `configurationMs` parameter on
`PayloadAssembler.assemble` → `DerivedMetricsCalculator.compute`
([PayloadAssembler.kt:72](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadAssembler.kt)).
The measurement starts at settings-plugin apply, so init-script/settings-compile time is
excluded — stated approximation, same v0 shared-daemon caveat as DaemonState today.

## 4. Implementation steps

1. `buildhound-commons` — `DerivedMetricsCalculator`: cacheable-aware `cacheableHitRate`
   as designed above; `compute(tasks, cores, configurationMs: Long? = null)` passes the
   value through instead of hardcoding null. Update `DerivedMetricsCalculatorTest`.
2. `buildhound-commons` — `PayloadScrubber.scrub`: route `task.nonCacheableReason`
   through `scrubText` (same roots/rules as `executionReasons`); update the class doc.
   Add `PayloadScrubberTest` cases.
3. `buildhound-commons` — add golden file
   `src/jvmTest/resources/golden/build-payload-v1-task-metadata.json` with populated
   `type`/`cacheable`/`nonCacheableReason`/`configurationMs` and a matching
   `GoldenPayloadTest` case. Existing `build-payload-v1.json` untouched (never edit).
4. `buildhound-gradle-plugin` — new `TaskMetadata` Serializable data class and a
   Gradle-type-free `TaskClassIntrospection` helper (name-based superclass walk for the
   two annotations, `_Decorated` strip) so the reflection logic unit-tests without
   `gradleApi()` on the test classpath (plan 015 precedent).
5. `buildhound-gradle-plugin` — `TaskEventCollector`: replace
   `BuildServiceParameters.None` with the new
   `Params { val taskMetadata: MapProperty<String, TaskMetadata> }` interface (plan 024
   later extends this same interface with `testResultLocations`, so define it as the
   shared home rather than a 016-only shape); `onFinish` fills
   `type`/`cacheable`/`nonCacheableReason` from a lazily-read map.
6. `buildhound-gradle-plugin` — `DaemonState`: start/end nanotime marks,
   `configurationCompleted()`, `Execution.configurationMs: Long?`, reset in
   `executionRan()`.
7. `buildhound-gradle-plugin` — `BuildHoundSettingsPlugin.apply`: create the holder, set
   the service parameter provider, register the `whenReady` callback (IP gate, dictionary
   walk in `runCatching` → warn, `DaemonState.configurationCompleted()`); internal
   test-only failpoint property `buildhound.internal.failTaskGraphSnapshot` that makes
   the walk throw (failure-injection seam, precedent `buildhound.optin.file`).
8. `buildhound-gradle-plugin` — `TelemetryFinalizerAction` + `PayloadAssembler`: derive
   and thread `configurationMs` (HIT → 0, measured, else null). No other payload changes.
9. Functional tests (step 10 below) and unit tests for 4–8.
10. `docs/architecture.md` — decision-log rows: (a) isolated-projects degradation
    contract — dictionary empty, `type`/`cacheable` null, `cacheableHitRate` null; the
    plan-021 IP job asserts exactly this; (b) `cacheableHitRate` definition change and
    the FROM_CACHE-implies-cacheable guard. Note in §2 that `whenReady` is the sanctioned
    config-time hook for graph-derived data.
11. Re-read this plan against the diff; record any divergence here in the same PR.

## 5. Test strategy

- **Unit (commons):** cacheable-only denominator over mixed outcomes; FROM_CACHE task
  with `cacheable == false/null` still counted cache-relevant; all-flags-null → null;
  flags present but zero cache-relevant tasks → null; `configurationMs` passthrough;
  scrubber redacts an absolute path and a secret-shaped value inside `nonCacheableReason`.
- **Unit (plugin):** `TaskClassIntrospection` — annotated class, annotated superclass
  with decorated subclass, un-annotated class, `DisableCachingByDefault(because = …)`.
- **Golden:** new v1 file deserializes with populated fields; round-trip lossless;
  existing golden file still passes untouched.
- **Functional (TestKit):** fixture with three task types (`@CacheableTask` custom task,
  plain task, `@DisableCachingByDefault(because = "…")` task): payload carries FQN types
  without `_Decorated`, correct flags and reason, hit rate matches the cacheable-only
  formula by hand. CC store run → `configurationMs > 0`, cc=MISS_STORED; CC hit run →
  types still populated (replayed parameters), `configurationMs == 0`. Isolated-projects
  run (`-Dorg.gradle.unsafe.isolated-projects=true`) → build SUCCESS, no plugin error,
  `type`/`cacheable` null, `cacheableHitRate` null.
- **Failure injection:** failpoint property set → build SUCCESS, one warn line, payload
  written with null types and everything else intact.

## 6. Risks

- **CC hazards:** the `whenReady` closure must never leak into execution time — it only
  ever runs during configuration, and the CC-hit functional test proves parameter replay.
  Provider evaluation order is the load-bearing assumption; it is the same mechanism the
  shipped `projectKey` provider relies on, and the CC store/hit tests pin it.
- **Isolated projects:** `allTasks` from settings scope is an IP violation by design —
  the `BuildFeatures` gate must run *before* touching the graph. Degradation contract is
  tested here once and continuously by plan 021's CI job.
- **Metric semantics change:** dashboards will show a step change (hit rate typically
  rises once non-cacheable work leaves the denominator) and nulls for degraded builds.
  Accepted: pre-release, and the old number was wrong. Server stores derived metrics
  as-sent; no migration.
- **Static cacheability is approximate:** `cacheIf {}`-only tasks read as
  `cacheable = false` until they hit the cache (then the FROM_CACHE guard corrects the
  metric). True runtime cacheability is plan 038.
- **Security/privacy:** `type` is a class FQN — declared, spec-listed data comparable to
  task paths; may embed org package names, same exposure class as module names.
  `nonCacheableReason` is free text from plugin authors and routes through the scrubber
  (step 2). No new env reads, no tokens, no absolute paths introduced.
- **Composites:** included-build tasks keep null metadata and drop out of the hit rate —
  documented; revisit if pilot data shows it matters.

## 7. Exit criteria

- `./gradlew build` green, including the new unit, golden, functional, IP-flagged and
  failure-injection tests; configuration cache stays on.
- A real build of this repo produces a payload where every root-build task has a
  non-null `type`, `cacheable` is true for known-cacheable task classes,
  `derived.cacheableHitRate` matches the cacheable-only formula recomputed by hand, and
  `derived.configurationMs` is > 0 (cold) and 0 on a CC-hit rerun.
- `avoidedMs`/`criticalPathMs` remain null; schema untouched (no field added or removed);
  existing golden file unmodified in the diff.
- `docs/architecture.md` decision log carries the IP-degradation and hit-rate rows.
- Plan 026's by-type rollups can group tasks by `type` from stored payloads.
