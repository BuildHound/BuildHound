# Plan 023 — Kotlin build-report bundling + Kotlin dashboard panel

**Status: planned — roadmap phase 2b** · 2026-07-03

## 1. Source

- [Spec §3.4](../build-telemetry-spec.md) (Kotlin build reports: validate gradle.properties
  wiring, never mutate KGP properties, bundle in the Finalizer, schema-version tagged),
  [§4](../build-telemetry-spec.md) (`kotlin` payload field), [§6](../build-telemetry-spec.md)
  (Kotlin dashboard view), [§3.7](../build-telemetry-spec.md) (scrubbing of new free text).
- [Roadmap phase 2b](../build-telemetry-roadmap.md): "Kotlin build-report bundling (json dir)
  + Kotlin dashboard panel."
- Research: [comparison-to-spec.md](../research/comparison-to-spec.md) §2.3/§5 item 5 (zero
  prior art — greenfield collector over an unstable format) and §4 item 10 (specify the
  format-tolerance strategy *before* the bundler lands: name-keyed lookup, unknown-field
  tolerance, per-version fixtures); [plugin-ecosystem-gap-analysis.md](../research/plugin-ecosystem-gap-analysis.md)
  §7 (Kotlin reports listed as spec'd-not-built).

## 2. Scope

**In:**

- `buildhound { kotlinReports { bundle = true } }` DSL block (default true).
- Read (never write) the KGP wiring from Gradle properties `kotlin.build.report.output`
  and `kotlin.build.report.json.directory`; emit a copy-paste fix at `warn` when bundling
  is on, wiring is absent, and the build shows Kotlin compilations (gating below).
- `KotlinReportParser` in the plugin: lenient, name-keyed, allowlist-only parser over the
  KGP json report files; per-KGP-version fixtures.
- Additive schema v1 fields in commons: `BuildPayload.kotlin: KotlinInfo?` with
  `KotlinInfo(reportSchema, perTask)` per spec §4; new golden file (existing one untouched).
- Scrubber coverage for the new free-text field (`nonIncrementalReasons`).
- Cardinality/size bounds enforced in code (counts below), honoring plan 019's budgets.
- Dashboard build-detail **Kotlin panel**: compiler phase times, slowest compilations,
  incremental-compilation effectiveness. Absent data ⇒ no panel.

**Out:** Kotlin panel in the standalone HTML artifact (spec §3.8 — a later artifact
iteration once this payload field exists, alongside plan 017's timeline reuse) ·
aggregate Kotlin trends/rollup page (server rollup territory, plan 026 family) ·
`POST /v1/kotlin-report` raw HTTP-report endpoint (spec §5, unowned, later) ·
`toolchain.kgp` population (spec §3.2 EnvironmentCollector; today only gradle/jdk are set,
`PayloadAssembler.kt:67`) · Kotlin daemon JVM stats (plan 029) · task type/cacheable
(plan 016) · global payload caps (plan 019 — this plan only bounds its own section).

## 3. Design

**Data flow.** Settings apply captures the two KGP Gradle properties as providers
(pattern precedent: `buildhound.vcs.timeout.ms`, `BuildHoundSettingsPlugin.kt:66-68`) and
passes them plus `kotlinReports.bundle` into the Flow-action parameters
(`BuildHoundSettingsPlugin.kt:78-100`). At build end, `TelemetryFinalizerAction.execute`
(`TelemetryFinalizerAction.kt:93-174`) reads the configured json directory, parses the
report files, and hands the resulting `KotlinInfo?` to `PayloadAssembler.assemble`, which
already scrubs the whole payload once at assembly (`PayloadAssembler.kt:74-75`) — so the
local file, HTML artifact, and upload all see the same scrubbed `kotlin` section for free
(`TelemetryFinalizerAction.kt:127-166`).

**Reading the directory.** The bundler reads *whatever directory the property points at* —
it does not require the "expected dir" the spec's copy-paste hint suggests (mild
divergence from §3.4 wording; requiring an exact path punishes teams with existing report
wiring for no gain — spec text amended in the same PR). KGP appends timestamped `.json`
files across builds, and KGP writes its report from its own build-finish machinery whose
ordering against our FlowAction is unspecified, so two guards apply: only files with
`lastModified >= payload.startedAt − 60 s` are candidates (the margin covers configuration
time before the first task start, `PayloadAssembler.kt:45`), and "no in-window file yet"
degrades to `kotlin = null` — stale data from a previous build is never bundled; absent
beats wrong. Bounds: ≤ 20 files, ≤ 10 MiB each; excess skipped with one `warn`.

**Parser tolerance (research §4 item 10, decided here).** The KGP json report is an
explicitly unstable internal format. The parser therefore: decodes to `JsonElement` (no
`@Serializable` mirror of KGP types), extracts by field *name* with every field optional,
ignores unknown fields, and never indexes by position. Allowlisted subset per task record:
task path, total duration, incremental flag, non-incremental/rebuild reason names
(enum-shaped in KGP), name-keyed compiler phase times, lines of code. Deliberately **not**
bundled: compiler arguments, changed-file lists, and IC log lines — they carry absolute
paths and classpaths (spec §3.7). A parse failure of one file or record skips it; the
whole bundling path is internally `runCatching`-guarded so it can only ever produce
`null`, never fail the finalizer (architecture §2 rule 3).

**Schema (additive, spec §4 shape):**

```kotlin
@Serializable data class KotlinInfo(
    val reportSchema: String? = null,          // version tag from the report, else "unknown"
    val perTask: List<KotlinTaskReport> = emptyList(),
    val truncatedTasks: Int = 0,               // how many records the cap dropped
)
@Serializable data class KotlinTaskReport(
    val taskPath: String,
    val durationMs: Long? = null,
    val incremental: Boolean? = null,
    val nonIncrementalReasons: List<String> = emptyList(),
    val compilerTimesMs: Map<String, Long> = emptyMap(),
    val linesOfCode: Long? = null,
)
```

`BuildPayload` gains `val kotlin: KotlinInfo? = null` (`BuildPayload.kt:12-34`). Old
servers tolerate it via `ignoreUnknownKeys` (`BuildHoundJson.kt:11-15`); old plugins omit
it (default null, `explicitNulls = false`). Bounds in code: `perTask` ≤ 200 (top by
duration, remainder counted in `truncatedTasks`), ≤ 10 reasons/task at ≤ 200 chars,
≤ 32 `compilerTimesMs` keys at ≤ 64 chars.

**Warning gating (spec §3.4 "warns if absent", made non-noisy).** With `bundle = false`:
silent. Output includes `json` + directory set: read it. Output includes `json` but no
directory: `warn` + fix (KGP itself requires the directory). Otherwise: `warn` + copy-paste
gradle.properties block **only when the build ran Kotlin compilations** — detected from
`TaskExecution.type` containing `KotlinCompile` once plan 016 populates it, falling back
to a task-name heuristic (`compile*Kotlin*`) that gates only this log line, never payload
data. Non-Kotlin projects stay silent (roadmap: degrade to absent, never nag).

**Dashboard.** The detail view already renders from the full payload returned by
`GET /v1/builds/{buildId}` (`Routes.kt:118-125`, `dashboard.js:120-173`), so the panel is
pure client-side — zero server/store change. When `build.kotlin.perTask` is non-empty,
`detailView` appends a "Kotlin" section: chips (compilations, incremental share of
compilations and of Kotlin compile time — the effectiveness numbers), a slowest-compilations
table (path, duration, incremental, top reasons), and summed top compiler phase times.
All values via `textContent` only (plan 012 discipline, `dashboard.js:1-2`); explicit
zeros over hidden cells per plan 018's ledger conventions.

## 4. Implementation steps

1. **Ordering spike (gates the design):** TestKit fixture applying `kotlin("jvm")` (version
   from `libs.versions.toml` `kotlin`) with wired gradle.properties; assert the KGP json
   report is observable inside a FlowAction on Gradle 8.14 and 9.x, CC on. If the race
   loses on some combo, that combo's documented behavior is `kotlin = null` — record the
   finding in this plan and in the functional test name.
2. `buildhound-commons` `BuildPayload.kt`: add `KotlinInfo`/`KotlinTaskReport` and the
   `kotlin` field as above.
3. New golden file `buildhound-commons/src/jvmTest/resources/golden/build-payload-v1-kotlin.json`
   (populated kotlin section incl. `truncatedTasks`); new `GoldenPayloadTest` cases
   (decode, round-trip, absent-field default). `build-payload-v1.json` is not touched.
4. `PayloadScrubber.scrub` (`PayloadScrubber.kt:28-34`) additionally routes
   `kotlin.perTask[].nonIncrementalReasons` through `scrubText`; update the class KDoc
   free-text inventory; `PayloadScrubberTest` cases (path-bearing reason, secret-shaped
   reason).
5. `BuildHoundExtension.kt`: add `KotlinReportsSpec { bundle: Property<Boolean> }` +
   accessor; convention `true` set in `BuildHoundSettingsPlugin.apply`.
6. New `buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/KotlinReportParser.kt`:
   pure over strings (no Gradle/file types — plan 004 retro, unit-testable), JsonElement
   extraction, allowlist, caps, `truncatedTasks`. Per-version fixture files under
   `src/test/resources/kotlin-reports/` (one per KGP minor we test, starting 2.0 and
   current) + `KotlinReportParserTest`.
7. New `KotlinReportBundler.kt` (plugin): directory listing, window/size/count bounds,
   file reads, parser invocation, internal `runCatching` → `KotlinInfo?`; the warning
   matrix from §3 lives here. Unit tests with temp dirs (stale file ignored, oversized
   skipped, unreadable dir → null + no throw).
8. `PayloadAssembler.assemble` gains a `kotlin: KotlinInfo?` parameter (scrub keeps
   running last); `PayloadAssemblerTest` updated.
9. `BuildHoundSettingsPlugin.kt` + `TelemetryFinalizerAction.Parameters`: wire
   `kotlinReports.bundle` and the two `gradleProperty` providers; call the bundler before
   `assemble` (`TelemetryFinalizerAction.kt:110`).
10. Functional tests (source set `functionalTest`, patterns from
    `BuildHoundSettingsPluginFunctionalTest.kt`): see §5.
11. `dashboard.js`: Kotlin panel in `detailView`; extend the node smoke harness
    (`dashboard-smoke.js`, run by `DashboardScriptTest`) with a payload carrying `kotlin`
    (panel renders) and one without (no panel).
12. Docs, same PR: spec §3.4 sentence amended to "reads the configured json directory"
    (divergence in §3); architecture decision-log row: *KGP json build report treated as
    an unstable external format — name-keyed allowlist parser, per-version fixtures,
    window-matched files, absent-over-wrong* (closes research §4 item 10).

## 5. Test strategy

- **Unit (plugin):** parser fixtures per KGP version; unknown/missing/renamed fields
  survive; caps + `truncatedTasks`; bundler window filtering, size/count bounds,
  garbage-json and unreadable-dir degradation; warning-matrix decisions as pure asserts.
- **Commons:** golden-file cases (step 3); scrubber cases (step 4); `DerivedMetrics`
  untouched — no new derived math in this plan.
- **TestKit functional:** (a) wired KGP fixture → payload `kotlin.perTask` has the
  `compileKotlin` record, twice for CC store then reuse ("Configuration cache entry
  stored"/"reused" asserted, precedent `payload keeps flowing on configuration cache
  reuse`); (b) non-Kotlin fixture → `kotlin` absent from payload, no warning in output;
  (c) KGP applied, wiring missing → build succeeds, output contains the copy-paste block;
  (d) **failure injection** (cross-phase guardrail): json directory property pointing at a
  regular file, and separately a directory containing malformed json → build succeeds,
  payload written, `kotlin` null, single `warn`; (e) stale-report guard: pre-created old
  report file in the dir is not bundled.
- **Server/dashboard:** node smoke harness cases (step 11); no server route changes, so
  `ApplicationTest`/Testcontainers suites are unaffected.

## 6. Risks

- **CC safety:** all file IO happens in the FlowAction at execution time — no
  configuration-phase file reads (architecture §2 rule 9). The two `gradleProperty`
  providers are ordinary CC inputs; a gradle.properties edit invalidating the entry is
  correct behavior. No Project/Gradle types anywhere new; isolated-projects is unaffected
  (nothing project-scoped), and the warning heuristic's plan-016 input already degrades to
  empty under IP.
- **Report-availability race** (KGP write vs our FlowAction, §3): mitigated by the step-1
  spike, window matching, and absent-over-wrong semantics; `kotlin = null` documented as
  "not observable", mirroring `processes: []` semantics from research.
- **Format drift:** KGP renamed metrics across K2 already; parser tolerance strategy +
  per-version fixtures pin behavior; a new KGP version at worst yields sparser records,
  never a failure.
- **Schema compatibility:** additive field with default; new golden file only; contract
  tests enforce (architecture §3.2).
- **Privacy:** new data is spec §4-declared; path-bearing KGP fields (compiler args,
  changed files, IC logs) are excluded at the allowlist, and the one retained free-text
  field routes through the scrubber. No tokens involved; report files are in-project
  build outputs.
- **Payload growth:** bounded in code (§3); interacts with plan 019's global budget —
  under overflow the kotlin section is truncated via its own caps before 019's
  task-array strategy engages.

## 7. Exit criteria

- A wired KGP fixture build produces a payload whose `kotlin.perTask` names the Kotlin
  compile task with duration, incremental flag, and at least one compiler phase time, on
  Gradle 8.14 and 9.x with CC store and reuse both green.
- A non-Kotlin build's payload omits `kotlin` entirely and logs no Kotlin warning; a
  Kotlin build without wiring prints the copy-paste fix and still succeeds.
- Every failure-injection case (bad dir, garbage json, oversized file) leaves the build
  green with a scrubbed payload and `kotlin` absent.
- The dashboard build detail shows the Kotlin panel (phase times, slowest compilations,
  incremental effectiveness) for a build with data and no panel otherwise; node smoke
  harness covers both.
- New golden file committed, existing golden untouched, `./gradlew build` green; spec §3.4
  wording and the architecture decision log updated in the same PR.
