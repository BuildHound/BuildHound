# 069 — Build-structure inventory (declared project tree) + `isolatedProjects` flag

## Source

Research finding **F19** ("Build-structure inventory + modularization / IDE-sync ROI"),
`docs/research/ingest-corpus-analysis.md`, distilled from the corpus items on eBay's
ProjectCostSummarizer / modularization ROI and the Pocket Casts *modularization-vs-IDE-sync*
trade-off (2–3.6× faster builds but 5× slower sync). Payload schema is spec §4; privacy bar
spec §3.7; IDE-sync telemetry is a **locked v1 non-goal** (spec §1 / `spec:13`). Roadmap Phase 4
(differentiators).

This plan ships the **collection half only** — the declared build-structure tree plus the
already-computed-but-never-shipped `isolatedProjects` flag. Collection is temporally upstream of
analysis: the module-count trend and modularization rules render nothing until this data has
ridden across many builds, so they ship in a follow-up that consumes it — the same
collection-before-analysis ordering as fingerprints (plan [022](implemented/022-input-fingerprints-compare.md))
→ the comparison endpoint.

## Scope

**In**

- A new nullable `BuildStructureInfo` payload block: `projectCount`, `maxDepth`,
  `includedBuildCount`, `buildSrcPresent`, `sourcesInRoot`, and `emptyIntermediateCandidates` —
  Gradle paths of aggregator-shaped projects, a **ranked heuristic candidate list, not a verdict**.
- The additive `EnvironmentInfo.isolatedProjects` flag. The plugin already computes
  `buildFeatures.isolatedProjects.active` at `whenReady` (`BuildHoundSettingsPlugin.kt:123`) but
  drops it; shipping it now starts the series accruing for the future before/after-IP cut.
- Config-time descriptor-tree capture (`projectsLoaded` — `settingsEvaluated` originally, revised
  during implementation, see Design) + an execution-time `BuildStructureValueSource` for the
  filesystem probes. New golden `build-payload-v1-build-structure.json`.

**Out / deferred to a foregrounded follow-up (next free number)**

- Server persistence (additive migration + hot column), `GET /v1/rollups/modularization`
  (single-module-idle-cores rule × `derived.parallelUtilization`; ghost-project = declared every
  build yet zero `task_executions` rows over the window; the empty-intermediate candidates), and the
  **sync-health page** (projectCount-over-time vs build p50 vs sync p50, segmenting existing build
  durations by `environment.ideSync`). The sync-health page **reverses the spec:13 non-goal** — a
  spec-owner decision that belongs in its own plan (carrying the spec §1 + `docs/architecture.md`
  decision-log edits), coupled with the modularization rule it makes honest, never the rollup without
  the page.
- The IP before/after benefit *comparison* view. Only the enabling `isolatedProjects` flag ships here.
- Plugin-side "zero tasks in N days" sharpening of empty-project detection — that judgment is
  longitudinal (server-side, cross-build) and cannot touch the task graph under IP anyway.

## Design

Modules: `buildhound-gradle-plugin` (collection), `buildhound-commons` (schema + golden). No server change.

- **Config-time capture — IP-safe, zero file reads.** In `BuildHoundSettingsPlugin.apply`, register
  `settings.gradle.projectsLoaded { }` **(implementation divergence: not `settingsEvaluated` as
  originally planned — see note below)**: the descriptor tree only populates after the settings
  script's `include(...)` calls run, so `apply()` is too early (F19 narrowing). Walk
  `settings.rootProject` — a `ProjectDescriptor`, **not** a `Project` — into an `AtomicReference`
  mailbox (the `toolchainHolder`/`taskMetadataHolder` pattern, plans
  [046](implemented/046-toolchain-agp-kgp-ksp-collection.md)/[016](implemented/016-task-type-cacheable-capture.md)):
  `projectCount`, `maxDepth` (path-segment count), `settings.gradle.includedBuilds.size`, and a
  serializable `path → (buildFilePath, hasChildren)` map. Descriptors are settings-level metadata, so
  the walk is **not** a cross-project access — unlike the plan-016 task dictionary it stays populated
  under isolated projects, which is exactly what makes the future before/after-IP cut possible.
  `ProjectDescriptor.buildFile` returns a path only; **no `.exists()` here** (a config-phase file read
  becomes a CC fingerprint input). Whole walk in `runCatching` → warn + empty on failure.

  **Divergence from the committed plan:** `settingsEvaluated` was the intended hook, but
  `Gradle.includedBuilds` throws `"Included builds are not yet available for this build"` when read
  from inside it (verified empirically against the project's own Gradle distribution — `include(...)`
  registrations exist by `settingsEvaluated`, but a composite isn't wired up until the next lifecycle
  step). `projectsLoaded` is that next step: the descriptor tree is unchanged and `includedBuilds` is
  populated there, it still fires before any project's build script evaluates (still configuration
  time, still before the task graph), and it was also verified empirically to populate correctly
  under `-Dorg.gradle.unsafe.isolated-projects=true` — so every claim this plan makes about
  `settingsEvaluated` (IP-legal, config-time-only, no file reads) holds identically for
  `projectsLoaded`. Only the hook name changes; the design and risk analysis below are unaffected.
- **Execution-time probes — `BuildStructureValueSource`.** A new `ValueSource` on the
  `FingerprintValueSource` pattern (plan 022) + the capture-location/read-at-execution split of plan
  [024](implemented/024-test-collection.md): params carry the captured map + counts + `rootDir`
  (all serializable). `obtain()` runs every `.exists()`: `emptyIntermediateCandidates` =
  `hasChildren && !buildFile.exists()` (the `allprojects{}`-configured aggregator with no own build
  file — sorted + capped), `buildSrcPresent` (`<rootDir>/buildSrc`), `sourcesInRoot` (`<rootDir>/src`).
  Absolute `buildFilePath`s live **only** as a transient ValueSource param (baked into the local CC
  entry, exactly like `VcsValueSource`'s `rootDir` at `BuildHoundSettingsPlugin.kt:185`, plan
  [050](implemented/050-git-info-from-subdirectory.md)); the shipped block carries Gradle paths + counts + booleans only.
- **Plumbing.** Register the value source under the existing `masterEnabled` gate. Add
  `TelemetryFinalizerAction.Parameters.buildStructure` (`@Optional`) and an `isolatedProjectsActive`
  scalar set from `buildFeatures.isolatedProjects.active.getOrElse(false)` — parallel to
  `configurationCacheRequested` (`BuildHoundSettingsPlugin.kt:287`). `PayloadAssembler.assemble` gains
  `buildStructure` and `isolatedProjects` args: the former → a new `BuildStructureInfo` (null when
  unknown), the latter merged inside the existing `environment?.let {}` next to `configurationCache`.
- **Schema (`BuildPayload.kt`, additive, `schemaVersion` stays 1).** New `BuildStructureInfo` data
  class + `buildStructure: BuildStructureInfo? = null` on `BuildPayload`; `isolatedProjects: Boolean? =
  null` on `EnvironmentInfo`. No `PayloadScrubber`/`PayloadCapper` change (no free text; the candidate
  list is capped in the value source). New golden only — no existing golden edited.

## Test strategy

- **Unit / golden (`commons`):** `PayloadAssemblerTest` maps a `CollectedBuildStructure` →
  `BuildStructureInfo` and the flag → `EnvironmentInfo.isolatedProjects`; a new
  `build-payload-v1-build-structure.json` golden via `GoldenPayloadTest`. Assert existing goldens stay
  byte-identical (null-omission) and `schemaVersion == 1`.
- **TestKit functional (`BuildStructureFunctionalTest`, new):** a multi-module build with an
  intentional empty aggregator (`include(":libs:legacy")` — has children, no `build.gradle.kts`), a
  `buildSrc/`, and a root `src/`. Assert `projectCount`/`maxDepth`/`includedBuildCount`, the aggregator
  in `emptyIntermediateCandidates`, and `buildSrcPresent`/`sourcesInRoot` true. A second run asserts CC
  **reuse** (`TestKitCc`) — `.exists()` re-runs at execution without re-fingerprinting configuration.
- **IP (`IsolatedProjectsFunctionalTest`, extend):** under isolated projects the inventory still
  populates (`projectCount > 0`, not null) and `environment.isolatedProjects == true` — the one IP
  claim worth pinning rather than trusting.
- **Never-fail:** an internal failpoint (mirroring `buildhound.internal.failTaskGraphSnapshot`) forces
  the walk to throw; assert the build still succeeds and the block degrades to null.

## Risks

- **CC — config vs execution split.** Descriptors + buildFile *paths* are captured at config time
  (in-memory, no I/O); every `.exists()` runs in the ValueSource at execution, so nothing new enters
  the CC key. Pinned by the CC-reuse functional test.
- **Isolated projects.** The descriptor walk is settings-level and IP-legal (no `Project` touch), so
  unlike the plan-016 dictionary it does **not** degrade to empty under IP — pinned by the IP test. The
  empty-intermediate signal stays a plugin-side *filesystem* heuristic precisely because the "zero
  tasks" refinement needs the task graph (illegal under IP) and cross-build history (server-side) —
  hence deferred, not attempted here.
- **Additive schema.** New block + new flag + new golden only; `schemaVersion` stays 1; existing
  goldens untouched (null-omission verified). No capper/scrubber edit.
- **Privacy (§3.7).** Only Gradle paths (`:libs:legacy`), counts, depths, and booleans ship. Absolute
  `buildFile`/`projectDir` paths never leave the transient ValueSource param — asserted by the golden
  (no absolute path present), matching the `VcsValueSource` rootDir precedent (plan 050).
- **Heuristic, not a verdict.** `emptyIntermediateCandidates` false-positives on intentional
  aggregators (a *recommended* Gradle pattern), so the field is named "candidates", the plugin ships
  only the structural fact, and the "modularize / delete" judgment is downstream, longitudinal, and
  deferred.
- **Never-fail.** The `projectsLoaded` walk and `obtain()` are each `runCatching`-guarded → warn +
  null block; the master switch gates registration. `obtain()` additionally treats `projectCount` as
  the walk's own success signal — a failed/skipped walk degrades the *whole* block to null rather than
  shipping `buildSrcPresent`/`sourcesInRoot` alone (found by the forced-failure functional test).

## Exit criteria

- `BuildStructureInfo` + `EnvironmentInfo.isolatedProjects` ship additively; `schemaVersion == 1`; the
  new `build-payload-v1-build-structure.json` golden passes and every existing golden is byte-identical.
- A multi-module TestKit build reports correct `projectCount`/`maxDepth`/`includedBuildCount`, flags the
  empty aggregator as a candidate, and populates `buildSrcPresent`/`sourcesInRoot`; a second run is a CC hit.
- Under isolated projects the inventory still populates and `isolatedProjects == true`.
- The forced-failure path degrades to a null block with the build green.
- `./gradlew build` green.
