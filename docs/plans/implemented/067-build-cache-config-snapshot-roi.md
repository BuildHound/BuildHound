# 067 — Build-cache configuration snapshot + remote-cache ROI

**Status: Implemented** — merged in `691b746` (config snapshot, opt-in transfer-timing
fields, `/v1/rollups/cache-roi`, report badge + dashboard card) and `92352e8` (review
fixes: honest store-byte reads off the *Details* type, transfer-byte miss-sentinel guard,
consent-surface KDoc/spec updates, dashboard-smoke coverage). All exit criteria met,
pinned by `BuildCacheConfigFunctionalTest`, `GoldenPayloadTest`, `CacheRoiCalculatorTest`,
`CacheRoiRoutesTest`, and `CacheRoiStoresIntegrationTest`. The planned `enabled` field was
dropped from `BuildCacheConfigInfo` per the divergence note below (superseded by plan
051's `invocation.buildCacheEnabled`, which landed first); `schemaVersion` stayed 1
throughout, as scoped.

## Source

- Research finding **F17** (`docs/research/ingest-corpus-analysis.md`, §2 exec summary + §5,
  high-impact): BuildHound records `FROM_CACHE` outcomes and `derived.cacheableHitRate` but never
  whether a cache was **configured at all**, so it cannot tell "0 % hit: no remote cache configured"
  from "remote cache broken/cold" — the most basic cache triage.
- Source articles behind F17: the Talaiot deep-dives and the caching/CC essays in
  `docs/research/processed/`; the Develocity build-cache operation-timings panel referenced in
  `docs/research/dashboard-ux-research.md:27` and `docs/build-telemetry-research.md:65`.
- Builds on **plan 038** (internal-adapters `CacheOrigin` + the *specced-then-dropped* transfer
  timings, `038:76,141-142,168-169`), **plan 039** (`extensions` map + registry), **plan 016**
  (config-time mailbox pattern), **plan 046** (`toolchainHolder` mailbox), **plan 032**
  (server rollup + who-is-behind panel precedent). Spec §3.7 (privacy), §4 (payload).

## Scope

**In**

- **Config snapshot (core plugin, public API):** read `Settings.buildCache` **after settings
  evaluation** — `local.isEnabled`, `remote?.isEnabled`, `remote?.isPush`, remote backend **type
  name** (normalized, not URL) — plus `StartParameter.isBuildCacheEnabled()`. Additive
  `EnvironmentInfo.buildCache` block (commons), new golden file.
- **Per-build config badge (report + dashboard):** render the snapshot directly from the payload —
  "remote cache: not configured / configured (push, type)". No server work.
- **Transfer-timing revival (opt-in internal-adapters module):** finish plan 038 — record
  per-task cache transfer **bytes + load/store ms** in `BuildOperationAdapter` (today
  `BuildOperationAdapter.kt:86-93` records only `isHit`/`stored` booleans). Additive fields on
  `InternalTaskDetail`/`TaskAccum`.
- **Fleet remote-cache ROI rollup (server):** a token+tenant-scoped `/rollups/cache-roi` endpoint
  consuming the already-shipped-but-directionally-unread `CacheOrigin` (`LOCAL_HIT`/`REMOTE_HIT`)
  from the payload `extensions["internalAdapters"]` — remote-hit rate segmented by build mode, plus
  a **ranked near-zero-CI-reuse candidate** joined to the config snapshot.

**Out (deferred)**

- No rules-engine verdict copy / projected-savings numbers (F4 territory). ROI ships as data +
  one dashboard card; deep-dive drilldowns are a follow-up.
- No materialized `cache_origin` column — the rollup reads payload `jsonb` (see Design); a
  denormalized column is a later optimization if the window scan proves costly.
- No change to `derived.cacheableHitRate` (core scalar stays as-is; it stays local/remote-blind).
- `StartParameter.isBuildCacheEnabled()` is emitted here for now but **belongs to F1's future
  `environment.invocation` block** — when that lands, this scalar moves there and `buildCache`
  references it instead of double-emitting (noted, not built).

## Design

**Config snapshot (core, `BuildHoundSettingsPlugin`).** Reuse the plan-016/046 **mailbox pattern**:
a `settings.gradle.settingsEvaluated { s -> ... }` callback (public API) snapshots `s.buildCache`
into an `AtomicReference<BuildCacheConfigSnapshot>` holder, exposed via
`settings.providers.provider { holder.get() }` and threaded into the Flow finalizer parameter
(alongside `toolchainHolder`, `BuildHoundSettingsPlugin.kt:271`). Gradle resolves the provider when
it finalizes the finalizer params (after configuration, hence after `settingsEvaluated`), bakes the
plain snapshot into the CC entry, and **replays it on a CC hit** — correct here, because cache
*configuration* is stable across builds (unlike per-build `configurationMs`). Reading
`s.buildCache` at `settingsEvaluated` guarantees the `buildCache{}` block is fully evaluated (never
read at `apply()`, where it may be empty). `isBuildCacheEnabled()` is a CC-safe `StartParameter`
scalar read directly (like `parallel`/`maxWorkers` at `:255-256`). Every read is `runCatching →
warn`, snapshot degrades to `null`. `PayloadAssembler.assemble` gains a `buildCache:
BuildCacheConfigInfo?` param, mapped into the `EnvironmentInfo(...)` block (`:137-151`).

**Commons (additive).** New nullable field on the existing type — a legal additive change (plan-027
`ide`/`aiAgent`, plan-046 toolchain precedent):

```kotlin
@Serializable data class BuildCacheConfigInfo(
    val localEnabled: Boolean? = null,
    val remoteEnabled: Boolean? = null,
    val remotePush: Boolean? = null,
    val remoteType: String? = null,      // normalized backend simpleName, e.g. "HttpBuildCache"; null if none
)
// EnvironmentInfo gains:  val buildCache: BuildCacheConfigInfo? = null
```

> **Divergence (implementation):** the planned `enabled` field (`StartParameter.isBuildCacheEnabled()`)
> was **dropped from `BuildCacheConfigInfo`.** The §Out note anticipated exactly this — *"when [F1's
> `environment.invocation`] lands, this scalar moves there and `buildCache` references it instead of
> double-emitting."* F1's invocation block (**plan 051**) has since landed and already carries
> `invocation.buildCacheEnabled` wired from `settings.startParameter.isBuildCacheEnabled`
> (`BuildHoundSettingsPlugin.kt:366`), so re-emitting it here would be the double-emit the plan set out to
> avoid. `BuildCacheConfigInfo` is new/never-shipped, so removing the field pre-merge is not a schema
> break; a consumer that wants "is the build cache flag on" reads `invocation.buildCacheEnabled` and
> cross-refs `buildCache` for "…and is a remote backend even configured". The transfer-timing schema bump
> was **not** taken: the new `InternalTaskDetail.transferBytes/loadMs/storeMs` are additive-nullable, so
> `InternalAdaptersPayload.SCHEMA_VERSION` stays **1** (the plan permitted this — "optional").

`schemaVersion` stays **1**. `encodeDefaults=true`+`explicitNulls=false` omit the null default, so
`build-payload-v1.json` stays valid untouched; add `build-payload-v1-buildcache.json` for the
populated case. Existing goldens never edited.

**Transfer timings (opt-in module).** In `BuildOperationAdapter.finished`, extend the
`BuildCache{Local,Remote}{Load,Store}` branches (`:86-93`) to read transfer size + duration
reflectively (`callLong`/`callBytes`-style, version-gated like the origin getters), accumulating on
`TaskAccum`. Surface as additive nullable fields on `InternalTaskDetail`
(`InternalAdaptersModel.kt`): `transferBytes`, `loadMs`, `storeMs`. A missing getter degrades to
`null`, never throws (spec §3.1). Bump `InternalAdaptersPayload.SCHEMA_VERSION` **optional** (fields
are additive-nullable); if bumped, add a new adapter golden and leave
`build-payload-v1-internal-adapters.json` untouched. Stays entirely inside the opt-in module
(internal ops) — core never sees it.

**Server ROI rollup.** New `get("/rollups/cache-roi")` in `Routes.kt` (project block), authed with
`call.authenticatedProject(tokens, TokenScope::allowsRead)` exactly like `/rollups/bottlenecks`
(`:476-479`) — token + tenant scoped. `store.cacheRoi(project.id, days, nowMs)` aggregates over the
window's payload `jsonb`: `jsonb_array_elements(payload->'extensions'->'internalAdapters'->'tasks')`
filtered to `origin IN ('LOCAL_HIT','REMOTE_HIT','MISS')`, grouped by build `mode`, yielding
remote-hit rate per mode. The origin lives **only** in the payload jsonb (not the `task_executions`
V4 table), so SQL jsonb extraction is the mechanism — bounded by window size like the bottlenecks
two-window scan; a materialized column is the deferred fallback if it proves costly.

**Two-tier availability (load-bearing).** `CacheOrigin` is **required, not a sharpener** for the
remote-hit rate: core `FROM_CACHE` is undifferentiated local/remote, so with the opt-in module off
there is **no remote-hit rate at all** — never fabricate one from `cacheableHitRate`. The rollup
then reports `remoteHitRateAvailable=false` and degrades to the **config-snapshot** summary (share
of window builds with `remoteEnabled`). With origin data present, it adds the remote-hit rate and
the CI-reuse signal. **CI-reuse is a ranked candidate, not a verdict:** "CI shows near-zero
remote-cache reuse — check cache config" (cold/first-build CI legitimately shows near-zero reuse),
gated on `remoteEnabled=true` in the snapshot to avoid firing on unconfigured fleets. Dashboard
gets one cache-ROI card modeled on plan-032's who-is-behind panel.

## Test strategy

- **Commons unit / golden:** `GoldenPayloadTest` — new `build-payload-v1-buildcache.json`
  round-trips with a populated `environment.buildCache`; assert `build-payload-v1.json` still
  deserializes with `buildCache == null`. Adapter model round-trip for the new transfer fields.
- **TestKit functionalTest (core):** a build with `buildCache { local { isEnabled = true }; remote(HttpBuildCache) { isEnabled = true; isPush = true } }`
  in `settings.gradle.kts` asserts payload `environment.buildCache` = enabled/push/`"HttpBuildCache"`;
  a build with **no** `buildCache{}` block asserts `remoteEnabled == null`/`false` and **no URL/path**
  anywhere in the payload. A `--configuration-cache` store→hit pair asserts the snapshot **replays**
  unchanged on the hit.
- **Adapter unit (Gradle-free):** transfer-timing accumulation + `OriginClassifier` unchanged;
  reflective getters degrade to null on a stubbed result missing the method.
- **Server (Testcontainers):** seed builds carrying `extensions.internalAdapters.tasks[].origin`
  across modes; `/rollups/cache-roi` returns the expected per-mode remote-hit rate and the CI-reuse
  candidate; a project with **no** origin data returns `remoteHitRateAvailable=false` +
  config-snapshot summary; a second tenant's token cannot read the first's ROI (tenant scoping).

## Risks

- **CC safety (config snapshot):** `settingsEvaluated` + `provider { holder.get() }` is a
  config-time-only read baked into the CC entry — no config-phase file read, no new CC fingerprint
  input (plan-016 precedent). The read captures `settings` in a provider lambda whose *value* (a
  plain data class) is serialized, not the live `BuildCacheConfiguration`. **CC-hit replay is
  correct** for stable cache config; the **only** stale case is `StartParameter.isBuildCacheEnabled()`
  when `--build-cache` is toggled on a CC hit (F1's known limitation, shared with the existing
  fingerprint capture) — documented, not fixed.
- **Privacy (§3.7, named):** capture **booleans + normalized type simpleName only**. Never call
  `DirectoryBuildCache.getDirectory()` (absolute path) or `HttpBuildCache.getUrl()` (hostname +
  possible credential). A TestKit assertion greps the whole payload for the configured URL/path and
  fails if present. The remote type is the class `simpleName`, a code identifier, not user data.
- **Additive-schema:** new nullable `buildCache` field + new golden; `schemaVersion` stays 1;
  existing types/goldens untouched. Adapter transfer fields are additive-nullable in the opt-in
  module.
- **Origin-required (not a sharpener):** remote-hit ROI is impossible without the opt-in module;
  degrade to config-snapshot-only, never synthesize a rate from `cacheableHitRate`.
- **Ranked-candidate not confirmed-fix:** near-zero-CI-reuse surfaces as an investigate-candidate
  gated on `remoteEnabled`, never a "misconfigured" verdict (cold CI is legitimate).
- **Isolated-projects (a *positive* here):** the config snapshot is a settings-level read with **no
  internal API and no project walk**, so it survives IP + CC intact. The ROI origin comes from
  execution-time build operations, **not** the plan-016 `whenReady` dictionary, so it does **not**
  inherit the empty-dictionary-under-IP gate — it degrades only when the opt-in module is absent.
- **Multi-tenancy:** `/rollups/cache-roi` is token + tenant-scoped identically to every read route
  (`authenticatedProject`); pinned by a cross-tenant test.
- **Never-fail:** every plugin/adapter read is `runCatching → warn`, snapshot degrades to null; the
  server rollup tolerates builds with no origin data (union/empty, never error).

## Exit criteria

- `environment.buildCache` populated from public `Settings.buildCache` after settings evaluation
  (local/remote enabled, push, normalized type), replaying correctly on a CC hit — pinned by
  functionalTest; no cache dir path or remote URL anywhere in the payload (grep test green).
- Report + dashboard render a per-build "remote cache configured / not configured" badge from the
  payload with no server call.
- Opt-in adapter records per-task transfer bytes/load-ms/store-ms; core stays internal-API-free.
- `/rollups/cache-roi` returns per-mode remote-hit rate + the ranked CI-reuse candidate when origin
  data is present, degrades to config-snapshot-only otherwise, and is tenant-scoped — pinned by
  Testcontainers tests.
- New golden files added, existing goldens untouched; `schemaVersion` still 1.
- `./gradlew build` green.
