# Plan 029 — Process probe (spec §3.6): daemon/Kotlin JVM stats, CC-safe

**Status: planned — roadmap phase 3** · 2026-07-03

## 1. Source

- Spec [§3.6 Process probe](../build-telemetry-spec.md) and the `processes` block of the
  [§4 payload schema](../build-telemetry-spec.md); [§3.8 HTML artifact](../build-telemetry-spec.md)
  ("process snapshot"); [§6 dashboard](../build-telemetry-spec.md) build-detail page.
- Roadmap [Phase 3](../build-telemetry-roadmap.md) process-probe bullet and its exit
  criterion "process panel shows configured-vs-used memory".
- Research: [InfoKotlinProcess](../research/repos/InfoKotlinProcess.md) (ValueSource-in-
  BuildService recipe, store/reuse test template),
  [build-process-watcher](../research/repos/build-process-watcher.md) (jstat header
  mapping, `ps -o rss=`, `jinfo -flags`, GC/heap math traps),
  [comparison-to-spec §2.2 / §4.1](../research/comparison-to-spec.md) (measurement math).
- Precedent: bounded subprocess exec ([plan 015](015-vcs-exec-timeout.md), architecture §2
  rule 11, [`GitExec`](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/GitExec.kt)).

## 2. Scope

**In:** one end-of-build snapshot of the JVMs matching main classes `GradleDaemon`,
`KotlinCompileDaemon`, `GradleWorkerMain`, via a `ProcessProbeValueSource` wrapping
`jps`/`jstat`/`jinfo`/`ps` execs, held as a lazy `Provider` in the Flow finalizer's
parameters and resolved only at build end (CC-safe). Records per process: role, heap
used/committed/max, configured `-Xmx`, GC time, RSS, uptime. Adds a `processProbe { enabled }`
DSL block (default true), an additive `processes` schema block in commons + one new golden
file, a dashboard build-detail process panel (configured-vs-used memory), and an HTML-
artifact process-snapshot section. Bounded timeout on every exec; degrade to `processes: []`
on any failure.

**Out:** time-series sampling (spec defers to v1.x) · the overhead budget / self-benchmark
harness — [plan 034](034-plugin-overhead-budget.md) (this probe's cost feeds it) · APK/AAB
sizes — [plan 031](031-artifact-size-capture.md) · CI spans — [plan 028](028-azure-devops-connector.md)
· INTERRUPTED/lost-build accounting — [plan 033](033-lost-build-accounting.md) (a daemon
killed before the finalizer still never reports) · server-side per-process rollups (the
panel reads the stored payload directly, as the task table already does).

## 3. Design

**Measurement math (pinned per research §4.1, non-negotiable):**

- Heap **used** = jstat `EU+OU+S0U+S1U` (survivors included, unlike build-process-watcher
  which drops them); **committed** = `EC+OC+S0C+S1C`; **max** from jstat `-gccapacity`
  (`NGCMX+OGCMX`) — *not* `-Xmx`, which is the configured value.
- **Configured `-Xmx`** from `jinfo -flags` per PID, read from `-XX:MaxHeapSize` (a literal
  `-Xmx4g` surfaces there anyway) — the "configured vs used" numerator the exit criterion
  names; capacity ≠ Xmx.
- **GC time** = jstat `GCT` **total** column, never `YGCT+FGCT` (omits `CGCT`, undercounts
  concurrent G1/ZGC — the research's headline trap).
- **RSS** = `ps -o rss= -p <pid>` (KB → MB), portable across Linux/macOS, replacing spec
  §3.6's Linux-only `/proc` mention.
- jstat columns mapped **by header name**, not position (layouts differ across JDKs); null
  for any absent column.

**Reuse-vs-vendor (roadmap asks us to evaluate).** `io.github.cdsap:commandline-value-source`
and `:jdk-tools-parser` hold InfoKotlinProcess's real exec/parse logic. We **vendor the
recipe, not the code**: (a) they run on Gradle's embedded Kotlin stdlib and we can't audit
their `apiVersion` pin (architecture §2 rule 10 hazard); (b) their model emits stringly
"1.2 GB"/"minutes" values, losing the structured numbers the schema needs; (c) our math
(survivors, GCT total, `-gccapacity`) diverges from theirs. A self-contained `ProcessMetrics`
runner reuses the proven `GitExec` bounded-exec machinery instead. Decision recorded in the
architecture log (step 12).

**Modules touched.**

- `buildhound-commons`: `ProcessInfo` + `ProcessRole` in `BuildPayload.kt`; add
  `processes: List<ProcessInfo> = emptyList()` to `BuildPayload` (additive, default empty).
- `buildhound-gradle-plugin`: `ProcessProbeValueSource` (emits a `List<CollectedProcess>`
  serializable DTO, mirroring `EnvironmentValueSource`), a Gradle-free `ProcessMetrics`
  runner + `ProcessParsing` (unit-testable like `VcsParsing`), wiring in
  `BuildHoundSettingsPlugin`, a new param on `TelemetryFinalizerAction.Parameters`, mapping
  in `PayloadAssembler`, `processProbe { enabled }` in `BuildHoundExtension`.
- `buildhound-report`: process-snapshot `<section>` in `report-template.html` after Tasks.
- `buildhound-server`: dashboard-only — a process panel in `dashboard.js` `detailView`. No
  route/migration/`BuildStore` change: `processes` already rides in the stored jsonb.

**Data flow.** `apply` registers the ValueSource (params: enabled, timeout) and stores its
`Provider` in `flowScope.always { … }` — the pattern the git/env/ci providers already use
(BuildHoundSettingsPlugin.kt:55-100). At build end `TelemetryFinalizerAction.execute`
resolves `parameters.processes` and passes it to `PayloadAssembler.assemble`. The probe
touches no `Project`/`Gradle` type and only reads `System`/`ProcessBuilder`, so CC store/reuse
is clean and isolated-projects is unaffected (no per-project state to degrade).

**Payload schema (additive).** `ProcessRole { GRADLE_DAEMON, KOTLIN_DAEMON, GRADLE_WORKER }`;
`ProcessInfo(role, heapUsedMb?, heapCommittedMb?, heapMaxMb?, configuredXmxMb?, gcTimeMs?,
rssMb?, uptimeS?)`. **No PID in the payload** (host-local noise, unneeded downstream, keeps
cardinality down — §6); role is the only key, multiple workers collapse to repeated
`GRADLE_WORKER` rows.

## 4. Implementation steps

1. **Commons schema.** Add `ProcessRole`, `ProcessInfo`, and `processes = emptyList()` to
   `BuildPayload.kt`; all fields nullable/defaulted so round-trip and old servers stay green.
2. **New golden file.** Add `build-payload-v1-processes.json` (a v1 doc with a populated
   `processes` array: a `GRADLE_DAEMON` with a configured-vs-used delta + a `KOTLIN_DAEMON`);
   extend `GoldenPayloadTest` with a case for it. **Never edit** the existing golden file
   (it already exercises the "field absent" path).
3. **`ProcessMetrics` runner (Gradle-free).** Reuse `GitExec`'s bounded-exec discipline
   (`ProcessBuilder` + `waitFor` + `destroyForcibly`, capped/drained stdout, discarded
   stderr, closed stdin). Methods: `listPids()` (`jps -l`, filter to the three main classes),
   `gcStats(pid)` (`jstat -gc`/`-gccapacity`), `flags(pid)` (`jinfo -flags`, once per PID),
   `rssMb(pid)` (`ps -o rss= -p`). Generalise `GitExec.run`'s existing `executable` seam (or
   add a sibling) to keep one bounded-exec code path.
4. **`ProcessParsing` (Gradle-free, unit-tested).** `parseJpsLines` → role by main class;
   `parseJstatByHeader(header, values, names)` → name-keyed map, null-on-absent; heap math
   (`used = EU+OU+S0U+S1U`, `committed`, `max` from `-gccapacity`, `gcTimeMs` from `GCT`
   s×1000); `parseJinfoMaxHeap` → `-XX:MaxHeapSize` bytes. Bytes→MB, s→ms conversions here.
5. **`ProcessProbeValueSource`.** Params `enabled`, `timeoutMillis`. `obtain()`: empty when
   disabled; list PIDs; per PID run jstat + jstat-capacity + jinfo + ps, each guarded so one
   failed probe drops one field not the process; the whole wrapped so any exception →
   `emptyList()`. Log exception **class** only (jinfo/ps output can embed paths/args). One
   timeout budget per exec; on repeated timeout stop probing further PIDs (mirrors `GitProbe`).
6. **DSL.** Add `ProcessProbeSpec { enabled: Property<Boolean> }` + a `processProbe` block to
   `BuildHoundExtension`; `enabled.convention(true)` in `apply`.
7. **Plugin wiring.** Register the ValueSource with `settings.providers.of(...)`, passing
   `enabled` AND-ed with the master switch (as env/vcs do) and a timeout from a new
   `buildhound.processprobe.timeout.ms` gradle property (default `GitExec.DEFAULT_TIMEOUT_MS`
   — the plan-015 test-seam/escape-hatch pattern). Store the provider into a new `processes`
   flow-action parameter.
8. **Finalizer + assembler.** Add `@get:Input @get:Optional val processes:
   Property<List<CollectedProcess>>` to `Parameters`; resolve with `getOrElse(emptyList())`.
   `PayloadAssembler.assemble` gains a `processes` arg, maps each `CollectedProcess` →
   `ProcessInfo`, sets `BuildPayload.processes`. Process fields are numeric/enum (nothing to
   scrub); confirm `PayloadScrubber` leaves the block untouched.
9. **HTML artifact.** Add a "Process snapshot" `<section>` after Tasks, rendered by the
   existing zero-dependency `render(d)`: a table (role, used/committed/max, configured Xmx,
   GC time, RSS, uptime) with a configured-vs-used bar per process; hidden when
   `d.processes` is empty (empty-hidden pattern). No new script dependency.
10. **Dashboard panel.** In `detailView`, after the cache summary, render `build.processes`:
    per-process configured-vs-used (used / Xmx, inline-SVG bar via the existing `svgEl`) plus
    GC time and RSS. Strings reach the DOM via `textContent` only; roles are an allowlisted
    enum. Hidden when absent.
11. **Failure-injection test** (phase guardrail) — see §5.
12. **Docs.** Add the vendor-not-reuse decision to the `docs/architecture.md` decision log,
    and a one-line §2 note that the bounded-exec rule (11) now covers JDK tools, not just
    git. Reconcile spec §3.6 wording to the pinned GCT-total / survivors / `-gccapacity`
    decisions (living-doc rule) and document the single-snapshot blind spots (in-process
    compilation, daemon exited early → empty list) as payload semantics.

## 5. Test strategy

- **Commons golden:** `GoldenPayloadTest` gains the `processes` case (step 2); round-trip +
  unknown-field cases stay green over the extended schema.
- **Plugin unit (Gradle-free, the bulk):**
  - `ProcessParsingTest`: header mapping picks columns by name across two synthetic JDK
    layouts (reordered/renamed) and returns null for an absent column; GC time reads `GCT`
    total not `YGCT+FGCT` (fixture where they differ); heap-used includes survivors; jinfo
    `-XX:MaxHeapSize=4294967296` → 4096 MB and tolerates a missing flag; jps filters only the
    three main classes.
  - `ProcessMetricsTest`: via `GitExec`'s fake-binary seam — timeout → degraded, non-zero
    exit → skipped process, stderr never captured.
  - `PayloadAssemblerTest`: DTOs map to `ProcessInfo`; empty input → `processes: []`; a
    configured-vs-used delta survives assembly + scrubbing.
- **Plugin functionalTest (TestKit):** a `processProbe { enabled = true }` build writes a
  payload whose `processes` is a valid list — present (≥ a `GRADLE_DAEMON`, the build runs in
  a daemon) or empty on agents without `jps` on PATH; assert the field exists, not a specific
  count (JDK-tool availability varies). Add the **store/reuse** case (InfoKotlinProcess
  template): two `--configuration-cache` runs, "entry stored" then "reused", probe still runs
  on reuse. Add `processProbe { enabled = false }` → empty list.
- **Failure injection:** a fake `jps` that hangs with
  `buildhound.processprobe.timeout.ms=200` — build still succeeds, payload still writes,
  `processes: []` (never-fail/never-hang rule).
- **Server:** no new test (jsonb passthrough); keep the dashboard smoke test green.

## 6. Risks

- **CC:** any provider resolution or `System`/`ProcessBuilder` read at configuration time
  breaks CC. Mitigation: all exec inside `obtain()`, provider only in flow params, resolved
  in `execute()` — the proven env/vcs pattern; store/reuse test is the backstop.
- **Isolated projects:** the probe has no per-project configuration state, so unlike the
  task-type dictionary it needs no `BuildFeatures` degradation — inherently IP-safe; the IP
  CI job (phase 2a) must stay green.
- **Schema compat:** additive with an empty-list default; new golden added, old untouched;
  contract/round-trip tests enforce.
- **Measurement correctness:** GCT-total / survivors / capacity≠Xmx are the research's
  explicit traps; unit tests pin each so a refactor can't silently reintroduce the undercount.
- **Security/privacy:** no PID or command line in the payload; fields are numeric; probe
  failures log exception **class** only (jinfo/ps output embeds absolute paths and JVM args,
  some secret-shaped like `-Dtoken=…`). No new network surface or token handling; jinfo
  output is parsed for MaxHeapSize only and discarded.
- **Overhead:** four short JDK-tool execs per JVM at build end, timeout-bounded — exactly the
  input plan 034 measures; one snapshot (no sampling) keeps it cheap.
- **Tool absence / short-lived processes:** missing `jps` (JRE-only agents), a Kotlin daemon
  that exited, or in-process compilation all yield `[]`. Documented as payload semantics
  (`processes: []` = "nothing observable"), not an error.

## 7. Exit criteria

- `./gradlew build` green; a real Kotlin-project build on an agent with JDK tools yields a
  `processes` array with at least a `GRADLE_DAEMON` (non-null configured `-Xmx` and heap-used)
  and, when a Kotlin daemon ran, a `KOTLIN_DAEMON`.
- Two `--configuration-cache` builds show "entry stored" then "reused" with the probe still
  populating `processes` on the reused build.
- With `processProbe { enabled = false }`, or JDK tools absent, or a probe timeout,
  `processes` is `[]` and the build still succeeds (failure-injection test green).
- The HTML artifact renders a process-snapshot section (hidden when empty) with no external
  requests (zero-CDN test still green).
- The dashboard build-detail page shows a process panel with configured-vs-used memory per
  process (roadmap phase-3 exit criterion).
- GC time reads `GCT` total and heap-used includes survivors, pinned by unit tests.
- New golden file added; existing golden file unchanged; all contract tests pass.
