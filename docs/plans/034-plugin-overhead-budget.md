# Plan 034 — Plugin-overhead budget + self-benchmark harness

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- Roadmap [phase 3, "Plugin-overhead budget + self-benchmark harness" bullet](../build-telemetry-roadmap.md)
  ("build-process-watcher precedent") and the [cross-phase guardrail](../build-telemetry-roadmap.md)
  "plugin overhead measured against the phase-3 budget from then on" — this plan defines that budget
  and the job that enforces it.
- Research: [build-process-watcher.md](../research/repos/build-process-watcher.md) (its
  `scripts/benchmark-jstat-metrics.sh` measures per-sample sampler cost — "ready-made evidence for
  an overhead budget"; the "benchmark its own cost" precedent);
  [comparison-to-spec.md §4 item 8](../research/comparison-to-spec.md) ("No overhead budget for the
  plugin itself … adopt a self-benchmark harness (the §7 gradle-profiler scenarios can compare
  with-plugin vs without-plugin) and a stated budget, since 'never fail the build' is necessary but
  not sufficient for adoption — 'never slow the build noticeably' is the real bar").
- Spec: [§7 Benchmark mode](../build-telemetry-spec.md) (the gradle-profiler pipeline this harness
  reuses), [§3.2 Finalizer](../build-telemetry-spec.md) and [§3.9 upload semantics](../build-telemetry-spec.md)
  (the code paths whose cost is budgeted).
- Precedent: [plan 030](030-benchmark-mode.md) ships `buildhound-ci-assets/profiler-scenarios/*.scenarios`
  and the scheduled gradle-profiler pipeline; this plan reuses that infrastructure when it exists and
  falls back to its own minimal fixture otherwise (dependency note below).

## 2. Scope

**In:**

- A **stated, measured overhead budget** for the plugin, written into `docs/architecture.md` §2 as a
  binding rule with a decision-log row, broken out across the four cost axes the roadmap names:
  configuration time (apply + value-source setup), per-task overhead (the `TaskEventCollector`
  listener), build-end finalizer time (payload assembly + HTML render), and the upload path.
- A **repeatable self-benchmark harness**: gradle-profiler scenarios comparing **plugin-on vs
  plugin-off** on a fixed fixture build, plus a small pure-Kotlin **delta calculator** in
  `buildhound-commons` that turns two gradle-profiler `benchmark.csv` outputs into per-scenario
  overhead numbers and a pass/fail verdict against the budget.
- A **CI job** (`overhead-budget`, watched → promotable to blocking) that runs the harness on a
  fixture and **flags budget breaches**, with a published artifact (the parsed overhead table).
- **Published results**: a `docs/overhead-budget.md` reference documenting the budget, how it is
  measured, and how to read the numbers; the CI job uploads the current run's table as a build
  artifact.

**Out (and where it lives):**

- The `mode=benchmark` payload block, `BenchmarkValueSource`, server series endpoint, dashboard
  `#/benchmark` view, and the *nightly product* profiler pipeline — [plan 030](030-benchmark-mode.md)
  (this plan **reuses** its `profiler-scenarios/` files and pipeline scaffold; it does **not**
  re-create them, and its harness runs the plugin *toggled*, not in `mode=benchmark`).
- Process-probe cost as a *line item* is budgeted here, but the probe itself is
  [plan 029](029-process-probe.md) (that plan's exec-heavy ValueSource is the single most likely
  future budget-breaker; this plan gives it the yardstick).
- Regression baselines/verdicts over the *product's* real-build series — [plan 025](025-regression-engine-v1.md);
  this plan's verdict is a static budget check against a plugin-on/off delta, not a rolling baseline.
- macOS/Windows/isolated-projects/CC-off CI axes — [plan 021](021-ci-matrix-expansion.md); this plan
  adds one Linux job (overhead is measured on a stable reference runner; cross-OS overhead is a later
  concern).
- Any change to the collection code itself; this plan **measures** the shipped plugin, it does not
  optimize it (optimizations that a breach motivates are follow-up work).

No payload-schema change of any kind — the harness reads gradle-profiler CSV, not BuildHound
payloads — so **no golden files**.

## 3. Design

**What "overhead" means, per axis (pinned so the number is unambiguous).** gradle-profiler drives the
same fixture from *outside* with two Gradle-user-home-isolated variants — plugin applied vs not — and
reports mean total build time ± stderr per scenario. Overhead = `mean(plugin-on) − mean(plugin-off)`
per scenario. The four roadmap axes map onto scenarios and one internal measurement:

- **Configuration time** — the `cc-hit` scenario (configuration cache reused): on a CC hit the plugin's
  apply-time work is replayed from cache and the value sources may re-obtain, so this scenario isolates
  steady-state config-phase cost. `--measure-config-time` (gradle-profiler flag) splits it out.
- **Per-task overhead** — the `incremental` scenario (a non-ABI source change, many tasks re-run):
  `TaskEventCollector.onFinish` (`TaskEventCollector.kt:25-38`) fires once per `TaskFinishEvent`; a
  task-dense scenario surfaces its marginal cost. The collector only appends to a
  `ConcurrentLinkedQueue`, so the expected per-task cost is near-zero — the budget encodes that
  expectation and the harness proves it stays true.
- **Build-end finalizer time** — the `no-op` scenario (up-to-date build): total wall time is dominated
  by fixed per-build cost, of which the Flow finalizer (`TelemetryFinalizerAction.execute`,
  `TelemetryFinalizerAction.kt:93-174`: snapshot → `PayloadAssembler.assemble` → HTML render → file
  writes) is the plugin's share. Measured as the plugin-on/off delta on `no-op`.
- **Upload path** — a `no-op` variant with `buildhound { server.url = <loopback sink> }` set vs unset,
  so `UploadGate.Decision.Upload` (`TelemetryFinalizerAction.kt:145-166`) exercises `PayloadUploader`
  against a local do-nothing HTTP sink. Isolates synchronous-with-spool upload cost (spec §3.9, plan
  008) from the rest of the finalizer.

**Budget values (initial, to be calibrated on the reference runner during implementation).** The plan
states *shapes and provisional caps*; the committed numbers come from the first green harness run on
the CI reference runner (recorded in the decision log, not invented here):

| Axis | Scenario | Provisional budget (calibrate at impl time) |
|---|---|---|
| Configuration (steady state) | `cc-hit` | ≤ 40 ms **and** ≤ 3 % of plugin-off mean |
| Per-task | `incremental` | ≤ 5 % of plugin-off mean |
| Finalizer | `no-op` | ≤ 150 ms **and** ≤ 8 % of plugin-off mean |
| Upload (loopback sink) | `no-op + server` | ≤ 250 ms over the no-server `no-op` mean |

Each cap is an **absolute floor OR a percentage**, whichever is *looser*, so a fast fixture on a fast
runner doesn't trip a percentage cap on sub-millisecond noise, and a slow runner doesn't let a large
absolute regression hide inside a percentage. The verdict also requires the plugin-on/off means to be
**statistically separated** (delta greater than the combined stderr) before a percentage breach counts —
same-machine noise must not mint false breaches (Bagan/Telltale lesson, echoed in plan 030 §6).

**Harness location — decision.** The harness is **not** a TestKit `functionalTest`: TestKit spins a
throwaway `hello` fixture (`BuildHoundSettingsPluginFunctionalTest.kt:26-47`) and measures nothing,
whereas gradle-profiler needs a *stable, warmed* fixture driven from outside the JVM. It lives as
CI-facing assets alongside the profiler scenarios (spec §7 / architecture §1: CI assets are
JVM-free-consumable), with the verdict math as the one pure-Kotlin, unit-tested piece in commons:

- **`buildhound-ci-assets/overhead/`** — an `overhead.scenarios` HOCON file parameterised over the
  plugin-on/off toggle, a self-contained 3-module Kotlin/JVM `fixture/` (no AGP/Android SDK, so any
  Linux runner builds it), and a `run-overhead.sh` wrapper that runs gradle-profiler twice (toggle via
  `-Dbuildhound.overhead.plugin=on|off`) then invokes the verdict tool. Reuses plan 030's
  `profiler-scenarios/*.scenarios` shapes when present; otherwise ships minimal copies (dependency note).
- **`buildhound-commons`** — `OverheadBudget` (the caps as data) + `OverheadCalculator.evaluate(on,
  off, budget)` returning per-axis `{deltaMs, deltaPct, separated, verdict}`. Pure and plain-unit-testable
  like `DerivedMetricsCalculator` — the single place plugin/CI/docs agree on what "breach" means. It
  parses the two gradle-profiler `benchmark.csv` files with name-keyed, unknown-column-tolerant column
  lookup (the format-drift discipline from [comparison-to-spec §4 item 10](../research/comparison-to-spec.md)).
- **`buildhound-ci-assets/overhead/bin/buildhound-overhead`** — a thin launcher calling
  `OverheadCalculator` on the two CSVs, exiting non-zero on any breach and printing a Markdown table.
  Glue only; the math is in commons.

**The plugin-on/off toggle in the fixture.** The fixture's `settings.gradle.kts` applies the plugin
conditionally on a Gradle property:
`if (providers.gradleProperty("buildhound.overhead.plugin").getOrElse("off") == "on") { apply … }`.
gradle-profiler passes the property per variant. When on, the fixture sets `buildhound { mode = local;
localBuilds { enabled = false } }` (or `mode = disabled` for the pure config-cost isolation cell) so no
network happens except in the dedicated upload cell, and no opt-in file is required. The plugin under
test is resolved via a `--include-build` of this repo or a locally published build (impl decides;
gradle-profiler supports `apply-plugin`-style setup via the fixture's own build).

**CI job.** New `overhead-budget` job in `.github/workflows/ci.yml` (currently three ubuntu jobs:
`build`, `build-floor`, `server-image`, `ci.yml:13-95`). Ubuntu-only, Temurin 26 for the build
toolchain / JDK 21 launcher (matching `build`, `ci.yml:19-36`), installs gradle-profiler (version
looked up at implementation time from its GitHub releases — never pinned from memory; per
architecture "check the latest released version" convention), runs `run-overhead.sh`, uploads the
Markdown table via `actions/upload-artifact`, and fails on a breach. Watched (non-blocking) initially —
same posture as plan 021's IP job — because same-machine timing on shared CI is noisy; promoted to
blocking once the runner-to-runner variance is characterised (criterion recorded in
`docs/overhead-budget.md`). The job is `workflow_dispatch`- and PR-triggerable but *not* on every push
if runtime proves heavy; the default trigger (PR + a scheduled cron) is decided at impl time to keep PR
CI fast.

**Published results.** `docs/overhead-budget.md`: the budget table, the four-axis methodology, the
plugin-on/off design, how to run `run-overhead.sh` locally, how to read the artifact, and the
promote-to-blocking criterion. The CI artifact is the living result; the doc is the stable reference.
Roadmap phase-3 guardrail is satisfied: "overhead is measured against this budget — a CI job flags
budget breaches."

## 4. Implementation steps

1. **commons — budget + calculator.** Add `OverheadBudget` (per-axis caps: absolute ms, percent,
   require-separation flag) and `OverheadCalculator.evaluate(on: ScenarioStats, off: ScenarioStats,
   budget)` returning per-axis `OverheadVerdict {deltaMs, deltaPct, separated, breached}` in
   `buildhound-commons` (`commonMain`). Add a `ProfilerCsv.parse(file)` helper: name-keyed column
   lookup over gradle-profiler `benchmark.csv`, tolerant of unknown/missing columns, extracting per
   scenario the mean and stderr. Pure, no I/O framework beyond reading a `String`.
2. **commons — tests.** `OverheadCalculatorTest`: breach when delta exceeds both floor and percent;
   pass when under the *looser* of the two; no-breach when delta ≤ combined stderr even if percent
   exceeded (separation guard); empty/single-row guards. `ProfilerCsvTest`: parse a checked-in sample
   `benchmark.csv` fixture, tolerate an extra unknown column, fail cleanly on a missing mean column.
3. **ci-assets — reference fixture.** `buildhound-ci-assets/overhead/fixture/`: a 3-module Kotlin/JVM
   project (root + two library modules, a handful of Kotlin files each, one JUnit test) with the
   conditional plugin apply keyed on `buildhound.overhead.plugin`. No Android/AGP. Its
   `gradle.properties` keeps `org.gradle.configuration-cache=true` (so the `cc-hit` scenario is real).
4. **ci-assets — scenarios.** `buildhound-ci-assets/overhead/overhead.scenarios`
   (gradle-profiler HOCON): `no-op`, `incremental` (non-ABI source-file mutator), `cc-hit`, and
   `no-op-upload` (property enabling a loopback server URL). Reuse plan 030's `profiler-scenarios/`
   shapes where they exist; otherwise these are the minimal copies. `--measure-config-time` set for the
   `cc-hit` cell.
5. **ci-assets — runner + verdict glue.** `overhead/run-overhead.sh`: run gradle-profiler once per
   variant (plugin on / off) into two output dirs, then invoke `buildhound-overhead` on the two
   `benchmark.csv` files; propagate its exit code. `overhead/bin/buildhound-overhead` (thin launcher
   calling `OverheadCalculator`; prints the Markdown table). A minimal loopback HTTP sink for the
   upload cell (a `nc`/tiny script that 200s and discards, no BuildHound server needed).
6. **CI job.** Add `overhead-budget` to `.github/workflows/ci.yml`: checkout, setup-java (26 + 21),
   install gradle-profiler (release looked up at impl time), `run-overhead.sh`, `upload-artifact`
   (the Markdown table + raw CSVs), fail on non-zero. Watched/non-blocking to start; PR + scheduled
   trigger (final trigger decided at impl time for PR-CI speed).
7. **Calibrate the budget.** Run the harness on the CI reference runner; record the observed
   plugin-on/off deltas; set the committed `OverheadBudget` caps from those numbers with headroom.
   This step turns the provisional table in §3 into real values.
8. **Docs — published results.** Add `docs/overhead-budget.md` (budget table, methodology, local-run
   instructions, artifact-reading guide, promote-to-blocking criterion). Update
   `buildhound-ci-assets/README.md` with the `overhead/` row.
9. **Architecture doc (same PR, guardrail).** Add a binding rule to `docs/architecture.md` §2 ("The
   plugin has a measured overhead budget; the `overhead-budget` CI job enforces it from phase 3 on")
   and a §7 decision-log row (the four-axis budget, the plugin-on/off gradle-profiler method, the
   verdict-math-in-commons decision, and the watched→blocking posture). Note the roadmap phase-3
   bullet as landed.
10. **Spec touch (if needed).** If §7 gains the overhead harness as a named companion to benchmark
    mode, amend §7 in the same PR (per the "any plan divergence patches the spec section" discipline,
    [comparison-to-spec §4 item 12](../research/comparison-to-spec.md)).

## 5. Test strategy

- **Commons unit (the load-bearing logic):** `OverheadCalculatorTest` and `ProfilerCsvTest` as in step
  2 — breach/pass across the floor-vs-percent looser rule, the stderr-separation guard, and CSV parse
  tolerance against a checked-in sample `benchmark.csv`. These run in the normal `./gradlew build`, so
  the verdict math is covered on every PR even though the full profiler run is not.
- **CI-asset lint / smoke:** the `overhead.scenarios` file is a syntactically valid gradle-profiler
  scenario (spot-checked; exercised for real only by the `overhead-budget` job, not unit CI, like plan
  030's scenario files). `run-overhead.sh` is shellcheck-clean.
- **Harness self-test (in the `overhead-budget` job):** the job asserts that the *plugin-off* variant
  produces **no** `build/buildhound/` output in the fixture and the *plugin-on* variant does — proving
  the toggle actually toggles, so a broken fixture can't report a spuriously tiny (or zero) overhead.
- **Failure-injection (guardrail — every phase adds one):** because this plan changes no plugin code,
  the failure-injection surface is the harness: a deliberately-broken `benchmark.csv` (missing column)
  makes `buildhound-overhead` exit non-zero with a clear message rather than reporting a false pass;
  covered by `ProfilerCsvTest` plus a CI assertion that an empty/garbled CSV fails the job loudly
  instead of silently passing.
- **No golden files** (no schema change); no Testcontainers (no server change).

## 6. Risks

- **Same-machine timing noise (correctness).** Sub-100 ms plugin overhead against multi-second builds
  on shared CI runners is easily lost in variance. Mitigations: absolute-OR-percent looser caps, the
  stderr-separation requirement before a percentage breach counts, gradle-profiler warm-ups (its
  defaults), and starting the job **watched** not blocking until runner variance is characterised. The
  budget is a *guardrail against regressions*, not a microbenchmark.
- **Fixture drift / cheating.** If the toggle silently applies nothing (or the fixture stops
  triggering tasks), overhead reads as ≈0 and the guardrail rots. The harness self-test (step 5 above,
  presence/absence of `build/buildhound/` output) is the anti-rot check — the same class of "did the
  skippable suite actually run" concern from [comparison-to-spec §4 item 11](../research/comparison-to-spec.md).
- **CC / isolated projects.** The harness runs the *shipped* plugin unchanged; it adds no CC input and
  no task-graph mutation. The `cc-hit` scenario deliberately exercises CC store/reuse (already green,
  architecture §2 rule 2); IP is out of scope for the overhead runner (plan 021 owns IP CI).
- **Schema compatibility.** None touched — the harness consumes gradle-profiler CSV, not payloads;
  `OverheadCalculator` lives in commons but adds no serializable payload type and no golden file.
- **gradle-profiler as a moving dependency.** Its CSV column layout can change across releases; the
  name-keyed, unknown-column-tolerant parser (step 1) and a pinned profiler version (looked up at impl
  time, not from memory) contain that. The version pin is recorded so a bump is a deliberate, reviewed
  change.
- **Security/privacy.** The fixture is synthetic; no PII, no real project data, no tokens — the upload
  cell hits a loopback do-nothing sink, never a real server, and `BUILDHOUND_TOKEN` is not needed.
  Published results are timing tables, not build content. No secrets enter the workflow or the fixture.
- **CI runtime cost.** A full plugin-on/off profiler run doubles build count; keeping the fixture small
  and choosing a PR+scheduled (not every-push) trigger bounds it. If PR runtime proves heavy, the job
  moves to scheduled-only with a `workflow_dispatch` escape hatch (decided at impl time; recorded in
  the doc).

## 7. Exit criteria

- `./gradlew build` green, including new `OverheadCalculatorTest` / `ProfilerCsvTest` in commons.
- `buildhound-ci-assets/overhead/` contains the fixture, `overhead.scenarios`, `run-overhead.sh`, and
  the `buildhound-overhead` verdict tool; running `run-overhead.sh` locally produces a per-axis overhead
  table and a pass/fail exit code.
- The `overhead-budget` CI job runs the harness, uploads the Markdown overhead table as an artifact,
  and **fails when overhead exceeds the budget** — demonstrated by a deliberately-tightened budget in a
  throwaway check turning the job red, then restored.
- The harness self-test proves the plugin-on variant produces telemetry output and the plugin-off
  variant does not (the toggle is real).
- `docs/overhead-budget.md` publishes the calibrated budget, the four-axis methodology, and the
  promote-to-blocking criterion; `buildhound-ci-assets/README.md` lists the `overhead/` assets.
- `docs/architecture.md` §2 gains the overhead-budget binding rule and §7 a decision-log row (same
  PR); the roadmap phase-3 bullet and the cross-phase "measured against this budget" guardrail are
  satisfied.
- Clean-context code-and-architecture and security/privacy reviews completed; findings addressed or
  accepted with a note.
