# 063 — Change blast-radius attribution: which changed module caused this build's work

**Status: implemented** · 2026-07-09 — landed in `7e222d6` (plugin+commons+server: change
blast-radius attribution + V13 (063)) with review fixes in `c452f24` (NUL escape + capper
test + diff hardening); see "Implementation notes (as built)" below for divergences from
the original design.

## Source

- Research finding **F13**, [`docs/research/ingest-corpus-analysis.md`](../research/ingest-corpus-analysis.md)
  (§4). Source article: the **Pocket Casts de-modularization / `gradle-profiler`** study — rebuild
  cost is a function of the changed module's downstream fan-out; the Gradle *Best Practices* guide's
  `api`→`implementation` advice.
- Complements **plan [026](implemented/026-server-rollups-project-cost.md)** (Project Cost): 026 ranks
  modules by *their own* cost; this ranks them by the cost they **inflict on others** — the number
  teams want before committing to modularization or `api`→`implementation`.
- Builds on the VCS pipeline: plans [004](implemented/004-vcs-collector.md)/[015](implemented/015-vcs-exec-timeout.md)
  (`GitExec`/`BoundedExec`), [050](implemented/050-git-info-from-subdirectory.md) (subdir discovery),
  [027](implemented/041-ci-connectors-gha-gitlab.md) (CI `targetBranch`). Spec §3.7 (no paths/PII), §4
  (additive schema), §5 (rollups).

## Scope

**In**

- **Plugin collection** of the changed-module set via one bounded `git diff --name-only --relative`
  against a resolvable base, mapped to **Gradle project paths** (never file paths). Two bases, in
  order: CI PR base ref (`ci.targetBranch`), else a new `.gradle/buildhound/last-built-sha` file
  written at the previous finalization. No base resolvable → the whole block is absent (degrade).
- **Additive schema:** nullable `BuildPayload.changedModules` + new `ChangedModulesInfo` /
  `ChangeDiffBase`. New golden file; `schemaVersion` stays 1.
- **Server rollup:** persist the per-build changed-module set; `GET /v1/rollups/change-blast-radius`
  ranks costliest-modules-to-change by median downstream **executed** time × change frequency,
  reusing plan-026's `task_executions` for the executed-duration join. Minimal card on `#/tasks`.

**Out (deferred)**

- **True dependency-graph / direct-dependents ("depth") analysis.** F13's "depth" = direct-dependents
  count; the edge list is not serialized today (F12 / plan [038](implemented/038-internal-adapters.md)).
  This slice ships the **executed-time proxy** over data already on hand, not structural depth.
- Per-file granularity (privacy: module paths only), continuous aggregates, and the CI recipe that
  fetches the PR base ref (belongs in the plan-041 / F5 `setup-buildhound` composite action).

## Design

**Modules:** `buildhound-gradle-plugin` (new ValueSource + finalizer write), `buildhound-commons`
(additive schema + golden), `buildhound-server` (migration, ingest insert, one rollup, dashboard card).

**Collection — `ChangedModulesValueSource`** (mirrors [`VcsValueSource`](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/VcsValueSource.kt)):
external-process I/O, so it is re-obtained when the Flow action realizes it each build — inheriting
the exact CC-hit-replay caveat `VcsValueSource` documents (a CC *hit* skips config, so a base baked at
store time is replayed; acceptable, same as `vcs`). `obtain()`:
1. Resolve base: if `targetBranch` param non-null → `git diff --name-only --relative <origin/target>...HEAD`
   (merge-base = PR changes), `base = CI_PR_BASE`; else if `.gradle/buildhound/last-built-sha` exists and
   parses → `git diff --name-only --relative <sha>` (cumulative since that recorded HEAD),
   `base = LAST_BUILT_SHA`; else return null.
2. Map each path to the **longest-prefix** entry of a serializable `moduleDirIndex: Map<relDir,gradlePath>`.
   Root-level build files (e.g. `gradle/libs.versions.toml`, root `build.gradle.kts`) map to `":"`
   (whole-build blast radius — the highest-radius change, deliberately not discarded); a path under no
   module maps to `unattributedChanges = true` (the raw path is **never** emitted). Emit the distinct
   set of gradle paths.

Reuse `GitExec`/`BoundedExec` (bounded, `--relative` also confines output to the Gradle root, plan 050),
`searchParents`, and `buildhound.vcs.timeout.ms`. Params: `enabled`, `rootDir`, `timeoutMillis`,
`searchParents`, `lastShaPath`, `targetBranch` (wired `ci.map { it?.targetBranch }`), and `moduleDirIndex`.

**Module-dir index (CC-critical).** Dereferencing the live `Settings` model inside an execution-time
lambda is a CC violation, and descriptors are empty at `apply()`. Use the `toolchainHolder` precedent
([`BuildHoundSettingsPlugin.kt:69,271`](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)):
fill an `AtomicReference<Map<String,String>>` in `settings.gradle.settingsEvaluated { }` (read the
`settings.rootProject` descriptor tree — `path` + `projectDir` relativized to `rootDir`, forward-slash,
`runCatching`), then wire `spec.parameters.moduleDirIndex.set(providers.provider { holder.get() })`. The
map bakes into the CC entry (correct — module structure is CC-keyed; a `settings.gradle` edit invalidates
it) while the git diff stays fresh. Mapping must be plugin-side: the server has `task_executions.module`
but no `projectDir`→path index, and emitting file paths would break §3.7.

**Last-sha write.** In `TelemetryFinalizerAction.execute`, after assembly, write `vcs.sha` to
`<rootDir>/.gradle/buildhound/last-built-sha` (`.gradle`, not `build/`, survives `clean`), `runCatching`
→ never fails the build. This is the base for the *next* local iterative build.

**Assembly.** `PayloadAssembler` gains `changedModules: CollectedChangedModules?` → `ChangedModulesInfo`,
threaded through the finalizer parameter like `vcs`/`ci`.

**Schema (commons, additive).**
```kotlin
val changedModules: ChangedModulesInfo? = null            // new nullable field on BuildPayload
@Serializable data class ChangedModulesInfo(
    val base: ChangeDiffBase,
    val modules: List<String> = emptyList(),               // ":app", ":core:common" — never file paths
    val unattributedChanges: Boolean = false)              // ≥1 change mapped to no module; path never emitted
@Serializable enum class ChangeDiffBase { CI_PR_BASE, LAST_BUILT_SHA }
```

**Server.** Migration `V{n}__build_changed_modules.sql` (next free integer at merge; additive) —
`build_changed_modules(project_id, build_id, started_at, module)`, indexed `(project_id, started_at)`,
`(project_id, module)`; inserted transactionally on ingest **only when the `builds` row was new**
(mirrors plan-026's `task_executions` idempotency). `GET /v1/rollups/change-blast-radius`
(`authenticatedProject(…, allowsRead)`, tenant-scoped, `days` clamp, `respondQuery` outage path):
per changed module M, `changeCount` = distinct builds where M changed; `downstreamExecutedMs` per build =
`sum(task_executions.duration_ms WHERE outcome='EXECUTED' AND module <> M)` (cost inflicted on *others*);
rank by `median(downstream) × changeCount`, `LIMIT 25`, deterministic tiebreak. In-memory + Postgres
stores agree (plan-026 `RollupCalculator` parity posture). Dashboard: one "Costliest modules to change"
card on `#/tasks` beside Project Cost, honest empty-state when no build carries the block.

## Test strategy

- **Unit (commons):** golden round-trip of a new `build-payload-v1-changed-modules.json` (add, never
  edit); `ChangedModulesInfo` defaults; enum serialization. Path→module longest-prefix + `":"`-root +
  `unattributedChanges` mapping as a pure helper unit test.
- **Unit (`GitExecTest`-style, real git, `@DisabledOnOs(WINDOWS)`):** temp repo, commit, mutate a file
  under a subdir → `git diff --name-only --relative <sha>` yields the expected relative path; missing
  base ref → `NonZeroExit` → null.
- **Functional (TestKit):** multi-module fixture + git repo; last-sha file seeded → payload
  `changedModules.modules` equals the touched modules, `base=LAST_BUILT_SHA`; no base → `changedModules`
  null; finalizer writes `.gradle/buildhound/last-built-sha`. CC-safe (build twice, store+hit).
- **Server (`testApplication` + Testcontainers):** ingest writes one `build_changed_modules` row per
  module, zero on duplicate `buildId`; `/v1/rollups/change-blast-radius` is 401 no-token / 403
  ingest-scope / tenant-isolated; ranking + median match in-memory over seeded fixtures; empty rollup
  renders without throw.

## Risks

- **CC — module-dir index:** the one real hazard. Never realize a `Settings`-dereferencing lambda at
  execution time; snapshot in `settingsEvaluated` into a holder, wire via `providers.provider` (above).
  Everything else (git exec, last-sha read) lives inside `obtain()`/finalizer at execution time —
  locations captured at config, no config-phase file read, no new CC fingerprint input.
- **Never-fail:** every git call is `BoundedExec`-bounded (`destroyForcibly`, timeout); base resolution,
  path mapping, descriptor read, and last-sha write are `runCatching` → warn/degrade to null, never a
  failed build.
- **CI base sparsity (dominant, not just shallow clones):** `origin/<targetBranch>` must be fetched
  locally; default `actions/checkout` (`fetch-depth: 1`) lacks it, so `CI_PR_BASE` degrades to null on
  most CI unless the pipeline fetches the base — the base-fetch belongs in the plan-041 / F5 recipe, not
  here. Documented; degrade is silent.
- **Privacy (§3.7):** emit Gradle project paths only (same exposure class as `TaskExecution.module`
  already shipped); `unattributedChanges` is a boolean, the raw path is never emitted; the base sha is
  not added to the payload (`vcs.sha` already ships). No absolute paths, no file lists.
- **Additive-schema:** new nullable field + new golden only; existing `VcsInfo` and all existing golden
  files untouched; `schemaVersion` stays 1; old plugin/server interoperate (field absent).
- **Isolated projects — differentiator:** works under IP. It uses the settings **descriptor tree** +
  task-path-derived `module`, **not** the plan-016 `whenReady` dictionary, so it does not inherit the
  empty-dictionary IP gate (still `runCatching` the descriptor read).
- **Attribution is an honest heuristic:** when a build changes several modules, the whole build's
  downstream executed time is attributed to *each* changed module (shared/over-counted). Documented like
  plan-026's `buildCostScalar` quirk — a ranking signal, not causal isolation; true direct-dependents
  ("depth") is deferred to F12/plan-038.
- **Multi-tenancy:** the rollup is read-scope + tenant-scoped and `days`/`LIMIT 25` bounded, like every
  plan-026 rollup.

## Exit criteria

- A multi-module build with a resolvable base emits `changedModules{base, modules, unattributedChanges}`
  carrying **module paths only**; no base → block absent — both pinned by TestKit.
- Finalizer records `.gradle/buildhound/last-built-sha`; the next build diffs against it (`LAST_BUILT_SHA`).
- New golden file added; no existing golden edited; `schemaVersion` == 1; contract tests green.
- Ingest writes `build_changed_modules` once per build (zero on duplicate); `GET
  /v1/rollups/change-blast-radius` returns tenant-scoped costliest-modules rows with in-memory/Postgres
  parity; `#/tasks` card renders with an honest empty state.
- Spec §5 notes the new rollup; `docs/architecture.md` decision log records the settings-descriptor
  module-dir-index-holder pattern. `./gradlew build` green.

## Implementation notes (as built)

- **Migration number = `V13`.** `V12__execution_reasons.sql` (plan 061) was the last claimed migration
  on this branch; `V13__build_changed_modules.sql` is the next free integer (the `V{n}` placeholder
  above, the 061 precedent). Noted in the migration header too.
- **Mapping semantics clarified (correct Gradle ownership, not a divergence).** Root (`""` → `":"`)
  is the legitimate catch-all: Gradle's root project *owns* every path not under a subproject, so a
  root-level file — a build file *or* any other non-subproject path — attributes to `":"` (whole-build
  radius), never discarded and never fabricated onto a build-file allowlist. `unattributedChanges` is
  therefore reframed as the **honest degraded flag**: it fires when a changed file matches **no** index
  entry at all — i.e. the descriptor walk produced an empty/partial index (root absent) while `git diff`
  still succeeded ("saw changes, couldn't attribute them"). Faithful to the plan's "path under no
  module" intent and alive/testable, without a fragile root-build-file heuristic. The pure
  `ChangedModuleMapper` uses **segment-safe** longest-prefix matching (`app` never captures `app-core`),
  unit-pinned with adversarial + boundary cases.
- **Window = benchmark-INCLUDED**, reusing the `taskRowsInDaysWindow` population projectCost/pluginCost
  read (`started_at >= cutoff`, no `builds` join or mode exclusion) — this is projectCost's
  cost-inflicted-on-others sibling (F13's own framing), not a fleet-view rollup. A benchmark rerun can
  nudge change-frequency; accepted, the same posture projectCost lives with.
- **Parity by fold-in-Kotlin** (the plan-058 `pluginCost` posture, not a projectCost-style SQL rollup):
  both stores flatten the window to `ChangeBlastBuild`s and defer to a single pure
  `RollupCalculator.changeBlastRadius`. The `median(downstream) × changeCount` fold has no clean SQL
  equivalent and `module != M` under SQL NULL semantics is a trap, so folding in Kotlin on both sides
  is what makes byte-for-byte parity automatic (Testcontainers-pinned).
- **Hook = `settingsEvaluated`** for the module-dir-index holder, exactly as the plan specifies — safe
  here because only `settings.rootProject`'s descriptor tree (path + `projectDir`) is read, never
  `Gradle.includedBuilds` (the call that forced plan 069's walk to `projectsLoaded`).
