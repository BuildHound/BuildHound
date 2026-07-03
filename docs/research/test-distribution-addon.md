# Test Distribution / Sharding Addon — Design Research

**Date:** 2026-07-03 · **Status:** research (design input for a future `docs/plans/` entry)
**Verdict up front:** follow the **Tuist model** (CI-level sharding, server-computed plan from
BuildHound's own ingested per-class timings) — not the Develocity Test Distribution model
(intra-task fan-out to a proprietary remote-agent broker, unreachable with public APIs). Package
as **addon** `dev.buildhound.test-sharding` (mutates `Test` task filters → excluded from core by
the no-mutation rule) backed by a **core server** shard-plan capability over test-timing history.

Related: [plugin-ecosystem-gap-analysis.md](plugin-ecosystem-gap-analysis.md) §2.5/§5/§6 (Tuist
inventory, Paparazzi premise correction, addon architecture), spec §3.5 (test granularity),
roadmap phase 2 (test collection) / phase 4 (flaky+quarantine).

---

## 1. The two prior-art models

### 1.1 Develocity Test Distribution (what cdsap's extension orbits)

One `Test` task's classes are partitioned across remote agents mid-build by the Develocity
broker; results stream back into the same build. Balancing uses "historical test execution data
provided by Develocity … balanced partitions of similar expected execution times" (TD 3.8
manual; internals proprietary/unverifiable). Requires commercial server + agent fleet + access
key. **Not reachable** with public Gradle APIs; contradicts self-host posture. The
cdsap/PaparazziTestDistributionExtension is *not* a sharding tool — it only isolates Paparazzi
HTML report fragments per executor (`td-<timestamp>/` dirs via a sysprop) and merges them in a
`@CacheableTask` finalizer, because TD's merge otherwise conflicts. Lesson retained: one logical
test run split across executors needs per-executor output isolation + merge — largely moot under
CI-level sharding (each shard is its own build).

### 1.2 Tuist Gradle sharding (source-verified on `main`, feature landed 2026-03-23/25)

Two phases:

- **Plan** (`tuistPrepareTestShards`, one CI job): discovers suites by walking every `Test`
  task's `testClassesDirs` compiled class dirs (every `.class` without `$` → FQCN; shard unit =
  test class, granularity hardcoded `suite`); `POST …/tests/shards` with
  `{reference, test_suites, shard_min/max/max_duration, granularity}`; writes provider-native
  fan-out artifacts (GHA `$GITHUB_OUTPUT` matrix, GitLab child-pipeline YAML, Buildkite pipeline
  YAML, CircleCI continuation, generic `.tuist-shard-matrix.json`).
- **Execute** (each fan-out job): inert unless env `TUIST_SHARD_INDEX` set; reference derived
  from CI env (`GITHUB_RUN_ID`+attempt, `CIRCLE_WORKFLOW_ID`, `BUILDKITE_BUILD_ID`,
  `CI_PIPELINE_ID`) or `TUIST_SHARD_REFERENCE`; fetches its slice
  (`GET …/tests/shards/{ref}/{i}`) **at configuration time**; filters every `Test` task via
  public `TestFilter.includeTestsMatching(fqcn)` + `filter.isFailOnNoMatchingTests = false`
  inside `doFirst`; stamps `shard_plan_id`/`shard_index` into uploaded test telemetry (feedback
  loop).

**Server balancing** (verified in Tuist's Elixir source): per-suite duration = ClickHouse
`quantile(0.90)(duration)` over the last **30 days**, CI runs only; greedy **LPT** (sort desc,
assign to least-loaded shard) with a module-affinity variant accepted when makespan ≤ 1.05× plain
LPT; unknown suites default to the median of known durations (no history at all: 5 s/suite);
shard count honors min/max/max-duration (max default 10).

**Defects to learn from (both source-verified):**

1. **Failure semantics invert never-fail:** unreachable server / non-2xx / underivable reference
   ⇒ `GradleException` — the build dies. Only the absent-index case is inert.
2. **Join-key mismatch:** the Gradle client sends bare class FQCNs; the server keys timing
   history as `"<modulePath>/<classFQCN>"`. `Map.get` never matches → every suite gets the
   median default → **LPT silently degenerates to count-based round-robin for the Gradle path**
   (the Xcode path resolves units server-side and does match). Static-analysis finding, not
   runtime-tested — but the code path is unambiguous.
3. CC hygiene: `Task.project` accessed in a task action, raw `System.getenv`, config-time HTTP
   baked into any CC entry.

---

## 2. BuildHound addon design

### 2.1 Packaging & data dependency

- Addon plugin `dev.buildhound.test-sharding` (settings plugin — needs all-projects `Test` task
  reach), publishing per the addon architecture (gap-analysis doc §6): registers into the core
  collector registry, contributes an `extensions["testSharding"]` payload block.
- Core server gains a shard-plan capability over the phase-2 test ingestion: the aggregate to
  serve is `(projectId, modulePath, classFqcn) → p90 duration over trailing 30d, CI-only` plus a
  global median fallback. Adopt Tuist's verified defaults: 30-day lookback, **p90** (absorbs
  flaky slowness better than p50), median for unknowns, 5 s floor with no history, optional
  module affinity at 1.05× makespan tolerance, max shards default 10.

### 2.2 Interface

- `BUILDHOUND_SHARD_INDEX` / `BUILDHOUND_SHARD_TOTAL` (+ optional `BUILDHOUND_SHARD_REFERENCE`),
  read via `providers.environmentVariable(...)` (CC-tracked). No index ⇒ addon fully inert
  (Tuist got this right).
- Default reference derived from the existing `CiEnvironmentProvider` SPI (`CiContext.runId` —
  already normalized per provider) instead of a hardcoded env ladder. GHA run-attempt joins the
  reference once captured (gap-analysis §4.1).

### 2.3 Plan protocol — one phase for v1

Skip the mandatory prepare task: each shard job discovers suites deterministically (sorted class
scan, Tuist's `testClassesDirs` walk is fine) and calls one **idempotent** endpoint —
"plan-or-get: reference R, suites S, total N → shard i". Server memoizes the plan by
(project, reference); first caller creates, others read. Tuist's catch-all guard (last shard also
runs anything not explicitly assigned) protects against inter-job discovery drift. A
matrix-emitting prepare task can come later; note BuildHound's first CI target is Azure DevOps,
which has no first-class dynamic matrix — "static shard count in YAML + index env var" is the
natural v1 anyway. Endpoint namespaced `/v1/addons/test-sharding/…` per the addon server
contract.

### 2.4 Filtering & CC hygiene

`Test.filter.includeTestsMatching(fqcn)` + `isFailOnNoMatchingTests = false` in `doFirst` —
public API, proven by Tuist. Do the shard fetch at **execution time** via an injected
`BuildService`/`ValueSource` with a short timeout — not at plugin-apply time — so failures
degrade per run, no shard slice is baked into a CC entry, and Tuist's two CC problems
(`Task.project` in an action; config-time HTTP) are avoided by construction.

### 2.5 Failure semantics — invert Tuist

Every failure path (unreachable server, non-2xx, timeout, bad index, missing plan) ⇒ `warn` +
**run all tests on every shard**. Correct results, temporarily slower, never a failed build —
the addon inherits the core never-fail rule. The addon never throws.

### 2.6 Feedback loop & join-key contract

- Stamp `shardPlanId`/`shardIndex` into the payload (`extensions["testSharding"]`) so per-shard
  timings improve future plans and the dashboard can show shard balance.
- **Define the unit key once** — `modulePath + "/" + classFqcn` — in `buildhound-commons`, used
  by test ingestion, the plan request, and the balancer, with a golden test pinning it. This is
  the single biggest lesson from Tuist's degeneration bug (§1.2-2).

### 2.7 Privacy interaction (spec §3.7)

Sharding requires the backend to key timings by test-class identifier. If class names are ever
pseudonymized in payloads, the same deterministic pseudonym must be applied in the shard-plan
request/response — the server balances in hashed space, the client maps hashes back to FQCNs
locally before building filters. Decide explicitly in the plan doc; this is the one place
sharding and the scrubber interact. (Today spec §3.5 ingests class FQCNs plaintext, so v1 has no
conflict.)

### 2.8 Relationship to Tuist quarantine (sibling addon)

Same packaging logic applies to `dev.buildhound.test-quarantine` (gap-analysis §2.4): fetch
quarantined list, `skipped` mode via `excludeTestsMatching`, `muted` mode via
`ignoreFailures + doLast` re-fail on non-quarantined failures. Both addons mutate `Test` tasks —
both stay out of core; both remain gated on flaky-detection precision (locked decision #3).

---

## 3. Sources

`tuist/tuist@main`: `gradle/src/main/kotlin/dev/tuist/gradle/TuistTestSharding.kt` (+
`TuistPlugin.kt`, `TuistTestInsights.kt`, `api/ShardsApi.kt` + models), `server/lib/tuist/shards.ex`,
`server/lib/tuist/shards/bin_packer.ex`, commits API (feature dates) ·
tuist.dev docs `guides/features/test-sharding/gradle`, blog 2026-03-25 ·
`cdsap/PaparazziTestDistributionExtension@main` (README, `TDPaparazziPlugin.kt`,
`MergePaparazziOutputsTask.kt`, `TDHtmlReportWriter.kt`), Plugin Portal
`io.github.cdsap.td.paparazzi` 0.5.0 · Develocity Test Distribution 3.8 manual
(docs.develocity.ai — proprietary internals unverifiable).
