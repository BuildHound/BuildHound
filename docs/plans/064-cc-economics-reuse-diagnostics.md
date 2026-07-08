# 064 — Configuration-cache economics & reuse diagnostics

## Source

Research finding **F14 — Configuration-cache economics & reuse diagnostics**
(`docs/research/ingest-corpus-analysis.md`), from the ingest-corpus articles on Gradle's own
configuration-cache guidance ("enable on CI for parallelism/early breakage; do not focus on cache
reuse"; store 270 MB → 65 MB after dedup). Spec §3.2 (start-parameters + heuristics, refined later),
§3.7 (pseudonymization), §3.9 (truncate + count). Builds on plans
[016](implemented/016-task-type-cacheable-capture.md) (`configurationMs`, config dictionary),
[022](implemented/022-input-fingerprints-compare.md) (salted build fingerprints),
[026](implemented/026-server-rollups-project-cost.md) (server rollups),
[028](implemented/028-azure-devops-connector.md) (CI correlation),
[033](implemented/033-lost-build-accounting.md) (`userId`/marker), and
[038](implemented/038-internal-adapters.md) (internal-adapters `derived` inputs).

## Scope

**In**

- **Plugin/commons (3 additive nullable fields, `schemaVersion` stays 1):**
  1. `EnvironmentInfo.configurationCacheParallel: Boolean?` — the `org.gradle.configuration-cache.parallel`
     flag, read as a provider (tracked CC input), wired like `configurationCacheRequested`.
  2. `DerivedMetrics.ccEntrySizeBytes: Long?` — best-effort byte sum of the newest CC entry dir, read
     at **finalizer** (execution) time; nullable, `runCatching`.
  3. `DerivedMetrics.ccLoadMs: Long?` — best-effort **core proxy** for CC entry-load cost, populated
     only on a CC **HIT**; null elsewhere and when unmeasurable. `configurationMs` stays 0 on a HIT
     (distinct field — F14 narrowing).
- **Server (F14 diagnostics):** per-day CC-reuse counters on `/trends`; a new tenant-scoped
  `GET /v1/rollups/cc-economics` returning the advisory CI-reuse classification, store/load/entry-size
  p50s, and flip-flop findings (plan-022 fingerprint equality within one machine's salt stream).

**Out / deferred**

- **Precise CC store/load split via internal-adapters.** F14 concedes "no public API… the split is a
  proxy." A precise per-op timer (a `ConfigurationCache*` `BuildOperationType` in
  `BuildOperationAdapter`) is future sharpening — the CC-load op is a *different* op type from the
  task-output cache ops captured today and fires before a config-phase-registered listener exists on a
  HIT, so it is unreliable. The core proxy is the faithful v1; the field is null-capable for the later timer.
- No `ccStoreMs` field (store cost is already inside `configurationMs` on `MISS_STORED`).
- No dashboard markup beyond the JSON surfaces; the "upgrade nudge when Gradle ships multi-entry CC
  storage" is a future rule, not this slice.

## Design

- **parallel flag (plugin).** `TelemetryFinalizerAction.Parameters` gains `@get:Input @get:Optional
  configurationCacheParallel: Property<Boolean>`, wired in `BuildHoundSettingsPlugin` from
  `settings.providers.gradleProperty("org.gradle.configuration-cache.parallel").map { it.toBoolean() }`
  (a provider read — a tracked CC input, resolved after config; never `System.getProperty`).
  `PayloadAssembler.assemble` threads it onto `EnvironmentInfo` beside `configurationCache`/`daemonReused`.
- **entry size (plugin).** New `CcEntrySize.newestEntryBytes(rootDir): Long?` — lists
  `<rootDir>/.gradle/configuration-cache/`, picks the **newest-modified** `<hash>` subdir (avoids the
  7-day-retention stale-entry pollution of a whole-dir sum), walks it summing `length()`; whole thing in
  `runCatching`, returns null on a missing/unrecognized layout. Called in the finalizer (has `rootDir`),
  passed into `DerivedMetricsCalculator.compute(..., ccEntrySizeBytes)` (pass-through, mirrors
  `configurationMs`). Finalizer-time read only — a config-phase read would become a CC fingerprint input.
- **ccLoadMs proxy (plugin).** `DaemonState` captures `System.nanoTime()` when the `TaskEventCollector`
  service is instantiated (`executionStartedNanos` — the first plugin-controlled instant after the CC
  entry is deserialized on a HIT). The finalizer, only when `ccState == HIT`, computes `ccLoadMs` as the
  interval from that anchor to the earliest task start — a proxy for entry-load + task-graph readiness,
  documented as such; null when there is no anchor/no tasks. Threaded through `compute(..., ccLoadMs)`.
- **Server read of CC state.** Add extracted `cc_state text` column to `builds` (V7 migration, nullable
  → historical rows null), populated at ingest from `environment.configurationCache`, mirrored in the
  in-memory store. `TrendPoint` gains nullable `ccMissStored/ccHit/ccRequested: Int?` per day; both stores
  (Kotlin + SQL) fill them, Testcontainers parity kept green (same rule as `RollupCalculator`).
- **cc-economics rollup.** New pure `CcEconomicsCalculator` (server §5 style, no Ktor/storage types),
  producing a `@Serializable CcEconomicsReport`: an advisory `CiReuseClass` enum
  (`EPHEMERAL_CI_EXPECTED_ZERO` / `REUSE_HEALTHY` / `REUSE_DEGRADED` / `DISABLED` / `INSUFFICIENT_DATA`)
  — annotation, never a "turn it off" fix — plus p50 store cost (`configurationMs` on `MISS_STORED`), p50
  `ccLoadMs` (HIT), p50 `ccEntrySizeBytes`, and flip-flop findings. `CcFlipFlopDetector` groups a
  window's builds by `(userId, hostnameHash)` (so salted `fingerprints.build` maps only compare inside
  one machine's salt stream — F14 narrowing), sorts by time, flags a `MISS_STORED` whose `fingerprints.build`
  equals a strictly-earlier build's in the same stream. `GET /v1/rollups/cc-economics` — read-scope,
  token + tenant-scoped, `days` clamped like `/trends` — loads the tenant window and returns the report.

## Test strategy

- **commons unit + golden:** a new `build-payload-v1-cc-economics.json` golden pinning the three new
  fields; existing goldens untouched (`GoldenPayloadTest`). `DerivedMetricsCalculatorTest` for
  `ccLoadMs`/`ccEntrySizeBytes` pass-through.
- **plugin unit:** `CcEntrySize` over a temp `.gradle/configuration-cache/` fixture (newest-subdir pick,
  missing dir → null, unreadable → null). `PayloadAssemblerTest` for `configurationCacheParallel`.
- **plugin functionalTest (TestKit):** extend the existing CC store/hit tests — a `MISS_STORED` build
  reports `ccEntrySizeBytes != null`; a HIT reports `configurationMs == 0` **and** a non-null (or cleanly
  null) `ccLoadMs`; `-Dorg.gradle.configuration-cache.parallel=true` surfaces `configurationCacheParallel`.
- **server:** `CcFlipFlopDetectorTest` + `CcEconomicsCalculatorTest` (pure); a Testcontainers parity test
  that the in-memory and Postgres `/trends` CC counters agree; a `Routes` test that `/v1/rollups/cc-economics`
  is 401 without a token and 404-empty cross-tenant.

## Risks

- **CC-safety (never a fingerprint input):** entry-size and ccLoadMs are read at **finalizer/execution**
  time; the parallel flag flows via `providers.gradleProperty` (already a CC input). No config-phase file
  read is added — the functional CC-reuse assertions stay green.
- **No public API — proxy honesty (F14 narrowing):** `ccLoadMs` is a labelled best-effort proxy, not a raw
  deserialize timer; `configurationMs` remains 0 on a HIT as a *distinct* field; the precise internal-op
  timer is Out (deferred to internal-adapters). Core adds no internal Gradle API.
- **Internal CC layout (F14 narrowing):** entry-size sums the undocumented `.gradle/configuration-cache/<hash>`
  tree — newest-modified-subdir heuristic + `runCatching`, degrade to null on any unrecognized layout;
  stale 7-day-retention entries excluded by summing only the newest entry.
- **Additive-only schema:** three new nullable fields, `schemaVersion` stays 1, one new golden; no existing
  payload type or golden edited.
- **Privacy (§3.7):** entry size is a byte count and parallel is a boolean — no paths. Flip-flop grouping
  uses already-pseudonymized `userId`/`hostnameHash`; `fingerprints.build` is already salted (no plaintext).
  No new collected data outside the field list above.
- **Isolated-projects:** none of the three fields depend on the plan-016 `whenReady` dictionary (empty under
  IP). `configurationMs`/`ccLoadMs` are already null on an IP hit; the flip-flop detector reads whatever
  `fingerprints.build` keys exist and degrades (fewer keys) — never crashes.
- **Multi-tenancy:** `/v1/rollups/cc-economics` is token + tenant-scoped like every `/rollups` route; the
  flip-flop window query is `project_id`-filtered, so salts never compare across tenants.
- **Never-fail:** every new plugin path sits inside the finalizer's outer `runCatching` → warn + marker;
  the server calculator is wrapped so a read can never fail and ingest is untouched.

## Exit criteria

- A `MISS_STORED` build carries `ccEntrySizeBytes` and `configurationCacheParallel`; a HIT carries
  `configurationMs == 0` with a `ccLoadMs` slot; all three replay correctly across a CC hit (functional test).
- New golden added, existing goldens unchanged; `schemaVersion == 1`.
- `GET /v1/rollups/cc-economics` returns the advisory CI-reuse class + flip-flop findings, tenant-scoped;
  `/trends` carries per-day CC counters with in-memory/Postgres parity green.
- `docs/architecture.md` decision log notes the ccLoadMs proxy-vs-internal-op choice.
- `./gradlew build` green.
