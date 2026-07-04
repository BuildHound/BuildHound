# Plan 038 — Internal-adapters module: cache origin, cache keys, tier-(b) fingerprints, criticalPath/avoided

**Status: in progress — commons foundation landed (df78f7a); mechanism spiked & proven** · 2026-07-03

> **Spike result (2026-07-04) — the load-bearing risk is resolved.** A throwaway settings-plugin
> spike (later discarded) empirically confirmed on Gradle 8.14 / JDK 21:
> - `gradleApi()` (via `java-gradle-plugin`) puts the internal types on the compile classpath —
>   `org.gradle.internal.operations.{BuildOperationListener,BuildOperationListenerManager,
>   BuildOperationDescriptor,OperationFinishEvent}` and
>   `org.gradle.api.internal.tasks.SnapshotTaskInputsBuildOperationType` all resolve and compile.
> - A `Plugin<Settings>` obtains `(gradle as GradleInternal).services.get(BuildOperationListenerManager)`
>   and `addListener(...)`; the listener **receives `SnapshotTaskInputsBuildOperationType.Result`
>   finished events and `result.hashBytes` (the cache key) is populated** when the build cache is on.
> - **It fires under configuration cache**: the second run of a `--configuration-cache --build-cache`
>   pair was a CC hit (`Reusing configuration cache`) yet still captured 2 snapshot ops with a cache
>   key. So the internal-op path is *not* defeated by CC — the plan's #1 risk.
>
> **Caveat for the real impl (not yet built):** the `BuildOperationListenerManager` is **daemon-scoped**
> — a listener `addListener`-ed in one build persists into the next build in the same daemon (this is
> *why* capture survived the CC hit). The real adapter must therefore (a) register at most once per
> daemon (a daemon-static `AtomicBoolean` guard, `DaemonState` precedent) and (b) reset its
> per-build accumulation at a build boundary (e.g. keyed by the core buildId, or a fresh accumulator
> published to a static bridge per build), so counts don't leak across builds in a warm daemon.
> Remaining steps 4–13 build the real module (adapter + service + registry contribution + core
> wiring + server comparator + dashboard + functional matrix) on this proven mechanism.

## 1. Source

- [Roadmap phase 4, item 2](../build-telemetry-roadmap.md): internal-adapters module — cache
  origin local/remote + per-task cache keys + tier-(b) input fingerprints via
  `SnapshotTaskInputsBuildOperationType`, `ExecuteWorkBuildOperationType` (execution reasons,
  caching-disabled reason, origin cache key ≥8.7), and `BuildCache{Local,Remote}{Load,Store}` ops —
  feature-flagged per Gradle version, degrading to "unknown"; upgrades the comparison page to
  per-property cause ranking and unlocks `avoidedMs`/`criticalPathMs`.
- Spec: [§3.1](../build-telemetry-spec.md) (the sanctioned "isolated `internal-adapters` module,
  feature-flagged per Gradle version, degrading to unknown origin gracefully" exception to the
  no-internal-APIs rule), [§4](../build-telemetry-spec.md) (payload: `derived.avoidedMs`/
  `criticalPathMs`), [§6](../build-telemetry-spec.md) (Comparisons page, "input-fingerprint diff
  arrives v1.x with cache-origin work"), [§9](../build-telemetry-spec.md) locked decision #1.
- Research: [cache-miss-input-fingerprints.md](../research/cache-miss-input-fingerprints.md) §2.3
  (verified internal type names + `@since` versions), §2.4 (gradle/gradle#9456 public-API refusal),
  §5 tier (b) + tier (c)-full; [repos/ArtifactTransformReport.md](../research/repos/ArtifactTransformReport.md)
  (transform executions are internal-adapters-only, and the avoidance-outcome/negative-savings
  taxonomy this plan mirrors); [plugin-ecosystem-gap-analysis.md](../research/plugin-ecosystem-gap-analysis.md)
  §2.1 (Tuist's identical internal-op usage is the independent confirmation there is no public route).

## 2. Scope

**In:**

- A new **separately-shipped, opt-in** Gradle module `buildhound-internal-adapters` (plugin id
  `dev.buildhound.internal-adapters`), the single sanctioned exception to architecture §2 rule 4.
  It is *not* wired into the core plugin's classpath; applying it is the consent to use internal
  APIs.
- A build-operation listener subscribing (via internal `BuildOperationListenerManager`) to:
  `SnapshotTaskInputsBuildOperationType` (per-task cache key + per-property value hashes; per-file
  capture **off by default**), `ExecuteWorkBuildOperationType` (`getExecutionReasons`,
  `getCachingDisabledReasonMessage`/`Category`, `getOriginBuildInvocationId`,
  `getOriginBuildCacheKeyBytes` ≥8.7), and `BuildCache{Local,Remote}{Load,Store}BuildOperationType`
  (local/remote origin split + pack/unpack/transfer timings).
- A **version-gated reflection adapter** per supported Gradle minor range, so a type/method mismatch
  degrades every field to "unknown" and never throws (spec §3.1 never-fail).
- Additive schema in commons: per-task cache origin + origin cache key + caching-disabled reason;
  a compact per-task input-property-hash + cache-key structure (tier b); and **population** of the
  long-null `derived.avoidedMs`/`criticalPathMs` — carried under the reserved addon `extensions`
  map (plan 039) so commons and core stay decoupled from the internal-op payload, with `derived`
  the only core-schema change.
- `avoidedMs` computed from cache-load/origin timings; `criticalPathMs` computed by
  `DerivedMetricsCalculator` from a task **dependency edge list** the adapter supplies (the graph
  data core has never had).
- Comparison-page upgrade (plan 022's `BuildComparator` + `GET /v1/builds/{a}/compare/{b}`): from
  allowlist-key ranking to **per-property cause ranking** and a local/remote origin lane, when
  tier-(b) data is present; otherwise it renders exactly as plan 022 leaves it.
- Architecture decision-log row recording the sanctioned exception, the per-version gate contract,
  and the Tuist-repo canary; spec §3.1/§4/§6 amendments.

**Out (and where they live):** tier (a) build-level/allowlist fingerprints + the compare endpoint +
comparisons page scaffold — **plan 022** (this plan upgrades them; hard dependency). The `extensions`
payload map (`extensions: Map<String, JsonElement>` on `BuildPayload`) + `BuildHoundCollectorRegistry`
+ `/v1/addons/<id>/…` namespace — **plan 039**, which owns and lands these first (039-before-038 is a
**hard dependency**; this module is 039's first real consumer and never adds its own copy of the
extensions field or registry). Static task `type`/`cacheable`/`configurationMs` — **plan 016** (this plan's runtime
cache key/origin is the dynamic complement). CC miss-*reason* capture — **plan 035**. Generic
cardinality/size-cap helpers — **plan 019** (this plan enforces its own per-file/per-task caps).
Artifact-transform telemetry (also internal-adapters-class, ArtifactTransformReport §Data collected)
— a later addition to the same module, not this plan. Process probe — **plan 029**; benchmark
experiment recipes — **plan 030**.

## 3. Design

**Why a separate module.** Core (`buildhound-gradle-plugin`) must stay free of internal Gradle
APIs (architecture §2 rule 4; verified — no `org.gradle.*.internal` imports in the collectors).
`BuildEventsListenerRegistry`/`TaskFinishEvent` (`TaskEventCollector.kt:25-37`) is outcome-level
only: it exposes no cache key, no origin, no per-property hash, and no dependency edges — confirmed
by gradle/gradle#9456's explicit refusal (research §2.4) and by Tuist reaching for the same internal
ops (research §2.1). ArtifactTransformReport reaches this data only by querying Develocity after the
fact (its §"Data collected"), which BuildHound will not do. So the data is reachable **only** through
internal build operations, and the spec's answer is an isolated, opt-in module.

**Module layout.** New `buildhound-internal-adapters` Gradle module (settings plugin
`dev.buildhound.internal-adapters`), added to `settings.gradle.kts` alongside the existing four
includes. `implementation(projects.buildhoundCommons)`; same Kotlin `apiVersion 2.0` /
`jvmTarget 21` / `-Xjdk-release=21` pins and JDK-21 consumer floor as the core plugin
(`buildhound-gradle-plugin/build.gradle.kts`). It applies **after** and cooperates with core: on
apply it looks up core's `TaskEventCollector` shared service by name
(`TaskEventCollector.SERVICE_NAME`, `TaskEventCollector.kt:53`) and, when core is absent, warns and
no-ops (addon rule — never fail, gap-analysis §6.1).

**The adapter (internal-API surface, isolated here).**
- `BuildOperationAdapter` registers a `BuildOperationListener` through the internal
  `BuildOperationListenerManager` (obtained from `gradle` internals; the one place a cast to a
  Gradle-internal type is allowed). Every `finished(...)` body is `runCatching → warn`.
- Per finished op, a **version gate** (`GradleVersion.current()` bucketed into ranges: `8.14–8.x`,
  `9.0–9.x`, `>9.x` unknown) selects a `SnapshotAdapter`/`ExecuteWorkAdapter`/`CacheTransferAdapter`
  that reads results by the exact `@since`-gated getters (research §2.3): origin cache key via
  `getOriginBuildCacheKeyBytes` only ≥8.7 (inside the 8.14 floor, so always present today, but the
  gate stays so a future removal degrades, not crashes). Reflection/`instanceof`-guarded access, no
  compile-time hard link to a method that a supported Gradle lacks; a missing type/method ⇒ that
  field is "unknown" and a one-time `info` line names the unmatched Gradle version.
- Captured per task path: `cacheKey` (hex of `SnapshotTaskInputs.getHashBytes`), `origin`
  (`LOCAL_HIT`/`REMOTE_HIT`/`STORED`/`MISS`/`UNKNOWN` derived from the `BuildCache*Load/Store` ops +
  `ExecuteWork` origin fields, mirroring Tuist's split and ArtifactTransformReport's
  avoidance-outcome taxonomy), `originBuildInvocationId`, `originCacheKey`, `cachingDisabledReason` +
  `cachingDisabledCategory`, and (default-off) per-input-property `{name → valueHash}` from
  `getInputValueHashesBytes`/`visitInputFileProperties`. Cache-transfer timings (pack/unpack/load/
  store ms, bytes) collected from the `BuildCache*` ops for `avoidedMs`.
- A **task dependency edge list** `(taskPath → dependencyPaths)`: `ExecuteWorkBuildOperationType`
  is per-work-unit and does not carry edges; the edges come from a config-time
  `gradle.taskGraph.whenReady { it.getDependencies(task) }` walk (public API, same IP-gated hook and
  `BuildFeatures` degradation contract plan 016 establishes for `allTasks`), snapshotted into the
  adapter's service parameters. This module owns that walk because criticalPath is its deliverable.

**Hashing / privacy.** Per-property value hashes and cache keys are already opaque digests produced
by Gradle. They are re-hashed with the per-project identity salt (HMAC-SHA256, `"fp:"` domain
separation) reusing plan 022's `FingerprintHashing`/`IdentitySalt` so tier-(b) hashes diff
cross-build within a project and can never be dictionary-reversed to the raw Gradle key. No salt ⇒
the tier-(b) block is **omitted**, never emitted raw. Property *names* pass through
`PayloadScrubber.scrubText` (`PayloadScrubber.kt:52`) — names are plugin-authored free text.
Per-file paths (`visitInputFileProperties`) are the payload-explosion and absolute-path risk, so
they are **off by default** (mirror Develocity's own gating history, research §5b) and, when opted
in, relativized/redacted through the scrubber before hashing.

**Payload (commons, additive).**
- `DerivedMetrics.avoidedMs`/`criticalPathMs` (already declared, `BuildPayload.kt:115-116`) become
  *populated* — no field added.
  `DerivedMetricsCalculator.compute` gains optional params:
  `compute(tasks, cores, configurationMs?, avoidedMs: Long? = null, dependencyEdges: Map<String, List<String>>? = null)`.
  `avoidedMs` is passed through (adapter-supplied, from transfer/origin timings); `criticalPathMs`
  is computed here — the longest weighted path over `dependencyEdges` using each task's `durationMs`
  (a pure DAG longest-path; null when edges are absent). This keeps the calculator the single source
  of truth for `derived` (plan 005) and lets server rollups recompute identically.
- The tier-(b) per-task detail (cache key, origin, origin key, caching-disabled reason, property
  hashes, transfer timings) rides in the reserved `extensions["internalAdapters"]` JSON blob
  (plan 039), version-tagged with its own `schemaVersion`, so `buildhound-commons` need not grow a
  typed field per internal-op release and the core plugin stays oblivious. `BuildHoundJson.payload`
  already `ignoreUnknownKeys` (`BuildHoundJson.kt:11-15`), so old servers tolerate it.
- New golden file `build-payload-v1-internal-adapters.json` (populated `extensions` +
  non-null `avoidedMs`/`criticalPathMs`); `build-payload-v1.json` is **never** edited.

**Assembly.** The adapter contributes through the `BuildHoundCollectorRegistry` (plan 039): core's
`TelemetryFinalizerAction` (`TelemetryFinalizerAction.kt:93-125`) already assembles once via
`PayloadAssembler.assemble` (`PayloadAssembler.kt:29-76`); the registry's named provider is read
there and merged into `extensions`, and the adapter's `avoidedMs` + `dependencyEdges` are threaded
into the `DerivedMetricsCalculator.compute` call at `PayloadAssembler.kt:72`. Plan 039 owns and lands
`BuildHoundCollectorRegistry` and the `extensions` map first (039-before-038); this plan consumes
them and adds no stub of its own.

**Comparison upgrade (server).** Plan 022 ships `BuildComparator` + `GET /v1/builds/{a}/compare/{b}`
in `queryRoutes` (`Routes.kt:107-135`), reading two payloads via `store.findById`
(`BuildStore.kt:51`). This plan extends `BuildComparator` (pure, unit-testable): when both builds
carry `extensions["internalAdapters"]`, for each task EXECUTED in B but avoided/cacheable in A it
diffs **per-property value hashes** and the **cache key**, ranks differing properties by
`|misses whose property differs| / |misses|` (the research §5c formula, now at property granularity),
and adds a `LOCAL_HIT`/`REMOTE_HIT`/`STORED`/`MISS` **origin lane** per build. Absent tier-(b) data ⇒
it falls back to plan 022's build-level/allowlist ranking (union, never error). No migration, no new
table — the payload jsonb already holds `extensions`.

## 4. Implementation steps

1. **commons** — `DerivedMetricsCalculator`: add `avoidedMs`/`dependencyEdges` params to `compute`;
   implement DAG longest-path `criticalPathMs` (weight = `durationMs`, cycle-guarded, null when edges
   absent) and pass `avoidedMs` through. Keep old call sites compiling via defaulted params.
   Update `DerivedMetricsCalculatorTest`.
2. **commons** — the reserved `extensions: Map<String, JsonElement> = emptyMap()` field on
   `BuildPayload` is **owned and landed by plan 039** (039-before-038); this plan depends on it and
   adds no copy. Golden files added, never edited.
3. **commons** — golden file `build-payload-v1-internal-adapters.json` + `GoldenPayloadTest` cases
   (deserialize, round-trip, absent-`extensions` default parses, `avoidedMs`/`criticalPathMs`
   populated). Existing golden untouched.
4. **settings** — add `include(":buildhound-internal-adapters")` to `settings.gradle.kts`.
5. **new module `buildhound-internal-adapters`** — `build.gradle.kts` mirroring the core plugin's
   toolchain/apiVersion/jvmTarget pins and `java-gradle-plugin`; `gradlePlugin { plugins { create(...) { id = "dev.buildhound.internal-adapters" } } }`; `functionalTest` source set + Gradle-matrix
   test task like the core plugin. Version catalog holds every version (no new external dependency
   expected beyond Gradle's own API + commons).
6. **new module** — `InternalAdaptersSettingsPlugin` (`Plugin<Settings>`): looks up core's
   `TaskEventCollector` service; warns + no-ops if core absent; registers the
   `TaskInputsCollector` build service and the `BuildOperationAdapter`; registers the config-time
   `whenReady` dependency-edge walk (IP-gated via `BuildFeatures`, `runCatching → warn`); a DSL
   `internalAdapters { perFileHashes = false }` (default off) for the per-file opt-in.
7. **new module** — `BuildOperationAdapter` + per-version `SnapshotAdapter`/`ExecuteWorkAdapter`/
   `CacheTransferAdapter` (reflection/`instanceof`-guarded, `@since`-correct getters, version gate,
   never-throw). This is the only place internal Gradle types appear.
8. **new module** — `TaskInputsCollector` build service (concurrent `taskPath → InternalTaskDetail`
   store; caps: ≤ 200 property hashes/task, ≤ 2000 task entries, per-file map off by default and
   ≤ 500 files/task when on; overflow drops remainder + one `warn`). Re-hash values/keys via the
   shared salt; scrub property names.
9. **new module** — `InternalAdaptersCollector` registered with `BuildHoundCollectorRegistry`
   (plan 039): serializes `InternalTaskDetail[]` + version-tag into
   `extensions["internalAdapters"]`, and exposes `avoidedMs` + `dependencyEdges` to core's
   derived-metrics call.
9a. **core plugin** — thread the registry's `avoidedMs`/`dependencyEdges` into the existing
    `DerivedMetricsCalculator.compute` call (`PayloadAssembler.kt:72`); no other core change (core
    stays internal-API-free; it only reads registry providers).
10. **server** — extend `BuildComparator` (plan 022): per-property/cache-key diff, per-property
    coverage ranking, origin lane, graceful fallback to tier-(a); `BuildComparatorTest` for each.
    No new route, no migration.
11. **dashboard** — comparisons page (plan 022) gains a per-property cause table and an origin
    lane; empty state when a build lacks tier-(b) data. All via `textContent`, allowlisted CSS.
12. **docs, same PR** — `docs/architecture.md`: §2 rule 4 cross-reference and a **decision-log row**
    (date 2026-07-03) recording (a) `buildhound-internal-adapters` as the sanctioned, opt-in,
    separately-shipped exception to no-internal-APIs, (b) the per-Gradle-version reflection-gate
    contract and never-fail degradation to "unknown", (c) the Tuist-repo breakage canary, (d) that
    `avoidedMs`/`criticalPathMs` are now populated (superseding plan 005's honest-null note). Spec
    §3.1 (module now exists, list the ops), §4 (`extensions.internalAdapters` shape + populated
    `derived` fields), §6 (comparisons page now does per-property cause ranking + origin) amended.
13. Re-read this plan against the diff; record any divergence here in the same PR.

## 5. Test strategy

- **Unit (commons):** `DerivedMetricsCalculator` — `criticalPathMs` over a hand-built DAG (linear
  chain = Σ durations; diamond = longest branch; disconnected tasks; single task; cycle guard
  returns null not hang); null when `dependencyEdges` absent; `avoidedMs` passthrough. Golden
  deserialize/round-trip; absent-`extensions` still parses.
- **Unit (module, Gradle-type-free where possible):** `criticalPath`/origin-classification helpers
  as plain functions (plan 015/016 precedent — keep logic out of Gradle types so it unit-tests).
  Caps tests (201st property dropped + warned; per-file off ⇒ empty file map).
- **functionalTest (TestKit), matrix per plan 021's Gradle legs:** a fixture with a `@CacheableTask`
  producing a cache hit run and a cache-store run —
  (i) cache key present and stable across two identical runs, differs when an input changes;
  (ii) origin `LOCAL_HIT` on the second run, `STORED`/`MISS` on the first;
  (iii) `caching-disabled` reason captured for a `@DisableCachingByDefault` task;
  (iv) `avoidedMs > 0` and `criticalPathMs > 0` in the payload; on a build with no cacheable work,
  `avoidedMs` is 0/null, `criticalPathMs` still reflects the chain;
  (v) `perFileHashes` default-off ⇒ no per-file entries; opted-in ⇒ present and relativized.
- **Version-gate / failure injection:** a stubbed adapter simulating an unmatched Gradle version ⇒
  build SUCCESS, `origin`/`cacheKey` "unknown", single `info`, no throw; a listener body forced to
  throw (test-only failpoint property, plan 016 precedent) ⇒ build SUCCESS, one `warn`, payload
  written with the internal-adapters block absent, `derived.avoidedMs`/`criticalPathMs` null.
- **Isolated projects:** `-Dorg.gradle.unsafe.isolated-projects=true` ⇒ SUCCESS, dependency-edge
  walk degrades to empty, `criticalPathMs` null, build-op capture (not graph-scoped) may still
  populate cache keys/origin; contract asserted here and continuously by plan 021's IP job.
- **Core-absent:** applying `dev.buildhound.internal-adapters` without core ⇒ SUCCESS + one warn,
  no payload contribution.
- **Server unit:** `BuildComparatorTest` — property differing on 2 of 4 misses ranks 0.5; cache-key
  identical but one property differs still explains; origin lane populated; both builds lacking
  tier-(b) ⇒ falls back to plan 022 ranking, no error.
- **Server routes / dashboard:** the existing `GET /v1/builds/{a}/compare/{b}` route test (plan 022)
  extended to assert per-property output when both payloads carry `extensions.internalAdapters`;
  dashboard node smoke harness covers the new cause table + origin lane + empty state.

## 6. Risks

- **Internal-API breakage across Gradle versions** — the whole premise. Mitigation: isolated module,
  per-version reflection adapter, `instanceof`/`@since` guards, never-throw listener bodies, a
  functionalTest matrix leg per supported Gradle (plan 021), and the Tuist repo as a breakage canary
  (research §2.1). A `>9.x` unknown bucket degrades rather than mis-reads. The core plugin never
  loads any of it, so a break can never take down the always-on path.
- **CC hazards.** The build-operation listener runs at execution time and captures no `Project`;
  the only config-time code is the `whenReady` dependency walk, IP-gated exactly as plan 016's
  `allTasks` walk. Provider-evaluation order for the edge snapshot follows the same load-bearing
  mechanism plan 016 pins with CC store/hit tests. No file access at apply time (architecture §2
  rule 9).
- **Isolated projects.** `getDependencies`/`allTasks` are IP violations by design → gated; worst
  case is null `criticalPathMs`. Cache-key/origin capture is build-op-based and not graph-scoped, so
  it can survive IP — asserted, not assumed.
- **Schema compatibility.** All of this is additive, so the top-level payload `SCHEMA_VERSION` stays
  **1** (no bump for additive fields): `derived` fields already exist (populate only); tier-(b) detail
  rides in the additive `extensions` map carrying its *own* nested `schemaVersion` (distinct from the
  payload version); golden files only added. Old servers `ignoreUnknownKeys`; old plugins simply omit
  the block and the comparator falls back to tier (a).
- **Payload size / cardinality.** Per-file hashes explode on large builds (research §5b) → off by
  default; per-property and per-task caps enforced in code (guardrail), overflow logged.
- **Security / privacy.** Tier-(b) values leaving the machine are salted 16-hex hashes of
  already-opaque Gradle digests — strictly stronger than raw keys; no salt ⇒ block omitted, never
  raw; property names scrubbed; per-file paths off by default and relativized/redacted when on. No
  new tokens, no env dumps, no absolute paths (spec §3.7). Origin-cache-key and origin-build-id are
  opaque ids, re-hashed. Dependency-review note: no new external dependency expected; the module uses
  only Gradle's own API + commons — any addition follows the catalog-lookup rule (CLAUDE.md).
- **Dependency ordering.** Hard dependency on plan 022 (compare endpoint/page to upgrade) and plan
  039 (`extensions: Map<String, JsonElement>` on `BuildPayload` + `BuildHoundCollectorRegistry`). 039
  owns and lands both first: **039-before-038** is a strict merge ordering, so this plan carries no
  registry/extensions stub — it simply consumes what 039 shipped.

## 7. Exit criteria

- `./gradlew build` green including the new module's unit + functionalTest (matrix), commons golden,
  and server comparator tests; configuration cache stays on; core plugin has **zero**
  `org.gradle.*.internal` imports (the module is the only place they appear).
- Applying `dev.buildhound.internal-adapters` on the pilot repo yields a payload whose
  `extensions.internalAdapters` carries per-task cache keys and a `LOCAL_HIT`/`REMOTE_HIT`/`STORED`
  origin, and whose `derived.avoidedMs` and `derived.criticalPathMs` are **non-null** (superseding
  the plan-005 honest nulls).
- Two same-sha builds that differ in one task input, ingested and opened on the comparisons page,
  name the **specific changed input property** and rank it first, and show the origin split
  (roadmap phase-4 exit: "explain a cache miss down to the changed input property").
- On a Gradle version outside every gated range (simulated), and on any adapter exception, the build
  succeeds and the affected fields read "unknown"/null with a single log line — never a failure.
- Without the module applied, core behaves exactly as before (`avoidedMs`/`criticalPathMs` null,
  no `extensions.internalAdapters`), proving the opt-in isolation.
- `docs/architecture.md` decision log carries the sanctioned-exception row; spec §3.1/§4/§6 amended;
  clean-context code and security/privacy reviews completed with findings addressed.
