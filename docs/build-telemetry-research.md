# Gradle Build Telemetry Platform — Research Report

**Working title:** open-source Develocity alternative — Gradle plugin (collector) + multi-tenant ingestion service + per-build HTML artifact + trend dashboard, with Azure Pipelines integration.
**Date:** July 2026 · **Status:** Research phase (spec + roadmap follow after review)

---

## 1. Executive summary

Everything you want to build is technically feasible with public, stable APIs — no Develocity license, no internal Gradle APIs required. The core collection mechanism is a settings plugin registering a `BuildService` that listens to task completion events plus the Flow API for build-finished handling; both are fully configuration-cache compatible on Gradle 8.x. Kotlin compiler detail comes free via Kotlin Build Reports, which can POST per-task JSON directly to your ingestion service. Cache hit/miss classification per task is available from task outcome data; *why* a cache miss happened requires input-fingerprint capture, which is the hardest and most valuable feature in this space (Bitrise's "Task Inputs" tab and their diagnostic-builds workflow exist precisely because of it).

The market validates the product shape. Tuist shipped exactly this architecture for Gradle in March 2026 (plugin → hosted dashboard, open source, remote cache attached). Bitrise sells cache + insights as a bundle with per-task cache analytics and threshold alerts. Datadog CI Visibility owns the pipeline-level view (trace model: pipeline → stage → job → step) but is weak inside the Gradle build. Nobody open-source combines: deep Gradle/Kotlin telemetry + CI-step context + regression intelligence + self-hostable backend. Talaiot is the closest OSS ancestor but ships raw data to your own InfluxDB/Grafana with no product layer on top — that gap is your opportunity.

Recommended headline decisions (detailed in §6): treat gradle-profiler as a *complementary benchmarking mode*, not the telemetry path; capture Azure Pipelines step data hybrid (REST Timeline API pulled by the backend + optional lightweight push step), packaged first as a reusable YAML template, later as a Marketplace extension; back the service with Postgres + TimescaleDB (or ClickHouse if you expect very high volume) and build it in Kotlin/Ktor for ecosystem credibility.

---

## 2. Landscape review

### 2.1 Develocity (the product being replaced)

The feature bar this project is measured against, worth listing because every competitor below is a partial clone of it: per-build "scan" with task timeline across workers; performance breakdown (configuration vs execution, serial vs parallel, avoidance savings from up-to-date/cache); local vs remote cache hit metrics and per-task outcome reasons; dependency-download timing; test results with flaky detection and history; daemon/JVM/infrastructure info; custom values/tags/links for arbitrary context; failure analytics; cross-build trends and comparisons. Two Develocity design ideas worth copying outright: **custom values** (arbitrary key/value context attached to a build — this is how CI metadata, git info, and feature flags get in) and **build comparison** (diff two builds' task fingerprints to explain cache misses).

### 2.2 cdsap (Iñaki Villar) ecosystem

Iñaki now works at Gradle; his OSS repos are effectively a map of what an independent build-telemetry stack needs.

**Talaiot** (~614★, v2.1.x) — the direct ancestor of your plugin. Architecture: a core plugin records task execution + build metrics, then hands them to pluggable *publishers* (InfluxDB, InfluxDB2/Flux, Elasticsearch, Prometheus PushGateway, JSON file, task-graph outputs, custom). Key lessons from its evolution:

- Metric model split into **build metrics** (duration, configuration time, requested tasks, success, environment: OS, JVM args, Gradle version, git branch, hostname...) and **task metrics** (path, module, type, duration, worker, outcome, rootNode, critical path participation), plus user-defined `customMetrics`.
- v2.0.4 added `processMetrics` by default: Gradle JVM args, Kotlin JVM args, and end-of-build process stats for Java/Kotlin daemons — evidence that process health belongs in the default payload.
- v2.0.5–2.0.6 chased **Gradle Isolated Projects** support and moved metric collection to `ValueSource`s evaluated at publishing time — the config-cache/isolated-projects treadmill is real and must be designed for from day one, not retrofitted.
- v2.1.0 introduced provider-based lazy metrics with two hook points (initialization / finalization) explicitly for Configuration Cache compatibility. Adopt this shape natively.
- Filters (exclude tasks/modules, min-duration thresholds) and `ignoreWhen` (skip publishing unless a property is set, e.g. only publish on CI) are small features users actually rely on.

**InfoKotlinProcess / InfoGradleProcess / InfoTestProcess** — tiny plugins that shell out to `jstat`/`jps` against the Gradle daemon, Kotlin daemon, and test worker JVMs at end of build, reporting heap usage/capacity and GC time (as build-scan custom values or console). Lesson: process health can be captured cheaply without an agent, using JDK tools already on the box, keyed off process discovery by main-class name. This is exactly the mechanism to reuse for your priority-3 metrics.

**build-process-watcher** (recent) — goes further: samples memory (heap + RSS) of GradleDaemon / GradleWorkerMain / KotlinCompileDaemon *during* CI builds over time, generates charts. Confirms demand for time-series-during-build sampling, not just end-of-build snapshots. A sampling sidecar (script or plugin-managed thread) is worth a phase-2 feature.

**Telltale** (+ **Bagan**, its Kubernetes-era predecessor) — experimentation frameworks: run a Gradle task N times across two branches/variants (cache modes: none / deps / local task / remote task / + transforms) on GitHub Actions, aggregate task-type, task-path, Kotlin build report, process, GC, and resource-usage reports, publish comparison site. Lesson: **A/B experimentation with controlled cache modes is a distinct product mode** from passive telemetry — and it composes gradle-profiler-style iteration with the same collectors your plugin ships. Telltale's report list is a ready-made checklist for your comparison reports.

**ProjectGenerator** — synthetic modularized Gradle projects; useful for your plugin's own integration testing and benchmark fixtures.

### 2.3 Tuist (tuist.dev) — closest architectural analog

Tuist announced first-class Gradle support on 2026-03-02: plugin id `dev.tuist` (v0.10.0 as of April 2026, applied in `settings.gradle.kts`, source in the open `tuist/tuist` monorepo — read `gradle-plugin/` there before writing your spec). Their bundle:

- **Build insights**: task execution timings and cache behavior, zero-config once the plugin is installed; dashboards with line/scatter views of build duration, cache hit rate; filters by environment (Local/CI), configuration, incremental/clean.
- **Remote build cache** integrated with Gradle's native build cache (their monetization anchor).
- **Test insights + flaky detection + automatic quarantine**: the plugin fetches the quarantined-test list from the server before each test task and excludes them via `excludeTestsMatching`. This closed-loop (server state → plugin behavior) is a genuinely differentiating pattern to copy.
- **Test sharding**, **selective testing**, **bundle-size analysis** (.aab/.apk breakdown, tracked over time, posted as GitHub PR comments), **Slack alerts**, **webhooks**, **CLI + API access to all data** (explicitly positioned for AI-agent consumption — they ship an MCP server and agent "skills" like fix-flaky-tests).
- Operationally notable: analytics upload **in background for local builds, foreground on CI** ("to avoid losing telemetry on short-lived agents") with a `uploadInBackground` override; documented data-retention policy; **self-hostable server and cache** — validates your self-host ambition and gives a reference for what a self-host story must document (server, cache, telemetry).

Tuist is the strongest evidence that "OSS plugin + hosted-or-self-hosted service" is the right product shape in 2026, and their docs sitemap is close to a table of contents for your own.

### 2.4 Bitrise Insights & Build Cache

Bitrise splits the offering: **Build Cache** (remote Gradle/Bazel/Xcode cache, CI-agnostic, config-cache supported) and **Insights** (analytics over CI + cache). Feature ideas worth stealing:

- **Cache hit rate drill-down hierarchy**: workspace → app → pipeline → stage → step → *individual Gradle task*, with trendlines and a breakdown table per level. Task-level cache hit rate is the single most actionable cache metric.
- **Task Inputs tab**: task execution data, input properties and file hierarchies for slow tasks — i.e. they capture task-input fingerprints to debug misses.
- **Execution-reason diagnostic builds**: a documented workflow that restores previous build outputs + Gradle metadata on CI to simulate an incremental build and surface Gradle's *execution reasons* (why a task wasn't UP-TO-DATE/FROM-CACHE). Gradle only reveals reasons relative to previous local state, so they made it a special build mode. Your plugin can capture `--info`-level execution reasons cheaply and offer a "diagnostic mode" later.
- **Alerts** on metric thresholds (e.g. cache hit rate below X) via Slack/email; **Bottlenecks page** auto-surfacing negative trends of the last 7 days — a "what got worse" landing page is higher value than raw charts.
- **Flaky test detection** by two signals: same commit hash producing different results across builds, and intra-build retries with mixed outcomes. Quarantine list injected into builds via env var (`BITRISE_QUARANTINED_TESTS_JSON`), consumed by steps or a Gradle init script.
- Cache ops observability: invocation counts, upload/download throughput, failure rate — remote-cache *transport* health is its own metric family (relevant when you later add a remote cache node).
- **Git Insights** (PR cycle time, merge frequency) — adjacent scope; note it, don't build it yet.

### 2.5 Datadog CI Visibility

The pipeline-level reference. Model: each pipeline execution is a **trace** with spans for pipeline → stage → job → step; explorer + facets over any tag; flame-graph view of a pipeline run. Ideas to import:

- **Queue/wait time as a first-class metric** (agent wait is often bigger than build regressions on CI).
- **Custom tags and measures** attachable at pipeline/job/stage/step level via a tiny CLI (`datadog-ci tag|measure --level job --tags k:v --measures m:1.2`, limits: 100 tags/100 measures, 300 chars). This is the cleanest UX for your "metrics from non-Gradle steps" requirement: ship an equivalent `report-metric` CLI/step.
- Job duration **compared against default-branch benchmarks** to flag regressions — the same baseline model you want (PR vs main).
- Test Optimization: flaky management (statuses like New Flaky), Test Impact Analysis (selective execution), correlation of tests to pipelines; monitors/alerts exportable from any explorer query; DORA metrics; logs + infra-metrics correlation per job.
- Azure DevOps is a supported provider — Datadog ingests it via **service hooks** (push on run/stage/job state changes), not polling. Precedent for your backend's Azure integration.

### 2.6 Adjacent OSS worth knowing (context, not requirements)

`gradle-doctor` (build-time sanity warnings), `gradle-analytics` style dashboards, AGP's `BuildAnalyzer`, and JetBrains' `k2-performance-metrics` (benchmark harness comparing compiler versions using build reports + a Kotlin Notebook for analysis — a nice lightweight pattern for your comparison reports). None combine collection + backend + regression intelligence.

---

## 3. What is technically possible — data sources

### 3.1 Gradle APIs (the collector core)

Config-cache-required on Gradle 8.x dictates the architecture; all of the below is stable and CC-safe:

- **Settings plugin** (applied in `settings.gradle.kts`, like Tuist and Develocity) — sees every project, can register build services before any project evaluates, and is the right home for a plugin that must observe the whole build. Apply-once semantics, no per-module boilerplate.
- **`BuildService` + `BuildEventsListenerRegistry.onTaskCompletion`** → `OperationCompletionListener` receiving `TaskFinishEvent`s: task path, start/end wall-clock, and result type — `TaskSuccessResult` exposes `isUpToDate`, `isFromCache`, plus incremental flag and execution reasons on success; failure/skip variants cover the rest. This yields the per-task outcome taxonomy (executed / up-to-date / from-cache(local?remote is not distinguished here — see below) / skipped / failed / no-source).
- **Flow API** (`FlowAction`, `FlowScope`, `FlowProviders.buildWorkResult`) — the CC-safe replacement for `buildFinished {}`: runs after the build completes with overall success/failure; this is where the payload gets assembled, the HTML artifact written, and the upload triggered.
- **`ValueSource`** — CC-safe capture of environment at execution time: git branch/sha (exec `git`), env vars, hostname, CI variables. Talaiot's late move to ValueSources confirms this is the pattern.
- **Local vs remote cache origin**: the TAPI task result alone says "from cache" but not which cache. Options: (a) Gradle's build-operation details via internal `BuildOperationListener` (what Talaiot and gradle-enterprise-alternatives tap — internal API, breaks occasionally), (b) infer from remote-cache HTTP access logs if you run the cache node, (c) accept local/remote-agnostic "from cache" in v1 and add origin later. Recommend (c) then (a) behind a version-guarded adapter.
- **Configuration-time visibility**: total configuration time is derivable (first task start − build start via a build-listener-free trick: record service instantiation time and `buildWorkResult` timestamps; or read `--profile` data). Per-project configuration cost is only available via internal build operations — defer.
- **Configuration cache + Isolated Projects constraints**: no `Project` references at execution time, everything through `Provider`s/serializable service parameters; test against `--configuration-cache` and (future-proofing) `-Dorg.gradle.unsafe.isolated-projects=true`. Talaiot's issue history (#408, lazy service params #419) is a free list of pitfalls.
- **Develocity-style custom values**: expose a small extension `buildTelemetry { tag("team","payments"); value("mrId", env) }` recorded through providers.
- Gradle version note: Gradle 9.x is current in 2026; verify API deprecations against 9.x during spec (TAPI events and Flow API are stable across 8→9).

### 3.2 Kotlin Build Reports (KGP built-in — free deep compiler telemetry)

Enabled purely via `gradle.properties`; outputs combinable: `file`, `single_file`, `json` (Kotlin ≥1.9.23), `build_scan`, and `http`. The HTTP output POSTs one JSON document per Kotlin task (`CompileStatisticsData`) to `kotlin.build.report.http.url` (+ optional basic auth, `verbose_environment`, git-branch inclusion, custom `label`, additional tags). Contents per task:

- Time metrics tree: total task time, daemon connect, incremental compilation breakdown (initial dirty-set calculation, dependency-change analysis, removed-class detection, cache update, per-round source compilation), compiler init, code analysis, code generation.
- Size metrics: cache directory size, ABI snapshot size, compiler iterations, lines of code.
- Build attributes: **non-incremental rebuild reasons** (e.g. `UNKNOWN_CHANGES_IN_GRADLE_INPUTS`) — the Kotlin-side "why did this recompile" signal.
- Identity: build UUID, task path, Kotlin version, label/tags.

Two integration options: point `http.url` straight at your ingestion service (simplest; correlate via build UUID — your plugin should set/propagate the same UUID as a report label or tag), or use `json` output and have the plugin bundle the files into its own payload (more robust on flaky networks, one upload path). Recommend the **json-dir + bundle** approach for CI and offer direct-http as an option. Caveats: schema explicitly "may change between versions" — version the parser; `build_scan` output truncates via custom-values limits (irrelevant for you); KMP: reports cover Kotlin JVM/JS/Native compile tasks; K2 changed some metric names around 2.0 (the JetBrains repo's `BuildPerformanceMetric.kt` is the source of truth).

### 3.3 Android/KMP/KSP specifics

AGP task types make module-level attribution meaningful (task *type* + module is the aggregation Talaiot added in 2.0.4 — include both). KSP appears as ordinary `ksp*` tasks, so processor cost is trackable per task; KSP2 runs in-process (no kapt daemon). APK/AAB size: parse outputs of `bundle*`/`assemble*` tasks or run `bundletool` in a small optional task; Tuist's bundle-analysis (breakdown + trend + PR comment) is the feature model. Toolchain versions (Gradle, AGP, KGP, KSP, JDK vendor/version, OS/arch) are all cheaply readable at configuration time via providers — they're the dimensions that make long-term trend data interpretable, so record them on every build.

### 3.4 Process health (priority 3)

Reuse the InfoKotlinProcess technique: at end of build (Flow action), discover JVMs by main class (`GradleDaemon`, `KotlinCompileDaemon`, `GradleWorkerMain`) via `jps`/`ProcessHandle`, sample `jstat -gc` / `jcmd GC.heap_info` for heap used/committed/max and GC time, plus `/proc/<pid>/status` RSS on Linux agents. Phase 2: optional low-frequency sampling thread during the build (build-process-watcher model) producing a small time series. Also record the *configured* JVM args for Gradle/Kotlin daemons (mismatch between -Xmx and actual usage is the classic finding).

### 3.5 gradle-profiler — recommendation (you asked me to advise)

gradle-profiler is a **benchmark harness**, not a telemetry source: it drives N warm-ups + M measured builds of HOCON-defined scenarios (with source mutators: abi/non-abi change, build-script change; cache controls like `clear-build-cache-before`; `--measure-config-time`, `--measure-local-build-cache`), across one or more Gradle versions, outputting mean/stderr CSV + HTML, or deep profiles (async-profiler/JFR flamegraphs, Chrome/Perfetto traces). It runs builds *from outside*; your plugin runs *inside* builds. They compose perfectly:

1. **Passive telemetry** (your plugin) on every real CI/local build — the product core.
2. **Scheduled benchmark mode**: a nightly/weekly Azure job runs gradle-profiler scenarios (clean, incremental-non-abi, no-op, config-cache-hit) *with your plugin active*, tagging builds `mode=benchmark, scenario=X, iteration=N`. The backend then gets controlled, low-noise series for regression detection — Telltale/k2-performance-metrics formalized exactly this.
3. Local deep-profiling stays a manual gradle-profiler use; your docs should teach it rather than reimplement it.

So: don't build on gradle-profiler; build *alongside* it, and ship the scenario files + pipeline template as part of the product.

### 3.6 Azure Pipelines integration (hybrid, as agreed)

Three complementary mechanisms:

- **Pull — Timeline REST API**: `GET {org}/{project}/_apis/build/builds/{buildId}/timeline?api-version=7.1` returns the full record tree (Stage → Phase → Job → Task) with start/finish times, result, worker name, log refs; the Builds API adds queue time (`queueTime` vs `startTime` = agent wait — the Datadog-style metric), source branch/commit, reason (PR/manual/schedule), definition. Backend pulls this after receiving a build payload (correlate via `BUILD_BUILDID` env var the plugin already recorded) — zero pipeline changes for teams, full non-Gradle step coverage.
- **Push — service hooks**: subscribe the backend to `build.complete` / stage-state-changed events per project (what Datadog does). Better than polling for multi-tenant SaaS; requires per-tenant setup, so make it optional on top of pull.
- **In-pipeline metric CLI/step** for arbitrary custom measures (Datadog's `datadog-ci measure` model): a tiny script/step `telemetry-cli metric --scope job --name apk.size --value 12345` posting to the backend with the build correlation id. This covers signing, upload, custom tooling steps that the timeline can't quantify beyond duration.

**Packaging advice:** start with a **reusable YAML template repo** (steps template that injects gradle.properties config, env token, and the metric CLI) — fastest to iterate, works for your clients immediately. Graduate to a **Marketplace extension** (custom task via tfx) once the product is stable and you want one-click adoption + a UI tab; extensions add publisher-account and review overhead you don't want during iteration. The Azure REST API and predefined variables (`BUILD_*`, `SYSTEM_*`, `AGENT_*`) should be verified against current api-version during the spec phase.

---

## 4. Metrics catalog — what to track

Ordered by your stated priorities. "Dim" = dimension (filter/group-by), "Meas" = measure (aggregate/trend).

### 4.1 Build level (every build)

| Metric | Kind | Source |
|---|---|---|
| Build id (UUID), start/end, wall duration | Meas | plugin |
| Outcome (success/failure), failed task, failure class | Dim | Flow API `buildWorkResult` |
| Requested tasks, root project name | Dim | StartParameter provider |
| Configuration time (approx) + config-cache state (hit/miss/stored/disabled) | Meas/Dim | plugin |
| Environment: CI vs local, hostname (anonymized), OS/arch, cores, total RAM | Dim | ValueSource |
| Toolchain: Gradle, AGP, KGP, KSP, JDK vendor+version | Dim | providers |
| VCS: branch, sha, dirty flag; PR id / target branch on CI | Dim | ValueSource / Azure env |
| CI context: pipeline, stage, job, build id, agent name, queue time | Dim/Meas | Azure env + Timeline API |
| Daemon: fresh vs reused, uptime, Gradle/Kotlin JVM args | Dim | plugin/process probe |
| User id (pseudonymous hash by default; configurable) | Dim | plugin |
| Custom tags/values | Dim | extension DSL |

### 4.2 Task + cache level (priority 1)

Per task: path, module, task type (class), plugin that registered it, start/end, duration, worker/thread lane (for timeline rendering), outcome (EXECUTED / UP-TO-DATE / FROM-CACHE / SKIPPED / NO-SOURCE / FAILED), cacheable flag + non-cacheable reason, incremental flag, execution reasons (when Gradle provides them), origin build id for cache hits when obtainable. Derived per build: cacheable-task hit rate, avoidance savings (sum of durations of avoided tasks based on historical mean execution time — the Develocity "saved time" number), critical path (longest dependency chain by end times), serial vs parallel utilization. Aggregations the dashboard needs: hit rate by task type, by module, by pipeline; slowest tasks p50/p95; "most expensive frequently-missing tasks" (miss rate × mean duration) — the single best optimization-targeting metric. Phase 2: task-input fingerprints for miss explanation / build comparison (the Bitrise Task Inputs / Develocity comparison feature; biggest engineering lift, biggest differentiator).

### 4.3 Tests & flakiness (priority 2)

Per test task: counts (passed/failed/skipped), duration, retried tests. Per test case (from JUnit XML the plugin parses, or a `TestListener`-based service): class+name, duration, result, failure message hash. Flaky signals: same-commit divergent outcomes across builds; intra-build retry pass-after-fail (adopt both Bitrise heuristics). Backend features this unlocks: flaky ranking, new-flaky-on-commit detection (Datadog), quarantine list served to the plugin which applies `excludeTestsMatching` before test tasks (Tuist's closed loop). Slow-test trends and time-per-module round it out.

### 4.4 Kotlin compile detail (rides with priority 1)

From build reports (§3.2): per Kotlin task incremental vs full + rebuild reason, compile-phase breakdown (analysis vs codegen vs IC bookkeeping), daemon connect time, LOC and lines/sec, IC cache sizes. Trendable insights: % non-incremental compilations and top rebuild reasons (the actionable one), analysis-time drift across Kotlin versions (K2 upgrades), per-module compile cost.

### 4.5 Process health (priority 3)

End-of-build snapshot per JVM (Gradle daemon, Kotlin daemon, workers): heap used/committed/max, GC total time, RSS; configured vs used memory delta; daemon starts per build (churn indicates memory pressure or config drift). Phase 2: sampled time series during build.

### 4.6 Dependency resolution & network (priority 4)

V1: total dependency-download time and bytes where obtainable (build-operation internals or repository access logs), resolution wall time per configuration is internal-API territory — record only coarse signals initially (e.g. `--refresh-dependencies` flag, offline flag, count of changing/dynamic versions as a lint-style metric). Remote-cache transport health (throughput, error rate) becomes relevant when a remote cache node enters the stack (Bitrise treats it as a first-class family).

### 4.7 Beyond build performance (as selected)

APK/AAB size per variant (+ breakdown later), tracked per commit with PR-comment potential; toolchain-version adoption timelines (which builds/agents still on old AGP etc.). CI-side: per-step durations from the Timeline API let you show "Gradle was 6 of 14 pipeline minutes" — checkout, cache restore, signing, upload all become visible, answering your original Azure requirement.

---

## 5. Feature candidates (input for the spec)

**Gradle plugin (OSS, settings-applied):** zero-config defaults + `buildTelemetry {}` DSL; outcomes/timings collector; Kotlin build-report bundling; test parsing; process probe; CI autodetection (Azure first: map `BUILD_*`/`SYSTEM_*` vars); opt-in local mode with pseudonymized identity (salted hash of username/hostname, salt per project, configurable to off/plain); offline spool-and-retry directory for failed uploads; foreground upload on CI / background locally (Tuist); `ignoreWhen`/filters (Talaiot); per-build **self-contained HTML artifact** (single file, embedded JSON + JS: timeline lanes, task table, cache summary, Kotlin panel — publishable via `PublishBuildArtifacts` and linkable from the dashboard).

**Ingestion service (multi-tenant):** token-per-project auth; versioned JSON ingest endpoint (+ endpoint compatible with raw Kotlin build-report HTTP POSTs); Azure Timeline puller + optional service-hook receiver; metric CLI endpoint; retention/downsampling jobs.

**Dashboard:** trends (duration p50/p95 by pipeline/branch/scenario, hit rate, flaky count, APK size); build detail page (mirror of HTML artifact + CI step context); *Bottlenecks/"what regressed"* landing page (Bitrise); PR vs main-baseline comparison with statistical guard (compare against rolling median of last N default-branch builds of the same scenario/pipeline, flag beyond MAD/stddev threshold — Datadog's benchmark model); budgets (max build duration, min hit rate, max APK size) evaluated per build → CI status/PR comment; alerts to Slack/Teams/webhook on threshold or trend breach; benchmark-mode series (gradle-profiler runs) rendered separately from noisy real-build series; API + CSV export for everything (Tuist's agent-friendly stance is worth copying, including an MCP surface later).

**Explicit non-goals for v1** (note in spec): remote build cache node (compose with Gradle's HTTP cache or Bitrise instead), Git/DORA insights, deep dependency-resolution tracing, IDE sync telemetry.

---

## 6. Architecture recommendations & key decisions

**Backend stack (asked to advise):** Kotlin + Ktor (or Spring Boot if you prefer batteries) — same language as the plugin means shared payload models in a KMP `commons` module (serialization compatibility guaranteed), and an OSS Gradle tool written in Kotlin end-to-end reads credibly to its audience. **Storage:** Postgres + TimescaleDB — relational for tenants/projects/quarantine lists, hypertables + continuous aggregates for the time series, one operationally boring database, fits a Docker/Compose self-host story (and Dokploy-style deploys) neatly. Choose ClickHouse only if per-task rows at high volume (many tenants × thousands of tasks × many builds/day) become the norm; the payload/API design below keeps that swap possible. Grafana can be offered as an optional read-only companion, but the product features (baselines, budgets, quarantine) need your own service regardless — Talaiot proves raw-Grafana-only is a dead end for a *product*.

**Payload:** one build = one versioned JSON document (schema_version field), gzip, containing build envelope + task array + kotlin reports + tests + processes; server normalizes into rows. Idempotent by build UUID. Retention (asked to advise): raw task-level 90 days, per-build rollups 13 months, daily aggregates indefinitely; make windows per-tenant config; document it like Tuist does.

**Regression intelligence:** only compare like with like — key baselines on (pipeline, scenario/requested-tasks signature, branch class). Real CI builds are noisy; the benchmark mode (§3.5) exists to give clean series, and PR comparisons should use rolling default-branch baselines with robust statistics, not single-build diffs.

**Risks / gotchas register:** Kotlin build-report schema drift (version the parser, tolerate unknown fields); internal Gradle build-operations if used for cache origin/config detail (isolate behind adapter, feature-flag per Gradle version); config-cache serialization traps in the service parameters (Talaiot #408/#419); Isolated Projects on the horizon; ephemeral-agent upload loss (foreground upload + spool); custom-value cardinality abuse by users (cap like Datadog: N tags, length limits); privacy of local telemetry (pseudonymize by default, document); payload size on 1000-module builds (compress, cap task array with overflow summary).

---

## 7. Open questions for the spec phase

1. Cache-origin fidelity: is local-vs-remote distinction required in v1 (forces internal API) or acceptable in v1.x?
2. Test-case granularity on very large suites — full per-case ingest vs per-class rollup with per-case only for failures/retries?
3. Quarantine closed-loop in v1 or after flaky *detection* proves itself?
4. HTML artifact: fully standalone (embedded JS, no CDN — works in Azure artifact viewer) confirmed as requirement?
5. Which of your projects becomes the pilot tenant (Betty's Kitchen infra is PHP/WordPress — presumably a client Android/KMP repo)?
6. Naming/branding + plugin coordinates (affects portal publishing setup) — decide before public code.

## 8. Primary sources

github.com/cdsap — Talaiot, InfoKotlinProcess, InfoTestProcess, Telltale, Bagan, build-process-watcher, ProjectGenerator · tuist.dev — Gradle announcement blog (2026-03-02), docs (install-gradle-plugin, build-insights/gradle, test-insights, bundle-size, self-hosting), plugin `dev.tuist` on the Gradle Plugin Portal, source in tuist/tuist · bitrise.io — Build Cache docs (Gradle, execution-reason diagnostic builds, command & cache metrics), Insights docs (flaky tracking, alerts, bottlenecks) · docs.datadoghq.com — CI Visibility (pipelines, custom tags & measures, Test Optimization) · JetBrains — "Introducing Kotlin Build Reports", JetBrains/kotlin report sources (`BuildReportsService.kt`, `BuildPerformanceMetric.kt`), Kotlin/k2-performance-metrics, kotlin-build-report-sample · gradle/gradle-profiler README & releases · Gradle user manual — build services, dataflow actions, configuration cache.
