# 053 — Honest degraded state when JUnit XML is disabled

## Source

- Research finding **F3**, [`docs/research/ingest-corpus-analysis.md`](../research/ingest-corpus-analysis.md)
  §3 — "Official advice to disable JUnit XML silently kills plan-024 test telemetry."
- Source article: [`docs/research/processed/Improve the Performance of Gradle Builds.md`](../research/processed/Improve%20the%20Performance%20of%20Gradle%20Builds.md)
  — Gradle's own guide recommends `reports.junitXml.required = false` on `Test` tasks "if you
  use a Build Scan." A team following it loses **all** BuildHound test telemetry (plan
  [024](implemented/024-test-collection.md)) with zero signal — a latent product risk, not a feature idea.
- Spec [§3.5](../build-telemetry-spec.md) (test collection parses JUnit XML), [§3.7](../build-telemetry-spec.md)
  (no absolute paths in payload), [§4](../build-telemetry-spec.md) (payload schema, additive-only).
  Cross-cutting rec §3 in the finding flags F3 as "a latent risk worth an issue today."

## Scope

**In**

- Read `reports.junitXml.required` per `Test` task in the plan-016/024 config-time
  `taskGraph.whenReady` walk (public API), thread it through the existing location channel.
- **Flag is authoritative:** when an *executed* Test task has XML disabled, emit an honest
  degraded state (task path) — and **do not** parse whatever is on disk for it.
- Additive schema: new nullable top-level `BuildPayload.testTelemetry: TestTelemetryInfo?`
  carrying `xmlDisabledTasks: List<String>` (task paths only). New golden file.
- Surface the degraded state in the **HTML artifact**, the dashboard **build detail**, and the
  **`#/tests` page**: "Test telemetry unavailable — JUnit XML disabled on `:app:test`."
- Docs (finding's "Do" (b)/(c)): a user-facing note — *keep `junitXml.required = true`, disable
  the HTML report for the perf win instead* — plus an architecture decision-log row and a
  standing-risk entry in [`build-telemetry-research.md §6`](../build-telemetry-research.md).

**Out**

- No new server route, store, or migration — the dashboard reads the new field from the existing
  token+tenant-scoped `GET /v1/builds/{id}` jsonb payload (deferred: none; there is nothing to add).
- No change to how *present* XML is parsed (plan 024 `JUnitXmlParser`/`TestResultCollector` core).
- No detection under isolated projects — the `whenReady` Test walk is already skipped there, so
  the signal is unavailable (test telemetry is already absent in that mode; not regressed here).
- Not flagging `tests { collect = false }` builds — that is the user's opt-out, not a Gradle-XML
  gap. Detection lives inside the existing `testsCollect` guard, so this falls out for free.
- No other degraded reasons (missing `junit-platform-launcher`, plan 024 §4a) — a future additive
  field on `TestTelemetryInfo`, not this slice.

## Design

Modules: `buildhound-gradle-plugin`, `buildhound-commons` (additive schema), `buildhound-report`,
`buildhound-server` (dashboard JS only). Builds on plans **016** (config-time dictionary), **024**
(test collection + `tests` block), **044** (durable `TestLocationSidecar`).

- **Capture (config time, CC-safe).** `BuildHoundSettingsPlugin.testLocationOf`
  ([BuildHoundSettingsPlugin.kt:324](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt))
  already reads `test.reports.junitXml.outputLocation` inside the `whenReady` callback
  ([:121](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt),
  non-IP branch). It gains one sibling read on the same `DirectoryReport`:
  `test.reports.junitXml.required.getOrElse(true)` (public `Report.getRequired(): Property<Boolean>`,
  no internal API). **This must be `whenReady`, never `apply()`** — `required` is only finalized
  after `afterEvaluate`; at apply time the `Test` task's convention hasn't resolved (the F3-equivalent
  of the "read after settings evaluation" narrowing).
- **Channel.** `TestResultLocations`
  ([TestResultLocations.kt](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TestResultLocations.kt))
  gains `val junitXmlRequired: Boolean = true`. It already rides both the service-param map and the
  durable sidecar; `TestLocationSidecar.encodeLine`/`parseLine`
  ([TestLocationSidecar.kt:55-72](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TestLocationSidecar.kt))
  add the boolean (default `true` when a line omits it, so an older sidecar degrades safely). CC-hit
  replay and composite-build durability come for free — same mechanism plan 024 relies on.
- **Flag-authoritative collection (finalizer).** `TestResultCollector.collect`
  ([TestResultCollector.kt:26](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TestResultCollector.kt))
  return widens to `TestCollectionResult(results: List<TestTaskResult>, xmlDisabledTasks: List<String>)`.
  For each location whose task outcome ∈ `{EXECUTED, FAILED}`: if `junitXmlRequired == false`,
  **short-circuit — record the task path in `xmlDisabledTasks` and skip parsing entirely** (do not
  `listFiles`/parse; the flag overrides whatever is on disk). Else parse as today. The two outputs
  are mutually exclusive per task. The outer `runCatching` → `getOrElse` degrade path returns
  `TestCollectionResult(emptyList(), emptyList())` (never-fail).
- **Assembly.** `TelemetryFinalizerAction`
  ([:223-236](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TelemetryFinalizerAction.kt))
  builds `TestTelemetryInfo(result.xmlDisabledTasks).takeIf { it.xmlDisabledTasks.isNotEmpty() }`
  and passes it to `PayloadAssembler.assemble` as a new `testTelemetry` param
  ([PayloadAssembler.kt:72](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadAssembler.kt)),
  set onto the payload. Only reachable inside the `testsCollect` guard, so `collect = false` never
  produces a note.
- **Schema (commons, additive).** `BuildPayload` gains
  `val testTelemetry: TestTelemetryInfo? = null`
  ([BuildPayload.kt:38](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt)
  neighbourhood) with `data class TestTelemetryInfo(val xmlDisabledTasks: List<String> = emptyList())`.
  Task paths are declared data (spec §4, same class as `TaskExecution.path`) — **no scrubber change**;
  the absolute `junitXmlDir` never reaches the payload.
- **Presentation.** Report `report-template.html`
  ([:180-211](../../buildhound-report/src/main/resources/dev/buildhound/report/report-template.html))
  and dashboard `testsPanel`/`testsView`/build-detail
  ([dashboard.js:344-441,586](../../buildhound-server/src/main/resources/web/dashboard.js)): when
  `d.testTelemetry.xmlDisabledTasks` is non-empty, render a degraded line naming the task paths — so
  an empty (or partial) `tests` block reads as "collection turned off," not "no tests." All strings
  via `textContent` (plan 012 discipline); task paths are the only interpolated data.

## Test strategy

- **Unit (commons):** new golden `build-payload-v1-test-telemetry.json` (populated `xmlDisabledTasks`)
  in `GoldenPayloadTest` — decode, round-trip lossless, absent-field → null default; existing
  `build-payload-v1.json` untouched (additive-only). `TestLocationSidecar` encode/decode round-trips
  the `junitXmlRequired` boolean, and a legacy line without it decodes to `true`.
- **Unit (plugin):** `TestResultCollector` — a location with `junitXmlRequired=false` + EXECUTED
  outcome yields `xmlDisabledTasks=[path]` and **empty `results` even when a stale `.xml` sits in the
  dir** (flag beats disk); `required=true` parses as before; an UP_TO_DATE/FROM_CACHE disabled task
  produces neither a result nor a note; failpoint → both lists empty, no throw.
- **TestKit functionalTest** (`BuildHoundSettingsPluginFunctionalTest` patterns): a JVM module with a
  real `Test` task and `reports.junitXml.required = false`, seeded with a stale `TEST-*.xml` → build
  SUCCESS, `tests` empty for it, `testTelemetry.xmlDisabledTasks` contains its path, **no phantom
  stale results**. Variants: CC store→hit (sidecar replays the disabled flag, note still emitted);
  `tests { collect = false }` (no note); isolated projects (`-Dorg.gradle.unsafe.isolated-projects=true`)
  → SUCCESS, no note (signal unavailable, matches the narrowing).
- **Server/dashboard:** extend the `dashboard-smoke.js` harness with a payload carrying
  `testTelemetry` (degraded line renders, task path shown) and one without (no degraded line). No
  server route/store change, so `ApplicationTest`/Testcontainers suites are unaffected; a jsonb
  round-trip of a `testTelemetry`-bearing payload rides the existing ingest→findById path.

## Risks

- **Stale-XML phantom results (inverse of plan 024 §6, named):** flipping `required = true → false`
  without `clean` leaves prior-run `TEST-*.xml` on disk that Gradle won't remove (the report dir is
  not a tracked output when disabled). If the collector parsed them, the payload would carry
  real-looking results *and* a disabled note for the same task — worse than F3's silent gap.
  **Mitigation:** the flag is authoritative — an XML-disabled executed task short-circuits before any
  `listFiles`, so results and note are mutually exclusive per task.
- **Privacy (§3.7, named):** `TestResultLocations` holds the absolute `junitXmlDir`, but **only the
  task path** is copied into `TestTelemetryInfo`; the dir never reaches the payload. Task paths are
  declared data (spec §4) — no scrubber change, degraded strings are built client-side in
  report/dashboard from those paths. Guarantee: no `junitXmlDir` leaves the machine.
- **Additive schema:** one nullable top-level field + one new type + one new golden; existing golden
  untouched, `schemaVersion` stays 1. Old servers tolerate it via `ignoreUnknownKeys`; old plugins
  omit it (null default). A dedicated block (not a marker row in `tests[]`) keeps plans 036/037/040
  joins on `TestTaskResult` clean and leaves room for future gap reasons additively.
- **Isolated projects:** the `whenReady` Test walk is gated off under IP, so `xmlDisabledTasks` is
  empty there — the signal degrades to unavailable, consistent with test telemetry already being
  absent under IP. Not a regression.
- **CC safety:** reading `.required` is a config-time property read (no file IO), identical to the
  existing `outputLocation` read; the sidecar write stays a config-time side effect, never a CC input;
  the flag replays from the CC entry on a hit. No new fingerprint input.
- **Never-fail:** the `.required` read is inside the existing `whenReady` `runCatching`; the widened
  `collect` degrade path returns an empty `TestCollectionResult`; the whole test path can only ever
  produce empty lists, never a failed build.
- **Multi-tenancy:** no new read route — the dashboard consumes the field from the existing
  token+tenant-scoped `GET /v1/builds/{id}`. No cross-tenant surface added.

## Exit criteria

- A build with a `Test` task that executed under `reports.junitXml.required = false` produces a
  payload whose `testTelemetry.xmlDisabledTasks` names that task path, `tests` carries **no** entry
  for it, and (with a stale XML seeded) **no phantom result** — pinned by unit + functional tests.
- The HTML artifact, dashboard build detail, and `#/tests` page each show "Test telemetry
  unavailable — JUnit XML disabled on `<taskPath>`" for such a build, and nothing for a build with
  results present or `tests { collect = false }`.
- CC store→hit replays the disabled flag (note stable across both runs); the isolated-projects and
  failure-injection runs emit no note and leave the build's own outcome unchanged.
- New golden committed, existing golden untouched; `junitXmlDir` never appears in any payload.
- Architecture decision log + `build-telemetry-research.md §6` carry the standing-risk / guidance
  rows ("keep XML on, disable the HTML report"); `./gradlew build` green.
