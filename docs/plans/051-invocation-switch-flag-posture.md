# 051 — Invocation-switch & performance-flag posture

## Source

- Research finding **F1** — "Invocation-switch & performance-flag posture"
  ([docs/research/ingest-corpus-analysis.md](../research/ingest-corpus-analysis.md), §3).
  Source articles: Talaiot deep-dives, the joint Gradle/Google/JetBrains *Best Practices* and
  *Performance* guides, the two "10 tips" lists, the 80%-reduction case study, Android + AGP-9
  docs. Nine of nine sweep agents flagged it — the corpus's strongest consensus gap.
- Spec [§3.2](../build-telemetry-spec.md) (environment/toolchain snapshot), [§4](../build-telemetry-spec.md)
  (additive payload schema), [§3.7](../build-telemetry-spec.md) (privacy). Builds on the
  `StartParameter` reads already wired for input fingerprints (plan
  [022](implemented/022-input-fingerprints-compare.md)) and the median+MAD baseline of plan
  [025](implemented/025-regression-engine-v1.md) / its INTERRUPTED-exclusion successor plan
  [033](implemented/033-lost-build-accounting.md).

## Scope

**In**

- An additive **`environment.invocation`** block (`EnvironmentInfo.invocation: InvocationInfo?`)
  carrying: (a) seven public, CC-safe `StartParameter` scalars —
  `isBuildCacheEnabled`/`isOffline`/`isRerunTasks`/`isRefreshDependencies`/`isConfigureOnDemand`/
  `getMaxWorkerCount`/`isParallelProjectExecutionEnabled` (all verified public on the repo's
  Gradle 9.6.1 `gradle-start-parameter-9.6.1.jar`); (b) a **plaintext, layer-attributed**
  `gradle.properties` allowlist (`org.gradle.caching`, `org.gradle.parallel`,
  `org.gradle.vfs.watch`, `android.enableJetifier`, `android.nonTransitiveRClass`); (c) genuinely-new
  plaintext `fileEncoding` and `locale` which, with the `parallel`/`maxWorkerCount` scalars from (a),
  stand **alongside** the salted `FingerprintInfo` hashes (never replacing them) so absolute rules
  ("Cp1252 fleet → set UTF-8") can fire. `parallel`/`maxWorkerCount` live once in `InvocationInfo`
  (as (a) scalars), not duplicated.
- **Regression-baseline hygiene:** exclude `rerunTasks`/`refreshDependencies` builds from the
  plan-025 `baselineWindow` (they have zero avoidance by design — same rationale that removed
  INTERRUPTED in plan 033; current exclusions are outcome/mode only).

**Out (named owners)**

- The **flags "who-is-behind" scorecard** (dashboard panel + `/v1/rollups/*` flag-posture
  endpoint mirroring plan [032](implemented/032-bottlenecks-landing-page.md)'s toolchain view,
  AGP-9-aware staleness rules) — **deferred to a follow-up plan**: collect-now / surface-later,
  the inverse of the 032→046 sequencing. This plan makes the scorecard a pure server read.
- `Test` `maxParallelForks`/`forkEvery` — deferred: they need the plan-016 `whenReady`
  dictionary and would inherit its isolated-projects-empty gate (§ Risks), diluting the
  invocation block's IP-robustness.
- `excludedTasks` (the `-x test` signal) — belongs to finding **F4**, not here.
- No new migration, no new read route, no `FingerprintInfo` change, no golden edit.

## Design

**Modules:** `buildhound-gradle-plugin` (collection), `buildhound-commons` (additive schema +
golden), `buildhound-server` (baseline filter). Payload `schemaVersion` stays **1**.

- **Commons (additive).** New `InvocationInfo` (all fields nullable / empty-defaulted) hung off
  `EnvironmentInfo.invocation` ([BuildPayload.kt:263](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt)):
  the StartParameter scalars, `fileEncoding`, `locale`, plus
  `properties: List<GradlePropertyPosture>` where `GradlePropertyPosture(key, value, origin)` and
  `origin: PropertyOrigin ∈ {PROJECT, GRADLE_USER_HOME, OVERRIDE, UNKNOWN}`. New golden
  `build-payload-v1-invocation.json` + a `GoldenPayloadTest` case; `build-payload-v1.json` and the
  fingerprints golden are **untouched** (never edit).
- **Plugin — new `InvocationValueSource` → `CollectedInvocation`** (a `Serializable` DTO, the
  `CollectedEnvironment` shape), wired as one more finalizer→`PayloadAssembler.assemble` param
  and mapped into `EnvironmentInfo.invocation` — the established multi-source pattern
  (`daemonReused`/`configurationCache`/`fingerprints`/`vcs`/`processes` already arrive this way,
  [PayloadAssembler.kt:136](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadAssembler.kt)).
  Gated on `extension.enabled`; every probe wrapped in the `guarded` idiom
  ([EnvironmentValueSource.kt:98](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/EnvironmentValueSource.kt))
  → never fails the build.
  - *Config-time capture (params, CC-safe scalars):* the seven `StartParameter` getters, read in
    `apply()` exactly as `isParallelProjectExecutionEnabled`/`maxWorkerCount` already are for
    fingerprints ([BuildHoundSettingsPlugin.kt:255-256](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)),
    plus the two `gradle.properties` **paths** (`<rootDir>` and `settings.gradle.gradleUserHomeDir`)
    as `String`s — locations only, no config-phase file read.
  - *Execution-time (`obtain()`):* read `file.encoding`/`user.language`+`user.country` sysprops;
    then resolve provenance. **Provenance is presence-by-precedence, not value-matching** (project
    and GUH can both declare `true`; only "which layer declares it" reveals GUH silently won). The
    load-bearing distinction is **GUH `gradle.properties` > project `gradle.properties`** (the
    finding's whole point, well-established Gradle behavior). Per key, attribute against Gradle's
    *Configuring the build environment* precedence table — the source of truth, confirmed at
    implementation, not an inline ordering: `-D<key>` system property ⇒ `OVERRIDE`; else GUH declares
    it ⇒ `GRADLE_USER_HOME`; else the project file declares it ⇒ `PROJECT`; else `UNKNOWN`. The
    namespaces differ by key family — `org.gradle.*` are *Gradle properties* (files + `-D` only),
    whereas `android.*` are read by AGP and **may** also honor project properties
    (`-P`/`ORG_GRADLE_PROJECT_*`); valid sources are confirmed per-key against how each key is read,
    never assumed uniform. Where a layer cannot be confirmed as the effective source, attribute
    `UNKNOWN` rather than guess — a confident-but-wrong "developer overrode this locally" defeats the
    feature. The effective **value** is resolved here too for the two `gradle.properties` files and
    the `-D`/env channels, so that part of the allowlist block stays CC-hit-fresh; the seven
    StartParameter scalars *and* the `android.*` family's `-P` override channel
    (`StartParameter.projectProperties`, only reachable at `apply()`-time) remain baked (see Risks).
- **Server — baseline hygiene.** `baselineWindow`
  ([PostgresStores.kt:241](../../buildhound-server/src/main/kotlin/dev/buildhound/server/PostgresStores.kt))
  gains two jsonb guards —
  `AND (payload->'environment'->'invocation'->>'rerunTasks') IS DISTINCT FROM 'true'` and the same
  for `refreshDependencies` — matching how `benchmarkSeries` already filters jsonb
  ([PostgresStores.kt:564](../../buildhound-server/src/main/kotlin/dev/buildhound/server/PostgresStores.kt));
  the `InMemoryBuildStore` filter mirrors it (plan-025 two-store parity). Bounded by the tight
  baseline key + `LIMIT N`, so cheap at pilot scale; an extracted hot column is a follow-up if
  volume strains it (the call plan 032 made for toolchain).

## Test strategy

- **Commons unit / golden:** `build-payload-v1-invocation.json` deserializes with populated
  scalars, plaintext encoding/locale, and a per-key `GradlePropertyPosture`; round-trip lossless;
  a payload with `invocation = null` still parses (additive default); existing goldens unchanged.
- **Plugin unit (Gradle-type-free):** the provenance attributor as a pure function over
  (allowlist, project-props map, GUH-props map, sysprops, env, cli-project-properties map, i.e.
  `StartParameter.projectProperties`/`-P`) — GUH-declares-and-wins ⇒ `GRADLE_USER_HOME`;
  project-only ⇒ `PROJECT`; value-equal-across-layers still attributes GUH (the
  presence-not-equality guard). **Precedence is per key family, confirmed per-key rather than
  assumed uniform** (review fix, initial implementation wrongly applied one uniform precedence to
  every key): `org.gradle.*` keys attribute a `-D<key>` system property ⇒ `OVERRIDE`, and a value
  resolvable only via env ⇒ `UNKNOWN` (never guessed); `android.*` keys are AGP-read project
  properties whose real command-line/env override channel is `-P`/`ORG_GRADLE_PROJECT_*` ⇒
  `OVERRIDE` — a bare `-D` on an `android.*` key is not a confirmed channel and is ignored rather
  than guessed as an override. Absent from every channel valid for the key's family ⇒ omitted.
- **Plugin functionalTest (TestKit):** a project `gradle.properties` with `org.gradle.caching=false`
  overridden by a GUH `gradle.properties` (via an isolated `-g` home) with `org.gradle.caching=true`
  ⇒ payload `origin=GRADLE_USER_HOME`, `value=true`; `--offline --max-workers=2` populates the
  scalars; a CC store→hit pair keeps the block present (baked scalars) and re-freshes the allowlist;
  `-Dorg.gradle.caching=false` together with `-Pandroid.nonTransitiveRClass=true` on one real
  invocation ⇒ both attributed `OVERRIDE`, proving the per-family channels end to end. Guard the
  GUH-file path against Windows backslash mangling (`invariantSeparatorsPath`, memory).
- **Server:** `RegressionEngine`/`baselineWindow` unit + Testcontainers — a seeded baseline window
  containing a `rerunTasks=true` build excludes it; in-memory and Postgres agree byte-for-byte
  (plan-025 parity oracle). No route test change (no new/altered endpoint).

## Risks

- **CC-hit replay of the config-captured scalars (narrowing #3).** The seven `StartParameter`
  booleans/ints are captured at configuration and baked into the CC entry, so toggling
  `--build-cache`/`--parallel`/`--max-workers` on a CC **hit** reports store-time values — the
  same limitation plan-022 fingerprints already carry (`parallel`/`maxWorkers`); documented, block
  still emitted. The allowlist's `gradle.properties`-file and `-D`/env channels are resolved in
  `obtain()` so they re-freshen on reuse; the `android.*` family's `-P` override channel
  (`StartParameter.projectProperties`) is itself a `StartParameter` read and so shares this same
  narrowing — a CC hit replays the store-time `-P` set, not the hit's actual command line. **Payoff:**
  the baseline-hygiene exclusion is safe *precisely because* `--offline` is part of the CC key and
  `--rerun-tasks`/`--refresh-dependencies` force a CC rebuild — those three flags can never be stale
  even though the cache/parallel/worker toggles (and the `-P` override channel) can.
- **Privacy — GUH `gradle.properties` routinely holds credentials (§3.7, dedicated review).** The
  ValueSource reads `~/.gradle/gradle.properties` for provenance but **extracts only the fixed
  allowlist keys** and emits only those keys + their (non-secret boolean/enum) values + the origin
  enum — never any other key/value, never the raw file, never the file path (the enum ships, not
  `gradleUserHomeDir`). Read broadly, emit narrowly — the EnvironmentValueSource env-read
  discipline. The allowlist is a fixed non-secret constant set, so no arbitrary free text reaches
  the payload and the existing whole-payload scrubber needs no new rule.
- **`vfs.watch` / daemon on-off are not public (narrowing #1).** Both live on the internal
  `StartParameterInternal`, off-limits to core (that is the internal-adapters module's territory).
  They are observed **only** as the `org.gradle.vfs.watch` allowlist key; the effective default-on
  state when the key is unset stays invisible — stated, not worked around.
- **Additive-only (narrowing #2).** `parallel`/`maxWorkers`/`file.encoding`/`locale` already exist
  as salted `FingerprintInfo` hashes; this plan adds plaintext *alongside* them — `FingerprintInfo`
  and its golden are never edited, `schemaVersion` stays 1, old servers `ignoreUnknownKeys`.
- **Isolated projects — a strength here (narrowing #4).** `StartParameter` + settings-level
  `gradle.properties` are settings-scope reads, so the invocation block is **fully populated under
  isolated projects** — unlike the plan-016 `whenReady` dictionary, whose IP-empty gate is exactly
  why `Test` `maxParallelForks`/`forkEvery` are deferred rather than folded in here.
- **Never-fail / no config-phase file read.** All file/sysprop/env IO is inside `obtain()` at
  execution time (locations captured at config), each `guarded`; a probe failure logs the exception
  *class* only (a `gradle.properties` path can embed a home dir) and degrades that field to null.
- **Multi-tenancy.** The server change is a filter inside the already tenant-scoped `baselineWindow`
  — no new route, no cross-tenant surface.

## Exit criteria

- A build emits `environment.invocation` with the seven `StartParameter` scalars, plaintext
  `fileEncoding`/`locale`/`parallel`/`maxWorkerCount`, and a per-key `properties` list whose
  `origin` correctly reports `GRADLE_USER_HOME` when `~/.gradle` overrides a project value — pinned
  by the TestKit test.
- The block is present and non-throwing under `-Dorg.gradle.unsafe.isolated-projects=true`
  (settings-scope reads), and on a CC-hit rerun (baked scalars, re-freshed allowlist).
- `FingerprintInfo` and every existing golden are unmodified in the diff; the new golden
  round-trips; `schemaVersion` is still 1.
- A regression baseline window excludes a `rerunTasks`/`refreshDependencies` build; in-memory and
  Postgres stores agree.
- Clean-context code + §3.2 security/privacy reviews addressed; `./gradlew build` green.
