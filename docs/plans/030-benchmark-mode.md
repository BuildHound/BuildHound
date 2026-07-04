# Plan 030 — Benchmark mode: gradle-profiler pipeline + cache-isolation experiment recipes

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- Roadmap [phase 3, "Benchmark mode" bullet](../build-telemetry-roadmap.md): gradle-profiler
  scheduled pipeline + Telltale's cache-isolation/tag-correlated methodology + the
  build-validation-scripts experiment recipes, feeding the comparison engine. Exit: "nightly
  benchmark series on the pilot".
- Spec: [§7](../build-telemetry-spec.md) (scheduled gradle-profiler pipeline; scenarios
  clean/no-op/incremental-non-ABI/config-cache-hit; `mode=benchmark, scenario, iteration` tags),
  [§2](../build-telemetry-spec.md) (side channel "gradle-profiler pipeline → tagged builds"),
  [§4](../build-telemetry-spec.md) (`mode`/`tags`), [§6](../build-telemetry-spec.md) (Trends/Comparisons).
- Research: [Telltale.md](../research/repos/Telltale.md) (twelve isolation modes via
  `GRADLE_HOME_CACHE_EXCLUDES`, seed-then-measure, tag correlation, profiler scenario generation,
  percentiles over noisy runs); [Bagan.md](../research/repos/Bagan.md) (N-iteration percentile
  comparison; experiment tagging validates the mode/scenario/iteration model; negative lessons on
  hardcoded wiring and duration-only analysis);
  [cache-miss-input-fingerprints.md §3](../research/cache-miss-input-fingerprints.md) (the three
  experiment pairs feeding the compare endpoint);
  [plugin-ecosystem-gap-analysis.md §4.4](../research/plugin-ecosystem-gap-analysis.md)
  (`BUILDHOUND_*` env-override pattern).

## 2. Scope

**In:** (a) additive `BenchmarkInfo?` block on the payload + golden file; (b) plugin
`BUILDHOUND_BENCHMARK_*` env activation → `mode=benchmark` + block + mirrored tags, CC-safe;
(c) server exclusion of benchmark builds from fleet trends/list by default + a
`GET /v1/benchmark/series` grouped by (scenario, isolationMode) with percentiles;
(d) dashboard `#/benchmark` series view; (e) a scheduled gradle-profiler pipeline in
`buildhound-ci-assets` with the isolation-mode cache-exclude table; (f) `profiler-scenarios/` files
+ a recipe doc naming the three experiment pairs.

**Out (and where it lives):** the compare endpoint/comparisons page + input fingerprints — **plan
022** (this plan produces the tagged *pairs* it consumes) · Kubernetes/cartesian-matrix
orchestration (Bagan's model) — deliberately not adopted; gradle-profiler same-machine scenarios
are the spec's choice · regression baselines/verdicts over the series — **plan 025** · the
with-plugin-vs-without self-benchmark harness — **plan 034** (reuses these scenario files) ·
isolated-projects/macOS/Windows/CC-off matrix axes — **plan 021** (this plan adds one *scheduled*
workflow, not PR-CI axes) · cache-origin/`avoidedMs`/real per-property causes — **plan 038**.

## 3. Design

**Two gaps this plan closes.** `BuildMode.BENCHMARK` already exists (`BuildPayload.kt:39-44`) and
`UploadGate.kt:32` already uploads benchmark builds like CI — but the plugin can never *emit* it:
`TelemetryMode` has only `AUTO/CI/LOCAL/DISABLED` (`BuildHoundExtension.kt:71`) and
`PayloadAssembler.resolveMode` (`PayloadAssembler.kt:22-27`) never returns `BENCHMARK`. And the
server includes all modes in fleet views: `BuildFilter.mode` defaults null, so
`InMemoryBuildStore.matching` / `PostgresBuildStore.trends` (`BuildStore.kt:94-100`,
`PostgresStores.kt:136-175`) would let a benchmark series pollute p50/p95 trends.

**Schema (commons).** Add, additively, next to `derived` on `BuildPayload` (`BuildPayload.kt:12-30`):

```kotlin
@Serializable
data class BenchmarkInfo(
    val scenario: String,               // "clean" | "no_op" | "incremental_non_abi" | "cc_hit"
    val iteration: Int? = null,         // profiler measurement index
    val isolationMode: String? = null,  // Telltale cache-mode label, e.g. "no_build_cache"
    val seedRef: String? = null,        // correlates measure builds to their seed run
)
```

plus `val benchmark: BenchmarkInfo? = null` on `BuildPayload`. Defaults keep old payloads/golden
files valid; `ignoreUnknownKeys` (`BuildHoundJson.kt`) means old servers tolerate it and old
plugins omit it. New golden `build-payload-v1-benchmark.json`; `build-payload-v1.json` untouched
(additive-only). A typed block (not just tags) because the server needs robust grouping/percentiles
per (scenario, isolationMode) and `tags` cardinality caps (plan 019) could clip free-form keys — the
spec's `scenario`/`iteration` *tags* are still populated (below), so the tag contract holds.

**Plugin — env activation, CC-safe.** The profiler runs the pilot's *real* build once per
scenario×iteration and must not edit the pilot's `buildhound {}` DSL per invocation. The pipeline
exports `BUILDHOUND_BENCHMARK_{SCENARIO,ITERATION,ISOLATION,SEED_REF}`; a new `BenchmarkValueSource`
(same execution-time pattern as `CiValueSource`, wired in `BuildHoundSettingsPlugin.apply` beside it,
`BuildHoundSettingsPlugin.kt:71-73`) reads `System.getenv()` in `obtain()` and returns
`Serializable CollectedBenchmark?` (null when unset). Env is read in the ValueSource, never at apply
time — nothing becomes a CC fingerprint input (architecture §2 rule 9), same discipline as
`CiValueSource`. Reuses CCUD's `BUILDHOUND_*` override idea
([gap-analysis §4.4](../research/plugin-ecosystem-gap-analysis.md); dovetails with plan 027).
`scenario`/`isolationMode` are validated against a fixed allowlist in the source so a typo cannot
mint a new series (cardinality-in-code guardrail).

**Plugin — mode/assembly.** `resolveMode` (`PayloadAssembler.kt:22-27`): a present
`CollectedBenchmark` forces `BENCHMARK` (over AUTO/CI/LOCAL; `DISABLED` still → null). `assemble`
(`PayloadAssembler.kt:29-76`) sets `payload.benchmark` and merges `scenario`/`iteration`/
`isolationMode` into `tags` (user tags win on clash, mirroring `ciInfo`'s merge,
`PayloadAssembler.kt:94-100`). `TelemetryFinalizerAction.Parameters` gains
`benchmark: Property<CollectedBenchmark>` (`@Optional`), wired from the value source and threaded
through `execute` (`TelemetryFinalizerAction.kt:105-125`). Strings pass the existing scrubber at
assembly (`PayloadAssembler.kt:75`).

**Server — exclude from fleet views.** Add `excludeModes: Set<String> = emptySet()` to `BuildFilter`
(`BuildStore.kt:14-18`). Query routes (`Routes.kt:107-135`, helper `buildFilterOrNull`
`Routes.kt:178-185`) default trends + `/v1/builds` to `excludeModes={"BENCHMARK"}` unless the caller
passes `mode=benchmark` or `includeBenchmark=true`. In-memory adds
`.filter { it.mode.name !in filter.excludeModes }` (`BuildStore.kt:94-100`); Postgres adds a
parameterised exclusion in `filterSql` (`PostgresStores.kt:91-99`) — bound param, never
interpolated. This is the "excluded from fleet trends" half of the deliverable, enforced in code.

**Server — series endpoint.** New `GET /v1/benchmark/series` in `queryRoutes`, read-scope-authed via
`authenticatedProject` (`Routes.kt:157-176`), params `scenario?/isolationMode?/branch?/days`
(coerced like trends). Returns per (scenario, isolationMode) group: an ordered point list
`{startedAt, buildId, iteration, durationMs, hitRate}` + summary `{p50, p90, min, count}`. New
`BuildStore.benchmarkSeries(...)`: in-memory groups matching `mode=BENCHMARK` payloads by
`benchmark.scenario`/`isolationMode`; Postgres reads keys from the jsonb `payload`
(`payload->'benchmark'->>'scenario'`) — no migration, the full document already rides in
`builds.payload` (`V1__core.sql:43`). Percentile math is a pure `BenchmarkSeriesCalculator` in
commons (plain-unit-testable, like `DerivedMetricsCalculator`) so plugin/server/UI can't disagree.

**Dashboard.** Extend `dashboard.js` routing (`dashboard.js:257-271`): `#/benchmark` fetches the
series, renders one section per scenario (percentile chip row + `durationMs` line chart reusing
`trendChart`, `dashboard.js:182-204`) with an isolation-mode selector and an empty state ("no
benchmark builds yet — see the profiler recipe"). All payload text via `textContent`, classes stay
allowlisted (`dashboard.js:21-32`); nav link in `index.html`; CSP style hash recomputes from bytes
(`DashboardRoutes.kt:29-40`).

**CI + recipes.** New `buildhound-ci-assets/profiler-pipeline/` with a scheduled Azure YAML and a
scheduled GitHub Actions workflow (both JVM-free per §7/architecture §1): install gradle-profiler
(version looked up at implementation time — sdkman or pinned release, per Telltale's `sdkman-action`
reference), run the pilot per scenario with `BUILDHOUND_BENCHMARK_*` set per iteration; the plugin
uploads each iteration as `mode=benchmark`. The twelve Telltale `GRADLE_HOME_CACHE_EXCLUDES` modes
ship as a documented table; the seed-then-measure protocol is documented; v1 wires two isolation
modes (full-cache, no-build-cache) and leaves the rest as recipe opt-ins. `BUILDHOUND_TOKEN` is
mapped from a secret exactly as `buildhound-gradle-steps.yml:24-27` (no secrets in the template; no
OpenAI/BigQuery side channel — explicit negative lesson from Telltale/Bagan).
`docs/recipes/benchmark-and-experiments.md`: the four scenarios, reading a low-noise series, and the
three build-validation pairs ([fingerprints §3](../research/cache-miss-input-fingerprints.md)) —
same-sha CI↔CI, CI↔local, two-checkout relocatability — each "run the pair, tag both
`buildhound.tags.put("experiment","exp05")`, open them on the comparisons page (plan 022)". The
handoff to the comparison engine is documented, not code-coupled.

## 4. Implementation steps

1. **commons/schema:** add `BenchmarkInfo` + `BuildPayload.benchmark` (`BuildPayload.kt`); add
   golden `build-payload-v1-benchmark.json` + `GoldenPayloadTest` cases (deserialize, round-trip,
   absent-default); leave `build-payload-v1.json` untouched.
2. **commons/percentiles:** add `BenchmarkSeriesCalculator` (p50/p90/min/count; empty + single-element
   guards) + unit tests.
3. **plugin/env:** add `CollectedBenchmark` (Serializable) + `BenchmarkValueSource` (env in
   `obtain()`, scenario allowlist, null when unset). No new DSL block required; a `benchmark {}`
   escape hatch is additive and optional if review wants it.
4. **plugin/wiring:** register the value source in `BuildHoundSettingsPlugin.apply`
   (`:71-73` area), add `benchmark: Property<CollectedBenchmark>` (`@Optional`) to
   `TelemetryFinalizerAction.Parameters`, pass it through.
5. **plugin/assembly:** extend `resolveMode` (benchmark overrides AUTO/CI/LOCAL; DISABLED→null) and
   `assemble` (set block, merge tags, user wins); thread through `execute`.
6. **plugin/tests:** `PayloadAssemblerTest` (env→benchmark, block populated, tags merged/user-wins,
   DISABLED short-circuits, absent env unchanged); functionalTest (`withEnvironment`
   `BUILDHOUND_BENCHMARK_SCENARIO=clean` → `mode=benchmark`); **failure injection** (bogus scenario
   or non-numeric iteration → `warn`, build succeeds, mode falls back).
7. **server/filter:** add `excludeModes` to `BuildFilter`; implement in both stores (bound param);
   default trends + `/v1/builds` to exclude `BENCHMARK` unless opted in (`buildFilterOrNull`).
8. **server/series:** add `BuildStore.benchmarkSeries` (in-memory + Postgres jsonb), DTOs, and
   `GET /v1/benchmark/series` in `queryRoutes` with the standard auth + storage-outage 503 helper
   (`runQuery`, `Routes.kt:140-151`).
9. **server/tests:** `PostgresStoresIntegrationTest` (benchmark excluded from trends/list by default,
   included with flag, grouped by scenario); `ApplicationTest` (series 401/403/happy-path; trends
   excludes benchmark; percentiles match commons).
10. **dashboard:** `#/benchmark` view + isolation selector + empty state (`dashboard.js`); nav link
    in `index.html`; extend the node smoke harness (`DashboardScriptTest`).
11. **ci-assets:** add `profiler-pipeline/{azure-nightly-benchmark.yml,github-nightly-benchmark.yml}`
    (scheduled), the `GRADLE_HOME_CACHE_EXCLUDES` isolation-mode table, and
    `profiler-scenarios/*.scenarios` (clean, no-op, incremental-non-ABI, cc-hit); update
    `buildhound-ci-assets/README.md` (the `profiler-scenarios/` row currently says "arrives phase 3").
12. **docs:** add `docs/recipes/benchmark-and-experiments.md`; amend spec §4 (`benchmark` block; tags
    mirror it), §7 (concrete scenario/isolation set + fleet-exclusion behaviour), §6 (series view);
    note the roadmap phase-3 bullet landed; add an `architecture.md` §7 decision-log row (benchmark
    excluded from fleet trends at the query layer; env-driven activation keeps the pilot DSL
    invocation-independent) in the same PR (guardrail).

## 5. Test strategy

- **Golden/contract (commons):** new `build-payload-v1-benchmark.json` deserializes with a populated
  `benchmark`; the untouched v1 file still parses (`benchmark == null`); round-trip stable.
- **Commons unit:** `BenchmarkSeriesCalculatorTest` — p50/p90/min over odd/even counts, single
  element, empty (null), unsorted input.
- **Plugin unit:** `PayloadAssemblerTest` (step 6) + `BenchmarkValueSource` allowlist test
  (unknown scenario → omit).
- **Plugin functionalTest (TestKit):** env run → `mode=benchmark` payload with block + tags;
  **failure injection** — malformed iteration degrades to `warn`, build succeeds, mode falls back.
- **Server (`testApplication` + Testcontainers):** default fleet trends/list exclude benchmark;
  explicit `mode=benchmark` includes them; `/v1/benchmark/series` groups by scenario, percentiles
  equal the commons calculator, read-scope-gated + tenant-scoped, storage outage → 503.
- **Dashboard:** node smoke harness covers `#/benchmark` render + empty state.
- **CI-asset lint:** scheduled YAML parses; scenario files are valid gradle-profiler scenarios
  (spot-checked, exercised for real by the nightly job, not unit CI).

## 6. Risks

- **Fleet-trend pollution (correctness).** An ingested benchmark build double-counts in unfiltered
  trends. Mitigation: default exclusion in both stores, asserted by integration tests — the reason
  the server change ships with the producing pipeline, not after it.
- **CC / isolated projects.** Activation is a ValueSource reading env at execution time — no new CC
  input (architecture §2 rule 9), no task-graph mutation, IP-neutral; the IP CI job (plan 021)
  still covers it. Profiler clean/cc-hit scenarios exercise CC store/reuse, already reported.
- **Schema compatibility.** Additive with defaults; only new golden files. Old servers ignore
  `benchmark`; old plugins send none and the series endpoint returns empty groups, not an error.
- **Cardinality/size.** Four short fields on benchmark builds only; scenario/isolation are
  allowlist-validated in the value source so the series count is bounded; the tags mirror is capped
  by plan 019.
- **Security/privacy.** No new PII — scenario/iteration/isolation are synthetic labels; strings pass
  the scrubber; the pipeline maps `BUILDHOUND_TOKEN` from a secret and never echoes it; no
  OpenAI/BigQuery side channel adopted; profiler/action versions pinned after an implementation-time
  lookup (Telltale's checksum-less `curl` fetch is explicitly not copied).
- **Statistical honesty.** Same-machine runs carry noise; the view/recipe present only percentiles
  over N iterations, never single runs (Bagan/Telltale), and label the isolation mode so cache-off
  is never compared against cache-on.
- **Scope creep.** Bagan's K8s cartesian matrix is not adopted; the plan stays within
  gradle-profiler same-machine scenarios per the spec.

## 6a. Review-driven changes (both clean-context reviews)

- *`benchmark` block now routes through scrubber + capper.* Both reviews found the block bypassed
  `PayloadScrubber` and `PayloadCapper` — `seedRef` is free-text env (`BUILDHOUND_BENCHMARK_SEED_REF`),
  unlike the allowlisted `scenario`/`isolationMode`. Fix: `scrub()` now scrubs `scenario`/
  `isolationMode`/`seedRef`, and `cap()` truncates an over-long `seedRef` to `maxValueChars` (a silent
  truncation, no `CapsSummary` count) — so the plan's "strings pass the scrubber" claim is now true.
- *Pipeline isolation mechanism corrected.* gradle-profiler has no global gradle-args CLI flag
  (`gradle-args` is a scenario-file property); the draft's `--gradle-arg` would have been ignored, so
  `no_build_cache` never disabled the cache. Fixed: the pipelines toggle the build cache via an
  `org.gradle.caching=false` `gradle.properties` override on the pilot (reset to pristine per
  isolation), matching `isolation-modes.md`.
- *Accepted residual:* the gradle-profiler download is pinned (`0.24.0`) + https but has no checksum
  (gradle-profiler publishes none). This matches the plan §6 bar (pinned + https, no unchecked fetch);
  a checksum is a follow-up if upstream starts publishing one.

## 7. Exit criteria

- `./gradlew build` green: new commons golden/unit, plugin unit + functionalTest, server unit +
  Testcontainers tests.
- A pilot build with `BUILDHOUND_BENCHMARK_SCENARIO=clean` (+ iteration/isolation) ingests as
  `mode=benchmark` with a populated block and mirrored `scenario`/`iteration` tags.
- Default `/v1/trends` and `/v1/builds` exclude benchmark builds; `mode=benchmark` shows them
  (verified by Testcontainers).
- `GET /v1/benchmark/series` returns per-scenario percentile series; `#/benchmark` renders them with
  an isolation selector and an empty state.
- The scheduled profiler pipeline + `profiler-scenarios/*.scenarios` exist in `buildhound-ci-assets`;
  the recipe doc names the three experiment pairs and the tags the compare endpoint (plan 022) keys on.
- Running the nightly pipeline against the pilot produces a per-scenario benchmark series on the
  dashboard (roadmap phase-3 exit: "nightly benchmark series on the pilot").
- Spec §4/§6/§7 amended; `architecture.md` decision-log row added; clean-context code and
  security/privacy reviews completed with findings addressed.
