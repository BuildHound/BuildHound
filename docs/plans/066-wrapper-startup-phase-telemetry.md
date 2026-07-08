# 066 — Wrapper & startup-phase telemetry (variant, SHA-pinning, GUH warmth)

## Source

Research finding **F16** ("Wrapper & startup-phase telemetry", `docs/research/ingest-corpus-analysis.md`
§4), from the ingest-corpus articles on ephemeral-CI startup cost (`-all` vs `-bin` wrapper
download, first-use JAR generation, `gradlew` re-download inside a Gradle Docker image). Relates to
spec §3.2 (environment snapshot), §3.7 (privacy), §4 (payload schema, additive-only), §7 (CI assets),
and the roadmap's "startup phase is dark to BuildHound" gap. Builds on plans
[022](implemented/022-input-fingerprints-compare.md) (execution-time ValueSource file reads + salt),
[029](implemented/029-process-probe.md)/[031](implemented/031-artifact-size-capture.md) (dedicated
nullable payload blocks; hash/size-only, no-path privacy), [016](implemented/016-task-type-cacheable-capture.md)
(isolated-projects degrade), [027](implemented/027-ci-env-breadth.md) (override precedence), and
[028](implemented/028-azure-devops-connector.md) (`gradleSharePct`, CI span tree).

## Scope

**In (plugin + commons + ci-assets):**

- A new execution-time `WrapperValueSource` reading `gradle/wrapper/gradle-wrapper.properties` and
  `gradle-wrapper.jar` → distribution variant enum (`BIN|ALL|CUSTOM`), `distributionSha256Sum` **pinned**
  boolean, full-hex SHA-256 of `gradle-wrapper.jar`, and Gradle-User-Home warmth signals.
- One additive nullable payload block `wrapper: WrapperInfo?` + two enums (`WrapperDistributionType`,
  `GuhWarmth`); a new golden file `build-payload-v1-wrapper.json` (existing goldens untouched).
- `GuhWarmth` classification (`COLD|WARM|UNKNOWN`) folded into `PayloadAssembler` against the build's
  already-computed `startedAt`.
- A **preventive** wrapper-integrity step in the Azure template (`buildhound-ci-assets`), gated
  `off|warn|fail`, running *before* `./gradlew` — the narrowing-2 preventive half, no network.

**Out (deferred, own follow-up plan — *validation/surfacing logic only*, not the data path):**

- Server-side **detective** cross-check of `wrapper.wrapperJarSha256` against gradle.org's published
  `gradle-wrapper.jar` checksums (the finding's "differentiator"). The `wrapper` block already rides
  ingest + jsonb storage transparently (shared-commons `BuildPayload` round-trips it, tenant-scoped, zero
  server change); the deferred slice is a bundled/refreshed known-good checksum table + a stored
  validation status, hooked post-`store.save` alongside `evaluator.evaluate`/`flakyAlerter.evaluate`
  (`Routes.kt` ~130) with a new token+tenant-scoped read route. Deferred because the checksum table's own
  provenance/refresh is a distinct supply-chain concern, and the plugin must ship the hash first.
- Finer **per-step pre-Gradle-gap decomposition** (Gradle-step start − ingested `startedAt` = wrapper
  download + JVM/daemon startup): a server rollup over plan-028's stored `ci_runs` tree
  (`GET /v1/builds/{id}/ci-run`) + `startedAt`. `gradleSharePct` (plan 028) is the coarse version already
  shipped; this needs the connector span tree parsed for "which span is the Gradle step" — its own plan.

## Design

- **`WrapperValueSource`** (plugin, mirrors `FingerprintValueSource`/`EnvironmentValueSource`): config
  captures *locations only* — `<rootDir>/gradle/wrapper/gradle-wrapper.properties`,
  `…/gradle-wrapper.jar`, and `settings.gradle.gradleUserHomeDir.absolutePath` (all plain-string params).
  All file I/O + hashing happens in `obtain()` at execution time, so nothing becomes a config-phase CC
  fingerprint input. Emits a `Serializable` `CollectedWrapper` DTO: `variant`, `distributionSha256Pinned`,
  `wrapperJarSha256`, plus transport-only `distMtimeMs`/`jarMtimeMs`/`distPresent` (never shipped).
  - *Variant*: parse `distributionUrl` — ends `-all.zip` → `ALL`, `-bin.zip` → `BIN`, else `CUSTOM`. The
    raw URL is **discarded** (custom-mirror host/credential vector, §3.7); only the enum survives.
  - *Pinned*: `distributionSha256Sum=` present in the properties → `true` (drift/unpinned signal).
  - *Jar hash*: SHA-256 of the `gradle-wrapper.jar` bytes as full hex — the jar is distribution-independent
    (identical for `-bin`/`-all`) and public, and full hex is exactly what the deferred cross-check needs.
  - *Warmth signals*: resolve this build's unpacked dist under
    `<gradleUserHome>/wrapper/dists/gradle-<GradleVersion.current()>-<variant>/…` and record its mtime,
    the wrapper jar's mtime, **and this daemon's own JVM start time**
    (`ManagementFactory.getRuntimeMXBean().startTime`) — see the corrected `PayloadAssembler` bullet below
    for why the JVM start time, not a build-timing value, is the anchor. A missing dist dir (system/IDE
    Gradle, not the wrapper) → `distPresent=false`.
  - Each probe is `runCatching`-guarded (class-name-only logging, like `EnvironmentValueSource.guarded`);
    any failure degrades that field to null / the whole DTO to `wrapper=null`. No subprocess here (pure
    file I/O + hash), so `BoundedExec` does not apply — the guard is `runCatching`, not a timeout.
- **Wiring** (`BuildHoundSettingsPlugin.apply`): register via `settings.providers.of(WrapperValueSource…)`
  gated on `extension.enabled`, and add `spec.parameters.wrapper.set(wrapper)` to the
  `TelemetryFinalizerAction` block (a new `@get:Input @get:Optional Property<CollectedWrapper>`).
- **Commons schema** (additive): `data class WrapperInfo(distributionVariant: WrapperDistributionType? = null,
  distributionSha256Pinned: Boolean? = null, wrapperJarSha256: String? = null, guhWarmth: GuhWarmth? = null)`;
  `enum WrapperDistributionType { BIN, ALL, CUSTOM }`; `enum GuhWarmth { COLD, WARM, UNKNOWN }`; new nullable
  `wrapper: WrapperInfo? = null` on `BuildPayload`. `SCHEMA_VERSION` stays `1`.
- **`PayloadAssembler`**: a pure, unit-testable `GuhWarmth.classify(distMtimeMs, distPresent, jvmStartMs)`
  — **anchored on this daemon's own JVM start time, not `startedAt`** (an implementation-time correction
  to this plan's original draft; see the Implementation-notes addendum below for the full rationale: the
  wrapper's bootstrap unpacks the distribution *before* launching the daemon JVM, which is before
  configuration, which is before any task — so comparing against any task-derived timestamp can **never**
  observe `COLD`, cold build or not). `COLD` when the dist mtime sits within a 5-minute fresh window of
  `jvmStartMs` (unpacked at/around this daemon's own bootstrap); `WARM` when meaningfully older; `UNKNOWN`
  when `distPresent` isn't `true` or either timestamp is unavailable (a guarded probe/introspection
  failure). `variant`/`pinned` are reported even when warmth is `UNKNOWN` (system/IDE Gradle): they
  describe the *committed* wrapper config, which is still the drift/unpinned signal — a deliberate
  decision, not an accident.
- **ci-assets** (`azure-pipelines/buildhound-gradle-steps.yml`): a new `validateWrapper: off|warn|fail`
  parameter driving a pre-`./gradlew` shell step that greps `gradle/wrapper/gradle-wrapper.properties` for
  `distributionSha256Sum=` and warns/fails when absent (enforce pinning); when the caller passes an
  expected jar SHA it also compares `sha256sum gradle/wrapper/gradle-wrapper.jar`. Network-free, no token.

## Test strategy

- **Unit (commons):** `GoldenPayloadTest` gains a `build-payload-v1-wrapper.json` deserialize+field-pin
  case (new golden, existing untouched — the additive contract). `GuhWarmth.classify` table test
  (cold/warm/absent-dist/missing-timestamp → correct enum; see the Implementation-notes addendum for
  why the anchor is this daemon's own JVM start time, not `startedAt`, and why "empty tasks" is no
  longer a distinct case).
- **Unit (plugin):** `WrapperValueSourceTest` over a temp dir — `-all`/`-bin`/custom URLs → variant;
  present/absent `distributionSha256Sum` → pinned bool; a known jar file → stable full-hex SHA-256;
  missing properties/jar/GUH → null fields, never throws.
- **TestKit functionalTest:** a build with a real `gradle-wrapper.properties` populates
  `payload.wrapper` (variant/pinned/jarSha256); assert **CC reuse** across two runs (the ValueSource adds
  no config-time input); a run with no wrapper dir degrades to `guhWarmth=UNKNOWN` with variant/pinned
  still present; an isolated-projects run still reports `wrapper` (timings flow, so `startedAt` and warmth
  work under IP — only the plan-016 *type* dictionary is empty).
- **ci-assets:** extend `buildhound-ci-assets/test/metric-cli-test.sh` (or a sibling) to exercise the
  `validateWrapper` grep logic: unpinned properties → non-zero in `fail`, zero in `warn`/`off`.

## Risks

- **Detective / trust-on-first-use limit (load-bearing, narrowing 2):** `wrapperJarSha256` is computed
  inside the JVM the (possibly compromised) wrapper *already launched*, so it catches accidental **drift
  and unpinning**, never a wrapper that actively subverts the read. This is exactly why the **preventive**
  ci-assets step runs *before* `./gradlew` and why the authoritative gradle.org cross-check is a separate
  server slice — the plugin telemetry is detection, not enforcement. Named here, not hidden by the defer.
- **CC safety:** locations captured at config, all reads in `obtain()` — no config-phase file read → no
  fingerprint input; TestKit asserts CC reuse. Matches plans 022/024.
- **Privacy (§3.7):** the raw `distributionUrl` (custom-mirror host/credential vector) is discarded —
  only the `BIN|ALL|CUSTOM` enum ships. Raw filesystem mtimes stay in the transport DTO, never the
  payload. `wrapperJarSha256` is a hash of a public, distribution-independent artifact — no PII, no path.
- **Isolated projects (narrowing / plan 016):** task *timings* still flow via `onTaskCompletion` under IP
  (only the type dictionary is left empty), so `startedAt` is available and warmth is computed normally —
  the ValueSource itself touches no project graph. Considered, not a degrade here.
- **Never-fail:** every probe `runCatching`-guarded → field null / `wrapper=null`; no subprocess, so no
  hang surface. The finalizer's outer guard + failure marker still apply.
- **Additive schema:** one new nullable block + two enums + one new golden; `schemaVersion` stays 1;
  existing golden files and payload types are untouched (server round-trips the new block via shared
  commons with zero change).
- **Multi-tenancy:** no new server route in this slice; the ingested `wrapper` block is stored
  tenant-scoped like all payload data. The deferred cross-check's read route must be token+tenant-scoped.

## Exit criteria

- A wrapper-launched build populates `payload.wrapper` with variant, `distributionSha256Pinned`,
  full-hex `wrapperJarSha256`, and a `guhWarmth` of `COLD`/`WARM`; a re-run is a CC hit.
- A system/IDE-Gradle (no wrapper dist under the resolved Gradle User Home) reports `guhWarmth=UNKNOWN`
  with variant/pinned still populated; a probe failure degrades to `wrapper=null` and never fails the
  build. (Superseding the original draft: a **task-less** build is no longer forced to `UNKNOWN` — see
  the Implementation-notes addendum — since the corrected JVM-start anchor needs no task at all.)
- The `-all` vs `-bin` variant + `COLD` warmth are visible in the payload, giving the startup-cost signal
  the finding calls for; the raw `distributionUrl` never appears anywhere in the payload.
- New `build-payload-v1-wrapper.json` golden deserializes; all existing goldens unchanged.
- The Azure template's `validateWrapper: fail` step fails a pipeline on an unpinned wrapper before Gradle
  runs; `off` is a no-op.
- `./gradlew build` green.

## Implementation notes (post-implementation addendum)

Implemented as designed, with a few clarifications where this plan's prose left the exact shape
underspecified:

- **`distributionUrl` privacy decision — followed exactly as designed, no divergence.** This plan
  already resolved the raw-URL question ("The raw URL is **discarded**… only the enum survives"):
  `WrapperInfo` carries only `WrapperDistributionType` (`BIN|ALL|CUSTOM`) — no version string, no
  host, no URL fragment anywhere in the schema. `WrapperParsing.classifyVariant` never returns or
  logs the input URL.
- **`GuhWarmth.classify`'s anchor was changed from `startedAt` to this daemon's own JVM start time —
  a correctness fix, not a stylistic one (caught in review before commit).** The original draft's
  formula (`COLD` when `distMtimeMs >= startedAt`, `startedAt` = the first task's `startMs`) is
  **structurally unable to ever return `COLD`**: the wrapper's bootstrap process unpacks the
  distribution and *then* launches the daemon JVM, which runs configuration, which precedes every
  task — so `distMtimeMs` (the unpack) is *always* causally earlier than `startedAt` (the first
  task), cold build or not. The inequality `mtime >= startedAt` can therefore never hold, making
  `COLD` dead code for any real invocation — silently defeating the whole finding (the "startup-cost
  signal the finding calls for" exit criterion above). Green tests didn't catch it because the unit
  tests fed hand-picked mtimes chosen to satisfy the inequality, and every functional test only ever
  reached `UNKNOWN` (TestKit never populates the GUH dist under an explicit `-g`), so nothing
  exercised a genuine end-to-end COLD/WARM decision.

  The fix: `classify(distMtimeMs, distPresent, jvmStartMs)` compares the dist's mtime against
  `ManagementFactory.getRuntimeMXBean().startTime` for *this daemon's own JVM* (captured in
  `WrapperValueSource.obtain()`), within a `GuhWarmth.FRESH_WINDOW_MS` (5 minutes) tolerance — the
  daemon JVM launches moments after the wrapper bootstrap unpacks the distribution, so this is the
  tightest anchor available without hooking the bootstrap process itself (which has already exited
  by the time our plugin's JVM runs). `jarMtimeMs` is no longer part of the decision (it was only
  ever a stopgap fallback in the broken formula) but is still collected on the DTO per this plan's
  original design intent, for potential future refinement. **Consequence for the task-less-build
  exit criterion**: since the new anchor doesn't need `startedAt` (or any task) at all, the
  `tasksEmpty → UNKNOWN` special case was removed — a task-less build (e.g. `gradle projects`) now
  gets a genuine classification instead of being forced to `UNKNOWN` for a reason that no longer
  applies. **Known accepted limitation of the fix** (documented in the enum's KDoc): a long-lived,
  *reused* daemon keeps the same `jvmStartMs` across every build it serves, so `COLD` can stay
  pinned on subsequent builds after the one that actually paid the download cost — cross-reference
  `environment.daemonReused` downstream to disambiguate. Ephemeral CI (finding F16's primary target)
  typically launches a fresh daemon per job, where this limitation does not apply.
- **Plugin unit-test coverage lives in `WrapperParsingTest.kt`**, not a class literally named
  `WrapperValueSourceTest` — matching this codebase's existing convention (`VcsValueSource` /
  `VcsParsingTest`, `InvocationValueSource` / `GradlePropertyProvenanceTest`): the pure
  parsing/hashing/probing logic is factored into an `internal object WrapperParsing`, unit-tested
  directly over temp files/dirs; `WrapperValueSource` itself (an abstract Gradle-managed
  `ValueSource`) is exercised only through TestKit (`WrapperFunctionalTest.kt` +  a wrapper case
  added to `IsolatedProjectsFunctionalTest.kt`), since Gradle's own decoration is required to
  instantiate it and no test in this repo instantiates a `ValueSource` directly.
- **ci-assets test lives in a new sibling `test/wrapper-integrity-test.sh`**, not an extension of
  `metric-cli-test.sh` (that file harnesses the unrelated `bin/buildhound-metric` CLI). The new
  script's `check_wrapper` function is a hand-synced mirror of the Azure template's inline shell
  step (the template stays fully self-contained — no external script dependency — matching the
  existing `verdictGate` step's style, since a template consumer only guarantees the template file
  itself, not a checkout of this repo's `bin/`); both are commented as needing to be kept in sync.
