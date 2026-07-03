# Plan 024 — Test collection per locked granularity + tests page

**Status: planned — roadmap phase 2b** · 2026-07-03

## 1. Source

- [Spec §3.5](../build-telemetry-spec.md) (locked test granularity: per-class rollup +
  `TestCaseDetail[]` only for failed/retried cases; "Collector parses JUnit XML from test
  tasks"; reserved `allCases` for additive expansion), [§4](../build-telemetry-spec.md)
  (`tests` payload block), [§6](../build-telemetry-spec.md) (Tests dashboard page),
  [§3.7](../build-telemetry-spec.md) (scrubbing new free text), [§9](../build-telemetry-spec.md)
  (locked decision #2 traceability).
- [Roadmap phase 2b](../build-telemetry-roadmap.md): "Test collection per locked
  granularity (per-class rollups + failure/retry detail) + tests page." Prerequisite for
  phase-4 flaky detection and the test-sharding addon.
- Research: [test-distribution-addon.md §2.6](../research/test-distribution-addon.md)
  (**pin the `modulePath + "/" + classFqcn` join key once in commons with a golden test** —
  Tuist's Gradle path sends bare FQCNs and silently degenerates its balancer; plans 036 and
  040 both join on this key), §2.7 (class names ride plaintext today — no scrubber conflict);
  [plugin-ecosystem-gap-analysis.md §2.1/§2.3](../research/plugin-ecosystem-gap-analysis.md)
  (per-class rollup + failure detail is enough to *detect* cross-run flakiness; the failing
  side always carries per-case detail — feasible public-API-only), §7 (test collection
  listed as spec'd-not-built);
  [dashboard-ux-research.md §4.2 item 7](../research/dashboard-ux-research.md) (start the
  Tests page with slowest classes + failures; ranked top-offenders; multi-line cells).

## 2. Scope

**In:**

- `buildhound { tests { collect = true } }` DSL block (default true), spec §3.4.
- **Collection source: JUnit XML result parsing, not Test-task listeners** — decided and
  justified in §3. The `Test` task's `junitXml.outputLocation` is discovered at
  configuration time into the collector's service parameters (plan 016 dictionary pattern);
  the XML is read in the Flow action at execution time. Zero task mutation, CC-safe, zero
  overhead when no test task runs.
- Additive schema v1 fields in commons: `BuildPayload.tests: List<TestTaskResult>` with
  per-class rollups (`TestClassResult`) and per-case detail *only* for failed/retried cases
  (`TestCaseDetail`), plus the spec-reserved `allCases` array (empty in v1). The
  `module/classFqcn` **join key** is defined once as `TestUnitKey.of(module, classFqcn)` in
  commons (this plan is its canonical definition site) with a golden test pinning its format;
  `TestClassResult.unitKey()` delegates to it. Plans 036/037/040 reference `TestUnitKey.of`.
- Retry detection: duplicate case entries within one task's results (Gradle Test Retry
  plugin re-runs append a second `<testcase>` for the same name) collapse to one
  `TestCaseDetail` with an ordered `outcomes` sequence.
- Scrubber coverage for the one new free-text field, `TestCaseDetail.message`.
- Cardinality/size bounds enforced in code (honoring plan 019's global budgets); this plan
  bounds only its own section.
- Dashboard **Tests page** (new `#/tests` route) + a tests section on **build detail**:
  slowest classes, failures/retries, ranked. Absent data ⇒ empty/contextual state.
- Tests section in the **standalone HTML artifact** (spec §3.8): summary + failures table.

**Out:**

- **Per-test-case ingestion for passing tests** — locked non-goal #2; only failed/retried
  cases get detail. `allCases` stays reserved-empty until a future additive plan fills it.
- **Flaky detection** (server cross-run/retry scoring + flaky page) — phase 4, plan 036.
  This plan only *collects* the signal (`outcomes` sequences, per-class pass/fail across
  builds) that 036 consumes.
- **Test-sharding addon** and its `/v1/addons/test-sharding/…` endpoint — phase 4, plan
  040; it references this plan's canonical `TestUnitKey.of` and the ingested per-class timings.
- **Test quarantine** — phase 4, plan 037.
- **Server-side aggregate test rollups / trends** (slowest-classes-over-time, flaky
  counters) — plan 026 rollup family; v1 Tests page reads per-build data via existing
  query API only.
- **Test class/case pseudonymization** — spec §3.5 ingests FQCNs plaintext; no conflict
  today (research §2.7). If class-name pseudonymization is ever added, plan 040 must apply
  the same deterministic map in the shard request — noted, not built here.
- Global payload caps (plan 019); task type/cacheable (plan 016); Kotlin block (plan 023).

## 3. Design

**Current behavior (verified).** `TaskEventCollector.onFinish` records every
`TaskFinishEvent` — Test tasks included — into `TaskExecution` records, but captures
nothing test-specific ([TaskEventCollector.kt:25-37](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TaskEventCollector.kt));
the service is registered with `BuildServiceParameters.None`
([BuildHoundSettingsPlugin.kt:45-50](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)).
`BuildPayload` has **no `tests` field at all** today
([BuildPayload.kt:12-34](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt))
— spec §4 shows one, but it was never added (the golden file has no `tests` key either,
[build-payload-v1.json](../../buildhound-commons/src/jvmTest/resources/golden/build-payload-v1.json)).
Adding it is a pure additive schema change. The FlowAction already reads whatever it needs
at execution time and hands collected data to `PayloadAssembler.assemble`, which scrubs the
whole payload once ([TelemetryFinalizerAction.kt:110-125](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TelemetryFinalizerAction.kt),
[PayloadAssembler.kt:74-75](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadAssembler.kt)).
The dashboard detail view renders purely client-side from `GET /v1/builds/{buildId}`
([Routes.kt:118-125](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt),
[dashboard.js:120-173](../../buildhound-server/src/main/resources/web/dashboard.js)); the
full payload is stored as jsonb ([PostgresStores.kt:58-64](../../buildhound-server/src/main/kotlin/dev/buildhound/server/PostgresStores.kt)),
so a per-build tests view needs **no store/migration change** — it queries the existing
jsonb payload on read.

**Collection source — decision: parse JUnit XML, do not add a Test listener.** The two
public-API options and why XML wins:

1. *`Test.addTestListener` / `TestListener`* (Tuist's choice): captures each case live, but
   requires **mutating every `Test` task** at configuration time to attach the listener.
   That violates the core "never silently mutate other tasks' config" rule
   (architecture §2, spec §3.4 KGP precedent — the same rule that pushes quarantine/sharding
   into addons). Listeners also capture closure state and are an awkward CC-serialization
   fit. Rejected for core.
2. *JUnit XML result parsing* (spec §3.5, explicit): the `Test` task already writes JUnit
   XML to `reports.junitXml.outputLocation` (default `build/test-results/<taskName>/`) as a
   normal, cacheable output. BuildHound reads those files in the Flow action at execution
   time — no task mutation, no config-phase file read, and **zero overhead when no test task
   ran** (there is simply no directory to read). This mirrors plan 023's KGP-report bundling
   exactly. Chosen.

**Data flow.**
- At `apply()` (root build only — included builds already return early,
  BuildHoundSettingsPlugin.kt:38-41), register `gradle.taskGraph.whenReady`; for every task
  that is-a `org.gradle.api.tasks.testing.Test` (name-based `isInstance`/superclass walk, no
  Gradle types leaked into helpers — plan 016 precedent), snapshot
  `(taskPath → junitXmlDir absolutePath, module)` into a plain `Serializable`
  `TestResultLocations` holder, delivered to the collector via the `testResultLocations:
  MapProperty<String, TestResultLocations>` added to plan 016's existing
  `TaskEventCollector.Params` (016 lands first and introduces `Params`; this plan *extends* that
  interface rather than replacing `BuildServiceParameters.None` — see §4 step 8). The map is set
  to `providers.provider { holder.get() }` (the exact CC-safe provider-evaluation mechanism plan
  016 uses; degrades to empty under isolated projects via the already-injected `BuildFeatures`).
  Discovery is config-time only, so capturing the output location is CC-safe.
- In `TelemetryFinalizerAction.execute`, a new `TestResultCollector.collect(locations,
  taskOutcomes)` reads each recorded XML directory *only for Test tasks that actually
  executed this build* (cross-referenced against the collector's `TaskExecution` outcomes so
  a `FROM_CACHE`/`UP_TO_DATE` test task with stale on-disk XML from a prior build is not
  re-ingested — absent beats wrong, plan 023 principle). Result: `List<TestTaskResult>`,
  passed into `PayloadAssembler.assemble`, scrubbed with the rest.

**Parser.** A Gradle-type-free, string/stream parser over the JUnit XML surface fields
(`<testsuite name= tests= failures= errors= skipped= time=>` and child `<testcase
name= classname= time=>` with `<failure>/<error>/<skipped>` children). Parse to per-class
rollups; keep `TestCaseDetail` only where a case failed/errored or appears more than once
(retry). Uses the JDK StAX/`javax.xml.stream` reader with external-entity/DTD resolution
**disabled** (XXE guard — test XML is a build output but still untrusted input; fail-closed).
Every file and record is `runCatching`-guarded; a malformed file is skipped, and the whole
collection path can only ever produce `[]`, never fail the finalizer (architecture §2 rule 3).

**Schema (additive, spec §4 shape).**

```kotlin
@Serializable data class TestTaskResult(
    val taskPath: String,
    val module: String? = null,
    val durationMs: Long? = null,
    val classes: List<TestClassResult> = emptyList(),
    val failedOrRetried: List<TestCaseDetail> = emptyList(),
    val truncatedClasses: Int = 0,
)
/** THE join key, defined once (research §2.6). Plans 036, 037, 040 reference `TestUnitKey.of` verbatim. */
object TestUnitKey {
    fun of(module: String?, classFqcn: String): String = "${module ?: ""}/$classFqcn"
}
@Serializable data class TestClassResult(
    val className: String,           // FQCN
    val passed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val durationMs: Long = 0,
) {
    /** Convenience that delegates to the canonical `TestUnitKey.of`. Never the source of truth. */
    fun unitKey(module: String?): String = TestUnitKey.of(module, className)
}
@Serializable data class TestCaseDetail(
    val className: String,
    val name: String,
    val outcomes: List<TestCaseOutcome> = emptyList(),   // ordered; multi-entry = retried
    val durationMs: Long = 0,
    val messageHash: String? = null,                     // SHA-256 hex of raw message
    val message: String? = null,                         // scrubbed + truncated
)
@Serializable enum class TestCaseOutcome { PASSED, FAILED, ERROR, SKIPPED }
```

`BuildPayload` gains `val tests: List<TestTaskResult> = emptyList()` and the schema keeps a
reserved `val allCases: List<TestCaseDetail> = emptyList()` **inside** `TestTaskResult` (spec
§3.5's "optional `allCases` array so expanding granularity later is additive"). Old servers
tolerate the new fields via `ignoreUnknownKeys` ([BuildHoundJson.kt](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildHoundJson.kt));
old plugins omit them (empty-list defaults, `explicitNulls = false`).

**Join key, pinned.** `TestUnitKey.of(module, classFqcn)` lives in commons and is the single
canonical definition of the `module/class` unit — defined *here* (024 lands first), computed by
test ingestion here, and referenced verbatim by the flaky comparison key (036), quarantine (037),
and the shard balancer (040); none of them redefine it or promote a competing
`TestClassResult.unitKey` as the source of truth. A null module yields the `"${module ?: ""}"`
empty-string prefix. A golden/consistency test kept with this single definition asserts its exact
string form (`":app/com.example.FooTest"`), so Tuist's degeneration bug (client FQCN vs server
`module/class`) cannot recur in BuildHound.

**Bounds in code (plan 019 budgets).** `classes` ≤ 2000 per task (top by duration, remainder
counted in `truncatedClasses`); `failedOrRetried` ≤ 500 per task; `message` truncated to 512
chars *after* scrubbing; `outcomes` ≤ 20 (retry storms clamp). `messageHash` is computed over
the raw pre-truncation message so it stays a stable flaky-signal key.

**Dashboard.**
- Build detail (`dashboard.js` `detailView`): when `build.tests` is non-empty, append a
  Tests section — a natural-language summary sentence ("208 tests in 34 classes across
  2 test tasks; 3 failed"), a failures/retries table (class, name, outcome sequence,
  duration), and a top-slowest-classes table (dimmed for all-passed). Explicit zeros, ranked
  descending (plan 018 ledger conventions, UX research §4.1).
- New **Tests page** at `#/tests`: v1 is a per-build-scoped view — pick a build (or default
  to latest matching the filter) and show its slowest classes + failures, reusing the detail
  components. Fleet-wide slowest/flaky trends are explicitly plan 026/036. Contextual empty
  state ("No test results ingested yet"), a nav link added to `index.html`. All strings via
  `textContent` (plan 012 discipline); no new server route — reads `GET /v1/builds` +
  `GET /v1/builds/{id}`.

## 4. Implementation steps

1. **Discovery spike (gates the collection design):** TestKit fixture with a JVM module and
   one JUnit 5 test; assert the `junitXml.outputLocation` is resolvable at
   `taskGraph.whenReady` and the XML files exist when the Flow action runs, on Gradle 8.14
   and 9.x, CC store + reuse. If a combo can't observe the dir, its documented behavior is
   `tests = []`; record here and in the test name.
2. `buildhound-commons` `BuildPayload.kt`: add the canonical `TestUnitKey` object,
   `TestTaskResult`, `TestClassResult` (with `unitKey()` delegating to `TestUnitKey.of`),
   `TestCaseDetail`, `TestCaseOutcome`, and the `tests` field with an empty-list default; keep
   `allCases` reserved inside `TestTaskResult`.
3. New golden file
   `buildhound-commons/src/jvmTest/resources/golden/build-payload-v1-tests.json` (a task with
   per-class rollups, one failed and one retried case, `truncatedClasses`); `GoldenPayloadTest`
   cases (decode, round-trip lossless, absent-field default, `TestUnitKey.of` exact-format
   consistency). **`build-payload-v1.json` is not touched** (additive-only rule).
4. `buildhound-commons` `PayloadScrubber.scrub` ([PayloadScrubber.kt:28-34](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/PayloadScrubber.kt)):
   route each `tests[].failedOrRetried[].message` through `scrubText`; update the class-KDoc
   free-text inventory (adds test failure text alongside execution reasons). `PayloadScrubberTest`
   cases: absolute path in a message, secret-shaped assertion text.
5. `buildhound-gradle-plugin`: new `TestResultLocations` (`Serializable` map holder,
   precedent `CollectedCi`) and a Gradle-type-free `TestTaskIntrospection` helper (name-based
   `Test` superclass check) so location/type logic unit-tests without `gradleApi()` (plan 015/016
   precedent).
6. `buildhound-gradle-plugin` new `JUnitXmlParser.kt`: pure over file bytes/streams
   (unit-testable), StAX reader with DTD/external entities disabled, per-class rollup +
   failed/retried detail + retry collapse + `messageHash`; bounds and `truncatedClasses`
   applied here.
7. `buildhound-gradle-plugin` new `TestResultCollector.kt`: given the recorded locations and
   the executed-task outcomes, list + read the XML dirs (only for Test tasks with a real
   execution outcome this build), invoke the parser, assemble `List<TestTaskResult>`; whole
   path internal `runCatching` → `[]`, never throws. Temp-dir unit tests.
8. `buildhound-gradle-plugin` `TaskEventCollector`: **extend plan 016's existing
   `TaskEventCollector.Params` interface** (016 lands first and is the one that replaces
   `BuildServiceParameters.None` with a real `Params` holding `taskMetadata:
   MapProperty<String, TaskMetadata>`); this plan *adds* `testResultLocations:
   MapProperty<String, TestResultLocations>` to that same interface — it does **not** re-derive
   from `None` or introduce a second params type. Merged shape once both land: one `Params` with
   both `taskMetadata` (016) and `testResultLocations` (024). Reading lazily in `onFinish` is
   **not** needed (locations are consumed in the finalizer, not per-event) — expose the map via a
   `snapshotLocations()` accessor instead. 016 is a prerequisite; sequence it first.
9. `buildhound-gradle-plugin` `BuildHoundExtension.kt`: add `TestsSpec { collect: Property<Boolean> }`
   + accessor; convention `true` in `BuildHoundSettingsPlugin.apply`.
10. `buildhound-gradle-plugin` `BuildHoundSettingsPlugin.apply`: register the `whenReady`
    callback (BuildFeatures IP gate first → empty map under isolated projects; walk Test tasks;
    `runCatching` → single warn), wire `tests.collect` into the FlowAction params, and an
    internal failpoint property `buildhound.internal.failTestCollection` (failure-injection seam,
    precedent `buildhound.optin.file`).
11. `buildhound-gradle-plugin` `TelemetryFinalizerAction` + `PayloadAssembler.assemble`: gather
    `List<TestTaskResult>` before assembly (gated on `tests.collect`), thread it through as a new
    `tests` parameter (scrub still runs last, PayloadAssembler.kt:74-75); one-line summary log gains
    a test count. `PayloadAssemblerTest` updated.
12. `buildhound-report` `report-template.html`: add a Tests `<section>` (summary sentence +
    failures table), rendered from `d.tests`; hidden when absent. `ReportAssetsTest` stays green
    (zero-network invariant unaffected — data is embedded).
13. `buildhound-server` `dashboard.js`: Tests section in `detailView`; new `#/tests` route +
    view; nav link in `index.html` (recompute-safe — the CSP style hash is derived from bytes,
    DashboardRoutes.kt:29-40, so an `index.html` edit is fine). Extend the node smoke harness
    (`dashboard-smoke.js`, run by the dashboard script test) with a payload carrying `tests`
    (section renders, failures listed) and one without (no section).
14. Functional tests (source set `functionalTest`) — see §5.
15. Docs, same PR: architecture decision-log row — *test telemetry collected by parsing the
    Test task's JUnit XML output in the Flow action (public API, no task mutation), not via a
    Test listener; `module/class` join key defined once as `TestUnitKey.of` in commons for flaky
    (036), quarantine (037) + sharding (040)*.
    Note in architecture §2 that JUnit XML dirs are read at execution time (rule 9 parity with
    the KGP-report reader).
16. Re-read this plan against the diff; record any divergence here in the same PR.

## 5. Test strategy

- **Unit (commons):** golden-file cases (step 3); `TestUnitKey.of` exact-format assertion
  (incl. null-module → empty-string prefix), with `TestClassResult.unitKey()` shown to delegate;
  scrubber cases (step 4); no new derived math (`DerivedMetricsCalculator` untouched).
- **Unit (plugin):** `JUnitXmlParser` over fixture XML — all-pass suite (rollup only, no
  detail), a failure (detail + `messageHash` + scrubbed/truncated message), a retried case
  (two `<testcase>` → one detail, `outcomes = [FAILED, PASSED]`), skipped counts, malformed
  XML → skipped, and an XXE payload (external entity ignored, no file read); bounds +
  `truncatedClasses`; `TestTaskIntrospection` (Test subclass, decorated subclass, non-test).
  `TestResultCollector` temp-dir tests: missing dir → `[]`, stale-XML-from-prior-build (task
  not executed this run) ignored, unreadable dir → `[]` + no throw.
- **TestKit functional** (patterns from
  [BuildHoundSettingsPluginFunctionalTest.kt](../../buildhound-gradle-plugin/src/functionalTest/kotlin/dev/buildhound/gradle/BuildHoundSettingsPluginFunctionalTest.kt)):
  (a) JVM fixture with a passing + a failing test → payload `tests` has the task with correct
  per-class counts, the failing case in `failedOrRetried`, build still SUCCESS for the
  telemetry (plugin never fails the build; the *build itself* fails on the test — assert the
  payload is still written and uploaded/spooled); (b) no-test fixture → `tests` empty, zero
  overhead (no test-results dir); (c) CC store then reuse → `tests` populated both runs
  (locations replayed from the entry), precedent test `payload keeps flowing on configuration
  cache reuse`; (d) `tests { collect = false }` → `tests` empty; (e) **failure injection**
  (guardrail): `buildhound.internal.failTestCollection` set → build proceeds, payload written,
  `tests` empty, single `warn`; (f) isolated-projects run
  (`-Dorg.gradle.unsafe.isolated-projects=true`) → SUCCESS, no plugin error, `tests` empty
  (locations map empty by the IP gate) — the plan-021 IP job then watches this continuously.
- **Server/dashboard:** node smoke-harness cases (step 13). No server route/store change, so
  the `ApplicationTest`/Testcontainers suites are unaffected; a jsonb round-trip of a
  `tests`-bearing payload is exercised by the existing ingest→findById path in an added case.

## 6. Risks

- **CC safety:** the `whenReady` closure runs only at configuration time (capturing
  `Settings` there is safe, plan 016 precedent); all XML file IO is in the Flow action at
  execution time — no config-phase file reads (architecture §2 rule 9). Location delivery
  rides the same provider-evaluation order the shipped `projectKey` provider and plan 016's
  dictionary rely on; the CC store/hit functional test pins it.
- **Isolated projects:** enumerating tasks across projects from settings scope is an IP
  violation — the `BuildFeatures` gate runs *before* the graph walk, degrading to an empty
  location map (`tests = []`). Contract tested here once, continuously by plan 021's job.
- **No task mutation:** the chosen XML-parsing source touches no `Test` task config, keeping
  test collection in core (unlike sharding/quarantine, which mutate and stay addons). This is
  the load-bearing reason the feature is core, not an addon — stated in the decision log.
- **Stale-output race:** a cached/up-to-date Test task leaves prior-build XML on disk; the
  collector ingests only tasks with a real execution outcome this build (absent-over-wrong),
  so hit-rate-friendly builds don't double-count.
- **Schema compatibility:** additive fields with defaults; new golden file only; existing
  golden untouched; contract tests enforce (architecture §3.2). Reserved `allCases` keeps the
  locked granularity expansion additive (spec §3.5).
- **Security/privacy:** new collected data is spec §4-declared. Class/method names and counts
  are declared data (same exposure class as task paths/module names). The one free-text field,
  `TestCaseDetail.message` (assertion/exception text — routinely embeds absolute paths and can
  embed secret-shaped values), routes through the scrubber and is truncated; `messageHash`
  lets flaky detection group without shipping full text. XML parsing disables DTD/external
  entities (XXE). No env reads, no tokens. Class names ride plaintext (spec §3.5) — if
  pseudonymization is later added, plan 040 must mirror it in the shard request (research §2.7,
  noted OUT).
- **Payload growth:** bounded in code (§3); interacts with plan 019's global budget — the
  tests section truncates via its own caps (drop passing-class detail first is already the
  design; only failures carry detail) before 019's task-array strategy engages.

## 7. Exit criteria

- A JVM fixture with passing, failing, and retried tests produces a payload whose `tests`
  names the test task with correct per-class `passed/failed/skipped` counts, a
  `failedOrRetried` entry for the failure (with a scrubbed, truncated message and a stable
  `messageHash`) and for the retry (`outcomes = [FAILED, PASSED]`), on Gradle 8.14 and 9.x
  with CC store and reuse both green.
- A build with no tests, and a build with `tests { collect = false }`, both produce a payload
  with `tests` empty and add no measurable overhead; the failure-injection and
  isolated-projects runs both leave the build's own outcome unchanged, the payload written,
  and `tests` empty with a single warn (injection) / no error (IP).
- `TestUnitKey.of(module, classFqcn)` returns exactly `"<module>/<classFqcn>"` (and
  `TestClassResult.unitKey()` delegates to it), asserted by a committed consistency test kept
  with the single definition, so plans 036, 037, and 040 can reference it verbatim to join.
- The dashboard build detail and the standalone HTML artifact show the tests summary +
  failures for a build with results and nothing for a build without; the `#/tests` page renders
  with a contextual empty state when no results exist; node smoke harness covers both.
- New golden file committed, existing golden untouched, `./gradlew build` green; the
  architecture decision log carries the collection-source and join-key row.
