# Plan 031 — APK/AAB/AAR size capture + trend view (spec §4 artifacts)

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- Roadmap [phase 3](../build-telemetry-roadmap.md): "APK/AAB size capture using
  AndroidArtifactsSizeReport's `onVariants`/`toListenTo` mechanics → spec §4 `artifacts`
  field + trend view."
- Spec [§4](../build-telemetry-spec.md) declares the payload field
  `"artifacts": { "apk": [ { "variant": "release", "sizeBytes": 0, "type": "AAB" } ] }`;
  [§5](../build-telemetry-spec.md) lists an `apk_sizes` core table and PR-vs-baseline
  APK-size delta; [§6](../build-telemetry-spec.md) Trends page.
- Research: [AndroidArtifactsSizeReport.md](../research/repos/AndroidArtifactsSizeReport.md)
  (reference mechanics, CC- and isolated-projects-proven on Gradle 9.2.1) and
  [comparison-to-spec.md §2.3](../research/comparison-to-spec.md) (adopt the mechanics
  wholesale; emit structured records, not filename-encoded keys; react to AGP from the
  settings context; do not delete task outputs).

## 2. Scope

**In:**

- Additive schema: an `artifacts` block on `BuildPayload` (`ArtifactSizes` + `ArtifactSize`
  records `{variant, module, type, sizeBytes}`), nullable and defaulted so it stays additive
  at `SCHEMA_VERSION = 1` (no version bump); a new golden file is added (v1 golden untouched).
- An **optional AGP-only collector**, wired from the settings plugin, that reacts to
  `com.android.application` / `com.android.library` per project via
  `use().wiredWith().toListenTo(SingleArtifact.APK/BUNDLE/AAR)` + `BuiltArtifactsLoader`,
  writes structured size records to a well-known build dir, and is read by the existing
  Flow finalizer. Inert on non-Android builds by class-lookup — the plugin must not require
  AGP on its classpath.
- Server: an `apk_sizes` hot-column table (spec §5) populated on ingest from the payload,
  and a `GET /v1/artifacts/trends` endpoint returning per-(variant, module, type) size
  series.
- Trend view: an artifact-size panel on the dashboard Trends page and an artifacts section
  in the standalone HTML report (current-build sizes; the historical series is dashboard-only
  since the artifact is one build).
- Docs same PR: spec §4 artifacts example aligned to the record shape actually emitted;
  architecture decision-log row for the AGP-optional-collector pattern and the additive
  `artifacts` field at schema v1.

**Out:**

- Artifact **composition** breakdown (dex/resources/native-lib split), download-size
  estimate, per-artifact diffing — none are in spec §4; not planned.
- PR-vs-baseline APK-size **regression verdict** and budgets — that is the regression engine
  (plan 025); this plan only lands the data + raw trend.
- Bottlenecks/"what regressed" landing surfacing of size regressions (plan 032).
- Task `type`/`cacheable` capture (plan 016), timeline (plan 017), cardinality/size caps on
  the task array (plan 019) — independent; the artifacts array gets its own small cap here.
- iOS/other non-Android artifacts — Android only in v1 (spec is Android/KMP-scoped).

## 3. Design

**Schema (commons, additive).** `BuildPayload` today ends at `derived`
(`buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt:29`)
and has no artifacts field — spec §4 declares it but schema v1 never carried it. Add:

```kotlin
@Serializable data class ArtifactSize(
    val variant: String, val module: String? = null,
    val type: ArtifactType, val sizeBytes: Long,
)
@Serializable enum class ArtifactType { APK, AAB, AAR }
@Serializable data class ArtifactSizes(val android: List<ArtifactSize> = emptyList())
```

and `val artifacts: ArtifactSizes? = null` on `BuildPayload`; `SCHEMA_VERSION` stays `1`.
Spec §4 nests under `apk`; we emit `android` because the list mixes
APK/AAB/AAR and the `type` field already disambiguates — the spec example is corrected to
match in the same PR (recording the drift, per
[comparison-to-spec.md §4 item 12](../research/comparison-to-spec.md)). Additive rules
(architecture §3.2): the field defaults to `null`, so an old payload with no `artifacts`
key decodes unchanged and a new payload's block is simply ignored by any older reader via
`ignoreUnknownKeys` — no version change is needed. The v1 golden is never edited; a new
golden carrying an `artifacts` block is added alongside it. Because the constant is
untouched, ingest's `schemaVersion <= SCHEMA_VERSION` acceptance (`Routes.kt:69-74`) is
unaffected and older servers tolerate the unknown block by construction.

**Collector — the AGP coupling problem.** BuildHound is a *settings* plugin applied once
(`BuildHoundSettingsPlugin.apply`, `BuildHoundSettingsPlugin.kt:26`); AndroidArtifactsSizeReport
is a *project* plugin applied per module. The reference `onVariants`/`toListenTo` wiring
needs a `Project` and the AGP `AndroidComponentsExtension`, neither of which the settings
plugin has, and AGP is deliberately **not** on the plugin classpath (the plugin must apply
cleanly to non-Android builds — architecture §2, comparison-to-spec §2.3). Design:

1. From `apply()`, register a per-project reaction with
   `settings.gradle.lifecycle.beforeProject { project -> ... }` (the CC/isolated-projects-safe
   settings-side hook; no `Project` is captured across the config-cache boundary — the
   callback runs per project during configuration). This is new to the plugin: nothing today
   uses `beforeProject` (`grep` confirms only `logger.lifecycle` call sites exist).
2. Inside the reaction, probe AGP by class-lookup —
   `runCatching { Class.forName("com.android.build.api.variant.AndroidComponentsExtension") }`
   — and no-op silently if absent (the reference's `Class.forName` guard,
   AndroidArtifactsSizeReport §Architecture step 1). All AGP-touching code lives in a
   separate class (`AndroidArtifactCollector`) loaded only after the probe succeeds, so the
   settings plugin's own classes never link AGP symbols at load time.
3. `AndroidArtifactCollector` uses `project.plugins.withType(AppPlugin/LibraryPlugin)` and
   the AGP `ApplicationAndroidComponentsExtension.onVariants` /
   `LibraryAndroidComponentsExtension.onVariants` to register one measuring task per variant,
   wired `variant.artifacts.use(task).wiredWith { it.input }.toListenTo(SingleArtifact.APK|BUNDLE|AAR)`.
   AGP types are referenced through `compileOnly` (never `implementation`) so no AGP jar
   ships with the plugin.

**Measuring tasks.** A `SizeReportTask` per artifact kind. APK uses
`BuiltArtifactsLoader` (`variant.artifacts.getBuiltArtifactsLoader()`, `@Internal`) to
enumerate every output (ABI/density splits); AAB/AAR read the single `@InputFile`. Each task
writes **one JSON line per artifact** (`{module, variant, type, sizeBytes}`, size from
`File.length()`) into `<rootDir>/build/buildhound/artifacts/` — a shared, well-known dir
rooted at the *root* build dir so the finalizer finds every module's output in one place.
Deviations from the reference's pitfalls (AndroidArtifactsSizeReport §Limitations): outputs
are declared `@OutputFile`/`@OutputDirectory` and never wiped or deleted post-hoc (so tasks
stay UP-TO-DATE and don't mutate outside execution); the module/variant/type are structured
fields, not regex-decoded key strings; file names come from `File`/`Path` APIs, never
`substringAfterLast("/")` (Windows-safe). Path-sensitive inputs use `@PathSensitive(RELATIVE)`.

**Finalizer read.** `TelemetryFinalizerAction.execute` (`TelemetryFinalizerAction.kt:93`)
already assembles the payload from collectors. Add a step that reads the artifacts dir
(under `rootDir`, already a parameter — `TelemetryFinalizerAction.kt:93`,
`BuildHoundSettingsPlugin.kt:93`), parses the JSON lines into `ArtifactSize` records, and
passes them to `PayloadAssembler.assemble`, which sets `payload.artifacts`. Missing/empty dir
→ `artifacts = null` (never `[]` vs `null` ambiguity: null means "not an Android build /
nothing produced"). The read is wrapped in the finalizer's existing `runCatching` so a parse
failure degrades to a `warn` + marker, never a failed build (hard rule, architecture §2).
The dir sits under `build/buildhound/` alongside the payload; no config-phase file access is
added (architecture §2 rule 9 — the read happens in the Flow action at execution time).

**Cardinality/size budget (in code, not docs — guardrail).** `PayloadAssembler` caps the
artifacts list (e.g. 200 records) and truncates deterministically (largest-first) if a
pathological multi-flavor project exceeds it, so the field cannot blow the payload budget.

**Server ingest + storage.** A new migration `V{n}__artifacts.sql` (claim the next free
version integer at implementation time — plans 025/026/028/031/036/037/039 all add
migrations, so the merge order determines numbering; renumber deterministically to the next
free `V{n}` when merging) adds
`apk_sizes (project_id, build_id, started_at, module, variant, type, size_bytes)` — spec §5's
`apk_sizes` table, tenant-scoped like every table (architecture §5), FK to `builds`.
`PostgresBuildStore.save` (`PostgresStores.kt:37`) inserts one row per `payload.artifacts?.android`
record inside the existing insert path (idempotent: rows are `DELETE`d by (project, build_id)
then re-inserted, or `ON CONFLICT` no-op'd, matching the build's `ON CONFLICT DO NOTHING`
dedupe at `PostgresStores.kt:44`). The full payload still rides in `builds.payload` jsonb;
`apk_sizes` is the hot-query projection for trends, exactly as `builds.hit_rate`/`branch` are
today.

**Trend endpoint.** Add `store.artifactTrends(projectId, filter, days, nowMs)` to
`BuildStore` (`BuildStore.kt:47`) returning `List<ArtifactTrendPoint>`
(`{day, module, variant, type, avgSizeBytes, maxSizeBytes, builds}`), grouped by day and by
(module, variant, type), mirroring the existing daily `trends` rollup shape
(`BuildStore.kt:59`, `PostgresStores.kt:136`). Route `GET /v1/artifacts/trends` in
`queryRoutes` (`Routes.kt:107`) with the same `authenticatedProject(..., allowsRead)`,
`buildFilterOrNull`, and `days` coercion the existing `/v1/trends` uses (`Routes.kt:127-133`).
In-memory store gets the parallel implementation for tests/DB-less dev
(`InMemoryBuildStore`, `BuildStore.kt:82`).

**Dashboard trend view.** `web/dashboard.js` `trendsView` (`dashboard.js:206`) gains an
"Artifact sizes" panel that fetches `/v1/artifacts/trends`, groups series by
(module/variant/type), and renders each with the **existing** `trendChart(points, valueOf,
color, formatValue)` helper (`dashboard.js:182-204`) — one line per artifact series, a
bytes→MB formatter, no new charting code. Empty response → the panel renders a muted "No
Android artifacts in range" line (contextual empty state, consistent with plan 018's
direction). Payload-derived strings (module/variant) reach the DOM via `textContent`/`<title>`
only, per the dashboard's untrusted-data rule (`dashboard.js:2-3`); the CSP is unchanged
(`DashboardRoutes.kt:29-40`).

**HTML artifact section.** The standalone report shows the *current* build's artifact sizes
as a small sorted table (module, variant, type, size) — one build has no series. It reads
`payload.artifacts.android` already embedded in the report's JSON blob
(`ReportAssets.render`, `ReportAssets.kt:24`); the section hides when the array is
absent/empty (the zero-network guarantee and escaping are unchanged — same embedded blob).

## 4. Implementation steps

1. **commons schema:** add `ArtifactSize`, `ArtifactType`, `ArtifactSizes` to `BuildPayload.kt`;
   add `artifacts: ArtifactSizes? = null` to `BuildPayload`; `SCHEMA_VERSION` stays `1`
   (additive nullable field — no bump).
2. **Golden file:** add a new golden (e.g.
   `buildhound-commons/src/jvmTest/resources/golden/build-payload-artifacts.json`)
   containing an `artifacts.android` array (APK + AAB + AAR examples); extend
   `GoldenPayloadTest` with a case asserting the artifacts decode. Leave
   `build-payload-v1.json` and its assertions untouched (contract: golden files are
   never edited — `GoldenPayloadTest.kt:8`); add a case proving the existing artifacts-free
   v1 golden still decodes under the updated model with `artifacts == null` (backward
   compatibility).
3. **plugin — presence probe + reaction:** in `BuildHoundSettingsPlugin.apply`, add
   `settings.gradle.lifecycle.beforeProject { ... }` that class-probes AGP and, on success,
   delegates to `AndroidArtifactCollector(project, artifactsDir)`; gated behind
   `extension.enabled`. The dir is `File(settings.rootDir, "build/buildhound/artifacts")`.
   Included-build guard (`BuildHoundSettingsPlugin.kt:38`) already returns early for
   non-root builds — unchanged.
4. **plugin — collector:** add `AndroidArtifactCollector.kt` (AGP `compileOnly`): `onVariants`
   registration of `SizeReportTask` instances wired via `toListenTo(SingleArtifact.APK/BUNDLE/AAR)`;
   `BuiltArtifactsLoader` for APK enumeration; JSON-line output to the shared dir; no output
   deletion. Add `SizeReportTask.kt` with `@InputFile`/`@InputDirectory` + `@PathSensitive`,
   `@OutputFile`, and `@Internal` loader.
5. **plugin — build script:** add AGP `compileOnly` to `buildhound-gradle-plugin/build.gradle.kts`
   (coordinate `com.android.tools.build:gradle-api`; **look up the latest released version at
   implementation time** from Maven Central metadata — do not pin from memory) and add the
   version to `gradle/libs.versions.toml` (the only place versions live). `compileOnly` keeps
   AGP out of the shipped plugin.
6. **plugin — finalizer:** add an artifacts-read step in `TelemetryFinalizerAction.execute`
   (inside the existing `runCatching`) that parses the artifacts dir and passes records to
   `PayloadAssembler.assemble`.
7. **plugin — assembler:** `PayloadAssembler.assemble` gains an `artifacts: List<ArtifactSize>`
   parameter, applies the size/cardinality cap, and sets `payload.artifacts`
   (`null` when empty). Update `PayloadAssemblerTest` for the cap + null-when-empty behavior.
8. **server — migration:** `V{n}__artifacts.sql` (claim the next free version integer at
   implementation time — see the storage note above) creating `apk_sizes` (tenant-scoped, FK
   to `builds`, index on `(project_id, started_at)`).
9. **server — store:** `PostgresBuildStore.save` inserts `apk_sizes` rows; add
   `artifactTrends(...)` to both `PostgresBuildStore` and `InMemoryBuildStore`; add
   `ArtifactTrendPoint` + the `BuildStore.artifactTrends` interface method.
10. **server — route:** `GET /v1/artifacts/trends` in `queryRoutes`, read scope, same filter
    + days handling as `/v1/trends`.
11. **dashboard:** add the artifact-size panel to `trendsView` using `trendChart`; bytes→MB
    formatter; muted empty state.
12. **HTML report:** add the current-build artifacts table to `report-template.html`, hidden
    when the array is absent.
13. **docs, same PR:** correct spec §4's artifacts example to the emitted record shape and the
    `android` key; architecture decision-log rows for (a) the AGP-optional collector pattern
    (`beforeProject` + class-probe + `compileOnly`, plugin never requires AGP) and (b) adding
    the `artifacts` field as an additive nullable at schema v1 (no version bump).

## 5. Test strategy

- **commons golden/contract:** the new artifacts golden decodes with the artifacts array; the
  existing v1 golden decodes with `artifacts == null`; round-trip lossless for a payload
  carrying artifacts; unknown-field tolerance test unchanged (`GoldenPayloadTest`).
- **commons unit:** `ArtifactSize` serialization; enum names APK/AAB/AAR are stable serial
  names.
- **plugin unit (Gradle-free, `PayloadAssemblerTest`):** empty artifacts → `artifacts == null`;
  over-cap list truncated largest-first to the bound; records passed through unchanged under
  the cap.
- **plugin functionalTest (TestKit):**
  - *Inertness on non-Android builds* — the existing single-`:hello` fixture
    (`BuildHoundSettingsPluginFunctionalTest.kt:26`) still produces a payload with
    `artifacts == null` and applies cleanly with **no AGP on the classpath** (the core
    never-fail/never-require-AGP guarantee).
  - *Android build* — a minimal Android fixture (or the `samples/nowinandroid` app,
    AGP 9.0.0) assembling a debug variant produces a payload whose `artifacts.android`
    carries a non-zero APK size for the variant, under `--configuration-cache` (CC-safe),
    matching AndroidArtifactsSizeReport's proven store/reuse pattern; a second run reuses the
    configuration cache and still emits sizes (the reference's isolated-projects/CC evidence
    is the template — mirror its two-build store-then-reuse assertion).
  - *Failure injection (phase guardrail)* — a corrupt/locked artifacts dir makes the read
    fail; the build still succeeds, the finalizer warns, and the failure marker is written
    (extends the existing marker test, `:374`).
- **server (Testcontainers + Ktor testApplication):** ingesting a payload with artifacts
  populates `apk_sizes`; `GET /v1/artifacts/trends` returns per-(module,variant,type) daily
  points; read scope enforced (403 for ingest-only token, like the existing trends test);
  re-ingest of the same buildId is idempotent (no duplicate `apk_sizes` rows).
- **dashboard smoke (`dashboard-smoke.js`):** the trends view renders an artifact panel from a
  stubbed response and a muted empty state when the array is empty.

## 6. Risks

- **CC / isolated projects.** `gradle.lifecycle.beforeProject` is the sanctioned settings-side
  per-project hook and captures no `Project` across the CC boundary; the AGP `toListenTo`
  pattern is proven CC- **and** isolated-projects-safe on Gradle 9.2.1 by the reference's E2E
  test (comparison-to-spec §2.3). Residual: our `beforeProject` reaction is new plugin
  surface — the Android functional test runs under `--configuration-cache` (store + reuse),
  and the isolated-projects CI leg (plan 021) exercises it. Degradation contract: on isolated
  projects the collector still works per-project (no cross-project reads); if AGP wiring ever
  fails under IP, artifacts degrade to `null`, never a build failure.
- **AGP not on classpath / non-Android builds.** The class-probe + isolating all AGP symbols
  in `AndroidArtifactCollector`/`SizeReportTask` (loaded only after the probe) means the
  settings plugin's own classes never link AGP; the inertness functional test pins this.
  `compileOnly` keeps AGP out of the published artifact.
- **AGP API drift.** `SingleArtifact`, `onVariants`, and `BuiltArtifactsLoader` are AGP public
  Variant/Artifacts APIs (not internal — architecture §2 rule 4 respected). Different AGP
  majors can shift signatures; the collector is guarded so a `NoSuchMethodError`/link failure
  degrades to no artifacts, not a broken build. The compatibility matrix (plan 021) should add
  an AGP version to watch; a Renovate trigger (comparison-to-spec §3 item 14) is a cheap
  follow-up.
- **Schema compatibility.** The `artifacts` field is additive and nullable, so
  `SCHEMA_VERSION` stays `1`: the field defaults to `null`, the v1 golden is never edited, and
  `ignoreUnknownKeys` lets an older server silently ignore the new block. Because the constant
  is untouched, ingest's `schemaVersion > SCHEMA_VERSION` guard (`Routes.kt:69`) never rejects
  an artifacts-bearing payload — forward-compat is by construction. Contract tests enforce.
- **Security/privacy.** New data is artifact **byte sizes** only — no paths, no file contents,
  no identity (spec §3.7 respected). `module`/`variant` are project-internal Gradle names
  (`:app`, `release`), not PII, and are low-cardinality; they reach the dashboard DOM via
  `textContent`/`<title>` only. The size records are structured declared fields — the free-text
  scrubber does not apply, and nothing new flows to logs. `apk_sizes` is tenant-scoped like
  every table; the trend endpoint reuses the existing read-scope auth. No new token/secret
  surface.
- **Cardinality/payload budget.** The artifacts list is capped in the assembler (guardrail:
  budgets in code); a pathological flavor matrix truncates deterministically rather than
  bloating the payload.

## 7. Exit criteria

- A real Android build (the pilot / `samples/nowinandroid`) produces a payload whose
  `artifacts.android` carries a non-zero APK size per assembled variant, under configuration
  cache (store and reuse), and the standalone HTML report shows those sizes offline.
- A non-Android build (and this repo's own build) applies the plugin cleanly with no AGP on
  the classpath and emits `artifacts == null`; the finalizer never fails the build even when
  the artifacts read is made to fail (marker written).
- On the compose stack, ingesting artifact-bearing builds populates `apk_sizes`, and the
  dashboard Trends page renders an artifact-size series per (module, variant, type) from
  `GET /v1/artifacts/trends`, with a muted empty state when there is no Android data.
- `./gradlew build` green; the new artifacts golden is added and the v1 golden is unchanged
  (`SCHEMA_VERSION` still `1`); spec §4 and the architecture decision log are updated in the
  same PR.
