# 052 — Per-project configuration-time attribution (public-API tier)

**Status: implemented.** Landed in `d0c1218` (plugin+commons: per-project configuration-time
attribution) with review fixes in `0dc23be` (collision-free sidecar names + unconditional timing
clear); the Design/Risks sections below already reflect the as-shipped, post-review-fix behavior.

## Source

Research finding **F2** (`docs/research/ingest-corpus-analysis.md`), sourced from the Android
*Optimize/Profile your build* docs, Gradle *Best Practices for Performance & Tasks*, the "80%
build-time reduction" case study, and the `awesome-android-agent-skills` `android-gradle-logic`
skill. Related spec/roadmap: §3.4 (DSL/collection), §4 (payload), plan-016 configuration-duration
scalar (`derived.configurationMs`). This slice implements F2's **CORE, public-API tier** only:
per-project *evaluation* timing via `gradle.lifecycle.beforeProject`/`afterProject`. It also fixes
the stale note the finding calls out (`build-telemetry-research.md:95`).

## Scope

**In**

- Time each project's evaluation on **CC-miss / DISABLED** builds via the isolated-projects-safe
  `gradle.lifecycle.beforeProject`/`afterProject` hooks the plugin already uses (plan 031), correlate
  the two callbacks through project-scoped `extraProperties`, and persist per-project durations to a
  `.gradle/buildhound/` sidecar the Flow finalizer reads (the plan-031/044 sidecar pattern).
- Additive nullable payload block `projectEvaluations: List<ProjectEvaluation>?` (path + `evaluationMs`),
  ranked slowest-first, **absent (null) on a CC hit** and when nothing was captured. New golden file;
  additive `CapsSummary.droppedProjectEvaluations`. `schemaVersion` stays **1**.
- **Works under isolated projects** — the headline of F2: this block is populated under IP where the
  plan-016 `whenReady` task dictionary is intentionally empty.
- Correct `build-telemetry-research.md:95` (its "per-project config cost is internal-ops-only — defer"
  note is stale: the public `GradleLifecycle` API supersedes it) in the same PR.

**Out (named follow-ups — not dropped, sequenced)**

- **internal-adapters tier-2 ops** (per-plugin apply time, eagerly-realized-but-never-executed task
  counts, configurations-resolved-during-configuration count) → a follow-up on the plan-038 module.
  Narrowing 5: `tasks.configureEach` (eager-realization) and `ResolvableDependencies.afterResolve`
  (resolved-during-config) have approximate **public-API** paths and could migrate to core later.
- **Server "configuration hotspots" Bottlenecks family** (top-N slowest-configuring projects, trend
  across CC-miss builds; a 5th family beyond plan-032's four) → a follow-up server plan (new migration +
  `Bottlenecks.kt` rollup + dashboard). The block rides in the stored payload `jsonb`, so that family
  consumes it later with **zero re-collection**.
- No decomposition of `derived.configurationMs`; no HTML-report markup change (the data lands in
  `build-payload.json`, which the artifact already embeds — spec §3.8).

## Design

- **Collector (`buildhound-gradle-plugin`).** A new top-level `installProjectEvaluationCollector(gradle,
  timingsDir: File)` — deliberately top-level like `installAndroidArtifactCollector`, so the two
  `IsolatedAction`s capture only the serializable `timingsDir`, **never the plugin instance** (narrowing 2).
  `beforeProject { project -> project.extensions.extraProperties.set(START_KEY, System.nanoTime()) }`;
  `afterProject { project -> ... }` reads that start, computes `elapsedMs`, and overwrites this project's
  `<sanitized-path>.jsonl` line. Both bodies `runCatching`-guarded, logging via `project.logger` (never
  the plugin companion logger). **Not IP-gated** — no `if (isolatedProjects)` guard. Wired from `apply()`
  under the existing `if (masterEnabled)` block, next to `installAndroidArtifactCollector`, with the dir
  at `File(settings.rootDir, ".gradle/buildhound/config-timings")` (under `.gradle`, not `build/`, so a
  same-invocation `clean` can't wipe it — the plan-044 rationale).
- **Sidecar IO** — a new `ProjectEvalRecordIo`/sidecar object mirroring `ArtifactRecordIo` +
  `TestLocationSidecar`: one JSON object per file (`{path, evaluationMs}`), Gradle-free/unit-testable,
  defensive parse (malformed line skipped, never fatal). Config-time write is a **side effect, never a CC
  input** (the TestLocationSidecar contract). One file per project ⇒ no write contention under parallel/IP.
- **Finalizer (`TelemetryFinalizerAction`).** **Read-then-clear the dir unconditionally at the top of
  `execute()`** — before the `enabled`/`mode` short-circuits and regardless of `ccState` (052 review
  fix; the plan originally cleared only on enabled non-HIT builds, which let a DSL-disabled build's
  files leak into a later enabled build — see the DSL-only-disable risk below). Clearing and
  *reporting* are separate decisions: `val projectEvaluations = if (ccState == HIT) null else drained`,
  mirroring the existing `configurationMs` HIT branch. All `listFiles`/`delete` stay at execution time,
  never at config time. Thread into `PayloadAssembler.assemble(..., projectEvaluations = ...)`;
  assembler emits the block only `takeIf { it.isNotEmpty() }`, sorts slowest-first, and caps to top-N
  via `PayloadCapper` (recording `droppedProjectEvaluations`).
- **Schema (`buildhound-commons`).** `data class ProjectEvaluation(val path: String, val evaluationMs:
  Long)`; `BuildPayload.projectEvaluations: List<ProjectEvaluation>? = null`; additive
  `CapsSummary.droppedProjectEvaluations: Int = 0`. New golden `build-payload-v1-project-evaluations.json`.
- Correct `build-telemetry-research.md:95`.

## Test strategy

- **Unit** (`buildhound-gradle-plugin`, off the Gradle classpath): sidecar encode/parse round-trip,
  malformed-line skip, `readAll` over a dir, `clear`.
- **Golden** (`buildhound-commons/jvmTest`): the new golden deserializes with a populated ranked list;
  the pre-existing `build-payload-v1.json` (authored before the field) still deserializes (additive proof).
- **Functional / TestKit** — the arbiter of the CC story (this writes a file *directly from an
  `IsolatedAction`*, which no existing collector does):
  1. multi-project build → `projectEvaluations` present, per-project, sorted slowest-first;
  2. **CC store-then-hit**: assert the CC entry is **reused** (not invalidated), block present on the
     MISS build, **null on the HIT** build;
  3. run under `-Dorg.gradle.unsafe.isolated-projects=true` → block still populated (contrast the empty
     `whenReady` dictionary);
  4. narrow invocation after a broad one → no stale project leaks (proves finalizer read-then-clear);
  5. master-switch off (`enabled=false`) touches nothing under `.gradle/buildhound/config-timings`;
  6. *(052 review fix)* DSL-disabled broad build then enabled narrow build → the narrow payload never
     contains the project only the disabled build configured (proves the unconditional drain). Unit
     tests additionally pin the collision-free file-name encoding (`:a:b` vs `:a-b` both survive).

## Risks

- **CC input hazard (named).** All directory *reads* (`listFiles`) and the clear run in the FlowAction
  (execution time). An `apply()`/config-time clear would register the dir as a CC fingerprint input and
  force a permanent MISS. Config time does only `mkdirs`+`writeText` (a side effect, never an input — the
  TestLocationSidecar contract); pinned by TestKit test 2.
- **Stale-file correctness (named, not hygiene).** A narrower invocation configures fewer projects, so a
  prior build's per-project file for a now-unconfigured project would otherwise leak. Mitigation:
  finalizer **read-then-clear on every finalizer pass** — enabled or not, HIT or not (052 review fix) —
  + per-project overwrite on MISS + the HIT-guard (drained but reported null on a hit). Residual: a
  build whose finalizer never ran (interrupted) leaks its files into exactly the next MISS build, whose
  payload **misattributes** the interrupted build's timings for projects it did not itself reconfigure;
  that build's own finalizer then clears the dir, bounding the damage to one payload — accepted for
  best-effort telemetry (an unconditional clear cannot help when no finalizer runs at all).
- **DSL-only `enabled=false`/`mode=DISABLED` does not stop the collector (named, implementation finding).**
  `masterEnabled` — the env/property override resolved at `apply()` time — is the *only* thing that can
  gate `installProjectEvaluationCollector`'s registration (mirroring `installAndroidArtifactCollector`);
  a settings-script `buildhound { enabled = false }` DSL value is configured *after* `apply()` returns (the
  `plugins {}` block always applies before the rest of the script body runs), and a `beforeProject`/
  `afterProject` `IsolatedAction` cannot capture the extension to re-check it later (architecture §7's
  2026-07-03 decision-log row: "the isolated-projects-safe `GradleLifecycle.beforeProject` hook cannot
  isolate an action holding a service/extension reference"). So a build with the master switch ON (the
  common case) but DSL `enabled = false`/`mode = DISABLED` still writes per-project timing files under
  `.gradle/buildhound/config-timings/`. Contrast `TestLocationSidecar`, whose write sits inside
  `taskGraph.whenReady` (a plain, non-isolated closure that runs after the DSL configures) and so *can*
  honor a DSL-only disable. Two existing `BuildHoundSettingsPluginFunctionalTest` cases (`mode disabled
  writes no payload`, `enabled false disables collection and salt creation`) asserted the *whole*
  `.gradle/buildhound` dir was untouched in this scenario; that passed only because
  `AndroidArtifactCollector` happens to no-op without AGP applied. Both were narrowed to assert the
  identity-salt file specifically (their actual intent) with a comment explaining the gap — not silently
  loosened. **Consequence (as originally shipped): cross-build misattribution, not inert leftovers.**
  The finalizer's `enabled`/`mode` early-return also skipped read-then-clear, so a DSL-disabled build's
  timing files survived it and the *next* enabled non-HIT build read them into its own payload —
  attributing another invocation's evaluation times to itself (this plan's first version mischaracterized
  the files as "sitting inert until read-then-clear self-heals"; they were read, and that read *was* the
  misattribution). Fixed by the unconditional drain at the top of `execute()` (see Design): every
  finalizer pass clears the sidecar even when disabled or on a CC hit, and only the reporting decision
  remains conditional. Pinned by `ProjectEvaluationFunctionalTest`'s DSL-disabled-broad →
  enabled-narrow case (the payload must not contain the never-reconfigured project).
- **Not a `configurationMs` decomposition (narrowing 1).** `beforeProject`/`afterProject` time project
  evaluation only (script + plugin apply + `afterEvaluate`); settings/init, buildSrc/included builds,
  task-graph population, and CC-store fall outside, and under parallel/IP per-project times overlap
  wall-clock. Labeled "project evaluation time (top-N)"; `derived.configurationMs` is untouched (still 0
  on HIT per existing code — our new block is instead null, a deliberate distinct choice, narrowing 3).
- **Isolated projects.** Deliberately **not** gated — `beforeProject`/`afterProject` are the IP-safe
  hooks; test 3 pins that the block survives IP.
- **Additive schema.** Nullable field + new golden + additive `CapsSummary` field; existing golden files
  and payload types are never edited; `schemaVersion` stays 1.
- **Privacy (§3.7).** Only Gradle project **paths** (`:app`, `:core:common`) and a duration scalar — the
  same bar as `TaskExecution.module`/`ArtifactSize.module`; no absolute `projectDir`, no PII, nothing for
  the scrubber to relativize.
- **Never-fail.** Every callback + sidecar IO + finalizer read/clear is `runCatching`-guarded and degrades
  to no block (`project.logger.info`), inside the finalizer's outer guard.
- **Multi-tenancy.** N/A this slice — no new read route. The block rides in the stored payload `jsonb`;
  the deferred server family reads it token + tenant-scoped like every other route.

## Exit criteria

- A multi-project CC-miss/DISABLED build emits `projectEvaluations` ranked slowest-first; the block is
  **null on a CC hit** and **populated under isolated projects**; the CC entry is reused across a
  store→hit cycle — all pinned by TestKit tests.
- New golden deserializes; `build-payload-v1.json` and every other existing golden still deserialize
  unedited; `schemaVersion` == 1; `CapsSummary.droppedProjectEvaluations` bounds a monorepo.
- `build-telemetry-research.md:95` corrected; the master switch leaves the sidecar dir untouched when off.
- `./gradlew build` green.
