# Plugin Ecosystem Gap Analysis — Tuist, eBay, CCUD, cdsap

**Date:** 2026-07-03 · **Status:** research (input for future `docs/plans/` entries)
**Question:** which metrics/features from four reference projects should BuildHound adopt, and per feature — CORE (main `dev.buildhound` plugin / server) or ADDON (separate companion plugin under `dev.buildhound.*`)?

Companion docs: [cache-miss-input-fingerprints.md](cache-miss-input-fingerprints.md) (the priority
cache-miss comparison feature) and [test-distribution-addon.md](test-distribution-addon.md)
(sharding addon design). Baseline context: [build-telemetry-research.md](../build-telemetry-research.md),
[build-telemetry-spec.md](../build-telemetry-spec.md), [architecture.md](../architecture.md).

**Method.** Multi-agent research: 5-angle web sweep → 24 primary sources fetched → 118 claims
extracted → top 25 adversarially verified (3 votes each; 19 confirmed, 6 refuted), followed by
three targeted source-verification passes (CCUD, sharding mechanics, cache-miss fingerprints)
that fetched repo files raw and quote verbatim. Claims below carry confidence tags. One
important correction: the adversarial pass *refuted* the `tuistPrepareTestShards` two-phase
sharding mechanism, but the follow-up pass **confirmed it from `main` source** — the feature is
recent (commits 2026-03-23/25) and verifiers had checked stale docs/releases. Where the two
passes disagree, source-verified findings win and are marked so.

---

## 1. Executive summary — ranked adoption candidates

| # | Feature | Verdict | Where | Effort | Rationale |
|---|---|---|---|---|---|
| 1 | **Input-fingerprint capture + two-build compare** (tier a+c) | **CORE** | plugin DSL + payload + server endpoint | M | The priority feature; pure public API; see companion doc |
| 2 | **CI provider expansion** (10 CCUD providers) + honor bare `CI` env for mode classification | **CORE** | `buildhound-commons` SPI | S | Today CircleCI/GitLab/Travis/Jenkins… builds are misclassified `mode=local` with no `ci` block (§4.3) |
| 3 | **Populate `TaskExecution.type` (+ `cacheable`)** | **CORE** | plugin | S | Fields exist (`BuildPayload.kt:98,102`), collector never fills them (scoped out of plan 005, no follow-up). CC-safe source resolved by Talaiot's proven pattern: configuration-time `taskGraph.allTasks` path→class dictionary (strip `_Decorated`) snapshotted into BuildService parameters, degrading to empty under isolated projects via public `BuildFeatures` — see [repos/Talaiot.md](repos/Talaiot.md), [comparison-to-spec.md](comparison-to-spec.md) §2.1. Unlocks by-type rollups (#4) and honest hit rate (the current `cacheableHitRate` mixes non-cacheable work into its denominator) |
| 4 | **eBay-style server rollups**: Project Cost family, task duration by name/type, top-25 rankings | **CORE** | server only | M | Computable from already-ingested data (§3); no plugin change except #3 |
| 5 | **IDE + AI-agent detection** | **CORE** | plugin env probe | S | CCUD-proven cheap detection (2 sysprops, 2 Gradle props, handful of env vars); new payload fields |
| 6 | **Flaky-test detection algorithm** (retry signal + cross-run divergence keyed on (commit, module)) | **CORE** | server | M | Already on roadmap (phase 4); Tuist supplies the concrete algorithm + comparison key (§2.3) |
| 7 | **Quarantine closed loop** (skipped/muted modes) | **ADDON** `dev.buildhound.test-quarantine` | new module | M | Mutates `Test` tasks (`ignoreFailures`, filters) — violates core's no-mutation rule (§2.4) |
| 8 | **Test sharding** (server-balanced LPT plan) | **ADDON** `dev.buildhound.test-sharding` + CORE shard-plan endpoint | new module + server | L | Full design in [test-distribution-addon.md](test-distribution-addon.md) |
| 9 | **Config-cache miss-reason capture + rollup** | CORE (best-effort) | plugin + server | M | eBay proves the rollup's value; capture has no public API — options in companion doc §2.6 |
| 10 | **`uploadInBackground` DSL knob** | **CORE** | plugin DSL | XS | Tuist parity. Note (corrected 2026-07-03): BuildHound ships synchronous-with-spool everywhere (plan 008) — no background path exists; the knob is an opt-out from blocking local builds on the upload attempt (§2.1). Spec §3.9's background-thread text is stale either way |
| 11 | Git remote URL (redacted) + source/PR links + GHA run-attempt | **CORE** | plugin + payload | S | CCUD-proven; fixes GHA buildUrl attempt-collision; redaction rules in §4.5 |
| 12 | `BUILDHOUND_*` env / `buildhound.*` sysprop config overrides | **CORE** | plugin | S | CCUD `Overrides` pattern: mechanical sysprop↔env naming, applied without touching build scripts |
| 13 | Hard timeout on git execs | **CORE** (hardening) | plugin | XS | CCUD uses 10 s + `destroyForcibly()`; BuildHound's `VcsValueSource` currently has none — a hung git stalls the finalizer |
| 14 | Known-volatile-input catalog + env sanity rules (gradle-doctor class of checks) | CORE server rules (maybe later a doctor addon) | server | M | Catalog sourced from android-cache-fix workarounds; see companion doc §5 |

Not adopted / deliberately skipped: CCUD's `git status --porcelain` full output (ships dirty-file
paths — violates spec §3.7; BuildHound's emptiness-only design is stronger), Hudson support
(dead), per-test-case ingestion for passing tests (locked decision #2 stands), Develocity
Test Distribution model for sharding (needs proprietary broker/agents, §5).

---

## 2. Tuist Gradle plugin (`dev.tuist`, monorepo `tuist/tuist` under `gradle/`)

Latest release 0.10.0 (Plugin Portal, 2026-04-22); sharding landed 2026-03-23/25, quarantine
skip/mute split 2026-04-27. Settings plugin (`Plugin<Settings>`) — same integration surface as
BuildHound. All claims source-verified on `main` unless noted.

### 2.1 Collection scope — validates BuildHound's phasing

- Per-task records: path, outcome enum **with `LOCAL_HIT`/`REMOTE_HIT` split**, cacheable,
  duration, **per-task build-cache key, cache artifact size** — captured via **internal**
  build-operation APIs (`BuildCacheLocalLoadBuildOperationType`, `BuildCacheRemoteLoadBuildOperationType`
  via `BuildOperationListenerManager`/`GradleInternal`). Direct evidence no public API exists for
  cache keys/origin → validates BuildHound's isolated `internal-adapters` plan (roadmap phase 4).
- Per-test-case telemetry (name, suite, status, duration, failure message/path/line, retry
  repetitions) via the **public** `TestListener` API → BuildHound's phase-2 test collection is
  feasible public-only, as spec'd.
- Upload semantics: same *intent* as BuildHound (don't lose telemetry on short-lived CI agents),
  different local mechanism — Tuist uploads on a background non-daemon thread locally
  (`uploadInBackground ?: !ciDetector.isCi()`), while BuildHound ships **synchronous upload with
  spool fallback everywhere** (`PayloadUploader`, plan 008; verified 2026-07-03 — spec §3.9's
  "background thread with JVM-exit flush" text describes a design that was never built and
  should be amended, see [comparison-to-spec.md](comparison-to-spec.md) §2.9). The
  `uploadInBackground` knob (#10) remains worth adopting, reframed: an opt-out for teams that
  prefer not to block local builds on the upload attempt, spooling instead.
- Dashboard metrics ("hit rate over time", "reliability trends") are server-side aggregates over
  these raw uploads — computation not source-verified, collection is.

### 2.2 What Tuist does NOT do (refuted claims — do not re-assert)

- No architectural dependency on the Tuist CLI binary for the Gradle plugin (refuted 3-0… i.e. 0-3 for the claim).
- The plugin does not bundle a remote build cache per its portal description.
- Quarantine has no verified "14-day auto-release" behavior.

### 2.3 Flaky detection (CORE, server)

Two mechanisms, both replicable over BuildHound's planned ingestion:

1. **Retry-based**: with the Test Retry plugin, fail-then-pass within one run ⇒ flaky. BuildHound's
   locked per-class + failure/retry-detail granularity already captures the needed signal
   (`outcomes: ["FAILED","PASSED"]` sequences, spec §3.5).
2. **Cross-run**: same commit + same Gradle project (module), divergent results across CI runs ⇒
   both runs marked flaky, no retries needed. Docs verbatim: "The Gradle project is part of the
   comparison key because the same test can behave deterministically differently across projects."
   Comparison key = **(commit sha, module path, test identity)**.

Granularity nuance for BuildHound: class-level rollups suffice to *detect* cross-run divergence
(class green in run A, red in run B), and the failing run always carries per-case detail (locked
shape), so the flaky *case* can be named from the failing side. No schema change needed; the
server-side algorithm is the new work. Fits roadmap phase 4 item 1 unchanged.

### 2.4 Quarantine closed loop (ADDON)

Source-verified mechanics: plugin fetches server's quarantined list before test tasks;
auto-enables on CI (`System.getenv("CI") != null`), off locally, DSL-overridable. Two modes:
**skipped** — `doFirst` applies `excludeTestsMatching`; **muted** — sets `ignoreFailures = true`
and re-fails via `doLast` + `TestListener` only on non-quarantined failures. Zero internal APIs
(verified: no `org.gradle.*.internal` imports in the quarantine path).

Verdict: **addon**, because it flips `ignoreFailures` on every `Test` task and injects filters —
the core plugin's "never silently mutates other plugins'/tasks' config" rule
(architecture §2, spec §3.4 KGP precedent) forbids that in `dev.buildhound` itself. An
explicitly-applied `dev.buildhound.test-quarantine` companion is the honest packaging: applying
it *is* the consent to mutation. Gate on proven detection precision stays (locked decision #3).

### 2.5 Test sharding

Confirmed from source (correcting the adversarial pass): two-phase, server-coordinated —
`tuistPrepareTestShards` posts discovered suites, server LPT-balances on 30-day p90 timings,
CI jobs carry `TUIST_SHARD_INDEX`, plugin fetches its slice at configuration time and filters via
public `TestFilter.includeTestsMatching` + `isFailOnNoMatchingTests=false` in `doFirst`.
Two defects worth learning from: **server unreachable ⇒ `GradleException` (build fails)** — the
inverse of BuildHound's never-fail rule — and a **join-key mismatch** (client sends bare class
FQCNs, server keys timings by `module/class`) that silently degrades Gradle-path balancing to
round-robin. Full analysis + BuildHound addon design: [test-distribution-addon.md](test-distribution-addon.md).

---

## 3. eBay metrics-for-develocity-plugin

Post-hoc **reporting framework**, not a collector: Gradle tasks query a Develocity server's API
(okhttp + `com.gabrielfeo.develocity.api`) and aggregate through pluggable summarizers
(`summarizers.add()`); task rule `metricsForDevelocity-<date|last-ISO8601>`. Verified: no
`BuildEventsListenerRegistry`/Flow/`buildScan.value` anywhere — zero build-time collection.
**Every eBay feature maps to BuildHound server-side rollup/reporting work over already-ingested
payloads (CORE, server).** The four summarizers are the inventory to replicate:

| Summarizer | What it computes | BuildHound status |
|---|---|---|
| **Project Cost** | Per module: total builds containing it; builds where ≥1 of its tasks executed; **buildImpactedUsers** (distinct users — a plain `Set` over user ids, works identically over BuildHound's hashed `userId`); aggregated serial task time; `buildAvgDuration`; `buildPercentage`; top-25 tasks by avg duration and by execution count; per-module build links; **`buildCostScalar = executedBuildAvgDuration × executedBuildPercentage.toInt()`** (int-truncates the percentage first; README hedges "may change") — surfaces modules both frequently built and expensive | New server rollup; all inputs already ingested (task path/module/duration/outcome + hashed user) |
| **Task Duration** | Total/ranked execution duration keyed by task NAME across modules or by task TYPE (implementation class, e.g. all `KotlinCompile` regardless of task name); daily count/total/min/avg/max | By-name: computable today. By-type: blocked on `TaskExecution.type` being populated — **field exists in schema (`BuildPayload.kt:98`) but `TaskEventCollector.onFinish` never fills it** because `TaskFinishEvent` descriptors expose only the path. Runtime gap, not schema gap. Resolved: Talaiot's configuration-time `taskGraph.allTasks` path→class dictionary is the CC-safe public-API source, with `BuildFeatures`-gated isolated-projects degradation ([repos/Talaiot.md](repos/Talaiot.md)) |
| **Config Cache Miss** | CC miss **reasons** with per-reason frequency over a window (`configCacheMissReport-P7D`), regex consolidation via `--pattern`; reads `gradleConfigurationCache.model.result.missReasons` from the Develocity API | BuildHound records only the CC state enum, no reasons anywhere in the schema. Rollup is trivial once reasons exist; capture is the hard part (no public Gradle API — options in [cache-miss-input-fingerprints.md §2.6](cache-miss-input-fingerprints.md)) |
| **User Query** | Ad-hoc per-user build queries | Covered by BuildHound's planned query API; hashed ids change UX (list pseudonyms), acceptable |

Also worth copying: the *extensible summarizer registration* idea maps to BuildHound's server as
pluggable rollup definitions — not urgent, note for the query-API design.

---

## 4. common-custom-user-data-gradle-plugin (CCUD, v2.7.0 2026-06-29)

Six Java files total; pure build-scan *decoration* (tags/values/links) — Develocity's own plugin
does the heavy telemetry. Everything below source-verified.

### 4.1 Captured inventory (vs BuildHound)

| CCUD capture | BuildHound status | Action |
|---|---|---|
| `os.name` tag | Have (`environment.os`) | — |
| **IDE detection**: `idea.vendor.name`=="Google"→Android Studio (+version from `android.studio.version` Gradle prop), =="JetBrains"→IntelliJ (+`idea.version`), `eclipse.buildId`→Eclipse, `VSCODE_PID`/`VSCODE_INJECTION`→VS Code, else "Cmd Line"; `idea.sync.active`→IDE-sync tag; skipped on CI | Missing entirely | Adopt (#5): `environment.ide` + `ideVersion` + `ideSync` additive fields. High value for local telemetry |
| **AI-agent detection** (new 2.7.x): `CLAUDECODE`→Claude Code, `CODEX_SANDBOX_NETWORK_DISABLED`/`CODEX_THREAD_ID`→Codex (best-effort), `CURSOR_AGENT`→Cursor, `OPENCODE`→OpenCode, `GEMINI_CLI`→Gemini CLI, `android.studio.agent`/`ANDROID_STUDIO_AGENT`→Gemini in Android Studio | Missing | Adopt (#5): `environment.aiAgent`. Cheap, increasingly interesting dimension |
| CI vs LOCAL tag incl. **bare `CI` env/sysprop** | Partial — see §4.3 | Fix mode classification |
| Per-CI metadata (12 providers) | 2 providers + generic | Adopt matrix (§4.4) |
| Git: remote URL (redacted), commit id, short id, branch (CI-env-first resolution), dirty tag, **full `git status --porcelain` output as value** | Branch/sha/dirty have; remote URL missing; porcelain detail **deliberately absent** (spec §3.7 — no paths) | Adopt redacted remote URL; do NOT adopt porcelain detail |
| Source links (`<repo>/tree/<sha>`, GitLab `/-/commit/`), GHA PR link, Buildkite PR-source link | Missing (only `ci.buildUrl`) | Adopt: compose server- or plugin-side from remote URL/`GITHUB_*`; reuse existing `isHttpUrl` gate |
| GHA `GITHUB_RUN_ATTEMPT` (+`/attempts/N` URL suffix), `CI run number`, `CI step` | Missing; **BuildHound's GHA buildUrl collides across re-run attempts today** | Adopt run-attempt (attributes + URL) |
| Jenkins `CI controller`/`NODE_NAME`, TeamCity/Bamboo agent | `agentName` deliberately dropped from payload (plan 005) | Re-evaluate only with pseudonymization |
| Develocity server/cache config overrides (`Overrides`) | Different concern | Copy the *pattern* (#12): `buildhound.<key>` sysprop ↔ `BUILDHOUND_<KEY>` env via mechanical `toUpperCase().replace('.','_')`, applied at `settingsEvaluated`. Skip their stack-trace-sniff hack |

Reverse view: BuildHound captures much CCUD doesn't (arch/cores/RAM, hashed identity, daemon
reuse, CC state, toolchain versions, tasks, derived metrics). No kernel/os.version capture in
CCUD either — that question closes as "no".

### 4.2 CCUD operational lessons

- **10-second hard timeout + `destroyForcibly()` on every git subprocess.** BuildHound's
  `VcsValueSource` has no timeout on `execOperations.exec` — hung git (fsmonitor, network
  worktree) would stall the build. Adopt (#13).
- Deferral discipline (CI metadata at `buildFinished` "so that CI metadata does not become a
  configuration cache input"; git in `buildScan.background`) — BuildHound already achieves the
  equivalent via ValueSources/Flow. Parity, no action.
- CCUD is `apply()`-time imperative; BuildHound's provider-based model is already stronger — do
  not copy structure, only the capture list.

### 4.3 Mode-classification gap (important, cheap fix)

CCUD treats bare `CI` env var **or system property** as CI. BuildHound's generic provider only
activates on `BUILDHOUND_CI=true`/`BUILDHOUND_CI_PROVIDER`, and `TelemetryMode.AUTO` resolves to
LOCAL when no provider matches — so today a CircleCI/GitLab/Travis/Jenkins build (all set `CI=true`
or are simply undetected) is recorded **`mode=local` with no `ci` block**: wrong baselines, wrong
foreground-upload behavior, wrong local-opt-in gating. Even before adding providers, honoring
bare `CI` for the CI/LOCAL decision closes the worst misclassification. (Provider stays
`"generic"`/unknown; only the mode flips.)

### 4.4 CI detection matrix to implement (detection var → mapping)

Vars marked ⚠ are standard provider vars CCUD does not itself read — verify against provider docs
during implementation (they were not source-verified).

| Provider (detect) | CiContext mapping |
|---|---|
| Jenkins (`JENKINS_URL`) | pipelineName=`JOB_NAME`, runId=`BUILD_NUMBER`, stageId=`STAGE_NAME`, buildUrl=`BUILD_URL`, agentName=`NODE_NAME`, branch=`BRANCH_NAME`∥`GIT_BRANCH` (strip remote prefix), sha=`GIT_COMMIT`⚠, PR=`CHANGE_ID`⚠/target `CHANGE_TARGET`⚠, attr controllerUrl=`JENKINS_URL` |
| TeamCity (`TEAMCITY_VERSION`) | ⚠ Caveat: CCUD reads build id/agent/serverUrl from the `TEAMCITY_BUILD_PROPERTIES_FILE` **properties file** — BuildHound's env-only SPI can't; env fallback: pipelineName=`TEAMCITY_PROJECT_NAME`⚠, jobId=`TEAMCITY_BUILDCONF_NAME`⚠, runId=`BUILD_NUMBER`⚠; no composable URL without the file chain |
| CircleCI (`CIRCLE_BUILD_URL`; canonical `CIRCLECI`⚠) | buildUrl=`CIRCLE_BUILD_URL`, runId=`CIRCLE_BUILD_NUM`, jobId=`CIRCLE_JOB`, pipelineId=`CIRCLE_WORKFLOW_ID`, branch=`CIRCLE_BRANCH`⚠, sha=`CIRCLE_SHA1`⚠, PR=`CIRCLE_PR_NUMBER`⚠, pipelineName=`CIRCLE_PROJECT_REPONAME`⚠ |
| Bamboo (`bamboo_resultsUrl` — lower-case!) | buildUrl=`bamboo_resultsUrl`, runId=`bamboo_buildNumber`, pipelineName=`bamboo_planName`, jobId=`bamboo_buildPlanName`, agentName=`bamboo_agentId`, branch=`bamboo_planRepository_branch`⚠, sha=`bamboo_planRepository_revision`⚠ |
| GitLab (`GITLAB_CI`) | buildUrl=`CI_JOB_URL` (attr pipelineUrl=`CI_PIPELINE_URL`), jobId=`CI_JOB_NAME`, stageId=`CI_JOB_STAGE`, branch=`CI_COMMIT_REF_NAME`, pipelineId/runId=`CI_PIPELINE_ID`⚠, sha=`CI_COMMIT_SHA`⚠, PR=`CI_MERGE_REQUEST_IID`⚠, target=`CI_MERGE_REQUEST_TARGET_BRANCH_NAME`⚠, pipelineName=`CI_PROJECT_PATH`⚠ |
| Travis (`TRAVIS_JOB_ID`) | buildUrl=`TRAVIS_BUILD_WEB_URL`, runId=`TRAVIS_BUILD_NUMBER`, jobId=`TRAVIS_JOB_NAME`, attr eventType=`TRAVIS_EVENT_TYPE`, branch=`TRAVIS_BRANCH`⚠, sha=`TRAVIS_COMMIT`⚠, PR=`TRAVIS_PULL_REQUEST`≠"false"⚠ |
| Bitrise (`BITRISE_BUILD_URL`) | buildUrl=`BITRISE_BUILD_URL`, runId=`BITRISE_BUILD_NUMBER`, branch=`BITRISE_GIT_BRANCH`⚠, sha=`BITRISE_GIT_COMMIT`⚠, PR=`BITRISE_PULL_REQUEST`⚠, target=`BITRISEIO_GIT_BRANCH_DEST`⚠, pipelineName=`BITRISE_TRIGGERED_WORKFLOW_ID`⚠ |
| GoCD (`GO_SERVER_URL`) | pipelineName=`GO_PIPELINE_NAME`, runId=`GO_PIPELINE_COUNTER`, stageId=`GO_STAGE_NAME`, jobId=`GO_JOB_NAME`, buildUrl=`<GO_SERVER_URL>/tab/build/detail/<pipeline>/<counter>/<stage>/<GO_STAGE_COUNTER>/<job>` (all-present guard), sha=`GO_REVISION`⚠ |
| Buildkite (`BUILDKITE`) | buildUrl=`BUILDKITE_BUILD_URL`, runId=`BUILDKITE_BUILD_ID`, branch=`BUILDKITE_BRANCH`, PR=`BUILDKITE_PULL_REQUEST`≠"false", sha=`BUILDKITE_COMMIT`⚠, pipelineName=`BUILDKITE_PIPELINE_SLUG`⚠, jobId=`BUILDKITE_JOB_ID`⚠, target=`BUILDKITE_PULL_REQUEST_BASE_BRANCH`⚠, agentName=`BUILDKITE_AGENT_NAME`⚠. Do NOT capture `BUILDKITE_COMMAND` (shell command line — scrub risk) |
| Generic (`CI` env **or sysprop**) | mode=CI only; provider `generic`; no metadata |

Azure detail: CCUD composes the build URL from `SYSTEM_TEAMFOUNDATIONCOLLECTIONURI`; BuildHound
uses `SYSTEM_COLLECTIONURI`. Both set on real agents; `SYSTEM_TEAMFOUNDATIONCOLLECTIONURI` is the
longer-documented one — consider reading both. CCUD's per-CI blocks are not mutually exclusive
(overlapping CIs all contribute); BuildHound's first-match-wins is cleaner — keep it, but order
built-ins most-specific-first once the list grows.

### 4.5 Redaction rules to copy (with fixes)

- `redactUserInfo`: parse with `java.net.URI`, replace non-empty userInfo with `******`;
  unparseable ⇒ drop the value entirely (fail-closed — copy this). CCUD bug not to copy: its
  `http`-prefix guard returns `ssh://user:pass@host/…` **unredacted** — BuildHound must redact
  all schemes.
- `toWebRepoUri` regex rebuilds `https://<host>/<path>` structurally discarding credentials, but
  only for hosts containing `github`/`gitlab` (fail-closed for others). Acceptable v1 for links;
  the redacted remote URL value itself should not be host-gated.

---

## 5. cdsap/PaparazziTestDistributionExtension (premise correction)

**Not a test-distribution tool.** It is a compatibility shim (plugin `io.github.cdsap.td.paparazzi`
0.5.0, 2026-06-06) that makes Paparazzi's HTML report survive **Develocity Test Distribution**:
each executor writes into a unique `td-<timestamp>/` dir (via `paparazzi.td.report.dir` sysprop),
and a `@CacheableTask` merge task (`mergePaparazzi<Variant>Outputs`, wired `finalizedBy`)
consolidates fragments. No timing logic, no sharding, no Develocity API calls; the distribution
itself is 100% proprietary Develocity (server + agents + access key).

Transferable lessons only: (1) report-writing test frameworks need per-executor output isolation
plus a merge step whenever one logical run is split — mostly moot under CI-level sharding (each
shard is its own build) but the per-run-dir + merge-finalizer pattern is the fix if plugin-level
merging is ever wanted; (2) what Develocity TD's balancer consumes is *expected execution time per
test class* — exactly the aggregate BuildHound's sharding endpoint must serve
([test-distribution-addon.md](test-distribution-addon.md)).

---

## 6. Addon architecture recommendation

Among the plugins analyzed here, no *addon-feeding-one-payload* pattern exists to copy: Tuist
ships insights + quarantine + sharding in **one** plugin; CCUD is a monolithic decorator. The
closest packaging prior art is Talaiot's core-plus-per-backend-publisher-plugin split
([repos/Talaiot.md](repos/Talaiot.md)) — it validates modular Gradle-plugin distribution, but its
modules are output sinks, not data contributors to a shared versioned payload. Modularity is a
BuildHound differentiator driven by two owner rules — core never mutates other tasks' config, and
optional features must not tax every build. Recommendation:

1. **Packaging.** Addons are separate Gradle plugins under the existing Maven group
   (`dev.buildhound:buildhound-addon-<name>`, plugin ids `dev.buildhound.<name>`). Settings
   plugins when they need whole-build visibility (sharding, quarantine), project plugins only if
   inherently per-project. Each addon declares a compatible core version range; addon applied
   without core ⇒ warn + no-op (never fail — rule holds for addons too).
2. **Attachment.** Core registers its collector `BuildService`s under known names; an addon looks
   the service up and contributes through a small public `BuildHoundCollectorRegistry` interface in
   `buildhound-commons` (register a named provider evaluated in the Finalizer). This keeps the
   Flow-API finalizer single-owner (core) and addons CC-safe by construction (providers only).
3. **Payload channel.** Add a reserved additive field to schema v1:
   `extensions: Map<String, JsonElement> = emptyMap()`, key = addon id (`"testSharding"`,
   `"quarantine"`, …), value = addon-owned versioned JSON (each addon embeds its own
   `schemaVersion`). Rationale: `tags`/`values` are string-only and user-facing; typed top-level
   fields per addon would couple commons releases to every addon; a separate ingest endpoint per
   addon breaks single-payload idempotency/spool semantics. Server stores `extensions` as jsonb,
   addon-aware views layer on top. Golden-file tests cover a payload with a populated extension.
4. **Mutation boundary.** Anything that filters/reorders/mutes tests, mutates task config, or
   injects task actions lives in an addon. Pure observation (even of addon-domain data, e.g.
   reading Paparazzi/Compose report files if present) may live in core behind a config flag.
5. **Server contract.** Addon-specific endpoints (e.g. shard-plan) are namespaced
   `/v1/addons/<id>/…`, token-authed like ingest, and optional — core server runs fine without
   any addon tables (jsonb keeps ingest schema-stable).

---

## 7. What BuildHound already covers (do-not-duplicate list)

Per-task outcome/duration/incremental/executionReasons; env (OS/arch/cores/RAM, hashed identity,
daemon reuse, CC state enum); toolchain versions; VCS branch/sha/dirty; CI SPI (Azure, GHA,
generic) with branch-from-CI fallback; tags/values DSL; derived metrics (hit rate and parallel
utilization computed; avoided ms, critical path, and config time exist as schema fields but are
**hardcoded null in v0** — avoided ms needs cache-origin timings, critical path needs the task
dependency graph, config time is still uncollected; `DerivedMetricsCalculator`, plan 005);
standalone HTML artifact; gzip synchronous upload + spool/retry; secret scrubber; planned (spec'd): Kotlin build reports, test
collection, flaky+quarantine gate, process probe, APK size, Azure Timeline connector, benchmark
mode, metric CLI, regression engine, internal-adapters for cache origin/fingerprints.

## 8. Sources

Tuist: docs.tuist.dev (install-gradle-plugin, build-insights/gradle, test-insights/flaky-tests,
test-sharding/gradle), blog 2026-03-02 + 2026-03-25, Plugin Portal `dev.tuist` + maven-metadata,
`tuist/tuist@main` `gradle/src/main/kotlin/dev/tuist/gradle/{TuistPlugin,TuistBuildInsights,TuistTestInsights,TuistTestQuarantine,TuistTestSharding,CIDetector}.kt`,
`api/ShardsApi.kt` + models, `server/lib/tuist/shards.ex`, `server/lib/tuist/shards/bin_packer.ex` ·
eBay: `eBay/metrics-for-develocity-plugin@main` README + summarizer READMEs
(projectcost, taskduration, configcachemiss) + `ProjectCost{Model,ReportTask,Summarizer}.kt`,
`ConfigCacheMissSummarizer.kt`, `DevelocityBuildService.kt` ·
CCUD: `gradle/common-custom-user-data-gradle-plugin@main` (all six sources:
`CustomBuildScanEnhancements`, `CiUtils`, `Utils`, `Overrides`, `CustomDevelocityConfig`,
`CommonCustomUserDataGradlePlugin`), Plugin Portal (2.7.0), removal commit `73eb485` ·
cdsap: `cdsap/PaparazziTestDistributionExtension@main` (plugin + lib sources), Plugin Portal
`io.github.cdsap.td.paparazzi` · Develocity TD 3.8 manual (docs.develocity.ai) ·
Local: `buildhound-commons`/`buildhound-gradle-plugin` sources in this repo (verified this session).
