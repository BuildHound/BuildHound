# 054 — Server-side rule-based recommendations engine

**Status: planned** · 2026-07-08

## Source

Research finding **F4 — Rule-based recommendations engine** (`docs/research/ingest-corpus-analysis.md`),
from the corpus articles: the 880-star `awesome-android-agent-skills` `android-gradle-logic` skill,
"Path to Build Happiness", the Spring-Boot build essay, "10 Techniques", the Gradle-9.6 & Javamaxxing
pieces, and the joint Gradle/Google/JetBrains **Best Practices** guide (conformance rule IDs). Spec §6
(Dashboard/insights) and §5 (tenant-scoped read routes); cross-cutting rec §7.1 — the server-side
rules engine is the center of gravity, F4 built first so the others (F8–F14, F21) have a home and it
feeds the future MCP/agent surface (F21).

## Scope

**In**

- A pure server-side `RecommendationEngine` (the `RegressionEngine`/`BottleneckCalculator` discipline:
  the store fetches, the engine computes) producing a **ranked `Recommendation` list** over
  already-ingested `BuildPayload`s. Five rule families, each self-gating to null when its signal is
  absent/insufficient:
  1. **Hygiene/threshold** — cacheable hit rate < target (`derived.cacheableHitRate`), CC not enabled
     (`environment.configurationCache == DISABLED` share), Kotlin/Gradle **daemon GC pressure**
     (`processes[].gcTimeMs ÷ uptimeS`, plan 029).
  2. **KAPT tax** — `tasks[].type` matching `Kapt` → KAPT share of EXECUTED build time per module +
     projected KSP savings; suppressed when `toolchain.ksp` is already present (plan 046).
  3. **Conformance (BP-*)** — rule IDs aligned to the Best Practices taxonomy; ship only the checks
     computable from shipped fields today (see §3), the rest reserved-dormant.
  4. **Gradle-10 readiness card** — CC-DISABLED share + daemon JDK < 21 (`toolchain.jdk`) + (when
     present) parent-lookup deprecations (`extensions.internalAdapters.deprecations`, plan 047).
  5. **Wasted-work** — over `requestedTasks` + the new `excludedTaskNames`: iterative local builds
     spending large shares on lint/test/packaging; the inverse habitual `-x test`-on-CI smell.
- **One additive collection change:** `BuildPayload.excludedTaskNames: List<String> = emptyList()`
  (public CC-safe `StartParameter.excludedTaskNames`), plugin-wired like `requestedTasks`, new golden.
- Two tenant-scoped read routes (`allowsRead`): `GET /v1/rollups/recommendations?days=…` (fleet) and
  `GET /v1/builds/{buildId}/recommendations` (per-build).
- `Recommendation.origin` (`MEASURED` | `ESTIMATED`) + nullable `projectedSavingsMs` — savings framed
  measured-per-fleet, the JDK-21 floor phrased "expected" (narrowing 5).

**Out (deferred)**

- The `#/recommendations` dashboard page, and family 4's "annotate CC-hit trend on Gradle-version
  upgrades" chart tail — both dashboard concerns; this slice ships the JSON, not the SPA view.
- The MCP tool + `GET /v1/builds/{id}/diagnosis` (F21) — this engine is the *content source*, a
  separate plan is the surface.
- Plaintext parallel/maxWorkers (F1) and build-cache-configured snapshot (F17) collection — separate
  plans; rules needing them ship **dormant** here, never guessing from a salted hash.
- Any edit to existing payload types or golden files; `schemaVersion` stays **1**. No plugin-side
  advice — all interpretation is server-side.

## Design

Modules: **buildhound-server** (new `Recommendations.kt`: types + pure engine; route in `Routes.kt`;
one store data method), **buildhound-commons** (additive field), **buildhound-gradle-plugin** (wire it).

- **Data access (the load-bearing groundedness point).** Families 2/4 need per-build inputs no existing
  `BuildStore` method exposes — `bottlenecks` returns hot-column `TaskRow`/`BuildKpiRow` only,
  `toolchainAdoption` returns aggregated dimension rows, and none carry `processes[]`,
  `configurationCache`, a per-build `jdk∧ksp` join, or `extensions.internalAdapters`. Those live only
  in the build-level jsonb payload. Add **one** abstract read
  `BuildStore.windowPayloads(projectId, days, cap): List<BuildPayload>` (Postgres from the `payload`
  jsonb column, `InMemoryBuildStore` from its map, newest-first, capped) and have the **pure**
  `RecommendationEngine.compute(payloads, settings, now)` run per-build then aggregate. The per-build
  route is then `findById` → the same engine on a one-element list. Both stores feed one pure function
  the identical payload set → byte-for-byte parity (plan 026/032 discipline), verified by
  Testcontainers. Wiring mirrors `RegressionEngine` (store provides data, route feeds the pure engine),
  not a store-internal rollup method.
- **`Recommendation`** (`@Serializable`): `ruleId` (stable — `HYGIENE-CACHE-OFF`, `HYGIENE-GC`,
  `KAPT-TAX`, `BP-…`, `G10-READINESS`, `WASTE-CI-X-TEST`), `severity` (`INFO`/`WARN`/`HIGH`), `title`,
  `advice`, `evidence: Map<String,String>` (observed shares/counts/module), `projectedSavingsMs: Long?`,
  `origin`. `RecommendationsRollup` wraps `List<Recommendation>` + window meta. Each rule is a pure fn
  returning `Recommendation?` — the `RegressionEngine` `INSUFFICIENT_DATA` guard, so a missing signal is
  silence, never a false positive.
- **`excludedTaskNames`** — `BuildHoundSettingsPlugin` reads
  `settings.startParameter.excludedTaskNames` (a `Set`) **sorted** into a new finalizer
  `ListProperty<String>` param (parallel to `requestedTasks`, `BuildHoundSettingsPlugin.kt:290` /
  `TelemetryFinalizerAction.kt:106`); `PayloadAssembler.assemble` maps it to the field. Part of the CC
  key like `requestedTasks`, replayed verbatim on a hit. No `StartMarker` change (interrupted-build
  synthesis doesn't consume it).
- **KAPT rule** groups EXECUTED `tasks[]` whose `type` **contains `Kapt`** (substring, not exact FQCN —
  plan 016 strips `_Decorated` and the class moves across versions), sums `durationMs` per module ÷
  module executed total; suppresses when the build's `toolchain.ksp != null`.
- **Conformance (family 3)** ships two concrete checks from shipped fields — e.g. `BP-CC-ENABLE`
  (config-cache DISABLED across N projects) and `BP-KSP-OVER-KAPT` (reuses family 2's signal) — with
  the remaining BP-* IDs reserved and rendered dormant (`origin` note "signal unavailable"), so the
  family carries real weight instead of a vague "guide says X".
- **Gradle-10 card** composes CC-DISABLED fraction + distinct daemon-JDK majors < 21; the parent-lookup
  term is added only when `extensions.internalAdapters.deprecations` is present, else the card renders
  from CC+JDK with a note (narrowing 3).

Cross-refs: builds on **016** (task-type dictionary), **026/032** (pure-calculator + windowed reads),
**029** (process/GC probe), **046** (toolchain incl. `ksp`), **047** (deprecations via internal-adapters);
relates to **022/025** (existing engines), **035** (blocked CC-miss — *not* a dependency), **038**
(internal-adapters/`CacheOrigin`).

## Test strategy

- **Unit (`RecommendationEngineTest`, pure — `RegressionEngineTest` style):** each family fires on its
  positive fixture and stays silent on absent/insufficient signal; hedged KAPT/JDK savings math;
  KAPT null-`type` degrade (IP); `ksp`-present suppression; Gradle-10 card with and without the
  internal-adapters section; ranking order.
- **Store parity (`RecommendationStoresIntegrationTest`, Testcontainers):** `windowPayloads` +
  `RecommendationEngine.compute` agree byte-for-byte between `InMemoryBuildStore` and
  `PostgresBuildStore` over the same seeded builds; cap + window honored.
- **Route (`RecommendationRoutesTest`):** `allowsRead` required; per-build variant is tenant-scoped
  (foreign/unknown id → 404, never a cross-tenant peek); `days` clamp; **empty list, not 500**, on no
  data.
- **Commons golden:** new `build-payload-v1-excluded-tasks.json` + a `GoldenPayloadTest` assertion;
  existing goldens untouched; `OpenApiContractTest` updated for the two routes.
- **Plugin TestKit (`BuildHoundSettingsPluginFunctionalTest`):** a build invoked with `-x test` carries
  `excludedTaskNames=[":…:test"]` and survives a CC hit.

## Risks

- **Additive-schema.** Only `excludedTaskNames` (empty-default list) is added; new golden file, no
  existing golden edited, `schemaVersion` stays 1. All recommendation output is server-computed → no
  wire-schema surface.
- **CC-safety.** `excludedTaskNames` is a `StartParameter` read at config time into a finalizer param
  (part of the CC key like `requestedTasks`), replayed on a hit — no config-phase file read, no new
  fingerprint input class.
- **Isolated projects.** `tasks[].type` is null under IP (plan 016's empty dictionary) → KAPT/type-keyed
  rules must read a missing type as "no signal", never as "KAPT absent" (narrowing 4); documented and
  tested.
- **Never-fail (server translation).** Every rule returns null on absent/insufficient data
  (`INSUFFICIENT_DATA` discipline) → the engine emits an empty/partial list, never a 500 or a false
  positive; the plugin field degrades to empty. Numbers hedged: `origin=ESTIMATED` for the blog/"likely"
  KAPT-30–50 % and Gradle-10-JDK-21 claims, `MEASURED` only for observed per-fleet shares (narrowing 5).
- **Dormant-rule honesty.** Rules needing F1 (parallel/maxWorkers plaintext) or F17
  (build-cache-configured) self-gate to "signal unavailable" — no inferring "parallel off" from a
  salted hash (F1 narrowing 2), no absolute advice from differential fingerprints.
- **Retention window.** `windowPayloads` is bounded by *build* retention; plan-042 purges raw task rows
  earlier, so the task-typed KAPT signal degrades to null past the raw window — expected, not a bug.
  A build cap bounds memory (the analog of the `days` clamp).
- **GC proxy.** `gcTimeMs` is jstat GCT — cumulative since daemon start — so `gcTimeMs÷uptimeS` is a
  coarse ratio; a finer per-PID delta is F15's scope, not this plan's. Documented in the rule copy.
- **Multi-tenancy.** Both new routes are token + tenant-scoped (`allowsRead`); the per-build variant
  404s foreign/unknown ids (mirrors `/builds/{id}/verdict`).
- **Privacy (§3.7).** Recommendations carry only project-internal Gradle names (module/task paths,
  type FQCNs), version strings, and derived shares/counts — no absolute paths, env dumps, PII, or
  secrets; BP-* / advice strings are static copy. No new collection beyond the CC-safe
  `excludedTaskNames` (task-name shape, already shipped for `requestedTasks`).

## Exit criteria

- `GET /v1/rollups/recommendations` and `GET /v1/builds/{id}/recommendations` return ranked,
  tenant-scoped recommendations; empty (not error) on no data.
- All five families implemented with the narrowed degradations; KAPT/Gradle-10 numbers carry `origin`
  and hedged copy; family 3 ships ≥2 concrete BP-* checks with the rest reserved-dormant.
- `excludedTaskNames` collected CC-safely; new golden added, existing goldens untouched, `schemaVersion`
  1; TestKit `-x test` + CC-hit green.
- In-memory/Postgres parity (Testcontainers) + route/unit tests green; OpenAPI contract updated.
- Both §3 reviews (`kotlin-gradle-reviewer` + the mandatory §3.2 security & privacy review) pass or
  findings are addressed.
- `./gradlew build` green.
