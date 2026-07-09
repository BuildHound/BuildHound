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

Settings plugin: `plugins { id("dev.buildhound") version "x" }` in `settings.gradle.kts`. Compatibility contract: Gradle 8.14+ and 9.x (floor from the JVM-21 owner decision and `BuildFeatures`; see architecture decision log), config cache on/off both green, `isolated-projects` best-effort (CI job tracks it, non-blocking). JDK 21+ runtime for the plugin (owner decision superseding the original Java 11+). No internal Gradle APIs on the always-on core path in v1; the cache-origin feature lives in an isolated `internal-adapters` module, feature-flagged per Gradle version, degrading to "unknown origin" gracefully. *Delivered (plan 038), reorganized (plan 074):* the `buildhound-internal-adapters` module is now **bundled with the core plugin** and driven from the single `buildhound { internalAdapters { } }` block — one plugin (`dev.buildhound`), one config block, no second plugin to apply. It stays a **separate module** so all internal-Gradle-API code is quarantined in one place, and it is **dormant by default**: every `internalAdapters` toggle is off, and — on a daemon where no build has enabled a toggle — no internal API class is loaded and no `extensions["internalAdapters"]` key is produced. **Consent moves from applying a separate plugin → flipping a specific toggle** (the plan-038 "applying is the consent" model). *Known limitation (plan 075):* the toggle state is re-read only at configuration time, so on a configuration-cache **hit** a warm daemon that already opted in on an earlier build can still capture on a later all-off build (reusing a pre-toggle CC entry) until the next CC miss — a consent-model edge (data is still scrubbed), deferred to an execution-time-rehydration follow-up. When enabled, it subscribes a `BuildOperationListener` (via internal `BuildOperationListenerManager`) to `SnapshotTaskInputs`/`ExecuteTask`/`ExecuteWork`/`BuildCache{Local,Remote}{Load,Store}`, capturing per-task cache key + `LOCAL_HIT`/`REMOTE_HIT`/`STORED`/`MISS` origin + caching-disabled reason (salted, never raw), per-task cache-transfer byte counts + load/store wall time (plan 067), and the config-time dependency graph — gated by `collectCacheOrigins`. It contributes `extensions["internalAdapters"]` (plan-039 registry) and **populates the long-null `derived.avoidedMs`/`criticalPathMs`**; every field version-gated and reflection-guarded to "unknown", never a failed build (architecture decision log). Per-input-property hashes + the comparison-page per-property cause ranking are a v1.x follow-up on this module.

### 3.2 Collection pipeline

- **TaskEventCollector**: `BuildEventsListenerRegistry.onTaskCompletion(BuildService)`. Per `TaskFinishEvent`: path, module (derived), start/end ms, result → outcome enum `EXECUTED | UP_TO_DATE | FROM_CACHE | SKIPPED | NO_SOURCE | FAILED`, incremental flag, execution reasons (when present). Task type/class captured at configuration time into the service parameter map (provider-lazy, CC-safe).
- **EnvironmentCollector**: ValueSources for git (branch, sha, dirty), hostname, user (hashed per §3.7), OS/arch/cores/RAM, toolchain versions (Gradle, JDK; AGP/KGP/KSP read from plugin classpaths where applied), daemon reuse.
- **Finalizer**: `FlowAction` on `FlowProviders.buildWorkResult` — assembles the payload from the service, computes derived metrics (hit rate, avoidance estimate, critical path, parallel utilization), writes HTML artifact, invokes uploader. Never fails the build: all errors log at `warn` and write a failure marker file.
- **Config-cache state** recorded as `HIT | MISS_STORED | DISABLED | INCOMPATIBLE` (from start parameters + heuristics; refined later).
- **Invocation posture** (plan 051): `environment.invocation` ships genuinely-new plaintext `fileEncoding`/`locale` plus the fixed 5-key `gradle.properties` allowlist (`org.gradle.caching`, `org.gradle.parallel`, `org.gradle.vfs.watch`, `android.enableJetifier`, `android.nonTransitiveRClass`), each with its declaring layer — alongside, never replacing, the salted `FingerprintInfo` hashes (§3.7).

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
    // Internal-adapters capture (plans 038/044/074): bundled with core, every toggle OFF by default and
    // dormant — reads internal Gradle APIs, so flipping a toggle is the per-feature consent (§3.1).
    internalAdapters {
        collectCacheOrigins = false // per-task cache origin/keys + critical-path / avoided-time + transfer bytes/load-store ms
        collectDeprecations = false // Gradle deprecation summaries
        collectLogWarnings = false  // WARN-level log lines (logger.warn)
        perFileHashes = false       // reserved (v1.x), no effect yet
    }
}
```

Config overrides (plan 027, CCUD `Overrides` pattern): every DSL knob above **except `server.token`**
is overridable via a `buildhound.<key>` gradle property or a `BUILDHOUND_<KEY>` env var (`<KEY>` =
`<key>.uppercase().replace('.','_')`), precedence **explicit DSL value → override → default**. Keys:
`enabled`, `mode`, `server.url`, `identity.pseudonymize`, `htmlReport.enabled`, `localBuilds.enabled`,
`localBuilds.requireOptInFile`, `upload.uploadInBackground`, `internalAdapters.collectCacheOrigins`,
`internalAdapters.collectDeprecations`, `internalAdapters.collectLogWarnings`,
`internalAdapters.perFileHashes`. `server.token` is excluded by construction
(an override would serialize the token into the on-disk CC entry). `uploadInBackground` opts a **local**
build out of blocking on the inline upload — it spools and the next build's drain sends it (no new
thread — §3.9); CI/benchmark always upload inline. New payload fields (spec §4): `environment.{ide,
ideVersion,ideSync,aiAgent}`, `vcs.remoteUrl` (redacted, all-scheme, fail-closed), and top-level
`links` (`commitUrl`/`pullRequestUrl`, github/gitlab, https-gated).

Kotlin build reports: plugin does **not** silently mutate KGP properties; it reads whatever directory `kotlin.build.report.json.directory` names (it does not require a specific path), and emits a single copy-paste `gradle.properties` fix when the wiring is absent — either `kotlin.build.report.output=JSON` with no directory set, or no report wired at all on a build that ran Kotlin compilations (honest, CC-safe, no ordering hazards; non-Kotlin builds stay silent). Bundler reads the JSON files in the Finalizer and embeds them (schema-version tagged) in the payload. Ordering/staleness are handled by a modified-time window rather than assuming KGP writes before our Finalizer runs (plan 023 §4a): only reports touched within 60 s of build start are bundled, so a stale report from a prior build is treated as absent, never mis-attributed.

### 3.5 Tests (locked granularity)

Collector parses JUnit XML from test tasks (works for JVM + KMP jvm targets; Android unit tests). Ingested shape per test task: per-class rollup (class fqn, counts passed/failed/skipped, duration) + `TestCaseDetail[]` **only** for failed or retried cases (name, duration, outcome sequence, failure message hash + truncated text). Retry detection: duplicate case entries / Gradle `retry` plugin outputs. Schema reserves an optional `allCases` array so expanding granularity later is additive, not breaking.

### 3.6 Process probe

End-of-build (Finalizer): enumerate JVMs via `jps -l` matching main classes (`GradleDaemon`, `KotlinCompileDaemon`, `GradleWorkerMain`), then per PID `jstat -gc` + `jstat -gccapacity`, `jinfo -flags`, and `ps` (as implemented, plan 029). Record per process: `role`, `pid`, heap used/committed/max, GC time, RSS, configured `-Xmx`, uptime, `gcCollector`, `compactObjectHeaders` → the "configured vs used" delta.

**Measurement math (locked, research §4.1):** heap **used** = `EU+OU+S0U+S1U` (survivors **included**); **committed** = `EC+OC+S0C+S1C`; **max** = `-gccapacity` `NGCMX+OGCMX` (JVM capacity, *distinct* from configured `-Xmx`); **GC time** = jstat `GCT` **total** column (never `YGCT+FGCT`, which omits `CGCT` and undercounts concurrent G1/ZGC); configured **`-Xmx`** = jinfo `-XX:MaxHeapSize`; **RSS** = `ps -o rss=` (portable, replacing the earlier Linux-only `/proc`); **uptime** = `ps -o etime=`. jstat columns are read **by header name**, not position (JDK layouts differ). *`pid` ships (plan 065), superseding the original "no PID" decision above:* it is an ephemeral, host-local integer — not PII, a path, or a secret — meaningful only as a correlation key *within one* `hostnameHash` (e.g. the GC-time pid-delta refinement, research F15), never a rollup group key. **Command line is never stored** (jinfo/ps args can embed secrets — §3.7); since plan 065 the same jinfo `-flags` line additionally feeds a **typed allowlist** (`gcCollector`, `compactObjectHeaders`) — only fixed `-XX:` flags are parsed and every output is an enum/bool/int, so the `-Dtoken=…`/`-cp`/classpath content on that line is discarded by construction, not merely scrubbed (unit-pinned leak test, the plan-051 allowlist discipline). `role` remains the **primary** key, so multiple workers are still repeated `GRADLE_WORKER` rows; `pid` is a within-host refinement, not a replacement.

**Payload semantics / blind spots.** One snapshot, no sampling (time-series is v1.x). `processes: []` means "nothing observable" — not an error: JDK tools absent (JRE-only agent), a Kotlin daemon that exited before the finalizer, in-process compilation, or `processProbe { enabled = false }` all yield an empty list. A daemon killed before the finalizer runs never reports at all (that lost-build case is plan 033). Every exec is timeout-bounded; any failure degrades to `[]`, never a failed build.

### 3.7 Privacy & identity

Identity fields: `userId = "u_" + hex12(hmacSha256(projectSalt, "user:" + username + "@" + hostname))`, `hostnameHash = "h_" + hex12(hmacSha256(projectSalt, "host:" + hostname))` — first 12 hex chars, domain-separated inputs; enumeration resistance rests on salt secrecy, not digest length, so truncation is safe. Salt generated per project on the server, fetched once and cached (interim until tenancy lands: generated locally into `.gradle/buildhound/identity.salt`, see plan 003). `pseudonymize=false` sends plaintext (team choice); `strict` sends nothing. Payloads never include absolute paths outside the project, env dumps, or tokens; a scrubber strips values matching secret-like patterns from execution reasons/failure text.

*Failure detail (plan 047):* a failed build ships `failure.message` and `failure.stackTrace` — both routed through the scrubber (in-project paths relativized, out-of-project paths and secret-shaped values redacted) then truncated (message ≤512, stacktrace ≤8 KiB); `failure.messageHash` is a SHA-256 over the **raw** message (a stable cross-build key, computed pre-scrub). The uploaded/written payload carries the truncated trace; the local, zero-network HTML artifact may render a fuller (still scrubbed) copy. This deliberately expands past the earlier hash-only `FailureInfo` (see the architecture decision log).

*Build warnings (plan 047, re-homed by plan 074):* two catchers in the bundled `buildhound-internal-adapters` module, each an explicit, independent `buildhound { internalAdapters { } }` toggle **off by default** — `collectDeprecations` (Gradle deprecation summaries via build-op progress) and `collectLogWarnings` (`WARN`-level log lines via `LoggingOutputInternal`). Both read internal Gradle APIs (barred from the always-on core path by architecture §2 rule 4), so they stay quarantined in the module and dormant until enabled; flipping a toggle is the consent (plan 074). Enabling either catcher logs a `warn` that it reads internal APIs with no compatibility guarantee, so a Gradle upgrade may silently stop capture (the build is never affected). Captured warnings are deduped, scrubbed (each message through the same scrubber), length- and count-capped, and ride `extensions.internalAdapters`. They cover the two named channels, **not** every compiler diagnostic — Gradle exposes no single "all warnings" stream.

Governance for the plan-027 source fields: `vcs.remoteUrl` is redacted for **every** scheme (userInfo stripped) and **fails closed** — when the value can't be confidently parsed (whitespace, or an ambiguous userInfo such as a raw `/` inside the password) it is dropped rather than emitted, so a credential never ships; the top-level `links` are host-gated (github/gitlab only) and always `https://`; `environment.aiAgent` is positive-only attribution (only a confirmed agent is named — a miss is silent). The `extensions` channel (plan 039) is opaque addon-owned JSON that core does **not** deep-scrub — each addon owns its own §3.7 bar; core only size-caps it (`caps.droppedExtensions`).

### 3.8 Standalone HTML artifact (locked: no CDN)

Single self-contained `buildhound-report.html`: inlined CSS/JS (a small vendored chart lib or hand-rolled SVG — no external requests, so it renders inside Azure artifact viewers and email attachments). Content: header (build id, outcome, duration, env, toolchain), task timeline by concurrency lane (lanes computed greedily from per-task start/end overlaps — max observed parallelism — not the Gradle `worker` id, which stays unpopulated; plan 017), sortable task table with outcome/duration/type, cache summary donut + top cacheable misses, Kotlin panel (incremental %, rebuild reasons, slowest compilations), tests summary with failures, process snapshot, build-failure card (exception class + scrubbed message + stacktrace, plan 047) and a warnings panel (captured deprecations / `logger.warn` lines, shown only when the opt-in `internalAdapters` block is present, plan 048), link to the dashboard build page when server configured. Data embedded as one JSON blob → the artifact doubles as an offline payload copy.

### 3.9 Upload semantics

One synchronous upload attempt per payload for **every** mode, inside the Flow-API finalizer (`PayloadUploader`): a 15 s timeout on both connect and request, gzip JSON `POST <url>/v1/builds` with an `Authorization: Bearer` header, redirects never followed; a non-loopback plaintext-`http://` URL logs a warning. Failures are classified — transport errors, 5xx, and 408/429 spool the payload to `build/buildhound/spool/<buildId>.json.gz` for retry; other 4xx (bad token, bad payload) are permanent rejections, dropped with a warning and never retried. The **next** build's finalizer drains up to 10 spooled files oldest-first *before* uploading its own payload; a rejected file is deleted and never blocks younger ones, the spool is trimmed to 20 files, and any file over 8 MB is dropped unread. Upload gating: CI and benchmark modes always upload; local mode uploads only when `localBuilds.enabled` and (by default) the `~/.buildhound/optin` marker is present (§3.4/§3.7). Idempotency: build UUID; the server dedupes.

Payloads are gzipped and **size-capped before upload** (see §4 `caps`, plan 019): a hard byte budget with the overflow strategy (drop per-task execution reasons first, then truncate the task array with summary counts — never drop the build envelope), plus tag/value cardinality and free-text length caps; the server re-clamps defensively at ingest.

*Deliberately not a background thread:* the original v0.1 sketch's local background/JVM-exit-flush upload was dropped — there is no reliable flush guarantee at daemon shutdown, and Talaiot's `publishOnNewThread` during `BuildService.close()` silently drops data (plan 008). *Planned, not shipped:* an opt-out `uploadInBackground` knob for local builds (Tuist parity — an opt-out from blocking local builds, not the default; plan 027), and an optional CI logging-command annotation emitted on spool (future `buildhound-ci-assets` work).

*As built (plan 033) — lost-build accounting:* a build that dies mid-run (OOM kill, `kill -9`, agent eviction) never reaches the Flow finalizer, so it used to vanish. `BuildOutcome` now has a third, additive value `INTERRUPTED` for a never-finalized build. **Primary (plugin):** the execution-time collector writes a tiny `build/buildhound/started/<buildId>.json` start-marker on its first task event (CC-safe — execution-time IO only, no ci/vcs value source since a build-service param bakes and replays stale on a hit); the *next* build's finalizer deletes its own marker, then synthesizes an `INTERRUPTED` payload (`finishedAt == startedAt`, empty tasks, no derived) for any *other* stale marker and routes it through the same gate/uploader (bounded ≤20/build, TTL-pruned ~14 d). **Fallback (server, Azure-only, opt-in):** on a `build.complete` connector hook for a run with no ingested payload, an expected-build check fetches the Timeline and — if it completed — records a deterministic-id `interrupted:<provider>:<runId>` build (idempotent, tenant-scoped), covering the ephemeral-agent case the marker cannot. Both are additive and never mistaken for a finalized build (empty tasks + `INTERRUPTED`). `/v1/trends` counts interrupted builds separately and **excludes** them from duration/hit-rate/failure aggregates (their duration is synthetic); the regression engine (plan 025) must exclude them too.

## 4. Payload schema (v1, `schemaVersion: 1`)

Top-level document (kotlinx-serialization models in `buildhound-commons`; server tolerates unknown fields):

```jsonc
{
  "schemaVersion": 1,
  "buildId": "uuid", "projectKey": "from-token",
  "startedAt": 0, "finishedAt": 0, "outcome": "SUCCESS|FAILED|INTERRUPTED",
  "failure": { "taskPath": "...", "exceptionClass": "...", "messageHash": "...",
               "message": "scrubbed+truncated", "stackTrace": "scrubbed+truncated" },
  "requestedTasks": ["assembleDebug"], "mode": "ci|local|benchmark",
  "environment": { "os": "...", "arch": "...", "cores": 0, "ramMb": 0,
                   "hostnameHash": "...", "userId": "...", "daemonReused": true,
                   "configurationCache": "HIT|MISS_STORED|DISABLED|INCOMPATIBLE",
                   "ide": "...", "ideVersion": "...", "ideSync": false, "aiAgent": "...",
                   "buildCache": { "localEnabled": true, "remoteEnabled": true, "remotePush": true,
                                   "remoteType": "HttpBuildCache" } },
    // ^ ide/ideVersion/ideSync/aiAgent are additive (plan 027); aiAgent is positive-only attribution
    //   (only a confirmed agent, e.g. CLAUDECODE, is named — a miss is silent, never a wrong guess)
    // ^ buildCache is the committed Settings.buildCache config snapshot (plan 067, F17): booleans + the
    //   normalized remote backend simpleName ONLY — never the remote URL or the local cache directory
    //   (§3.7). remoteType/remoteEnabled null when no remote backend is configured. The --build-cache
    //   invocation flag is a separate signal: environment.invocation.buildCacheEnabled (plan 051).
  "toolchain": { "gradle": "9.x", "jdk": "...", "agp": "...", "kgp": "...", "ksp": "...", "springBoot": "..." },
  "vcs": { "branch": "...", "sha": "...", "dirty": false, "remoteUrl": "..." },
    // ^ remoteUrl is redacted all-scheme + fail-closed (userInfo stripped; dropped when it can't be
    //   confidently parsed — never a credential; §3.7). Additive (plan 027).
  "ci": { "provider": "azure-devops", "runId": "...", "pipelineName": "...",
          "jobId": "...", "buildUrl": "...", "runAttempt": 1, "attributes": {} },
  "links": { "commitUrl": "https://…", "pullRequestUrl": "https://…" },
    // ^ composed from the redacted remote — github/gitlab only, https-gated; null when nothing
    //   composes (plan 027). Never a hyperlink to a non-https / non-supported origin.
  "tags": {}, "values": {},
  "tasks": [ { "path": ":app:compileDebugKotlin", "module": ":app", "type": "KotlinCompile",
               "startMs": 0, "durationMs": 0, "outcome": "FROM_CACHE", "cacheable": true,
               "nonCacheableReason": null, "incremental": false, "worker": 3,
               "executionReasons": ["..."] } ],
  "derived": { "cacheableHitRate": 0.0, "avoidedMs": 0, "criticalPathMs": 0,
               "parallelUtilization": 0.0, "configurationMs": 0 },
  "caps": { "droppedTags": 0, "droppedValues": 0, "truncatedValues": 0,
            "droppedExecutionReasons": 0, "truncatedExecutionReasons": 0,
            "truncatedNonCacheableReasons": 0, "droppedTasks": 0, "droppedTaskOutcomes": {},
            "droppedArtifacts": 0, "droppedExtensions": 0 },
    // ^ present only when the caps enforcement dropped/truncated something (plan 019); omitted otherwise.
    //   droppedArtifacts (plan 031) and droppedExtensions (plan 039) count overflow drops in those arrays
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
  "benchmark": { "scenario": "clean", "iteration": 3, "isolationMode": "no_build_cache", "seedRef": "..." },
  "artifacts": { "android": [ { "variant": "release", "module": ":app", "type": "APK", "sizeBytes": 0 } ],
                "jvm": [ { "module": ":service", "kind": "BOOT_JAR", "sizeBytes": 0 } ] },
  "changedModules": { "base": "CI_PR_BASE|LAST_BUILT_SHA",
                      "modules": [":app", ":core:common"], "unattributedChanges": false },
    // ^ change blast-radius attribution (plan 063, F13): the set of Gradle MODULE PATHS that changed
    //   since a resolvable diff base — never a file path or a changed-file list (§3.7); ":" for a
    //   whole-build root change. Null/omitted when no base resolved (no CI target branch + no recorded
    //   previous-build HEAD, git absent/timeout/detached HEAD). unattributedChanges flags a change that
    //   mapped to no module (empty/partial descriptor index); the raw path is never emitted.
  "extensions": { "<addonId>": { "schemaVersion": 1, "…": "opaque addon-owned JSON" } }
}
```

The `links` block (plan 027) is present only when a redacted remote resolves to a github/gitlab
host and a sha or PR number is available; both URLs are always `https://`, never a hyperlink to an
unsupported or non-https origin.

The `extensions` block (plan 039) is a reserved `Map<addonId, JSON>` carrying opaque, addon-owned
contributions (each with its own `schemaVersion`). It is additive (new golden `build-payload-v2ext.json`;
v1 golden untouched) and, unlike core fields, is **not deep-scrubbed by core** — each addon owns its
own §3.7 bar. The plan-019 `PayloadCapper` bounds it to a 256 KiB budget (largest-first drop, counted
in `caps.droppedExtensions`), enforced at plugin assembly and re-clamped on server ingest.

The `artifacts` block (plan 031 Android, plan 072 JVM) is present whenever *either* list is non-empty;
`android` is a single list mixing APK/AAB/AAR (disambiguated by `type`, not a filename-encoded key),
`jvm` mixes `bootJar`/`bootWar`/`jar`/`war` (disambiguated by `kind`) and is measured only for archive
tasks that actually produced output this invocation (§3.2) — a default Spring Boot module contributes
both a `JAR` and a `BOOT_JAR` row. Byte size only — no path or contents (§3.7); `module`/`variant` are
project-internal Gradle names. Server-side, `android` is projected into the `apk_sizes` hot table for
the per-(module, variant, type) trend at `GET /v1/artifacts/trends`; `jvm` is not yet projected (deferred,
plan 072 Risks).

The `benchmark` block (plan 030) is present only on `mode=benchmark` builds; its `scenario`/
`iteration`/`isolationMode` are **also** mirrored into `tags` (the tag contract). Benchmark builds
are **excluded from fleet trends/lists by default** at the query layer (opt in with `mode=benchmark`
or `includeBenchmark=true`) so a benchmark series never pollutes p50/p95; they have a dedicated
per-`(scenario, isolationMode)` percentile series at `GET /v1/benchmark/series` + the `#/benchmark`
dashboard view.

The `changedModules` block (plan 063, research F13) records the set of Gradle **module paths** that
changed since a resolvable diff base — a CI PR base ref (`CI_PR_BASE`, the PR's own changes vs its
target branch's merge-base) or the recorded previous-build HEAD (`LAST_BUILT_SHA`, cumulative since the
last local build). The plugin runs one bounded `git diff --name-only --relative` on the always-on VCS
exec path (plans 004/015/050) and maps the changed files to modules **plugin-side** (the server has no
`projectDir`→path index), emitting **only** the derived module set — file paths never ship (§3.7); `":"`
denotes a whole-build-affecting root change (a root build file / version catalog), and
`unattributedChanges` flags a change that mapped to no module (empty/partial descriptor index) without
ever emitting the raw path. Null/omitted when no base resolved. Server-side it drives the
costliest-modules-to-change rollup at `GET /v1/rollups/change-blast-radius`.

## 5. Ingestion service

**Stack:** Kotlin + Ktor, Postgres 16 + TimescaleDB, Flyway migrations, one Docker image (+ compose file with Postgres) for self-host; horizontally stateless.

**Tenancy:** `org → project → apiToken(s)` (scoped: ingest-only vs read). All rows carry `project_id`; queries always tenant-filtered; per-project settings (retention overrides, identity salt, baseline config, budgets, alert channels).

**API (v1):**
- `POST /v1/builds` — gzip payload, token auth, idempotent on buildId. Async post-processing job: normalize rows, compute rollups, trigger connector enrichment + regression evaluation.
- `POST /v1/metrics` — the CLI endpoint: `{correlation: {provider, runId} | buildId, scope, name, value|text, unit?}` (Datadog tag/measure model; caps: 100 measures/run, key+value ≤ 300 chars).
- `POST /v1/kotlin-report` — accepts raw KGP HTTP-report POSTs for teams preferring direct `kotlin.build.report.http.url` wiring; correlated by label/buildId.
- `GET /v1/...` query API mirroring every dashboard view (JSON + CSV) — public, documented, versioned (agent/MCP-friendly). *(Delivered plan 042: OpenAPI 3.1 at `/openapi.yaml` + a zero-CDN `/docs` viewer, kept in lockstep with the route table by a contract test; a read-only MCP surface ships as the separate `buildhound-mcp` module.)*
- `GET /v1/builds/{a}/compare/{b}` — read-scope, tenant-scoped input-fingerprint comparison (plan 022): ranks the salted-hash inputs (build-level + declared toolchain/env fields) that differ between two builds by how much of B's cache misses (executed in B, avoided in A) they could explain, with catalog notes; 400 when `a == b`, 404 when either build is missing or foreign-tenant.
- `GET /v1/builds/{buildId}/parallelism` and `GET /v1/builds/{buildId}/graph?format=gexf|dot` — read-scope, tenant-scoped parallelism-blocker analytics (plan 062, research F12): sweep-line gating-task detection over the core task timeline (always available) plus duration-weighted degree centrality + a label-escaped dependency-graph export over the already-serialized `internalAdapters` edge list (both degrade to null/404, never 500, when the build carries no edges — adapters off, isolated projects, or capped). 404 when the build is missing or foreign-tenant.
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

**Data model (core tables):** `builds` (hypertable, envelope + derived), `tasks` (hypertable), `kotlin_compilations`, `test_classes`, `test_case_events` (failures/retries only), `processes`, `ci_spans`, `custom_metrics`, `apk_sizes`; continuous aggregates: hourly/daily build stats per (project, pipeline, branch-class, mode), task-type stats, hit-rate series, flaky counters. **Retention defaults** (per-project overridable): task/kotlin/ci-span raw 90d → build-level 13mo → daily aggregates indefinite; nightly downsample + purge jobs. *(Delivered plan 042: per-project windows via `PUT /v1/admin/retention` (new `admin` scope) + the dashboard **Admin** page, enforced by a nightly `RetentionSweeper` purge (`BUILDHOUND_RETENTION_SWEEP_HOURS`); daily aggregates are never purged.)*

*As built (plan 026):* the `tasks` hypertable is landed as the plain `task_executions` table (project_id, build_id, denormalized started_at + user_id, path, module, name, type, outcome, cacheable, duration_ms), written on ingest in the build's transaction (a duplicate build adds no task rows); TimescaleDB conversion + continuous aggregates stay deferred (on-read group-by until volume demands). Three read-scope, tenant-scoped rollups compute on read over it: `GET /v1/rollups/project-cost` (per-module eBay Project Cost family — builds, executedBuilds, `buildImpactedUsers` = `count(distinct)` over the hashed userId, serialTaskMs, buildAvgDurationMs, buildPercentage, `buildCostScalar` with eBay's int-truncated percentage), `GET /v1/rollups/task-duration` (top-25 by name and by type, `byTypeAvailable` false until task types populate), `GET /v1/rollups/negative-avoidance` (top-25 tasks/types where an avoided run beat its group's executed median). `days` clamps to `[1,365]`.

*As built (plan 032):* two more read-scope, tenant-scoped rollups power the landing page. `GET /v1/rollups/bottlenecks?period=7` (period clamped `[1,90]`) compares this window to the prior equal window: headline KPI deltas (`buildCount`, `successRate`, `avgDurationMs`, `hitRate` each as `{current, prior, deltaPct}`) plus four ranked families — **regressed** (EXECUTED-only average, this-vs-prior; groups seen `< 2×` per window are flagged `isNew`/`isVanished` rather than shown as ∞/−100 %), **slowest work** (total wall over every outcome), **negative avoidance** (reuses the plan-026 signal), and **cache-miss hotspots** (cacheable tasks that still executed — `cacheDataAvailable:false` until the plugin's `cacheable` flag populates, plan 016, so the view degrades honestly). Benchmark builds are excluded (fleet view). `budgetBreaches`/`trendRegressions` are `null` until a verdict store is wired. `GET /v1/rollups/toolchain?days=30` returns, per dimension (gradle, jdk, agp, kgp, ksp), a version distribution (`builds`, `sharePct`, `distinctUsers` = `count(distinct)` over the hashed userId, `lastSeenMs`) + a "behind the latest observed" list; agp/kgp/ksp report `available:false` until the plugin collects them (never an empty chart read as consensus). Both stores fetch raw windowed rows and defer to a shared pure `BottleneckCalculator`/`ToolchainCalculator`, so in-memory and Postgres agree byte-for-byte (Testcontainers parity).

*As built (plan 063, research F13):* `GET /v1/rollups/change-blast-radius?days=30` (read-scope, tenant-scoped, `days` clamped `[1,365]`, benchmark-**included** — projectCost's cost-inflicted-on-others sibling) ranks the costliest Gradle modules to change. The plugin's additive `changedModules` block is projected on ingest into a normalized `build_changed_modules(project_id, build_id, started_at, module)` table (migration `V13`, one row per changed module, written in the build's transaction only when the `builds` row was newly inserted — the plan-026 `task_executions` idempotency). Per changed module M: `changeCount` = distinct window builds where M changed; per build, its *downstream* cost is that build's executed task time in **every other** module (`sum(duration_ms WHERE outcome=EXECUTED AND module != M)`); the rollup ranks by `median(downstream) × changeCount` (top-25, deterministic module tiebreak). Both stores flatten the window (the changed-module set joined to `task_executions` executed durations) and defer to the shared pure `RollupCalculator.changeBlastRadius`, so in-memory and Postgres agree byte-for-byte (Testcontainers parity, the plan-058 fold-in-Kotlin posture — the median has no clean SQL equivalent). An honest heuristic: a build changing several modules attributes the whole build's downstream to each (shared/over-counted — a ranking signal, not causal isolation; true direct-dependents "depth" is deferred to F12/plan 038). The `#/tasks` page renders a "Costliest modules to change" card beside project cost, with an honest empty state when no window build carried the block.

**Regression engine:** baseline key = (project, pipeline|scenario, requestedTasks-signature, branch class main|pr, mode). Rolling baseline = median + MAD over last N (default 20) matching builds on the default branch. Evaluations on ingest: PR-vs-baseline delta (duration, hit rate, APK size, non-incremental-Kotlin %), budget checks (absolute thresholds per key), trend alerts (7d degradation, Bitrise-style bottlenecks feed). Outputs: build annotations, alert dispatch (Slack/Teams incoming-webhook + generic webhook; email later), and a per-build verdict endpoint the CI template can poll to fail/warn a pipeline (`GET /v1/builds/{id}/verdict`).

*Concrete rule as built (plan 025):* per metric, `< 3` baseline builds ⇒ `INSUFFICIENT_DATA` (no cold-start FAIL); zero/degenerate MAD ⇒ a `> 2× median` (or `< median/2` for lower-is-better) fallback; else a robust z-score `0.6745·(value − median)/MAD` compared to per-project `warnZ`/`failZ` thresholds (defaults 3.5 / 5.0), direction-aware (duration up = bad, hit rate down = bad). Budgets are absolute ceilings, evaluated independently, and always FAIL. The overall verdict is the worst metric status (`FAIL > WARN > PASS > INSUFFICIENT_DATA`). `requestedTasks-signature` = `md5` of the sorted task names joined by newline. The verdict endpoint returns `{status, metrics:[{name, value, baselineMedian?, mad?, z?, budget?, status}], baselineKey, evaluatedAt}`. `POST /v1/metrics` caps are enforced in code and rejected `422` (not clamped): ≤ 100 measures/run, `name` ≤ 150, `value`/`text`/`unit` ≤ 300, `scope` ≤ 64 chars. Per-project settings (baseline N, default branch, thresholds, budgets, alert channels) are read/written via `GET`/`PUT /v1/settings` (write requires the all-scope token). v1 rolling baselines cover duration + hit rate; custom metrics are budget-checked (their rolling baselines arrive with the rollup family). Alerts are https-only, sourced only from stored settings (no SSRF), fire-and-forget, and de-duped (one alert per fresh FAIL per key).

**Flaky detection (gates quarantine):** signals = same-sha divergent class/case outcomes across builds + intra-build retry pass-after-fail; scored per case with decay; surfaced in dashboard first. Quarantine API + plugin `excludeTestsMatching` loop only after detection precision is validated on the pilot project (locked decision #3).

*As built (plan 036):* server-side only (no plugin/schema change) over the plan-024 `tests` block, projected on ingest into a narrow `test_class_outcomes` hot table. A pure `FlakyDetector` emits both signals — **retry** (a case `FAILED`-then-`PASSED` within one build) and **cross-run** (the same `(sha, module/class)` reaching a passed-only and a failed build; the **same-sha** requirement is the confounder guard so a fix-between-runs regression is not counted) — with `minSamples=3`/`minFlakeRate=0.05` thresholds in code. `GET /v1/flaky?days=N` (read-scope, tenant-scoped) returns per-(module, class) records ranked by flake rate; both stores feed the one detector (Testcontainers parity). A newly-flaky class fires exactly one edge-triggered plan-025 `FLAKY` alert. **No decay/half-life in v1** — a fixed window is easier to validate before the pilot has labelled ground truth. The `#/flaky` dashboard page renders it. Precision gate for plan 037: labelled flagged-set precision **≥ 0.90** on the pilot.

## 6. Dashboard (SPA served by the server)

Pages: **Overview/Bottlenecks** (what regressed in 7d: duration, hit rate, flaky count, budget breaches — delivered plan 032 as the landing route `#/` + `#/bottlenecks`: headline KPI cards with semantic delta chips (colour encodes goodness, not sign), a 7/14/30-day toggle, the four ranked families, and a toolchain-adoption section — per-dimension version distribution + who-is-behind, with agp/kgp/ksp shown as an honest "not collected yet" panel) · **Trends** (p50/p95 duration, hit rate, avoided time, config-cache hit %, filter: pipeline/branch/mode/env) · **Builds** list → **Build detail** (mirror of HTML artifact + CI span tree + custom metrics + baseline verdict) · **Tasks explorer** (by type/module: duration, miss-rate×duration ranking — delivered plan 026 at `#/tasks`: Project Cost, Task Duration name/type toggle, Negative Avoidance) · **Kotlin** (incremental %, rebuild reasons, slowest) · **Tests** (slowest classes, failures) · **Flaky** (delivered plan 036 at `#/flaky`: per-(module, class) records with flake rate, the firing signal, sample count, and first/last seen) · **Comparisons** (two builds side-by-side; tier-(a) salted input-fingerprint diff ships now via `GET /v1/builds/{a}/compare/{b}`, plan 022 — ranks the differing inputs that could explain B's cache misses vs A, with a known-volatile-key note catalog; per-property cause ranking arrives v1.x with cache-origin work) · **Budgets & Alerts** config · **Admin** (projects, tokens, connectors, retention, salt rotation). Charts follow Tuist's line/scatter toggle pattern; every view has CSV/JSON export.

*As built (plan 048):* the **Build detail** page renders a **Failure** section (exception class + scrubbed message + stacktrace) whenever the build carries failure detail (plan 047), and a **Warnings** section (deprecations + `logger.warn` lines + a dropped-count note) whenever the opt-in `extensions.internalAdapters` block is present — both read straight from the full `BuildPayload` that `GET /v1/builds/{buildId}` already returns, so **no new endpoint, projection, schema field, or migration** was added. The standalone HTML artifact carries the same Warnings section (its Failure card shipped with plan 047). Both surfaces render identical `textContent`-only blocks, hidden when the data is absent. Cross-build warnings *aggregation* (a warnings-over-time view / dedicated endpoint) is a deliberate follow-up — it would need an ingest-time projection into a hot table, unlike this per-build render.

## 7. CI assets (`buildhound-ci-assets`)

- **Azure YAML template** (steps template): injects `BUILDHOUND_TOKEN`, validates gradle.properties, publishes the HTML artifact, optional verdict gate step. Equivalent GitHub Actions composite / GitLab include follow the connector order.
- **Metric CLI**: a POSIX-sh `curl`-wrapper `buildhound-metric --name sign.duration --value 42 --unit s` reading correlation from provider env vars automatically (same env mappings as the plugin SPI — shared spec, reimplemented in shell for zero-JVM steps). *As built (plan 025):* server URL + token come only from the environment (`BUILDHOUND_SERVER_URL`/`BUILDHOUND_TOKEN`, never a flag — no token in `ps`); `BUILDHOUND_BUILD_ID` overrides correlation; it never fails the step by default (transport/config errors warn to stderr and exit 0), with `--strict` to opt into non-zero. The Azure steps template exposes a `verdictGate: off|warn|fail` parameter that polls `GET /v1/builds/{id}/verdict` after the build (§8).
- **Benchmark mode** (plan 030): scheduled gradle-profiler pipeline (`buildhound-ci-assets/profiler-pipeline/`) running the pilot per scenario (`clean`, `no_op`, `incremental_non_abi`, `cc_hit`; warm-ups per profiler defaults) with the plugin active. Activation is env-driven — `BUILDHOUND_BENCHMARK_{SCENARIO,ITERATION,ISOLATION,SEED_REF}` forces `mode=benchmark` + a typed `benchmark` block + mirrored `scenario`/`iteration`/`isolationMode` tags, so the pilot's `buildhound {}` DSL is invocation-independent. `scenario`/`isolationMode` are allowlist-validated plugin-side (a typo can't mint a series). Each run declares an **isolation mode** (Telltale's cache-cold labels — v1 wires `full_cache` + `no_build_cache`, ten more documented); **never compare across isolation modes**. Benchmark builds are **excluded from fleet trends/lists by default** at the query layer and get a dedicated per-`(scenario, isolationMode)` percentile series (`GET /v1/benchmark/series`, `#/benchmark`); the series shows **p50/p90/min over N iterations, never a single run**. Docs: `docs/recipes/benchmark-and-experiments.md` (reading the series + the three build-validation experiment pairs feeding the compare page).
- **Plugin-overhead budget** (plan 034): a companion self-benchmark harness (`buildhound-ci-assets/overhead/`) that reuses the gradle-profiler machinery *not* to measure the pilot but to measure **the plugin's own cost** — it drives a synthetic fixture twice with the plugin toggled on/off and checks the per-axis overhead (configuration/per-task/finalizer/upload) against a stated budget. The `overhead-budget` CI job flags breaches; the verdict math is a pure `OverheadCalculator` in `buildhound-commons`. See [`docs/overhead-budget.md`](overhead-budget.md). This is the roadmap phase-3 "never slow the build noticeably" guardrail — distinct from benchmark *mode*, which measures the pilot's builds, not the plugin.

## 8. Security, quality, distribution

Tokens hashed at rest; ingest rate-limited per token; payload schema validated + size-capped
(plan 019 budgets, enforced in code at assembly and defensively at ingest: ≤100 tags/values
with key ≤100 / value ≤300 chars, ≤10 execution reasons/task ≤500 chars, ≤20 000 tasks,
≤20 MiB JSON — overflow drops reasons then truncates the task array with `caps` counts, never
the build envelope; outer byte ceilings 32 MiB compressed / 64 MiB decompressed remain); HTML artifact CSP-safe (no external loads — locked #4). Testing: plugin via Gradle TestKit matrix {Gradle 8.14 (floor), 9.latest} × {CC on/off} × {Kotlin 2.0/2.2} on synthetic projects from cdsap/ProjectGenerator + one real KMP fixture; server via Testcontainers; golden-file tests for payload schema; contract test that `buildhound-commons` deserializes all historical schema versions. Distribution: plugin → Gradle Plugin Portal, server+dashboard → Docker images (self-host compose documented à la Tuist), license Apache-2.0, public docs site. Pilot tenant: the client project you'll designate (decision #5) — spec assumes multi-module Android/KMP with KSP.

## 9. Traceability of locked decisions

#1 cache origin → §3.1 internal-adapters (v1.x), §6 comparisons note · #2 test granularity → §3.5, §4 tests shape, additive `allCases` reserve · #3 quarantine gating → §5 flaky detection · #4 standalone HTML → §3.8, §8 CSP · #5 pilot project → §8 · #6 naming → resolved: BuildHound / buildhound.dev / `dev.buildhound` (header) · modular CI → §3.3 plugin SPI + §5 backend SPI + §7 shared env mappings; Azure demoted to first connector, not a dependency.
