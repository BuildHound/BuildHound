# Ingest-Corpus Analysis — new insights, feature gaps, better solutions, missed opportunities

Research date: 2026-07-07.

## 1. What this is and how it was produced

This document mines the **`docs/research/processed/`** corpus (formerly `ingest/`) — 39 Gradle build-performance
articles/blog posts (official Gradle & Android docs, practitioner tip lists, Talaiot
deep-dives, CC/CI/caching essays, one observability essay, one Spring-Boot build essay) plus
`Github_projects.md` (Kuronometer, build-time-tracker, and an 880-star `awesome-android-agent-skills`
repo) — for signal BuildHound has **not** already captured in its spec, plans, or the earlier
research set (`README.md`, `comparison-to-spec.md`, `plugin-ecosystem-gap-analysis.md`,
`cache-miss-input-fingerprints.md`, `dashboard-ux-research.md`, `test-distribution-addon.md`).

Method — a fan-out/verify workflow, not a skim:

1. **Baseline.** Three agents digested BuildHound's own docs (spec v0.1, roadmap, architecture +
   decision log, all 51 plan files 000–050) and the whole prior research set into a
   "already-known" baseline. Nothing in the baseline is allowed to count as a finding.
2. **Sweep.** 8 themed agents read the article corpus (one theme each) and 1 web agent
   investigated the GitHub projects; every finding had to name its source and justify absence
   from the baseline. 69 raw findings.
3. **Dedup/rank.** One editor merged semantic duplicates and dropped baseline-covered or
   no-product-angle items → 28 candidates with impact ratings.
4. **Adversarial verify.** Each candidate got **two independent skeptics**: a *novelty* lens
   (grep/read the spec, plans, payload schema, and prior research to prove it is already covered)
   and a *technical-validity* lens (is the Gradle/AGP/CI claim true, and is the action feasible
   for a settings plugin under configuration cache, without internal APIs, without breaking
   pseudonymization or the additive-schema/never-fail rules — verified against the repo's actual
   Gradle 9.6.1 distribution and plugin source). A finding survives only if **both** pass.
   **16 of 28 confirmed.** Six candidates lost their validity check to a mid-run spend-limit
   outage and were re-verified in a second pass (§5). Six were genuinely refuted (§6).

Every claim below carries the verification narrowings inline — they are load-bearing, because
several findings are *narrower* than they first look (already partially specced, or feasible
only with a caveat).

**Result: 22 confirmed findings** (16 first-pass + 6 reconciled in §5), 6 refuted (§6), from 69 raw.

## 2. Executive summary — the highest-impact items

The strongest signal in the corpus is that **BuildHound already collects almost everything the
ecosystem's playbooks key on, and turns almost none of it into interpretation.** The high-value
work is analytical (server-side rules and derived views over data already in the payload), not
new collection.

| # | Finding | Type | Why it matters |
|---|---|---|---|
| F1 | **Invocation-switch & flag posture** (plaintext + provenance + who-is-behind scorecard) | gap | Unlocks the single most obvious OSS-Develocity insight: "hit rate is 0% because `org.gradle.caching` is off." Nine of nine sweep agents flagged this independently — the strongest consensus gap. |
| F2 | **Per-project / per-plugin configuration-time attribution** | gap | `configurationMs` is a blind scalar; users with 12–20 s of config have no way to find the guilty module. Public `beforeProject`/`afterProject` API makes tier-1 collectable today and refutes the old "internal-ops-only, defer" note. |
| F3 | **Official advice to disable JUnit XML silently kills plan-024 test telemetry** | gap | Gradle's own perf guide recommends `reports.junitXml.required = false`; a team following it loses all BuildHound test data with zero signal. A latent product risk, not a feature idea. |
| F4 | **Rule-based recommendations engine** (KAPT→KSP, guide conformance, Gradle-10 readiness, wasted-work) | gap | The connective tissue that converts collected signals into advice; doubles as the content the MCP/agent surface serves. |
| F5 | **GitHub Actions distribution: `setup-buildhound` recipe + job-summary deep links** | opportunity | Exploits the April-2026 `gradle/actions` v6 proprietary-caching backlash: "no cache-key metadata leaves your infrastructure" is a one-line positioning gift for *the* open-source Develocity alternative. |
| F6 | **`build-logic` composite builds are now the agent-prescribed default** | insight | Reprioritizes the deferred composite-build telemetry gap (plan 045): the lost task dictionary sits on the default path of exactly the well-structured projects BuildHound targets. |
| F17 | **Build-cache *configuration* snapshot + remote-cache ROI** (§5) | gap | Can't tell "0 % hit: no cache configured" from "cache broken/cold" — the most basic cache triage. Pure public-API read (`Settings.buildCache`). |

The rest (§4) are medium-impact analytical features and collection extensions, almost all
"pure analysis over an already-shipped field." §5 adds six reconciled findings (F17–F22); §6
records six that were refuted.

---

## 3. High-impact findings

### F1 — Invocation-switch & performance-flag posture `feature-gap`

**Corpus signal (9 independent agents; Talaiot, Best-Practices/Performance, the two "10 tips"
lists, the 80%-reduction case study, several deep-dives, Android docs, AGP-9 guide).** Every
performance playbook opens with "is the build cache / parallel / daemon / config-cache even
on?" — yet BuildHound records only CC state and `daemonReused`.

**Do:**
- Add an additive **`environment.invocation`** block from public, CC-safe `StartParameter`
  reads: `isBuildCacheEnabled`, `isOffline`, `isRerunTasks`, `isRefreshDependencies`,
  `isConfigureOnDemand`, `getMaxWorkerCount`, `isParallelProjectExecutionEnabled` (all verified
  public getters on the repo's Gradle 9.6.1 distribution; the plugin already reads
  `settings.startParameter` CC-safely).
- A curated **plaintext `gradle.properties` allowlist** (`org.gradle.caching/parallel/vfs.watch`,
  `android.enableJetifier`, `android.nonTransitiveRClass`) with **per-key layer provenance** —
  user-home `~/.gradle` silently overrides project values, and "repo default off" vs "developer
  overrode it locally" changes the fix.
- **Promote `file.encoding`/`locale`/`parallel`/`maxWorkers` from salted-hash-only to plaintext**
  (new additive fields *alongside* the existing `FingerprintInfo` hashes — never edit them) so
  absolute rules like "Cp1252 fleet → set UTF-8" can fire. Differential fingerprint comparison
  structurally cannot produce absolute advice; only plaintext can.
- **Regression-baseline hygiene:** exclude `--rerun-tasks`/`--refresh-dependencies` builds from
  the plan-025 median+MAD baselines (they have zero avoidance by design and pollute baselines the
  way INTERRUPTED builds did pre-plan-033; current exclusions are INTERRUPTED + benchmark only).
- A **flags "who-is-behind" scorecard** mirroring the plan-032 toolchain-adoption panel, with
  AGP-9-aware staleness rules (AGP 9 removed/hard-enabled many `android.*` flags, so "flag still
  set" becomes a stale-config warning).

**Verification narrowings.** (1) `vfs.watch` *enablement* and daemon on/off are **not** public
`StartParameter` reads — they live on the internal `StartParameterInternal`; observe them only via
the explicit-`gradle.properties` allowlist, and accept that the effective default-on state stays
invisible. (2) `parallel`/`maxWorkers`/`file.encoding`/`locale` already exist as salted fingerprint
hashes — the gap is *plaintext interpretability*, not absence. (3) CC-hit replay: config-time
captures bake into the CC entry, so CLI toggles of `--build-cache/--parallel/--max-workers` on a CC
*hit* report store-time values (a limitation the existing fingerprint capture already has — document
it; the baseline-hygiene flags are safe because `--offline` is part of the CC key and
`--rerun/--refresh` currently force a CC rebuild). (4) `Test` `maxParallelForks/forkEvery` capture via
the plan-016 `whenReady` dictionary inherits the isolated-projects gate (empty dictionary under IP).

*Novelty:* only three tangential prior touches exist (`build-telemetry-research.md:179` floats
`--refresh/--offline` as "coarse signals," `repos/Talaiot.md:42` documents the switch set as prior
art, `cache-miss-input-fingerprints.md:239` has a one-line plaintext-promotion idea) — none reached
the spec, a plan, or code.

### F2 — Per-project / per-plugin configuration-time attribution `feature-gap`

**Corpus signal (6 agents; Android Optimize/Profile docs, Best-Practices/Performance & /Tasks,
the 80% study, the deep-dive, the agent skill).** A user seeing 12–20 s of configuration has no
way to find the guilty module, plugin, or eagerly-resolved configuration — the exact breakdown
Develocity / `--profile` / Build Analyzer provide.

**Do:**
- **CORE, public API:** time each project's evaluation via
  `gradle.lifecycle.beforeProject`/`afterProject` (isolated-projects-compatible) from the settings
  plugin, delivered via Flow-action params. On CC hits configuration doesn't run, so the block is
  correctly absent — and per-project config cost only matters on exactly the CC-miss/DISABLED
  builds where users ask.
- **internal-adapters tier:** plugin-apply, project-evaluation, task-realization ops → per-plugin
  apply time, eagerly-realized-but-never-executed task counts (configuration-avoidance violations),
  count of configurations resolved during the configuration phase (the guide's "0 s resolving during
  configuration" target).
- **Dashboard:** a "configuration hotspots" Bottlenecks family (top-N slowest-configuring projects,
  trend across CC-miss builds) — absent from plan-032's four task-level families.

**Verification narrowings.** (1) `beforeProject`/`afterProject` time *project evaluation only*
(build script + plugin application + `afterEvaluate`); settings/init eval, buildSrc/included builds,
task-graph population, and CC-store time fall outside, and under parallel/IP configuration the
per-project times overlap wall-clock — so they **will not sum to `configurationMs`**. Label the view
"project evaluation time (top-N)", not a decomposition of the scalar. (2) The two `IsolatedAction`s
cannot share mutable state — correlate via project-scoped `extraProperties` (both callbacks get the
same `Project`) or the proven sidecar/BuildService pattern, and never capture the plugin instance.
(3) Existing code coerces `configurationMs` to **0** (not null) on CC HIT; the new block should be
*absent* on HIT. (4) **Fix the repo:** `build-telemetry-research.md:95` ("per-project configuration
cost is only available via internal build operations — defer") is now **stale** — the public
`GradleLifecycle` API the plugin already uses supersedes it. Parts of tier-2 (eager-realization
counts via `tasks.configureEach`, resolved-during-config via `ResolvableDependencies.afterResolve`)
also have approximate public-API paths and could migrate to core later.

### F3 — Official advice to disable JUnit XML silently kills plan-024 test telemetry `feature-gap`

**Corpus signal (`Improve the Performance of Gradle Builds.md`).** Gradle's performance guide
explicitly recommends `reports.junitXml.required = false` on `Test` tasks "if you use a Build Scan,
which provides richer test insights." BuildHound's entire test collection (plan 024) parses JUnit
XML in the Flow action — **a team following official guidance loses all test telemetry with zero
signal.**

**Do:** (a) read `reports.junitXml.required` per `Test` task via the plan-016 config-time
dictionary and emit an honest degraded state ("test telemetry unavailable: JUnit XML disabled on
`:app:test`") in payload, artifact, and Tests page instead of silently-empty results; (b) document
"disable the HTML report, keep the XML one" as BuildHound's variant of the guidance; (c) register a
standing risk as the docs-recommended optimization spreads.

**Verification narrowings.** Read the flag explicitly — do **not** infer from a missing directory
(Gradle's binary results may still create `build/test-results/<task>/` with XML off). The degraded
message must reference the task path only (already in payload), never the absolute `junitXmlDir`.
Under isolated projects the `whenReady` walk is skipped, so the signal is unavailable there (but
test telemetry is already absent in that mode).

### F4 — Rule-based recommendations engine `feature-gap`

**Corpus signal (the agent skill, "Path to Build Happiness," the Spring-Boot essay, "10
Techniques," the Gradle-9.6 & Javamaxxing pieces + 3 more).** BuildHound collects nearly every
signal the playbooks key on but never turns them into advice. Build one server-side rules framework:

1. **Threshold rules** from the agent-skill checklist — hit rate > 80 %, CC enabled, parallel on,
   daemon GC pressure ("Kotlin daemon GC is 15 % of uptime — raise `kotlin.daemon.jvmargs`").
2. **KAPT tax** — `KaptTask` types are visible in the plan-016 dictionary and KSP detection exists
   (plan 046); compute "KAPT share of executed build time" per module with projected savings (sources
   cite KAPT = 30–50 % of build time, KSP ~2× faster). The most-cited Android win of five years,
   automated.
3. **Conformance rules** keyed to the official joint Gradle/Google/JetBrains *Best Practices* guide,
   with rule IDs aligned to its taxonomy ("the official guide says X, your fleet violates it in N
   projects" borrows all three vendors' authority; planned IDE inspections will share the vocabulary).
4. **Gradle-10 readiness card** composing already-collected signals — CC-DISABLED share (CC becomes
   default), daemon JDK < 21 agents, parent-lookup deprecations (plan 047) — plus annotating CC-hit
   trend charts on Gradle-version upgrades (9.6's precision tracking shifts hit rates for reasons
   unrelated to user fixes).
5. **Wasted-work rules** over `requestedTasks` — local iterative builds burning large shares on
   lint/test/packaging the iteration didn't need ("narrow your invocation," with measured minutes),
   plus the inverse quality smell of habitual `-x test` on CI.

Doubles as the content the MCP/agent surface serves (→ F-R10).

**Verification narrowings.** (1) Family 1 (hygiene/gradle-doctor class) is already *conceptually*
earmarked in `plugin-ecosystem-gap-analysis.md` item 14 and `cache-miss-input-fingerprints.md` §4 —
what's new is the concrete framework (checklist thresholds, human advice with projected savings,
rule IDs). Families 2–5 are genuinely absent everywhere. (2) The `-x test` rule needs a small
additive field — the plugin captures `taskNames` but not `excludedTaskNames` (public, trivial,
CC-safe). (3) The Gradle-10 card's parent-lookup signal exists only when the opt-in internal-adapters
module is on — degrade to CC-state + daemon-JDK otherwise. (4) `TaskExecution.type` is null under IP —
KAPT rules must tolerate missing types. (5) Hedge the numbers: "KAPT 30–50 %" and "Gradle 10 requires
JDK 21 daemon" are blog/"likely" claims — phrase savings as measured-per-fleet, the JDK floor as
expected.

### F5 — GitHub Actions distribution: `setup-buildhound` recipe + job-summary deep links `missed-opportunity`

**Corpus signal (`Choice, Clarity, and the Future of Caching in Gradle Actions.md`, `You Are
Probably Already Running gradleactions.md`).** `gradle/actions` v6 moved `setup-gradle` caching
toward a proprietary Develocity-lineage component (Enhanced Caching "Free Preview" for private
repos, explicit intent to charge large commercial users; the service "may collect metadata such as
cache keys"). Backlash forced a Safe-Harbor clause and a `basic` opt-out provider — while 45,000+
OSS repos run `setup-gradle` by default.

**Do:** (1) ship a **`setup-buildhound`** recipe/extension of the existing composite action
(plan 041) pairing `cache-provider: basic` with BuildHound telemetry via init script —
"no cache-key metadata leaves your infrastructure," one YAML line. (2) When
`provider = github-actions`, have the Flow finalizer append a Markdown block (outcome, duration, hit
rate, dashboard deep link) to **`$GITHUB_STEP_SUMMARY`** — a documented plain-file-write contract that
degrades silently when absent, within the never-fail rule. Generalize to Azure's `task.uploadsummary`.

**Verification narrowings.** (1) BuildHound already ships a composite action
(`buildhound-ci-assets/github/action.yml`, plan 041) writing minimal `::warning::`/`::error::`
annotations — the *new* part is the `cache-provider: basic` pairing/positioning and the STEP_SUMMARY
block (grep confirms neither exists outside research notes). (2) `GITHUB_STEP_SUMMARY` is per-step:
a warm daemon reused across multiple Gradle steps retains the first step's path, so the block can be
misattributed/dropped — fine on typical single-Gradle-step ephemeral jobs, but docs + the silent-
degrade path must cover it (also ~1 MiB/step limit). (3) Azure's `task.uploadsummary` is a `##vso`
stdout command referencing a Markdown file, not an env-file append — same idea, different mechanism.
(4) "Develocity-backed" overstates it: v6's current caching logic resembles v5's open-source logic;
proprietary Develocity Artifact Cache logic is roadmap, not today's data path.

### F6 — `build-logic` composite builds are the agent-prescribed default `new-insight`

**Corpus signal (`awesome-android-agent-skills` `android-gradle-logic` skill; Gradle's *Best
Practices for Structuring Builds*).** An 880-star agent-skills repo codifies the Now-in-Android
pattern (a `build-logic` composite build with convention plugins) as the structure AI assistants
impose on multi-module Android projects, and Gradle's own guide prescribes it as preferred.
BuildHound's known composite degradations (plan 045's lost task-type/cacheable dictionary for
included-build tasks, deferred) therefore sit on the **default path** of exactly the well-structured
projects BuildHound targets — including its own `nowinandroid` harness (plan 043).

**Do:** raise **plan 045 from "deferred"**; extend the existing
`CompositeBuildTestCollectionFunctionalTest` fixture to assert non-null `tasks[].type`/`cacheable`
and `derived.cacheableHitRate` on the `build-logic` classpath-applied path, and pick plan 045's
option (a) or (b).

**Verification narrowings.** One 880-star repo doesn't *prove* "every project by default" — but the
conclusion stands on stronger ground the finding's phrasing missed: BuildHound's own
`CompositeBuildTestCollectionFunctionalTest` already demonstrates the service-params freeze on the
**classpath-applied (published-plugin)** path with exactly this structure — which **refutes plan 045
§2's rationale** that only `includeBuild`-of-BuildHound consumers are affected. The prevalence
argument (agent tooling propagating the structure) is the genuinely new part; the fixture largely
exists already.

---

## 4. Medium-impact findings

### F7 — Tag-cohort comparison: split any trend by a tag value `feature-gap`

The flagship organic-fleet workflow teams built on Talaiot+Grafana: tag builds `R8=true/false`,
chart mean task time grouped by the flag, conclude "R8 speeds builds 14 %." BuildHound has the
ingredients (tags/values in the payload) but no cohort split — Trends filters one slice, Comparisons
diffs exactly two builds, benchmark mode needs a deliberate harness. **Add "split by tag" to trend
charts:** multiple series keyed by a tag's values with median difference, % change, per-cohort
sample counts, reusing plan-025's robust-z machinery for an honest insufficient-data state. Covers
the real rollouts teams measure: K2, CC on/off, new AGP.
**Narrowing:** today's `BuildFilter` (`Routes.kt:674`) supports only branch/mode/outcome — there is
**no** tag-based trend filter at all, so this also requires tag querying/indexing over the payload
`jsonb` (extracted column or GIN index) as prerequisite server work, and the cohort-vs-cohort
statistic must be *adapted* from plan-025's one-value-vs-baseline robust-z, not reused verbatim.
`dashboard-ux-research.md` rec-4 proposes generic dimension slicing over *fixed* dimensions; the new
part is user-defined-tag multi-series with delta stats.

### F8 — Plugin-level cost attribution + costly-plugin catalog `feature-gap`

Build Analyzer's primary triage dimension is "plugins with tasks impacting build duration" because
plugin-owned time has a different fix path (update/replace/report upstream). BuildHound can derive an
owning-plugin rollup **server-side from already-collected task-type FQCN prefixes**
(`com.android.build.*` = AGP, `org.jetbrains.kotlin.*` = KGP…) with **zero new collection** — add a
"time by plugin" grouping to `#/tasks` and a top-plugins Bottlenecks card so third-party plugin
regressions after version bumps become visible. Layer two: collect an applied-plugin-id inventory +
a server catalog of known-costly plugins with mode-aware rules (the 80%-article's biggest win was
disabling Firebase Performance instrumentation on debug builds; lingering Jetifier is the same class).
Reuses the "plugin stays dumb, server rules carry the knowledge" pattern.
**Narrowings:** (1) FQCN-prefix rollup is *heuristic* attribution, weaker than Build Analyzer's
registration-based attribution — tasks typed `DefaultTask`/`Copy` or defined in build scripts can't
be attributed, so the rollup needs an "unattributed" bucket. (2) No public API enumerates arbitrary
applied plugin *ids* — probe known ids via `pluginManager.hasPlugin(id)` or report impl-class FQCNs
and map server-side (both fit the catalog design). (3) "Default since AGP 8" is a misreading — AGP 8
makes *category* grouping the default; plugin grouping is a separate breakdown. Jetifier cost is
config-phase (shows in the inventory layer, not the task-time rollup).

### F9 — Delivery-health page: DORA proxies + the retry tax `feature-gap`

Develocity 360's newest pitch frames build observability as the foundation of the four DORA keys;
BuildHound already stores three credible proxies with **zero new collection**: CI failure rate per
branch/pipeline (change-failure-rate), time-to-green (first FAILED → next SUCCESS on a branch; MTTR),
`queuedMs + duration + gradleSharePct` (lead-time contribution). Add the **retry tax:** the cited
IEEE study (Parry et al. 2022) found rerunning the failing build is the most common flakiness
response; detect rerun chains (`runAttempt > 1`, or same `projectKey+sha+requestedTasks` after
FAILED), price them in CI minutes, and join to `#/flaky` — "you spent N CI hours rerunning these 5
tests this month" is far more actionable than a flake-rate percentage.
**Narrowings:** frame as "DORA proxies from already-collected build data, sidestepping the deferred
Git-analytics non-goal" — the spec lists Git/DORA analytics as a v1 **non-goal** (`spec:13`), and a
coarse fleet `successRate` KPI already ships (plan 032). `runAttempt` is populated only for GitHub
Actions; other providers use the fallback heuristic. `queuedMs`/`gradleSharePct` exist only for
connector-enriched builds. Time-to-green is a CI-recovery proxy, not production MTTR — label it
honestly.

### F10 — Build Analyzer warning taxonomy as server rules `new-insight`

Build Analyzer's headline feature is a fixed warning taxonomy with remediation copy; BuildHound
ingests the classification inputs. Server-side rules over cross-build data: task EXECUTED in ~100 %
of builds with reasons matching "has not declared any outputs"/"upToDateWhen is false" ⇒ **always-run
warning**; `JavaCompile`/`Kapt` persistently `incremental=false` ⇒ **non-incremental annotation
processor**; AGP manifest/BuildConfig tasks never UP-TO-DATE ⇒ **dynamic `buildConfigField`/`resValue`
in debug builds**. Fleet-wide + historical is strictly stronger than Build Analyzer's single-build
view. Ship as a "Warnings" family on Bottlenecks with copy modeled on the official strings.
**Narrowings:** "dynamic debug values" is from the Profile-your-build docs, not Build Analyzer's
actual taxonomy (always-run, task-setup, non-incremental AP, CC, Jetifier). The task-setup-conflict
warning has **no feasible rule** from current telemetry (no output-property data) — drop it or add
collection. The non-incremental-AP rule is a proxy: it can flag a persistently non-incremental task
but cannot name the offending processor, and must exclude clean builds.

### F11 — Rerun-cause taxonomy over `executionReasons` `new-insight`

`executionReasons` are collected but treated as opaque strings. A server-side classifier bucketing
them (source change / implementation-classpath change / upstream output / output missing / caching
disabled / forced) unlocks two named detectors: (1) **build-logic invalidation storms** — a high
fraction of executed tasks with implementation/classpath reasons quantifies "N % of executed
task-hours this month were build-logic rebuilds; migrate buildSrc to an included `build-logic`
build"; (2) **ABI cascades** — Pocket Casts measured depth-12 ABI changes nearly erasing
modularization's benefit (10.8 s vs 13.8 s), and the Gradle guide calls `api`→`implementation` "one
of the most impactful changes"; classify builds cascade-vs-contained, rank upstream modules by
cascade frequency, surface an "`api`-overuse candidates" list.
**Narrowings:** precedent exists for *other* reason rollups (Kotlin incremental reasons, plan 023;
CC-miss reasons, plan 035/gap-item-9) — the Gradle **task** `executionReasons` taxonomy and both
detectors are new. Fix the rationale: core `executionReasons` ship in the v1 payload with no opt-in
module (the "internal-adapters reasons are salted hashes" claim is wrong — `cachingDisabledReason` is
scrubbed plaintext). Reasons are human-readable output, not an API contract — use version-tolerant
patterns + an "unclassified" bucket, and note `PayloadCapper` truncates reasons under pressure.

### F12 — Parallelism-blocker analytics: gating-task detection + graph centrality `new-insight`

`parallelUtilization` is a scalar that can't answer "which task serializes my build." Two composable
analytics: (1) **public-data** — scan each build's already-collected `startMs`/`durationMs` timeline
for spans where concurrency == 1 while work remained, attribute serialized ms to the gating task,
rank fleet-wide as a "parallelism blockers" family (delivers most critical-path insight without the
opt-in `criticalPathMs`); (2) **with internal-adapters edges** — duration-weighted degree centrality
per task (on Talaiot's Plaid example, 335 nodes/1106 edges, weighted degree immediately surfaced
`generateDebugFeatureTransitiveDep` as the structural bottleneck the single longest chain misses).
Cheap companion: GEXF/DOT export for Gephi power users.
**Narrowings:** dependency edges are captured today (plan 038) but used only for the `criticalPathMs`
scalar and are **not serialized** in the payload — server-side centrality/GEXF need an additive edge-
list field (size-capped via `PayloadCapper`). It was weighted *degree* centrality (not betweenness)
that surfaced the Plaid bottleneck. GEXF/DOT export is Talaiot prior art (`TaskDependencyGraphPublisher`)
— novel as a BuildHound feature, borrowed as an idea.

### F13 — Change blast-radius attribution: which changed module caused this build's work `new-insight`

Pocket Casts built a de-modularized fork and ran gradle-profiler to learn that rebuild cost is a
function of the changed module's graph depth. Make it continuous: collect the changed-module set via
bounded `git diff --name-only` against the previous build's sha or the CI PR base (existing
`BoundedExec` + VCS infra; emit **module paths, not file paths**, for privacy), then server-side join
with which modules' tasks EXECUTED vs avoided. Dashboard: "costliest modules to change" (median
downstream executed-time per change in module X, weighted by change frequency). Complements plan 026
Project Cost — 026 ranks modules by *their own* cost; this ranks them by the cost they *inflict on
others*, the output teams want before committing to modularization or `api`→`implementation`.
**Narrowings:** the plugin doesn't currently know "the previous build's sha" (no local last-sha
state); feasible bases are the CI PR base ref (via `CiEnvironmentProvider` SPI) or a new
`.gradle/buildhound/last-built-sha` file — shallow CI clones may lack the base commit, so degrade to
null. `vcs` today is strictly branch/sha/dirty/remoteUrl (plan 050 discards git-status output beyond
the dirty boolean), so changed-module collection is genuinely new. The article's "depth" means
direct-dependents count, not classical graph depth.

### F14 — Configuration-cache economics & reuse diagnostics `new-insight`

Gradle sells CC with three numbers BuildHound doesn't collect: store time, load time, entry size
(270 MB→65 MB after dedup). Collect the `org.gradle.configuration-cache.parallel` flag (provider
read); entry size by summing `.gradle/configuration-cache/<hash>` at finalizer time; measure **load
time on HIT** (new field `ccLoadMs`) to separate load cost from store cost fleet-wide. Two reuse
diagnostics: (1) CC entries are non-relocatable, so ephemeral CI agents essentially never hit — detect
the always-`MISS_STORED` CI pattern, annotate the CC-hit% trend so 0 % CI reuse isn't misread as
regression, emit a per-project "CC on CI: net win / net cost" verdict (Gradle's own guidance: enable
on CI for parallelism/early breakage, "do not focus on cache reuse"); (2) **flip-flop detection** —
change→build→rollback→build guarantees a miss because the single stored entry was overwritten; a
`MISS_STORED` whose plan-022 fingerprint map equals an earlier build's from the same user/host is
detectable waste, and an upgrade nudge when Gradle ships multi-entry CC storage.
**Narrowings:** Gradle exposes **no public API** for CC store/load durations or entry size — the
store/load split is a proxy from conditioning phase timing on CC state (and current code zeroes
`configurationMs` on HIT, so load cost must be a new field). Entry-size summing reads the internal,
undocumented `.gradle/configuration-cache/` layout — implement best-effort/nullable (newest-modified
subdir heuristic, `runCatching`, degrade on unrecognized layout; 7-day-retention stale entries
pollute a whole-dir sum).

### F15 — Extend the process probe with GC collector + JVM flags `new-insight`

Plan 029 already shells `jinfo`/`jstat` per daemon; one additive field set — active collector
(`UseG1GC`/`UseParallelGC`/`UseZGC`), `UseCompactObjectHeaders`, an **allowlist-extracted** set of
JVM flags, `org.gradle.workers.max` — unlocks the rules the sources prescribe: (1) Google's hard
threshold GC > 15 % of build time ⇒ raise heap (with a card naming `org.gradle.jvmargs` vs
`kotlin.daemon.jvmargs`); (2) ParallelGC-vs-G1 trial for throughput-bound daemons; (3) Gradle's "most
effective optimization is upgrading the daemon JVM" ⇒ fleet comparison of duration/GC by daemon JDK
major ("your JDK 17 daemons are p50 18 % slower than your JDK 21 daemons"), recommend Compact Object
Headers (JEP 519: ~22 % heap) on JDK 24+ with high rss; (4) Kotlin daemon `heapUsed` pinned at
configured Xmx ⇒ raise `kotlin.daemon.jvmargs`. Expose collector/workers as benchmark-mode slicing
dimensions.
**Narrowings:** `org.gradle.workers.max` is already captured but only as a salted hash (unusable for
rules — needs plaintext). `gcTimeMs` is `jstat` GCT, **cumulative since daemon start** — the 15 % rule
needs `uptimeS` normalization or a per-PID delta across builds (add a `pid` field, additive).
**Replace "scrubbed jvmargs summary" with allowlist extraction of specific flags** — plan 029
deliberately never stores raw `jinfo`/`ps` output (absolute paths, `-Dtoken=…` secret-shaped args); a
free-form scrubbed string would reopen that risk.

### F16 — Wrapper & startup-phase telemetry: `-bin`/`-all`, SHA pinning, GUH warmth, pre-Gradle gap `new-insight`

Everything before configuration is dark to BuildHound, yet on ephemeral CI the startup phase dominates
small builds (`-all` wrapper costs 9.2 s/~100 MB over `-bin` per cold agent; first-use JAR generation
adds seconds; `gradlew` inside a Gradle Docker image is 9 % slower because the wrapper re-downloads).
Cheap public-API captures: (a) read `gradle-wrapper.properties` via an execution-time `ValueSource` —
distribution variant `BIN|ALL|CUSTOM`, `distributionSha256Sum` pinned boolean, SHA-256 of
`gradle-wrapper.jar`; (b) classify cold-vs-warm Gradle User Home (`wrapper/dists` + generated-jars mtime
vs `startedAt`) as an environment-warmth enum explaining wall-clock volatility; (c) pre-Gradle gap =
CI step start (already in the connector span tree) − build `startedAt`. **Differentiator:** server-side
validation of the wrapper JAR hash against gradle.org's published checksums — GitHub's `setup-gradle`
automates this, but nothing exists for Azure DevOps (BuildHound's first-class CI), making BuildHound
the wrapper supply-chain validation layer for non-GitHub pipelines.
**Narrowings.** (1) The pre-Gradle-gap concept is partly shipped — plan 028 already computes
`gradleSharePct` = build wall-clock ÷ pipeline wall-clock; the new part is the finer per-step
decomposition (Gradle-step start − build `startedAt` = wrapper download + JVM/daemon startup), and it
depends on the Azure connector the spec marks post-MVP. (2) The wrapper-hash check is **detective, not
preventive** — the hash is computed inside a process the (possibly compromised) wrapper already
launched; a *preventive* check belongs in BuildHound's Azure YAML template (`buildhound-ci-assets`),
while plugin telemetry covers drift/unpinned detection. (3) "Nothing exists for Azure DevOps" →
"no first-class *automated* tooling" (Gradle docs describe manual checksum verification).

---

## 5. Reconciled candidates — all six re-verified and confirmed

These six lost their validity check to a mid-run spend-limit outage in the first pass. A second
verification run (12 agents, no errors) put both lenses on each again; **all six passed** — one is
high-impact. They are full findings, not weaker ones.

### F17 — Build-cache **configuration** snapshot + remote-cache ROI `feature-gap` · high

BuildHound records `FROM_CACHE` outcomes and `cacheableHitRate` but never whether a cache was
**configured at all** — so it cannot tell "0 % hit: no remote cache configured" from "remote cache
broken/cold," the most basic cache triage. **Do:** read public `Settings.buildCache` after settings
evaluation (`localEnabled`, `remoteEnabled`, remote `push`, normalized backend class name) plus
`StartParameter.isBuildCacheEnabled()` into an additive environment block via a deferred provider;
add a cache-ROI panel that **consumes the already-shipped-but-directionally-unread** internal-adapters
`CacheOrigin` (`LOCAL_HIT`/`REMOTE_HIT`) to segment remote-hit rate by mode and flag the
misconfigured-CI-persistence signature (near-zero CI reuse).
**Verification.** Confirmed public on the repo's Gradle 9.6.1 jar (`Settings.getBuildCache()` →
`BuildCacheConfiguration`; `getLocal()`/`getRemote()` with `isEnabled()`/`isPush()`, remote null when
unconfigured). **Read after settings evaluation, never at `apply()`** (the `buildCache{}` block may be
unevaluated). **Privacy:** capture booleans + normalized backend class name only — never
`DirectoryBuildCache.getDirectory()` (absolute path) or the `HttpBuildCache` URL (hostname/credential
leak, §3.7). *Novelty narrowing:* Layer-3 transfer bytes/ms/throughput is **not** novel — plan 038
explicitly specified it (`038:76,141-142,168-169`) then the implementation dropped it (recording only
booleans), and it's pre-noted in `build-telemetry-research.md:65` and `dashboard-ux-research.md:27` as
Develocity's build-cache operation-timings panel. The **config snapshot + ROI-from-origin** is the new
core; the transfer-timing revival is "finish what plan 038 specced," and keep it inside the opt-in
internal-adapters module (internal ops).

### F18 — Cache-miss diagnostics: non-relocatable detector + secret-volatility scoring `new-insight`

Two fleet-statistical detectors beyond the pairwise plan-022 compare: (1) **non-relocatability** —
aggregate per-task `CacheOrigin` across `hostnameHash` and flag cacheable tasks that consistently MISS
cross-host while siblings `REMOTE_HIT`; (2) **rotating secrets** — a credential-shaped family
(`*_KEY/*_TOKEN/*_SECRET`) in the volatile catalog + a per-fingerprint-key volatility score (fraction
of consecutive builds where the salted hash changed), surfacing rotated secrets/timestamps/run-ids
without ever seeing plaintext.
**Verification — important recasts.** Detector 1: the internal-adapters `CacheOrigin` enum is
**required, not a "sharpener"** — the core-only path is impossible because build fingerprints are
per-machine-salted (cross-host "matching fingerprints" is unobservable) and core `FROM_CACHE` is
undifferentiated local/remote. Drop the "despite matching fingerprints" framing; let the origin enum
self-gate the remote-cache confound (no `REMOTE_HIT`s anywhere ⇒ stay silent). The
"`PathSensitivity.ABSOLUTE` ⇒ annotate NONE/RELATIVE" fix is an **over-claim** — normalization strategy
is observable via **no** public or internal API, so surface it as a ranked *candidate*
("non-relocatable: investigate path sensitivity"), not a confirmed fix. Detector 2: the volatility
score is valid only **within a single machine's salt stream** (naive fleet aggregation is inflated by
differing salts), and can only score keys the user explicitly allowlists in `fingerprints {…}` or via
the opt-in per-property hashes — it **cannot auto-discover** a `System.getenv` secret unless that var
is allowlisted (privacy rule forbids env dumps). Name-pattern matching on key names is privacy-sound.

### F19 — Build-structure inventory + modularization / IDE-sync ROI `new-insight`

As a settings plugin, BuildHound can enumerate the declared project tree but never does. Collect
`projectCount`, path depth, empty-intermediate-project detection (the `allprojects{}`-configured but
task-less anti-pattern), `includedBuilds` count, `buildSrc` presence, sources-in-root. Rules:
"single-module build, 8 idle cores → modularize" (× `parallelUtilization`), "project `:subs`
configured every build, zero tasks in 30 days." Then track module-count-over-time vs build p50 **vs
sync p50** — Pocket Casts found 2–3.6× faster builds but 5× slower IDE sync (0.8 s→4.0 s), framing
sync as *the* modularization trade-off — and an isolated-projects before/after benefit cut (inverting
the currently purely-defensive IP contract).
**Verification.** Read the descriptor tree **lazily** (provider / `settingsEvaluated`, not `apply()`
where `include()` hasn't populated it); put `buildSrc`/sources-in-root filesystem probes in a
`ValueSource` (execution time) so they don't become config-time CC inputs; send counts/depths/booleans
and Gradle project **paths** only (no absolute `projectDir`, §3.7). Two scoping caveats: **IDE-sync
telemetry is an explicit v1 non-goal (`spec:13`)** — the sync-health page reverses that decision; and
the IP before/after slice needs a new additive `isolatedProjects` payload flag (the plugin computes
`buildFeatures.isolatedProjects.active` but doesn't ship it). Empty-project detection via
`buildFile.exists()` false-positives on intentional aggregators — heuristic, not a hard rule.

### F20 — Metrics egress (Prometheus/OTLP + Grafana recipe) as the Talaiot-migration wedge `missed-opportunity`

Talaiot's entire adoption story was publishing into infra teams already run (InfluxDB/Grafana,
Elasticsearch, Prometheus PushGateway, one-command Grafana image). BuildHound is a closed loop. Add a
machine-readable **egress** surface: a Prometheus-scrapable endpoint and/or OTLP push exposing
per-project KPIs (p50/p95 duration, hit rate, avoided time, flake rate) + a ready-made Grafana
dashboard JSON against the existing query API. Talaiot is now dormant — its installed base is
BuildHound's most qualified early-adopter segment ("your Grafana dashboards keep working").
**Verification — load-bearing caveat: multi-tenancy.** A stock `/metrics` is a single global
unauthenticated scrape, but every existing read route is token + tenant-scoped (`Routes.kt:218,455`) —
a global scrape would **cross-leak all tenants' KPIs**. The egress must be per-tenant token-scoped
scrape or per-tenant OTLP push (non-idiomatic for Prometheus but doable) — that's the real design
constraint. Scope labels to **per-project aggregates, not per-task/per-module** (Talaiot's own scaling
article documents the high-cardinality blowup per-task labels cause). "Avoided time" is derivable from
existing rollups, not a standing metric today. *Novelty narrowing:* docs already float a hypothetical
"optional read-only Grafana companion" (research §6 / `comparison-to-spec` §2.12) — but that consumes
BuildHound's data via its own service; the new core is an **outbound** Prometheus/OTLP stream into a
team's existing stack, tied to a Talaiot-migration strategy.

### F21 — Agent-facing surface: first-party SKILL.md + machine-readable diagnosis endpoint `missed-opportunity`

The 880-star `awesome-android-agent-skills` repo trains agents to run `./gradlew --scan` and read the
Develocity build scan as the primary diagnostic — i.e. **AI agents are being taught to upload
customers' build data to `scans.gradle.com` by default.** Counter with (1) a first-party BuildHound
agent skill teaching agents to diagnose from the HTML artifact's embedded JSON + query API + MCP module
(adoption channel **and** privacy differentiator), and (2) a synthesizing `GET /v1/builds/{id}/diagnosis`
endpoint + matching MCP tool packaging already-collected signals (dominant phase, hit rate vs target,
top hotspots, deltas vs previous comparable build) into one agent-consumable call.
**Verification.** Pure serialization/synthesis + docs — the data and adjacent endpoints already exist
(verdict = PASS/FAIL, compare = fingerprint diff, bottlenecks = rollup, `get_build` = raw payload). Two
diagnosis fields need narrowing: **dominant phase** is only partially derivable (`configurationMs` +
per-task durations give config-vs-execution, but there's no separate dependency-resolution phase, and
`configurationMs` is null on CC hits — honest-null-degrade); **hit rate vs target** needs a default
constant or a new additive `ProjectSettings` field (only regression z-thresholds exist today). The
MCP-tool half is the strongest-novelty part (the current six-tool surface deliberately excludes even
verdict/compare). `research.md:51` already flags agent "skills" as a competitor pattern — on the radar,
never adopted.

### F22 — Server-side JVM (Spring Boot) parity: `springBoot` toolchain + `bootJar` size `missed-opportunity`

Large multi-module Spring Boot platforms have exactly BuildHound's pain profile (CC on Gradle 9, build
cache, parallelism, config-time cost) and the Boot plugin now fully supports Gradle 9 + CC on Java
17–25 — a market the Android-centric collectors ignore. (1) Detect `org.springframework.boot` via the
plan-046 reflection-at-`whenReady` pattern → `toolchain.springBoot`, making the adoption panel work for
server teams; (2) add `artifacts.jvm` with `bootJar`/`jar` `sizeBytes` (the JVM-service analogue of APK
size).
**Verification.** Novel as a **signal** (no baseline collects any server-side/JVM-framework signal),
reusing existing **mechanisms** (plan-046 detection, plan-031 Flow-time size read). Two narrowings:
(a) Spring Boot's Gradle plugin exposes **no documented public version accessor** (unlike AGP/KGP), so
the realistic source is the jar-manifest `Implementation-Version` (the KSP-style fallback at
`ToolchainDetection.kt:132`) — presence detects reliably, **version may be honest-null**; (b) capture
the archive **path** at config time (`AbstractArchiveTask.getArchiveFile()` in `whenReady`), read
`File.length()` at Flow time, only when the task actually ran (measure-only-what's-built); `bootWar`
exists alongside `bootJar`, and both new fields need **new** golden files (never edit existing).
*Correction to the finding's framing:* plan 031's `artifacts.android` is **not** a shipped defect — the
architecture decision log treats the settings-vs-project classloader boundary as a mitigated risk with
SDK-gated passing tests.

---

## 6. Refuted / already-covered (recorded so they are not re-raised)

These six were dropped by the adversarial pass with concrete evidence — useful to log:

| Candidate | Why dropped |
|---|---|
| Tiered retention / downsampling for task rows | **Already specced+implemented** — spec §5 (`spec:265`) locks per-dimension tiered retention; `V10__retention.sql` ships per-project tiered windows. Not flat. |
| Dependency-download telemetry (network tax, per-repo, dynamic-version rules) | **Already specced** — `build-telemetry-research.md` §4.6 (`:179`) specifies download time/bytes, changing/dynamic-version counts, and the `--refresh/--offline` coarse flags as a V1 candidate. (The Build-Analyzer per-repo breakdown facts are all accurate — worth keeping as implementation reference for that deferred item.) |
| Stable Variant API (`onVariants`+`SingleArtifact`) for the plan-031 artifact rework | **Already specced** — `README.md:55` and `comparison-to-spec.md` already prescribe exactly `onVariants` + `wiredWith().toListenTo(SingleArtifact.*)` + `BuiltArtifactsLoader` as CC/IP-safe. |
| User-configurable task-telemetry filters (duration threshold, include/exclude) | **Already in the DSL** — spec §3.4 (`:95`) shows `filters { excludeTasks(...); minTaskDurationMs = 0 }`. |
| AGP 9 built-in Kotlin breaks KGP detection | **Refuted by the repo's own exemplar** — `samples/nowinandroid` is on AGP 9.0.0 with no `kotlin.android` applied and plan 046's exit criteria still record `kgp=2.3.0` detected. The premise (AGP 9 built-in Kotlin) is real but the breakage claim is false. |
| Gradle Problems API as the route for CC miss reasons (re-scope plan 035) | **Refuted** — Problems API exposes only `getReporter()` and routes problems *out* to Tooling-API clients; there is **no** public API for a plugin *inside* the build to consume/listen to Problems events. Plan 035's report-parsing re-scope stands. |

---

## 7. Cross-cutting recommendations

1. **The center of gravity is a server-side rules/derived-metrics engine, not new collection.** F4,
   F8–F14, F18, F20, F21 are all "analysis over data already (or nearly) in the payload." Building the
   F4 rules framework *first* gives the others a home and directly feeds the agent/MCP surface (F21).
2. **A few small, high-leverage collection additions gate a lot of value:** the F1
   `environment.invocation` block + plaintext-flag promotion; F2's public-API per-project evaluation
   timing; F17's `Settings.buildCache` config snapshot; F19's declared build-structure tree. All are
   CC-safe, additive, and each unblocks multiple rules/dashboard families.
3. **Two latent risks worth an issue today (independent of feature work):** F3 (official JUnit-XML-off
   advice silently blanks test telemetry) and F6 (composite/`build-logic` dictionary loss on the
   default project structure — reprioritize plan 045).
4. **Finish what plan 038 started:** F17's Layer-3 build-cache transfer bytes/ms were *specced in plan
   038 and silently dropped* — reviving them (inside the opt-in internal-adapters module) is a
   completion, not a new feature.
5. **Three positioning plays that cost little and land on live controversy:** F5 (`setup-buildhound` /
   `cache-provider: basic` on the `gradle/actions` v6 backlash), F21 (first-party agent skill vs
   agents defaulting to `scans.gradle.com` upload), and F20 (Prometheus/OTLP egress as the
   Talaiot-migration wedge) — all three lean into "the open-source, self-hosted, no-metadata-egress
   Develocity alternative." F22 (Spring Boot parity) widens the market beyond Android.
6. **Update `docs/build-telemetry-research.md:95`** regardless — its "per-project config cost is
   internal-ops-only, defer" note is stale (public `GradleLifecycle` API exists and the plugin already
   uses it, per F2).

---

*Provenance: workflow `wf_79d10615` (69 raw → 28 candidates → 16 confirmed) + reconciliation run
`wf_2b4ccd36` (6 spend-limit-dropped candidates re-verified, all 6 confirmed) = **22 confirmed**. Every
finding carries its source file(s) and its adversarial-verify narrowings inline; two independent
skeptics (novelty + technical-validity) had to pass each finding. Impact ratings are the synthesis
editor's; verification confidence was "high" across the confirmed set.*
