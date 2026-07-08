# 065 — Process-probe GC/JVM tuning signals + daemon-JDK candidates

## Source

- **Research finding F15**, `docs/research/ingest-corpus-analysis.md`
  (`### F15 — Extend the process probe with GC collector + JVM flags`), including its
  Narrowings block (plaintext `workers.max`, `gcTimeMs` is cumulative → normalize/delta,
  typed allowlist instead of a scrubbed jvmargs string).
- Source guidance F15 distils: Google's "GC > 15 % of build time ⇒ raise heap" threshold;
  Gradle's "the most effective optimization is upgrading the daemon JVM"; JEP 519 Compact
  Object Headers (~22 % heap, JDK 24+); ParallelGC-vs-G1 throughput trade-off; Kotlin daemon
  `kotlin.daemon.jvmargs` tuning.
- Builds on [plan 029](implemented/029-process-probe.md) (process probe, spec §3.6),
  [plan 022](implemented/022-input-fingerprints-compare.md) (`gradle.maxWorkers` **hash**),
  [plan 032](implemented/032-bottlenecks-landing-page.md) (toolchain + bottleneck rollups),
  [plan 030](implemented/030-benchmark-mode.md) (benchmark series). Spec §3.6 (process probe),
  §3.7 (privacy), §4 (payload schema); roadmap phase 3.

## Scope

**In** — all additive, all nullable; `schemaVersion` stays 1.

- **Plugin/commons capture (the spine).** From the jinfo `-flags` output plan 029 *already*
  fetches (no new subprocess): `ProcessInfo.gcCollector` (new `GcCollector` enum),
  `ProcessInfo.compactObjectHeaders: Boolean?`, `ProcessInfo.pid: Int?` (pid is already parsed,
  just not carried). Plus `EnvironmentInfo.workersMax: Int?` — plaintext `org.gradle.workers.max`
  from the `startParameter.maxWorkerCount` already captured CC-safely
  (`BuildHoundSettingsPlugin.kt:256`); the hashed `gradle.maxWorkers` fingerprint stays.
- **Server consumption as *ranked candidates*** (advisory, never confirmed fixes), reading the
  stored payload directly — the plan-029 server line (no route/store/migration): build-detail
  candidate cards (GC-pressure ⇒ raise heap; Kotlin pinned-Xmx ⇒ raise `kotlin.daemon.jvmargs`;
  ParallelGC-vs-G1 *suggestion*; CompactObjectHeaders on JDK 24+/high-rss), each naming the
  role-specific knob.
- **Daemon-JDK fleet comparison** — extend the plan-032 toolchain `jdk` rollup with p50 duration
  grouped by JDK **major** ("your JDK 17 daemons are p50 X % slower than your JDK 21 daemons").
- **Benchmark slicing** — `workersMax` as an optional `benchmarkSeries` slicing dimension.
- New golden `build-payload-v1-process-tuning.json`.

**Out (deferred)**

- ParallelGC-vs-G1 as an orchestrated **A/B trial** harness — ship the suggestion card, not the trial.
- Time-series per-build GC sampling (still plan-029's v1.x deferral) — *why* GC % is a proxy here,
  not Google's exact per-build metric.
- Collector as a benchmark slicing dimension (`processes` is an array — which daemon? — follow-up).
- A free-form "scrubbed jvmargs summary" — explicitly rejected (see Risks).

## Design

Three modules; no internal Gradle APIs (jps/jstat/jinfo/ps are external processes) so this stays in
CORE — no internal-adapters. Server changes read the already-tenant-scoped stored payload; no new
endpoint.

**Capture (plugin).** `ProcessParsing` gains **typed allowlist** extraction over the jinfo `-flags`
string already captured in `ProcessProbeCollector` (`tools.jinfoFlags(pid)`, line 27):
`parseGcCollector` maps the first present of `-XX:+UseG1GC|+UseParallelGC|+UseZGC|+UseSerialGC|
+UseShenandoahGC|+UseEpsilonGC` → `GcCollector`; `parseCompactObjectHeaders` reads
`-XX:[+-]UseCompactObjectHeaders` → `Boolean`. Only allowlisted `-XX:[+-]<KnownFlag>` /
`-XX:<KnownFlag>=<num>` tokens are read; every other token (paths, `-D…` args, classpath jinfo also
prints) is discarded by construction. `pid` is already parsed by `ProcessParsing.parseJpsLine`; the
collector carries it into `CollectedProcess.pid` (`ProcessProbeValueSource.kt:11`).
`PayloadAssembler` maps the three onto `ProcessInfo`.

**workersMax (plugin).** `EnvironmentValueSource.Parameters` gains `workersMax: Property<Int>`, set at
registration from `settings.startParameter.maxWorkerCount` (the same CC-safe scalar fingerprints
reads); `CollectedEnvironment` + `EnvironmentInfo` gain `workersMax: Int?`.

**Candidates (server).** A pure, unit-testable `DaemonTuningCandidates.evaluate(processes,
environment, toolchain)` → ranked `List<TuningCandidate>` (enum kind + role + advisory string),
consumed by the dashboard `detailView` process panel and the HTML artifact's process section
(plan-029 §6a surfaces). Rules, all advisory:
- **GC-pressure** — primary input is the uptimeS-normalized lifetime fraction
  `gcTimeMs / (uptimeS*1000)`; a pid-delta refinement applies **only** when
  `environment.daemonReused == true` AND GCT is monotonic vs the prior same-pid build (a negative
  delta ⇒ pid reuse/daemon restart ⇒ fall back to the fraction). ≥ 0.15 ⇒ "investigate high GC —
  raise heap", naming `org.gradle.jvmargs` (GRADLE_DAEMON) vs `kotlin.daemon.jvmargs` (KOTLIN_DAEMON).
- **Kotlin pinned-Xmx** — `heapUsedMb ≈ configuredXmxMb` (≥ 0.9) on KOTLIN_DAEMON ⇒ "raise
  `kotlin.daemon.jvmargs`". **No new field** — both already exist.
- **ParallelGC-vs-G1** — G1 + high GC fraction ⇒ "throughput-bound? trial ParallelGC" (suggestion).
- **CompactObjectHeaders** — `toolchain.jdk` major ≥ 24 + high rss + `compactObjectHeaders != true` ⇒
  "enable `-XX:+UseCompactObjectHeaders` (~22 % heap, JEP 519)".

**Daemon-JDK rollup (server).** `ToolchainSample` (`Bottlenecks.kt:86`) widens with `durationMs`;
`ToolchainVersionRow` gains `durationP50Ms`; `ToolchainCalculator.dimension` computes p50 per version
and the jdk dimension groups by **major** (leading numeric segment). The `PostgresStores` toolchain
pass (~730-769) adds `durationMs` to the jdk sample read. Not pure reuse — the sample/row types
genuinely widen.

**Benchmark slicing (server).** `benchmarkSeries` (`PostgresStores.kt:550`) gains an optional
`workersMax` filter (`payload->'environment'->>'workersMax'`).

## Test strategy

- **Commons golden** — new `build-payload-v1-process-tuning.json` (processes carrying
  pid/gcCollector/compactObjectHeaders + `environment.workersMax`); `GoldenPayloadTest` gains a case;
  `build-payload-v1-processes.json` **untouched**; round-trip + unknown-field cases stay green.
- **Plugin unit `ProcessParsingTest`** — collector picked from a realistic jinfo `-flags` line;
  `-XX:-UseCompactObjectHeaders` → false, `+` → true, absent → null; **a line containing
  `-Dtoken=… -cp /abs/path` yields ONLY the typed allowlisted fields** (leak-proof-by-construction
  assertion); an unknown/absent collector → `UNKNOWN`/null.
- **Plugin `PayloadAssemblerTest`** — pid/collector/headers/workersMax survive assembly + scrubbing
  unchanged.
- **Plugin functionalTest (TestKit)** — a real daemon build populates `gcCollector` + `workersMax`
  (or degrades to null on JRE-only agents); assert field *presence*, not values (JDK-tool availability
  varies). CC store/reuse case still populates on reuse.
- **Server unit** — `DaemonTuningCandidatesTest`: each rule fires/stays silent on fixtures; the GC
  pid-delta falls back to the fraction on a negative delta or `daemonReused=false`; candidates are
  advisory (no mutation). `ToolchainCalculatorTest`: p50-by-major over a JDK 17-vs-21 fixture.
- **Server Testcontainers** — toolchain rollup returns `durationP50Ms` per jdk major;
  `benchmarkSeries` filters by workersMax; both stores keep byte-parity (plan-026 discipline).

## Risks

- **`pid` reverses a plan-029 decision (named, not hand-waved).** Plan 029 asserts "no PID" in the
  `ProcessInfo`/`ProcessRole`/`ProcessProbeValueSource` doc comments and its §3/§6. Mitigation: add a
  **superseding `docs/architecture.md` decision-log entry** and update those doc comments in this PR.
  Justification: pid is an ephemeral host-local integer — not PII/path/secret, so nothing to scrub —
  used server-side only as a correlation key *within one `hostnameHash`*, never a rollup group key
  (cardinality unchanged).
- **GC % is a proxy — candidate, not Google's confirmed metric.** `gcTimeMs` is cumulative GCT; a true
  per-build GC % needs start+end sampling (deferred). The uptimeS-normalized fraction is the always-on
  input; the pid-delta only sharpens it and is guarded (monotonic + `daemonReused`). Every card says
  "investigate", never "confirmed fix".
- **Privacy — typed allowlist, leak-proof by construction (§3.7).** New fields are discrete typed
  enum/bool/int, **not** a map or free-form string; extraction reads only allowlisted `-XX:` flags and
  discards all other jinfo tokens, so the `-Dtoken=…`/absolute-path/classpath risk that made plan 029
  reject a "scrubbed jvmargs summary" cannot recur. Typed fields need no scrubbing — that is *why* they
  are safe.
- **Additive-schema.** All fields nullable/defaulted; `schemaVersion` stays 1; new golden added,
  existing goldens untouched; contract/round-trip tests enforce.
- **Configuration cache.** All capture stays in execution-time `obtain()` (jinfo already fetched, pid
  already parsed); `workersMax` is a config-time scalar baked into ValueSource params
  (`BuildHoundSettingsPlugin.kt:256`) — no new config-phase file read, no new CC fingerprint input.
- **Isolated projects.** The probe has no per-project state and does not touch the plan-016 `whenReady`
  dictionary — inherently IP-safe; the IP CI job stays green.
- **Multi-tenancy.** Candidate cards and the widened rollup read the already token + tenant-scoped
  payload/rollup routes — no new endpoint, no new authz surface.
- **Never-fail / never-hang.** Unchanged: any probe/parse miss drops one field to null; the whole
  `obtain()` still degrades to `emptyList()`; server rules that see nulls simply emit no candidate.

## Exit criteria

- A real daemon build carries `gcCollector` (e.g. `G1`) + `workersMax`, and — when observable —
  `pid`/`compactObjectHeaders`; JRE-only agents / probe timeouts still degrade to null/`[]` and the
  build succeeds.
- jinfo output containing `-D…`/absolute paths yields **only** the typed allowlisted fields
  (unit-pinned leak test).
- Build-detail shows ranked tuning candidates naming `org.gradle.jvmargs` vs `kotlin.daemon.jvmargs`
  per role; each is advisory, none auto-applied.
- Toolchain rollup reports p50 duration by JDK major; `benchmarkSeries` slices by `workersMax`; both
  stores stay byte-for-byte parity.
- New golden added, existing goldens unchanged, `schemaVersion` 1; decision-log + plan-029 doc comments
  updated; `./gradlew build` green.

## Implementation notes (2026-07-08)

- **How "consumed by the dashboard/artifact" is realized.** The HTML artifact is zero-network
  (locked decision #4) and the dashboard's build-detail reads the tenant-scoped payload route, so
  neither surface can call a server evaluator directly. The pure `DaemonTuningCandidates` is the
  pinned, unit-tested source of truth for the rules/thresholds; the dashboard `detailView` and the
  artifact's process section render client-side mirrors of its **primary-input** rules (lifetime
  fraction only) from the already-fetched payload. Server-side, the evaluator rides the existing
  plan-071 `GET /v1/builds/{buildId}/diagnosis` synthesis as an additive `tuningCandidates` field
  (openapi updated) — still no new endpoint/store/migration, and agents get the ranked list too.
- **pid-delta scope.** The refinement (monotonic GCT + `daemonReused` guard, negative-delta
  fallback) is implemented and unit-pinned in `DaemonTuningCandidates.gcFraction` via an optional
  prior-snapshot parameter; no store lookup for "the prior same-pid build" exists yet (this plan
  adds no store method), so the `/diagnosis` call site passes no prior and production cards use the
  always-on lifetime fraction — exactly the plan's "primary input"; the delta channel is ready for
  a follow-up that wires a prior-build fetch. When that follow-up lands, the prior-snapshot store
  lookup must be scoped by **both** tenant and `hostnameHash` — `pid` is only unique within one
  host (spec §3.6), so an unscoped lookup could correlate two different tenants' builds on the same
  CI runner by a coincidentally-reused PID.
