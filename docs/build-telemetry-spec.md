# BuildHound — Specification (v0.1 draft)

Companion to `build-telemetry-research.md`. Naming (decision #6, resolved 2026-07-02): product name **BuildHound**, domain **buildhound.dev**, plugin id `dev.buildhound`, Maven group `dev.buildhound`. The research doc predates the decision and keeps the old working name (BTP).

**Locked decisions incorporated:** cache local/remote origin deferred to v1.x · tests ingested as per-class rollups with per-case detail only on failure/retry · quarantine closed-loop gated on proven flaky detection · HTML artifact fully standalone (zero CDN/network) · CI integration is provider-agnostic via SPI, Azure DevOps is the first implementation, not a dependency.

---

## 1. Goals & non-goals

**Goals (v1):** capture build/task/cache/test/Kotlin-compile telemetry from Gradle 8.14+ builds with configuration cache required; per-build standalone HTML artifact; multi-tenant ingestion service + dashboard with trends, baselines, budgets, alerts; opt-in pseudonymized local-build telemetry; CI-agnostic core with pluggable provider integrations; OSS-ready codebase (Apache-2.0).

**Non-goals (v1):** remote build cache node · local/remote cache-origin split (v1.x) · quarantine enforcement (post-flaky-detection) · Git/DORA analytics · dependency-resolution tracing · IDE sync telemetry · per-test-case ingestion for passing tests.

## 2. System architecture

```
 Gradle build (CI or local)
 ┌────────────────────────────────────────────┐
 │ BuildHound settings plugin                 │
 │  ├ TaskEventCollector (BuildService)       │
 │  ├ EnvironmentCollector (ValueSources)     │
 │  ├ CiEnvironmentProvider SPI  ◄─ built-ins │
 │  ├ KotlinReportBundler (json dir)          │
 │  ├ TestResultCollector (XML)               │
 │  ├ ProcessProbe (end-of-build)             │
 │  └ Finalizer (Flow API)                    │
 │      ├ HTML artifact (standalone)          │
 │      └ Uploader (spool + retry)            │
 └───────────────┬────────────────────────────┘
                 │ POST /v1/builds (gzip JSON, token)
 ┌───────────────▼────────────────────────────┐
 │ BuildHound server (Ktor, multi-tenant)     │
 │  ├ Ingest API + validation + idempotency   │
 │  ├ CiConnector SPI ◄─ AzureDevOpsConnector │
 │  │   (timeline pull / webhook / links)     │
 │  ├ Postgres + TimescaleDB                  │
 │  ├ Rollup + retention jobs                 │
 │  ├ Regression engine (baselines, budgets)  │
 │  ├ Alert dispatch (Slack/Teams/webhook)    │
 │  └ Query API ──► Dashboard SPA + CSV/API   │
 └────────────────────────────────────────────┘
 Side channels: metric CLI (any CI step) → POST /v1/metrics
                gradle-profiler benchmark pipeline → tagged builds
```

Repos/modules: `buildhound-commons` (kotlinx-serialization payload models, shared plugin↔server), `buildhound-gradle-plugin`, `buildhound-server`, `buildhound-dashboard` (SPA, can live in server repo), `buildhound-ci-assets` (YAML templates, metric CLI, profiler scenarios), `buildhound-report` (standalone HTML artifact frontend, embedded into plugin resources at build time).

## 3. Gradle plugin

### 3.1 Application & compatibility

Settings plugin: `plugins { id("dev.buildhound") version "x" }` in `settings.gradle.kts`. Compatibility contract: Gradle 8.14+ and 9.x (floor from the JVM-21 owner decision and `BuildFeatures`; see architecture decision log), config cache on/off both green, `isolated-projects` best-effort (CI job tracks it, non-blocking). JDK 21+ runtime for the plugin (owner decision superseding the original Java 11+). No internal Gradle APIs in v1; the v1.x cache-origin feature gets an isolated `internal-adapters` module, feature-flagged per Gradle version, degrading to "unknown origin" gracefully.

### 3.2 Collection pipeline

- **TaskEventCollector**: `BuildEventsListenerRegistry.onTaskCompletion(BuildService)`. Per `TaskFinishEvent`: path, module (derived), start/end ms, result → outcome enum `EXECUTED | UP_TO_DATE | FROM_CACHE | SKIPPED | NO_SOURCE | FAILED`, incremental flag, execution reasons (when present). Task type/class captured at configuration time into the service parameter map (provider-lazy, CC-safe).
- **EnvironmentCollector**: ValueSources for git (branch, sha, dirty), hostname, user (hashed per §3.7), OS/arch/cores/RAM, toolchain versions (Gradle, JDK; AGP/KGP/KSP read from plugin classpaths where applied), daemon reuse.
- **Finalizer**: `FlowAction` on `FlowProviders.buildWorkResult` — assembles the payload from the service, computes derived metrics (hit rate, avoidance estimate, critical path, parallel utilization), writes HTML artifact, invokes uploader. Never fails the build: all errors log at `warn` and write a failure marker file.
- **Config-cache state** recorded as `HIT | MISS_STORED | DISABLED | INCOMPATIBLE` (from start parameters + heuristics; refined later).

### 3.3 CI provider SPI (plugin side) — per your modularity requirement

```kotlin
interface CiEnvironmentProvider {
    val id: String                                   // "azure-devops", "github-actions", ...
    fun detect(env: Map<String, String>): CiContext? // null = not this provider
}

data class CiContext(
    val provider: String,
    val pipelineId: String?, val pipelineName: String?,
    val runId: String?,       // correlation key for backend connectors
    val jobId: String?, val stageId: String?,
    val branch: String?, val commitSha: String?,
    val pullRequestId: String?, val targetBranch: String?,
    val buildUrl: String?, val agentName: String?,
    val attributes: Map<String, String> = emptyMap(), // provider-specific extras
)
```

Discovery: built-ins first, then `ServiceLoader` for third-party implementations on the settings classpath, then a **generic provider** honoring `BUILDHOUND_CI_*` env vars so unsupported CIs work with zero code. *As built (plan 027):* the built-in matrix is the CCUD 10 — Azure DevOps, GitHub Actions, GitLab, Jenkins, TeamCity (env-only partial), CircleCI, Bamboo, Travis, Bitrise, GoCD, Buildkite — registered most-specific-marker-first. GitHub carries `runAttempt` (+ an `/attempts/N` URL suffix so re-runs don't collide). When no `BUILDHOUND_CI_*` variable is active, the generic provider still classifies the build as CI — provider `generic`, no mapped fields — if the conventional `CI` variable is set and not `false`/`0` (CircleCI, GitLab, Jenkins, Travis and most others set it). The same truthiness rule applies to `BUILDHOUND_CI`: truthy activates the mapping, and an explicit falsy value forces non-CI for the generic provider, overriding `BUILDHOUND_CI_PROVIDER` and the bare-`CI` fallback (plan 014). First non-null `detect()` wins; explicit override via `buildhound { ci.provider = "..." }`. Azure mapping: `TF_BUILD`→detected, `BUILD_BUILDID`→runId, `BUILD_DEFINITIONNAME`→pipelineName (fallback `SYSTEM_DEFINITIONNAME`), `BUILD_SOURCEBRANCH`, `BUILD_SOURCEVERSION`, `SYSTEM_PULLREQUEST_PULLREQUESTID`, `AGENT_NAME`, URL composed from `SYSTEM_COLLECTIONURI`+project+buildId. The same interface file lives in `buildhound-commons` and is documented as a public extension point (README recipe: "add your CI in 30 lines").

### 3.4 Configuration DSL

```kotlin
buildhound {
    server { url = "https://buildhound.example.com"; token = providers.environmentVariable("BUILDHOUND_TOKEN") }
    mode = auto            // auto | ci | local | disabled  (auto: CI detected → ci)
    localBuilds { enabled = true; requireOptInFile = true } // ~/.buildhound/optin marker
    identity { pseudonymize = true }                        // false | true | strict(off)
    tags.put("team", "mobile"); value("mrLabel", providers.environmentVariable("MR_LABEL"))
    // tags/values are cardinality-capped (plan 019): ≤100 entries each, keys ≤100 chars,
    // values ≤300 chars; excess is dropped/truncated and recorded in the payload `caps` block.
    filters { excludeTasks("help", "tasks"); minTaskDurationMs = 0 }
    ignoreWhen { property("buildhound.skip") }
    // Salted input fingerprints for cache-miss comparison (plan 022). Built-in JDK/locale/OS/
    // timezone/parallelism keys are always captured; these add named inputs. Values are salted
    // 16-hex hashes — no plaintext leaves the machine.
    fingerprints { systemProperties("robolectric.offline"); envVars("CI"); gradleProperties("kotlin.incremental") }
    kotlinReports { bundle = true }        // validates gradle.properties wiring, warns if absent
    tests { collect = true }               // per-class rollup + failure/retry detail (locked)
    processProbe { enabled = true }
    htmlReport { enabled = true; outputDir = layout.buildDirectory.dir("reports/buildhound") }
    upload { uploadInBackground = false } // plan 027: local builds spool instead of blocking on the send
}
```

Config overrides (plan 027, CCUD `Overrides` pattern): every DSL knob above **except `server.token`**
is overridable via a `buildhound.<key>` gradle property or a `BUILDHOUND_<KEY>` env var (`<KEY>` =
`<key>.uppercase().replace('.','_')`), precedence **explicit DSL value → override → default**. Keys:
`enabled`, `mode`, `server.url`, `identity.pseudonymize`, `htmlReport.enabled`, `localBuilds.enabled`,
`localBuilds.requireOptInFile`, `upload.uploadInBackground`. `server.token` is excluded by construction
(an override would serialize the token into the on-disk CC entry). `uploadInBackground` opts a **local**
build out of blocking on the inline upload — it spools and the next build's drain sends it (no new
thread — §3.9); CI/benchmark always upload inline. New payload fields (spec §4): `environment.{ide,
ideVersion,ideSync,aiAgent}`, `vcs.remoteUrl` (redacted, all-scheme, fail-closed), and top-level
`links` (`commitUrl`/`pullRequestUrl`, github/gitlab, https-gated).

Kotlin build reports: plugin does **not** silently mutate KGP properties; it reads whatever directory `kotlin.build.report.json.directory` names (it does not require a specific path), and emits a single copy-paste `gradle.properties` fix when the wiring is absent — either `kotlin.build.report.output=JSON` with no directory set, or no report wired at all on a build that ran Kotlin compilations (honest, CC-safe, no ordering hazards; non-Kotlin builds stay silent). Bundler reads the JSON files in the Finalizer and embeds them (schema-version tagged) in the payload. Ordering/staleness are handled by a modified-time window rather than assuming KGP writes before our Finalizer runs (plan 023 §4a): only reports touched within 60 s of build start are bundled, so a stale report from a prior build is treated as absent, never mis-attributed.

### 3.5 Tests (locked granularity)

Collector parses JUnit XML from test tasks (works for JVM + KMP jvm targets; Android unit tests). Ingested shape per test task: per-class rollup (class fqn, counts passed/failed/skipped, duration) + `TestCaseDetail[]` **only** for failed or retried cases (name, duration, outcome sequence, failure message hash + truncated text). Retry detection: duplicate case entries / Gradle `retry` plugin outputs. Schema reserves an optional `allCases` array so expanding granularity later is additive, not breaking.

### 3.6 Process probe

End-of-build (Finalizer): enumerate JVMs via `jps -l` matching main classes (`GradleDaemon`, `KotlinCompileDaemon`, `GradleWorkerMain`), then per PID `jstat -gc` + `jstat -gccapacity`, `jinfo -flags`, and `ps` (as implemented, plan 029). Record per process: `role`, heap used/committed/max, GC time, RSS, configured `-Xmx`, uptime → the "configured vs used" delta.

**Measurement math (locked, research §4.1):** heap **used** = `EU+OU+S0U+S1U` (survivors **included**); **committed** = `EC+OC+S0C+S1C`; **max** = `-gccapacity` `NGCMX+OGCMX` (JVM capacity, *distinct* from configured `-Xmx`); **GC time** = jstat `GCT` **total** column (never `YGCT+FGCT`, which omits `CGCT` and undercounts concurrent G1/ZGC); configured **`-Xmx`** = jinfo `-XX:MaxHeapSize`; **RSS** = `ps -o rss=` (portable, replacing the earlier Linux-only `/proc`); **uptime** = `ps -o etime=`. jstat columns are read **by header name**, not position (JDK layouts differ). **No PID or command line** is stored (host-local noise; jinfo/ps args can embed secrets — §3.7); `role` is the only key, so multiple workers are repeated `GRADLE_WORKER` rows.

**Payload semantics / blind spots.** One snapshot, no sampling (time-series is v1.x). `processes: []` means "nothing observable" — not an error: JDK tools absent (JRE-only agent), a Kotlin daemon that exited before the finalizer, in-process compilation, or `processProbe { enabled = false }` all yield an empty list. A daemon killed before the finalizer runs never reports at all (that lost-build case is plan 033). Every exec is timeout-bounded; any failure degrades to `[]`, never a failed build.

### 3.7 Privacy & identity

Identity fields: `userId = "u_" + hex12(hmacSha256(projectSalt, "user:" + username + "@" + hostname))`, `hostnameHash = "h_" + hex12(hmacSha256(projectSalt, "host:" + hostname))` — first 12 hex chars, domain-separated inputs; enumeration resistance rests on salt secrecy, not digest length, so truncation is safe. Salt generated per project on the server, fetched once and cached (interim until tenancy lands: generated locally into `.gradle/buildhound/identity.salt`, see plan 003). `pseudonymize=false` sends plaintext (team choice); `strict` sends nothing. Payloads never include absolute paths outside the project, env dumps, or tokens; a scrubber strips values matching secret-like patterns from execution reasons/failure text.

### 3.8 Standalone HTML artifact (locked: no CDN)

Single self-contained `buildhound-report.html`: inlined CSS/JS (a small vendored chart lib or hand-rolled SVG — no external requests, so it renders inside Azure artifact viewers and email attachments). Content: header (build id, outcome, duration, env, toolchain), task timeline by concurrency lane (lanes computed greedily from per-task start/end overlaps — max observed parallelism — not the Gradle `worker` id, which stays unpopulated; plan 017), sortable task table with outcome/duration/type, cache summary donut + top cacheable misses, Kotlin panel (incremental %, rebuild reasons, slowest compilations), tests summary with failures, process snapshot, link to the dashboard build page when server configured. Data embedded as one JSON blob → the artifact doubles as an offline payload copy.

### 3.9 Upload semantics

One synchronous upload attempt per payload for **every** mode, inside the Flow-API finalizer (`PayloadUploader`): a 15 s timeout on both connect and request, gzip JSON `POST <url>/v1/builds` with an `Authorization: Bearer` header, redirects never followed; a non-loopback plaintext-`http://` URL logs a warning. Failures are classified — transport errors, 5xx, and 408/429 spool the payload to `build/buildhound/spool/<buildId>.json.gz` for retry; other 4xx (bad token, bad payload) are permanent rejections, dropped with a warning and never retried. The **next** build's finalizer drains up to 10 spooled files oldest-first *before* uploading its own payload; a rejected file is deleted and never blocks younger ones, the spool is trimmed to 20 files, and any file over 8 MB is dropped unread. Upload gating: CI and benchmark modes always upload; local mode uploads only when `localBuilds.enabled` and (by default) the `~/.buildhound/optin` marker is present (§3.4/§3.7). Idempotency: build UUID; the server dedupes.

Payloads are gzipped and **size-capped before upload** (see §4 `caps`, plan 019): a hard byte budget with the overflow strategy (drop per-task execution reasons first, then truncate the task array with summary counts — never drop the build envelope), plus tag/value cardinality and free-text length caps; the server re-clamps defensively at ingest.

*Deliberately not a background thread:* the original v0.1 sketch's local background/JVM-exit-flush upload was dropped — there is no reliable flush guarantee at daemon shutdown, and Talaiot's `publishOnNewThread` during `BuildService.close()` silently drops data (plan 008). *Planned, not shipped:* an opt-out `uploadInBackground` knob for local builds (Tuist parity — an opt-out from blocking local builds, not the default; plan 027), and an optional CI logging-command annotation emitted on spool (future `buildhound-ci-assets` work).

## 4. Payload schema (v1, `schemaVersion: 1`)

Top-level document (kotlinx-serialization models in `buildhound-commons`; server tolerates unknown fields):

```jsonc
{
  "schemaVersion": 1,
  "buildId": "uuid", "projectKey": "from-token",
  "startedAt": 0, "finishedAt": 0, "outcome": "SUCCESS|FAILED",
  "failure": { "taskPath": "...", "exceptionClass": "...", "messageHash": "..." },
  "requestedTasks": ["assembleDebug"], "mode": "ci|local|benchmark",
  "environment": { "os": "...", "arch": "...", "cores": 0, "ramMb": 0,
                   "hostnameHash": "...", "userId": "...", "daemonReused": true,
                   "configurationCache": "HIT|MISS_STORED|DISABLED|INCOMPATIBLE" },
  "toolchain": { "gradle": "9.x", "jdk": "...", "agp": "...", "kgp": "...", "ksp": "..." },
  "vcs": { "branch": "...", "sha": "...", "dirty": false },
  "ci": { "provider": "azure-devops", "runId": "...", "pipelineName": "...",
          "jobId": "...", "buildUrl": "...", "attributes": {} },
  "tags": {}, "values": {},
  "tasks": [ { "path": ":app:compileDebugKotlin", "module": ":app", "type": "KotlinCompile",
               "startMs": 0, "durationMs": 0, "outcome": "FROM_CACHE", "cacheable": true,
               "nonCacheableReason": null, "incremental": false, "worker": 3,
               "executionReasons": ["..."] } ],
  "derived": { "cacheableHitRate": 0.0, "avoidedMs": 0, "criticalPathMs": 0,
               "parallelUtilization": 0.0, "configurationMs": 0 },
  "caps": { "droppedTags": 0, "droppedValues": 0, "truncatedValues": 0,
            "droppedExecutionReasons": 0, "truncatedExecutionReasons": 0,
            "truncatedNonCacheableReasons": 0, "droppedTasks": 0, "droppedTaskOutcomes": {} },
    // ^ present only when the caps enforcement dropped/truncated something (plan 019); omitted otherwise
  "fingerprints": { "build": { "jdk.home": "9f86d081884c7d65…", "env-CI": "18ac3e7343f01690…" },
                    "tasks": {} },
    // ^ salted 16-hex input hashes for cache-miss comparison (plan 022); `tasks` is reserved for
    //   the per-Test capture add-on. Null/omitted when uncaptured.
  "kotlin": { "reportSchema": "2.x", "perTask": [ /* bundled CompileStatisticsData subset */ ] },
  "tests": [ { "taskPath": ":app:testDebugUnitTest", "durationMs": 0,
               "classes": [ { "class": "...", "passed": 0, "failed": 0, "skipped": 0, "durationMs": 0 } ],
               "failedOrRetried": [ { "class": "...", "name": "...", "outcomes": ["FAILED","PASSED"],
                                      "durationMs": 0, "messageHash": "...", "message": "truncated" } ] } ],
  "processes": [ { "role": "KOTLIN_DAEMON", "heapUsedMb": 0, "heapCommittedMb": 0, "heapMaxMb": 0,
                   "configuredXmxMb": 0, "gcTimeMs": 0, "rssMb": 0, "uptimeS": 0 } ],
  "artifacts": { "apk": [ { "variant": "release", "sizeBytes": 0, "type": "AAB" } ] }
}
```

## 5. Ingestion service

**Stack:** Kotlin + Ktor, Postgres 16 + TimescaleDB, Flyway migrations, one Docker image (+ compose file with Postgres) for self-host; horizontally stateless.

**Tenancy:** `org → project → apiToken(s)` (scoped: ingest-only vs read). All rows carry `project_id`; queries always tenant-filtered; per-project settings (retention overrides, identity salt, baseline config, budgets, alert channels).

**API (v1):**
- `POST /v1/builds` — gzip payload, token auth, idempotent on buildId. Async post-processing job: normalize rows, compute rollups, trigger connector enrichment + regression evaluation.
- `POST /v1/metrics` — the CLI endpoint: `{correlation: {provider, runId} | buildId, scope, name, value|text, unit?}` (Datadog tag/measure model; caps: 100 measures/run, key+value ≤ 300 chars).
- `POST /v1/kotlin-report` — accepts raw KGP HTTP-report POSTs for teams preferring direct `kotlin.build.report.http.url` wiring; correlated by label/buildId.
- `GET /v1/...` query API mirroring every dashboard view (JSON + CSV) — public, documented, versioned (agent/MCP-friendly).
- `GET /v1/builds/{a}/compare/{b}` — read-scope, tenant-scoped input-fingerprint comparison (plan 022): ranks the salted-hash inputs (build-level + declared toolchain/env fields) that differ between two builds by how much of B's cache misses (executed in B, avoided in A) they could explain, with catalog notes; 400 when `a == b`, 404 when either build is missing or foreign-tenant.
- Webhook receiver mount point per connector (below).

**CI connector SPI (backend side):**

```kotlin
interface CiConnector {
    val id: String
    val capabilities: Set<Capability>   // TIMELINE_PULL, WEBHOOK, DEEP_LINKS
    suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun?  // stages/jobs/steps + queue time
    fun parseWebhook(headers: Headers, body: ByteArray, config: ConnectorConfig): CiEvent?
    fun buildLink(ref: CiRunRef, config: ConnectorConfig): String?
}
```

`CiRun` = normalized tree of `CiSpan(kind=STAGE|JOB|STEP, name, start, finish, result, workerName)` + `queuedMs`. Connectors are registered per project with credentials (Azure: PAT or OAuth; later GitHub App, GitLab token). **AzureDevOpsConnector** (first, nice-to-have per your note — MVP ships without it): Timeline API pull triggered by build ingestion when `ci.provider=azure-devops`, optional `build.complete` service-hook receiver. GitHub Actions / GitLab CI connectors are v1.x fast-follows implementing the same interface; the interface + a `NoopConnector` ship in v1 so the extension point is public from day one. Enrichment is strictly additive: a build with no connector still renders fully.

**Data model (core tables):** `builds` (hypertable, envelope + derived), `tasks` (hypertable), `kotlin_compilations`, `test_classes`, `test_case_events` (failures/retries only), `processes`, `ci_spans`, `custom_metrics`, `apk_sizes`; continuous aggregates: hourly/daily build stats per (project, pipeline, branch-class, mode), task-type stats, hit-rate series, flaky counters. **Retention defaults** (per-project overridable): task/kotlin/ci-span raw 90d → build-level 13mo → daily aggregates indefinite; nightly downsample + purge jobs.

*As built (plan 026):* the `tasks` hypertable is landed as the plain `task_executions` table (project_id, build_id, denormalized started_at + user_id, path, module, name, type, outcome, cacheable, duration_ms), written on ingest in the build's transaction (a duplicate build adds no task rows); TimescaleDB conversion + continuous aggregates stay deferred (on-read group-by until volume demands). Three read-scope, tenant-scoped rollups compute on read over it: `GET /v1/rollups/project-cost` (per-module eBay Project Cost family — builds, executedBuilds, `buildImpactedUsers` = `count(distinct)` over the hashed userId, serialTaskMs, buildAvgDurationMs, buildPercentage, `buildCostScalar` with eBay's int-truncated percentage), `GET /v1/rollups/task-duration` (top-25 by name and by type, `byTypeAvailable` false until task types populate), `GET /v1/rollups/negative-avoidance` (top-25 tasks/types where an avoided run beat its group's executed median). `days` clamps to `[1,365]`.

**Regression engine:** baseline key = (project, pipeline|scenario, requestedTasks-signature, branch class main|pr, mode). Rolling baseline = median + MAD over last N (default 20) matching builds on the default branch. Evaluations on ingest: PR-vs-baseline delta (duration, hit rate, APK size, non-incremental-Kotlin %), budget checks (absolute thresholds per key), trend alerts (7d degradation, Bitrise-style bottlenecks feed). Outputs: build annotations, alert dispatch (Slack/Teams incoming-webhook + generic webhook; email later), and a per-build verdict endpoint the CI template can poll to fail/warn a pipeline (`GET /v1/builds/{id}/verdict`).

*Concrete rule as built (plan 025):* per metric, `< 3` baseline builds ⇒ `INSUFFICIENT_DATA` (no cold-start FAIL); zero/degenerate MAD ⇒ a `> 2× median` (or `< median/2` for lower-is-better) fallback; else a robust z-score `0.6745·(value − median)/MAD` compared to per-project `warnZ`/`failZ` thresholds (defaults 3.5 / 5.0), direction-aware (duration up = bad, hit rate down = bad). Budgets are absolute ceilings, evaluated independently, and always FAIL. The overall verdict is the worst metric status (`FAIL > WARN > PASS > INSUFFICIENT_DATA`). `requestedTasks-signature` = `md5` of the sorted task names joined by newline. The verdict endpoint returns `{status, metrics:[{name, value, baselineMedian?, mad?, z?, budget?, status}], baselineKey, evaluatedAt}`. `POST /v1/metrics` caps are enforced in code and rejected `422` (not clamped): ≤ 100 measures/run, `name` ≤ 150, `value`/`text`/`unit` ≤ 300, `scope` ≤ 64 chars. Per-project settings (baseline N, default branch, thresholds, budgets, alert channels) are read/written via `GET`/`PUT /v1/settings` (write requires the all-scope token). v1 rolling baselines cover duration + hit rate; custom metrics are budget-checked (their rolling baselines arrive with the rollup family). Alerts are https-only, sourced only from stored settings (no SSRF), fire-and-forget, and de-duped (one alert per fresh FAIL per key).

**Flaky detection (v1.x, gates quarantine):** signals = same-sha divergent class/case outcomes across builds + intra-build retry pass-after-fail; scored per case with decay; surfaced in dashboard first. Quarantine API + plugin `excludeTestsMatching` loop only after detection precision is validated on the pilot project (locked decision #3).

## 6. Dashboard (SPA served by the server)

Pages: **Overview/Bottlenecks** (what regressed in 7d: duration, hit rate, flaky count, budget breaches) · **Trends** (p50/p95 duration, hit rate, avoided time, config-cache hit %, filter: pipeline/branch/mode/env) · **Builds** list → **Build detail** (mirror of HTML artifact + CI span tree + custom metrics + baseline verdict) · **Tasks explorer** (by type/module: duration, miss-rate×duration ranking — delivered plan 026 at `#/tasks`: Project Cost, Task Duration name/type toggle, Negative Avoidance) · **Kotlin** (incremental %, rebuild reasons, slowest) · **Tests** (slowest classes, failures; flaky page v1.x) · **Comparisons** (two builds side-by-side; tier-(a) salted input-fingerprint diff ships now via `GET /v1/builds/{a}/compare/{b}`, plan 022 — ranks the differing inputs that could explain B's cache misses vs A, with a known-volatile-key note catalog; per-property cause ranking arrives v1.x with cache-origin work) · **Budgets & Alerts** config · **Admin** (projects, tokens, connectors, retention, salt rotation). Charts follow Tuist's line/scatter toggle pattern; every view has CSV/JSON export.

## 7. CI assets (`buildhound-ci-assets`)

- **Azure YAML template** (steps template): injects `BUILDHOUND_TOKEN`, validates gradle.properties, publishes the HTML artifact, optional verdict gate step. Equivalent GitHub Actions composite / GitLab include follow the connector order.
- **Metric CLI**: a POSIX-sh `curl`-wrapper `buildhound-metric --name sign.duration --value 42 --unit s` reading correlation from provider env vars automatically (same env mappings as the plugin SPI — shared spec, reimplemented in shell for zero-JVM steps). *As built (plan 025):* server URL + token come only from the environment (`BUILDHOUND_SERVER_URL`/`BUILDHOUND_TOKEN`, never a flag — no token in `ps`); `BUILDHOUND_BUILD_ID` overrides correlation; it never fails the step by default (transport/config errors warn to stderr and exit 0), with `--strict` to opt into non-zero. The Azure steps template exposes a `verdictGate: off|warn|fail` parameter that polls `GET /v1/builds/{id}/verdict` after the build (§8).
- **Benchmark mode**: scheduled pipeline template running gradle-profiler (scenarios: clean, no-op, incremental non-ABI, config-cache hit; warm-ups per profiler defaults) with the plugin active and `mode=benchmark, scenario, iteration` tags; docs explain reading the resulting low-noise series.

## 8. Security, quality, distribution

Tokens hashed at rest; ingest rate-limited per token; payload schema validated + size-capped
(plan 019 budgets, enforced in code at assembly and defensively at ingest: ≤100 tags/values
with key ≤100 / value ≤300 chars, ≤10 execution reasons/task ≤500 chars, ≤20 000 tasks,
≤20 MiB JSON — overflow drops reasons then truncates the task array with `caps` counts, never
the build envelope; outer byte ceilings 32 MiB compressed / 64 MiB decompressed remain); HTML artifact CSP-safe (no external loads — locked #4). Testing: plugin via Gradle TestKit matrix {Gradle 8.14 (floor), 9.latest} × {CC on/off} × {Kotlin 2.0/2.2} on synthetic projects from cdsap/ProjectGenerator + one real KMP fixture; server via Testcontainers; golden-file tests for payload schema; contract test that `buildhound-commons` deserializes all historical schema versions. Distribution: plugin → Gradle Plugin Portal, server+dashboard → Docker images (self-host compose documented à la Tuist), license Apache-2.0, public docs site. Pilot tenant: the client project you'll designate (decision #5) — spec assumes multi-module Android/KMP with KSP.

## 9. Traceability of locked decisions

#1 cache origin → §3.1 internal-adapters (v1.x), §6 comparisons note · #2 test granularity → §3.5, §4 tests shape, additive `allCases` reserve · #3 quarantine gating → §5 flaky detection · #4 standalone HTML → §3.8, §8 CSP · #5 pilot project → §8 · #6 naming → resolved: BuildHound / buildhound.dev / `dev.buildhound` (header) · modular CI → §3.3 plugin SPI + §5 backend SPI + §7 shared env mappings; Azure demoted to first connector, not a dependency.
