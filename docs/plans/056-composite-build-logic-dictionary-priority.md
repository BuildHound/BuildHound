# 056 — composite `build-logic` task dictionary: raise plan 045, move the join to the finalizer

## Source

Research finding **F6** (`docs/research/ingest-corpus-analysis.md` §3) — "`build-logic` composite
builds are the agent-prescribed default." Sources: the 880-star `awesome-android-agent-skills`
`android-gradle-logic` skill (codifies the Now-in-Android `build-logic` convention-plugin structure
as what AI assistants impose on multi-module Android projects) and Gradle's *Best Practices for
Structuring Builds* (prescribes it as preferred). F6 reprioritizes the **deferred**
[045](045-composite-task-dictionary.md) (task `type`/`cacheable` dictionary lost in composites);
its predecessor [044](implemented/044-composite-build-config-data-loss.md) already fixed the *test-telemetry*
consumer of the same freeze. This plan raises 045 and closes it.

## Scope

**In**

- Un-defer plan 045 and pick its **option (b)** — move the type/cacheable join off the collector's
  hot `onFinish` path into the finalizer — *refined* to deliver the dictionary via a **Flow-action
  parameter** (the [046](implemented/046-toolchain-agp-kgp-ksp-collection.md) channel), not a sidecar. Justified
  below.
- Extend `CompositeBuildTestCollectionFunctionalTest` (`buildhound-gradle-plugin/src/functionalTest`)
  to assert **non-null `tasks[].type`/`cacheable`** and **non-null `derived.cacheableHitRate`** on
  the existing classpath-applied `build-logic` composite fixture, including on the CC-reuse run.

**Out**

- Everything plan 044 already ships: the `TestLocationSidecar` for Test-task JUnit XML locations and
  the test-telemetry assertions. Untouched.
- No schema change (see Risks) and no isolated-projects behavior change.

## Design

**Why the gap is not cosmetic-and-`includeBuild`-only (refutes plan 045 §2).** 045 §2 said the
freeze hits "only the dev harness and any consumer who applies the plugin via `includeBuild`; the
classpath path (published plugin) is unaffected." That over-generalizes. The freeze trigger is **an
included build whose tasks run during the root's configuration** — orthogonal to how BuildHound
itself is applied. `CompositeBuildTestCollectionFunctionalTest` proves it: BuildHound is
**classpath-applied** there (`withPluginClasspath()` + `plugins { id("dev.buildhound") }`, *not*
`includeBuild("../..")`), yet the `build-logic` convention-plugin include runs `:jar` during root
configuration, whose task-finish event instantiates `TaskEventCollector` — freezing
`Params.taskMetadata` empty — *before* the root `taskGraph.whenReady` fills `taskMetadataHolder`
(`BuildHoundSettingsPlugin.kt:129`). Since the agent-prescribed default *is* a `build-logic`
composite, this sits on the published-plugin path, not a niche.

**The fix (option (b) via Flow param).** Today the dictionary rides the collector service param and
is read on `onFinish` — the lazy `metadata` field (`TaskEventCollector.kt:81`, its only reader) —
which is exactly what freezes. Instead:

- `TaskEventCollector.onFinish` records raw `TaskExecution` with `type`/`cacheable`/
  `nonCacheableReason` left null; the lazy `metadata` field and `Params.taskMetadata` are removed
  (dropping per-event work — a small win against the plan-034 finalizer/per-task budget).
- Add `TelemetryFinalizerAction.Parameters.taskMetadata: MapProperty<String, TaskMetadata>`, wired
  in the `flowScope.always {}` block from `settings.providers.provider { taskMetadataHolder.get() }`
  — byte-for-mechanism identical to `toolchain` (`BuildHoundSettingsPlugin.kt:271`, plan 046). A
  Flow-action param is *not* instantiated by task events (only the collector service is), so it
  resolves after configuration — after `whenReady` — even in a composite.
- The finalizer enriches right after `val tasks = collector.snapshot()`
  (`TelemetryFinalizerAction.kt:176`): `tasks.map { it.copy(type = meta[it.path]?.type, …) }` from
  the param, **before** the list reaches `PayloadAssembler.assemble` — `cacheableHitRate` is computed
  *inside* assemble (`PayloadAssembler.kt:174` → `DerivedMetricsCalculator`), so the join must
  precede it.

Once the join leaves `onFinish` the finalizer is the **sole reader** of the dictionary — precisely
the condition under which architecture §2 rule 12 prefers a Flow-action param over a sidecar. So
this is not a third option; it is 045's option (b) done the way the post-046 architecture
prescribes. It also collapses the classpath and composite paths onto **one** delivery channel.

**Why not option (a)** (lazy sidecar read gated on the first root-build task): it keeps a read on
the hot path, needs a fragile "is this the root build?" test on the event descriptor, and risks
latching `metadata` empty if one included-build event trips the lazy read first.

## Test strategy

- **Functional (extend `CompositeBuildTestCollectionFunctionalTest`).** On the existing composite
  fixture's failing `test` run, add to the payload assertions: an executed task (e.g. `:compileJava`
  or `:test`) has non-null `type` and non-null `cacheable`, and `payload.derived?.cacheableHitRate`
  is **non-null** (assert non-null, *not* `> 0` — a fresh all-EXECUTED run legitimately yields
  `0.0`). Must be confirmed **red on `main`** (both null today).
- **CC replay.** Extend the existing store→reuse test to also assert `type` non-null **on the reuse
  run**, proving the dictionary rides the CC entry (Flow param baked at store, replayed on hit), not
  just the store build.
- **Golden/CC-hit coverage.** No golden changes (no schema change). Existing commons golden tests,
  the classpath-path task-metadata tests, and CC-reuse functional tests stay green — the classpath
  path now flows through the same finalizer join and must not regress.

## Risks

- **CC-hit correctness (load-bearing).** The Flow-param provider resolves at CC **store** time
  (after `whenReady`), bakes the dictionary into the entry, and replays on a **hit** where
  `whenReady` never runs; task paths still match because the CC key pins the task graph — the same
  argument plan 044 used for the sidecar, and plan 046 already validated this exact channel on the
  nowinandroid composite for store/hit/off.
- **Prevalence is evidence, not proof.** One 880-star repo does not prove "every project by default"
  (F6's own narrowing). The plan does not rest on prevalence — the fixture *demonstrates* the freeze
  on the classpath path directly; prevalence only raises the priority.
- **Isolated projects unchanged (rule 13).** Under IP the `allTasks` walk is skipped → holder empty
  → Flow param empty → `type`/`cacheable`/`cacheableHitRate` null, by design. The new assertions run
  on the non-IP path; the IP degradation contract and the plan-021 IP job are untouched.
- **Additive/schema.** `type`/`cacheable`/`nonCacheableReason`/`cacheableHitRate` already exist;
  `schemaVersion` stays 1; no golden edited or added. Pure delivery-path fix, like plan 044.
- **Never-fail.** The enrichment map is a total lookup (missing key → null, today's degraded shape);
  the `whenReady` capture stays inside its existing `runCatching` (`BuildHoundSettingsPlugin.kt:124`).
  No new failure path, no config-phase file read.
- Privacy §3.7 untouched — no new data collected; `TaskMetadata` (type FQCN + cacheable booleans)
  already ships.

## Exit criteria

- `CompositeBuildTestCollectionFunctionalTest` asserts non-null `type`/`cacheable` and non-null
  `derived.cacheableHitRate` on the composite fixture (store **and** reuse runs); confirmed **red on
  `main`** before the fix.
- Classpath (non-composite) task-metadata + CC-reuse tests stay green; no per-task overhead
  regression (plan 034).
- Collector `Params.taskMetadata` / lazy `metadata` removed; finalizer owns the single join.
- Architecture §2 rule 12 amended (dictionary now on the finalizer Flow-param channel; the "type
  dictionary stays on the frozen-param path" caveat, lines 214–215, retired) + a decision-log row;
  plan 045 moved to `implemented/` (superseded/closed by this plan) or annotated resolved.
- `./gradlew build` green (unit + functional), CC reused where asserted.
