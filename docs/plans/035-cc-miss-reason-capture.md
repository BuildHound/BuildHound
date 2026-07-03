# Plan 035 — Configuration-cache miss-reason capture, best-effort

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- [build-telemetry-roadmap.md](../build-telemetry-roadmap.md) Phase 3: "Config-cache
  **miss-reason** capture, best-effort (report-file read in the Flow action; eBay-style
  per-reason frequency rollup server-side)".
- [build-telemetry-spec.md](../build-telemetry-spec.md) §3.2 (Finalizer, CC state
  `HIT | MISS_STORED | DISABLED | INCOMPATIBLE`), §4 (payload `environment` block), §5
  (server rollups over ingested payloads).
- [research/plugin-ecosystem-gap-analysis.md](../research/plugin-ecosystem-gap-analysis.md)
  §1 row 9 and §3 "Config Cache Miss" summarizer: eBay's `configCacheMissReport-P7D`
  proves the rollup's value; BuildHound "records only the CC state enum, no reasons",
  and capture is the hard part — no public Gradle API.
- Additive-schema and file-read-in-Flow patterns as landed in plan 005
  ([implemented/005-payload-assembly.md](implemented/005-payload-assembly.md)) and the
  finalizer's existing file writes (`TelemetryFinalizerAction.kt:127`).

## 2. Scope

**In:**

- Read Gradle's configuration-cache report file inside the Flow action (never at config
  time), parse structured invalidation/miss reasons best-effort, attach a new additive
  `configCacheMiss` field on `EnvironmentInfo`, capped per plan 019.
- Version-gated parser: the report format is not a stable API. Parse only on Gradle
  versions with a pinned fixture; on every other version, and on any parse failure,
  the field is absent. Never fail the build.
- Server-side per-reason frequency rollup (eBay `ConfigCacheMissSummarizer` shape):
  `GET /v1/config-cache/miss-reasons` returning `[{reason, category, count}]` over a
  window, tenant-scoped.
- Golden file for a payload carrying `configCacheMiss`; fixture report files pinned per
  investigated Gradle version (8.14.x, 9.x).

**Out (named where the work lives):**

- Internal-operation cache-key / origin capture and `avoidedMs`/`criticalPathMs`
  — plan 038 (internal-adapters), the sanctioned internal-API exception.
- CC state *classification* changes (`INCOMPATIBLE` detection, refined HIT/MISS
  heuristic) — the shipped `DaemonState` heuristic (`DaemonState.kt`) is unchanged here;
  this plan only enriches an existing `MISS_STORED` with *reasons*.
- Dashboard/HTML rendering of miss reasons beyond the query endpoint — the bottlenecks
  landing page (plan 032) and dashboard panels own presentation; this plan ships the
  data + endpoint only.
- Cardinality/size cap *machinery* is plan 019; this plan consumes it (a dependency).

## 3. Design

**Where.** The report exists only after a store (a `MISS_STORED` build); on a HIT the
configuration phase never runs and Gradle writes no report. The finalizer already
computes CC state at `TelemetryFinalizerAction.kt:209-214` and holds `rootDir`
(`Parameters.rootDir`, wired at `BuildHoundSettingsPlugin.kt:93`). Capture slots into
`execute()` between `ccState` computation and `PayloadAssembler.assemble` — read only
when `ccState == MISS_STORED`, entirely inside the existing top-level `runCatching`
(`TelemetryFinalizerAction.kt:94`) so any failure degrades to a `warn` + absent field.

**Report location & format (best-effort, investigated per version).** Gradle writes a
self-contained `configuration-cache-report.html` under
`<rootDir>/build/reports/configuration-cache/<hash>/<hash>/`. The HTML embeds the model
as a JSON blob assigned to a JS variable (`configurationCacheReportData` / a
`<script type="application/json">` island, exact shape version-dependent). There is **no
public API and no stable file contract** — this is why the roadmap marks it best-effort.
Implementation-time investigation (a numbered step below) pins the exact locator/shape
for each supported Gradle version and captures a real fixture; the parser is a small
tolerant reader keyed off `GradleVersion.current()` (already imported in
`EnvironmentValueSource.kt:15`), returning `null` on any unknown version or shape drift.

**New plugin types** (in `buildhound-gradle-plugin`, Gradle-free where possible so it
unit-tests without TestKit, per plan 004 retro):

- `ConfigCacheReportLocator` — resolves the report file from `rootDir` (newest
  `<hash>/<hash>/configuration-cache-report.html` by mtime); pure `File` logic.
- `ConfigCacheReportParser` — takes the report text + a `GradleVersion`, extracts the
  embedded JSON, maps each invalidation entry to a reason string and a coarse
  `category` (e.g. `input-file-changed`, `build-logic-input`, `undeclared-input`,
  `unsupported-type`, `other`), deduplicates, and applies the plan-019 caps (max N
  reasons, max length per reason, scrubbed via `PayloadScrubber.scrubText` because
  reasons routinely embed absolute paths). Returns `CollectedConfigCacheMiss?`.
- `CollectedConfigCacheMiss` — plain `Serializable` DTO the finalizer maps onto the
  schema, mirroring the `CollectedEnvironment` pattern.

**Schema (additive, `buildhound-commons`).** New nullable field on `EnvironmentInfo`
(`BuildPayload.kt:54`), defaulted so old plugins/servers keep working:

```kotlin
val configCacheMiss: ConfigCacheMissInfo? = null   // added to EnvironmentInfo

@Serializable
data class ConfigCacheMissInfo(
    val reportFormat: String? = null,          // e.g. "gradle-8.14", version-tagged like kotlin.reportSchema
    val reasons: List<ConfigCacheMissReason> = emptyList(),
    val truncated: Boolean = false,            // caps hit → true
)

@Serializable
data class ConfigCacheMissReason(
    val category: String,                      // coarse bucket, low-cardinality
    val message: String,                       // scrubbed, capped
    val count: Int = 1,                        // occurrences within this build
)
```

No existing field or enum is edited; `SCHEMA_VERSION` stays 1 (additive within the
major, architecture §3 rule 2).

**Server rollup.** Add `configCacheMissReasons(projectId, days, nowMs)` to `BuildStore`
(`BuildStore.kt:47`), returning a per-reason frequency list ranked by count. In-memory
impl aggregates over stored payloads' `environment.configCacheMiss.reasons`, keying on
`(category, message)`; Postgres impl computes it with a jsonb aggregation (deferred to
the Postgres path already behind the interface). Surface it read-scoped through
`queryRoutes` (`Routes.kt:107`) as `GET /v1/config-cache/miss-reasons?days=N`, mirroring
the `/trends` shape and its `days` coercion.

**Data flow.** apply-time: nothing new (no config-time file read — architecture §2
rule 9). Execution/Flow: `MISS_STORED` → locate report → parse (version-gated) → scrub +
cap → `CollectedConfigCacheMiss` → `EnvironmentInfo.configCacheMiss` → payload → upload.
Server ingest is unchanged (tolerant of the new field via `ignoreUnknownKeys`); the
rollup reads it back on query.

## 4. Implementation steps

1. **Investigate report formats.** Run a forced CC miss on Gradle 8.14.4 and 9.6.1 (the
   two CI matrix versions, `.github/workflows/ci.yml`), plus one 9.x mid-point; capture
   each real `configuration-cache-report.html`. Document the embedded-JSON locator and
   the invalidation-entry shape per version in the plan (update this file in the PR).
   Record which versions are "supported" (have a fixture) vs "degrade to absent".
2. **commons schema:** add `ConfigCacheMissInfo` + `ConfigCacheMissReason` and the
   `configCacheMiss` field on `EnvironmentInfo` (`BuildPayload.kt`). Defaults only.
3. **commons golden file:** add `build-payload-v1-cc-miss.json` under
   `jvmTest/resources/golden/` and a `GoldenPayloadTest` case that deserializes it and
   asserts the reasons/category/truncated fields. Never edit `build-payload-v1.json`.
4. **plugin `ConfigCacheReportLocator`:** resolve newest report file under
   `<rootDir>/build/reports/configuration-cache`; return `null` when absent.
5. **plugin `ConfigCacheReportParser` + `CollectedConfigCacheMiss`:** version-gated
   extraction → category mapping → dedup → `PayloadScrubber.scrubText(msg, roots)` →
   plan-019 caps (set `truncated`). Return `null` on any unknown version / parse failure.
6. **plugin wiring:** in `TelemetryFinalizerAction.execute`, when `ccState ==
   MISS_STORED`, call locator+parser (inside the existing `runCatching`), thread the
   result through `PayloadAssembler.assemble` into `EnvironmentInfo.configCacheMiss`.
   Pass `GradleVersion.current()` and the already-computed `scrubRoots(rootDir)`.
7. **plugin `PayloadAssembler`:** add the `configCacheMiss` parameter and map it onto
   `EnvironmentInfo` (the field is already scrubbed by step 5, so no double-scrub).
8. **server `BuildStore`:** add `configCacheMissReasons(...)` to the interface +
   `InMemoryBuildStore` aggregation; add the jsonb aggregation to `PostgresStores`.
9. **server route:** add read-scoped `GET /v1/config-cache/miss-reasons` in
   `queryRoutes` with `days` coercion mirroring `/trends`; register in the module.
10. **docs:** note the new field in the spec §3.2/§4 CC text (additive), and add a
    decision-log row to `architecture.md` §7 recording that CC-report parsing is an
    accepted best-effort, version-gated, non-API dependency that degrades to absent
    (this is the "assumption change" the guardrails require documented in-PR).

## 5. Test strategy

- **commons unit (`ConfigCacheMissInfoTest`):** round-trip serialization; unknown-field
  tolerance; empty-reasons default.
- **commons golden:** `build-payload-v1-cc-miss.json` deserializes and round-trips
  losslessly (extends `GoldenPayloadTest`).
- **plugin unit (`ConfigCacheReportParserTest`, pure — no Gradle):** parse each pinned
  fixture (8.14.4, 9.x) → expected reasons/categories; a fixture from an *unrecognized*
  version → `null`; a corrupt/truncated HTML → `null`; a reason containing an absolute
  path is scrubbed; over-cap reason list sets `truncated=true` and drops the tail.
- **plugin TestKit functional (`ConfigCacheMissFunctionalTest`):** run a build that
  stores CC, then force a miss on the second run (e.g. change a captured input) and
  assert the payload's `environment.configCacheMiss.reasons` is non-empty; assert a
  clean HIT build carries `configCacheMiss == null`; assert `DISABLED` (CC off) carries
  null. Run under the {8.14, 9.latest} matrix already in the functional source set.
- **failure-injection (phase guardrail):** unreadable/locked report file and a report
  present but unparseable both yield a valid payload with `configCacheMiss == null`, a
  single `warn`, and a *green build* — assert the build succeeds and no failure marker
  is written for this path (the reason is swallowed inside the finalizer's `runCatching`).
- **server:** `InMemoryBuildStore` rollup over several payloads groups by
  `(category, message)` and ranks by count; `GET /v1/config-cache/miss-reasons` requires
  a read-scoped token (401/403 paths), honors `days`, and is tenant-isolated; Postgres
  path covered by a Testcontainers case mirroring `PostgresStoresIntegrationTest`.

## 6. Risks

- **Format instability (primary risk).** The report is not an API; a Gradle upgrade can
  silently change the shape. Mitigation: version-gate on `GradleVersion.current()`, ship
  a fixture per supported version, and default to *absent* — a new Gradle version yields
  no data rather than wrong data or a crash. A CI canary test that parses each matrix
  version's real report catches drift when the wrapper/matrix bumps.
- **CC safety.** No configuration-phase file access is added (architecture §2 rule 9):
  the report is read only in the Flow action at execution time, from the `rootDir`
  already threaded through parameters — nothing new becomes a CC fingerprint input.
- **Isolated projects.** A store under `-Dorg.gradle.unsafe.isolated-projects` still
  produces a report. If a future IP mode relocates or omits it, the locator returns
  `null` and the field is absent — same graceful path; the non-blocking IP CI job
  (plan 021) watches for regressions.
- **Schema compatibility.** Field is additive and nullable; golden files are added, not
  edited; `SCHEMA_VERSION` unchanged; server tolerates it via `ignoreUnknownKeys`.
- **Privacy (dedicated review touchpoint).** CC miss reasons routinely embed absolute
  paths and build-logic file names — the free-text hazard §3.7 targets. Every reason is
  routed through `PayloadScrubber.scrubText` before entering the payload; `category` is
  a fixed low-cardinality vocabulary, never free text. No new PII.
- **Cardinality/size (in code).** Reason count and per-reason length are capped per
  plan 019 *in the parser*; `truncated` records a fired cap. The fixed `category`
  vocabulary bounds the server rollup's group-by.
- **Security.** The endpoint is read-scoped and tenant-filtered like `/trends` — no new
  ingest surface, no new authz path; reason text is stored as already-scrubbed strings
  in jsonb.

## 7. Exit criteria

- On a supported Gradle version, a build that stores the configuration cache produces a
  payload whose `environment.configCacheMiss.reasons` is non-empty and scrubbed; a HIT
  build and a CC-disabled build both carry `configCacheMiss == null`.
- On an unsupported Gradle version, or with an unreadable/unparseable report, the build
  is green, one `warn` is logged, and `configCacheMiss` is absent — no failure marker.
- The v1 golden file is untouched; a new `build-payload-v1-cc-miss.json` golden passes
  round-trip; contract tests stay green; `SCHEMA_VERSION` is still 1.
- `GET /v1/config-cache/miss-reasons` returns per-reason frequencies over the window,
  requires a read token, and is tenant-isolated (in-memory and Postgres).
- `docs/architecture.md` decision log records the best-effort, version-gated CC-report
  dependency; the spec CC text mentions the additive field.
- `./gradlew build` and the {8.14, 9.latest}×{CC on/off} functional matrix are green.
