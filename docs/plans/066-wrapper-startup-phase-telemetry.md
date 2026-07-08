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
    `<gradleUserHome>/wrapper/dists/gradle-<GradleVersion.current()>-<variant>/…` and record its + the
    wrapper jar's mtime; a missing dist dir (system/IDE Gradle, not the wrapper) → `distPresent=false`.
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
- **`PayloadAssembler`**: a pure, unit-testable `GuhWarmth.classify(distMtimeMs, jarMtimeMs, distPresent,
  startedAtMs)` — `COLD` when the dist mtime ≥ `startedAt` (created/re-downloaded during this build,
  explaining the Docker-image re-download cost); `WARM` when older; `UNKNOWN` when `distPresent=false`
  **or** `tasks` is empty (a task-less build makes `startedAt` fall back to `nowMs` — `PayloadAssembler:115`
  — and the comparison is meaningless). `variant`/`pinned` are reported even when warmth is `UNKNOWN`
  (system/IDE Gradle): they describe the *committed* wrapper config, which is still the drift/unpinned
  signal — a deliberate decision, not an accident.
- **ci-assets** (`azure-pipelines/buildhound-gradle-steps.yml`): a new `validateWrapper: off|warn|fail`
  parameter driving a pre-`./gradlew` shell step that greps `gradle/wrapper/gradle-wrapper.properties` for
  `distributionSha256Sum=` and warns/fails when absent (enforce pinning); when the caller passes an
  expected jar SHA it also compares `sha256sum gradle/wrapper/gradle-wrapper.jar`. Network-free, no token.

## Test strategy

- **Unit (commons):** `GoldenPayloadTest` gains a `build-payload-v1-wrapper.json` deserialize+field-pin
  case (new golden, existing untouched — the additive contract). `GuhWarmth.classify` table test
  (cold/warm/absent-dist/empty-tasks → correct enum).
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
- A system/IDE-Gradle (no wrapper dir) or task-less build reports `guhWarmth=UNKNOWN` with variant/pinned
  still populated; a probe failure degrades to `wrapper=null` and never fails the build.
- The `-all` vs `-bin` variant + `COLD` warmth are visible in the payload, giving the startup-cost signal
  the finding calls for; the raw `distributionUrl` never appears anywhere in the payload.
- New `build-payload-v1-wrapper.json` golden deserializes; all existing goldens unchanged.
- The Azure template's `validateWrapper: fail` step fails a pipeline on an unpinned wrapper before Gradle
  runs; `off` is a no-op.
- `./gradlew build` green.
