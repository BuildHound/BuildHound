# Architecture & Best Practices

> **Living document.** This is the working architecture design for BuildHound. It is
> expected to be updated and improved continuously during development:
> whenever an implementation, review, or retro produces a better insight, this document
> changes in the same PR. The product requirements live in
> [build-telemetry-spec.md](build-telemetry-spec.md); this document describes *how* we
> build it well.

## 1. System overview

```
 buildhound-gradle-plugin (settings plugin, runs inside every Gradle build)
        │  BuildPayload (schema v1, gzip JSON)
        ▼
 buildhound-server (Ktor, multi-tenant, OCI image) ──► Postgres + TimescaleDB
        │
        └─► dashboard SPA (phase 1) / query API
```

| Module | Type | JVM floor | Role |
|---|---|---|---|
| `buildhound-commons` | Kotlin Multiplatform (jvm today; js/native later) | 21 | Payload schema (kotlinx-serialization), `CiEnvironmentProvider` SPI — the contract everything builds against |
| `buildhound-gradle-plugin` | Kotlin/JVM + `java-gradle-plugin` | 21 | Settings plugin: collectors, finalizer, uploader |
| `buildhound-server` | Kotlin/JVM + Ktor, `application` | 21 | Ingest API, storage, rollups, regression engine, dashboard |
| `buildhound-report` | Kotlin/JVM (js candidate) | 21 | Standalone HTML artifact template + renderer, embedded into the plugin |
| `buildhound-ci-assets` | not a Gradle module | none | Azure YAML template, metric CLI (shell), profiler scenarios |

**Dependency rule:** `buildhound-commons` has no dependency on any other module and no Gradle API
types. The plugin and server never share *code* except through `buildhound-commons`. `buildhound-report`
depends on nothing but the payload JSON shape, and is the **shared payload-rendering channel**: both the
plugin (inlines the template + renderer into the standalone artifact) and the server (serves the same
renderer to the dashboard, e.g. `/timeline.js`) may depend on it (plan 017). Because it stays
dependency-free, that edge is resources-plus-pure-functions — nothing transitive arrives, and the two
surfaces render identically instead of drifting apart as duplicated copies would.

**JVM floors:** every module targets JVM 21 (owner decision, deviating from spec §3.1's
Java 11+; see decision log). Consequence for the compatibility matrix: the plugin
requires consumers to run Gradle on JDK 21+ — the TestKit matrix tests Gradle versions on
a 21+ daemon JVM only.

## 2. Gradle plugin best practices (binding)

These are the rules every plugin change is reviewed against:

1. **Settings plugin, apply once.** Applied in `settings.gradle.kts`; sees every project,
   registers services before any project evaluates. No per-module boilerplate.
2. **Configuration-cache safety is non-negotiable.** No `Project`/`Gradle` references at
   execution time. State flows only through `Provider`s, `ValueSource`s, and serializable
   `BuildService` parameters. Task completion via
   `BuildEventsListenerRegistry.onTaskCompletion(BuildService)`; build-finished via the
   Flow API (`FlowAction` + `FlowProviders.buildWorkResult`) — never `buildFinished {}`.
   The platform's own build keeps `org.gradle.configuration-cache=true` so regressions
   surface immediately.
3. **The plugin must never fail a build.** Every failure path logs at `warn`, writes a
   marker file, and returns. Each phase adds failure-injection tests for this.
4. **No internal Gradle APIs in v1.** The v1.x cache-origin feature gets an isolated
   `internal-adapters` module, feature-flagged per Gradle version, degrading gracefully.
5. **Laziness everywhere.** Extension properties are `Property`/`MapProperty`; conventions
   set via `convention()`, values read only at execution time. Nothing is resolved at
   configuration time that doesn't have to be.
6. **Compatibility is tested, not assumed.** TestKit functional tests live in a dedicated
   `functionalTest` source set. The realized CI matrix (plan 021): the Gradle axis is two
   jobs — `build` on 9.latest and `build-floor` on 8.14 (the floor: `BuildFeatures` needs
   8.5+, JDK-21 needs 8.14+; inner TestKit builds inherit the outer job's Gradle). The OS
   axis is `build` (ubuntu, **blocking**) + `build-macos` (**blocking** — plan 007's only
   field bug was macOS-only) + `build-windows` (**watched**, `continue-on-error`: OS-sensitive
   scrubber/spool/VCS surfaces, with `@DisabledOnOs(WINDOWS)` gaps). The config-cache axis is
   one **blocking** `functional-cc-off` leg (`-Pbuildhound.testkit.cc=off` flips inner-build
   CC via the `testkitCcFlag`/`runnerExplicit` seam) — not a full OS×Gradle×CC cross-product,
   because CC-off failure modes don't vary by OS or floor. **Isolated projects** runs as a
   **watched** (`continue-on-error`) `isolatedProjectsTest` job over the `@Tag("isolated-projects")`
   suite; the default `functionalTest` excludes that tag. Watched jobs are reviewed each
   phase-2a retro (they show red without failing the workflow).
7. **Identity & hygiene:** plugin id `dev.buildhound`, Maven group `dev.buildhound`
   (decision #6); `gradlePlugin {}` metadata kept publish-ready; `validatePlugins` runs
   in `check`.
8. **Extension points are public contracts.** `CiEnvironmentProvider` lives in
   `buildhound-commons`, is documented, and loadable via `ServiceLoader` — "add your CI in ~30
   lines" is an advertised feature.
9. **File access in `apply()` is a CC fingerprint input.** Gradle tracks every
   configuration-phase file read (even `File.isFile`), and existence changes invalidate
   the next build's cache entry — creating a file at apply time guarantees a miss on the
   following build. External state (marker files, salts, spool dirs) is read/created
   inside a `ValueSource` or at execution time instead. (Found by the plan-003
   functional test: the identity salt created during apply invalidated CC reuse.)
   Corollary (plans 023, 024): external build outputs — the KGP json report, the `Test`
   task's JUnit XML — are *read in the Flow action at execution time*, never in `apply()`;
   only their **locations** are captured at configuration time (a resolved `Provider`/path,
   not a file read), so discovery stays CC-safe and no report file becomes a fingerprint input.

10. **Plugin-classpath code runs on Gradle's embedded Kotlin stdlib.** The compiler
    ships stdlib 2.4 but Gradle 8.14 runs plugins on its embedded 2.0 stdlib, so a
    newer-stdlib API compiles fine and throws `NoSuchMethodError` at runtime (found
    when a 2.4-only `sequenceOf` overload broke CI detection). `buildhound-commons`,
    the plugin, and `buildhound-report` pin `apiVersion = 2.0` (the embedded stdlib of
    the oldest supported Gradle); bump it only when the support floor moves. The pin
    does NOT cover prebuilt dependencies (kotlinx-serialization declares stdlib 2.2) —
    only running the test suite on the floor Gradle does, so CI keeps a floor job.
    Shelf life: Kotlin 2.4 already deprecates `apiVersion 2.0`; when a future KGP drops
    it, the response is raising the Gradle floor to 9.x and moving the pin to 2.2 —
    the CI floor job survives that transition and remains the true backstop.

11. **Every subprocess gets a bounded wait.** Rule 3's "never fail a build" includes
    "never hang one": a stuck child (git fsmonitor, network worktree, credential
    helper) must degrade to missing data, not block the build. `ExecOperations.exec`
    has no timeout, so subprocesses run through JDK `ProcessBuilder` with
    `waitFor(timeout)` + `destroyForcibly()` — 10 s default (CCUD parity), capped and
    drained stdout, discarded stderr, closed stdin (`GitExec`, plan 015). Runners stay
    free of Gradle types so plain unit tests pin the timeout behavior.

12. **Task-graph-derived data is captured in `settings.gradle.taskGraph.whenReady`.**
    This is the sanctioned configuration-time hook: it runs only during configuration
    (never at execution), so capturing `Settings`/`Gradle` in the closure is CC-safe,
    and on a config-cache hit it does not run — the data must instead ride into
    execution as a **build-service/Flow parameter** finalized after configuration, so it
    replays from the CC entry (`TaskEventCollector.Params.taskMetadata`, plan 016;
    Talaiot precedent). Touching `taskGraph.allTasks` is an isolated-projects violation,
    so gate it behind `BuildFeatures.isolatedProjects.active` and degrade to empty; the
    whole walk is wrapped so a defect warns rather than fails (rule 3). Reflection over
    task classes stays name-based and Gradle-type-free (`TaskClassIntrospection`) so it
    unit-tests without gradleApi() on the test classpath (rule shared with §2.11).

13. **Isolated-projects degradation contract (binding, plan 021).** Any collector whose
    data needs configuration-time cross-project state must: (a) detect IP via the public
    `BuildFeatures.isolatedProjects.active`; (b) degrade to **null/empty, never partial** —
    derived metrics computed from degraded inputs also go null (honest nulls, plan 005);
    (c) log a single `info` line naming the degraded fields; (d) never warn-spam or fail the
    build; (e) ship a **blocking** TestKit degradation test in the same PR — a self-contained
    case that enables IP itself and asserts the degraded payload shape plus build success.
    Promotion is therefore **test-by-test**, not a job flip: the watched `isolatedProjectsTest`
    job (§2.6) runs the general suite under IP to catch unknowns and stays `continue-on-error`
    while the flag is `unsafe.`-prefixed; per-collector guarantees are the blocking degradation
    tests. First consumer: plan 016's type/cacheable dictionary (empty under IP → `type`/
    `cacheable` null → `cacheableHitRate` null), whose blocking degradation test already ships.

## 3. Kotlin Multiplatform best practices (binding)

1. **`buildhound-commons` is the only shared-code channel.** Models are pure data + 
   kotlinx-serialization; no platform types, no I/O, no Gradle/Ktor types leak in.
2. **Additive schema only.** New fields get defaults so old servers/plugins keep working;
   `ignoreUnknownKeys` on the shared `BuildHoundJson`. Golden-file tests pin every historical
   schema version and are never edited, only added to.
3. **Targets grow with need, not speculatively.** jvm-only today; `js()` when the report
   frontend moves to Kotlin/JS, native when the metric CLI justifies it. Hierarchical
   source sets from day one (`commonMain`/`jvmMain`).
4. **Single version catalog** (`gradle/libs.versions.toml`) governs every version in the
   repo. No hardcoded versions in build scripts.
5. **Planned:** convention plugins in an included `build-logic` once module count or
   config duplication grows (currently three small modules; duplication is acceptable and
   explicit).
6. Tests run on kotlin-test + JUnit Platform on all JVM targets.

## 4. OCI / container image best practices (binding)

The server ships as an OCI image (`buildhound-server/Dockerfile`, compose in `deploy/`):

1. **Multi-stage builds**: JDK + Gradle only in the build stage; runtime stage is JRE-only.
   Evaluate `jlink`/distroless once the dependency set stabilizes.
2. **Non-root runtime** (`USER 10001:10001`), no shell entrypoint tricks, exec-form
   `ENTRYPOINT`.
3. **No secrets in images or layers.** Configuration via environment variables; compose
   defaults are dev-only and say so.
4. **Deterministic and labeled**: OCI `org.opencontainers.image.*` annotations; base
   images pinned by digest before any published release; dependency layers cached
   separately from source layers.
5. **Small context**: `.dockerignore` keeps git metadata, docs, and build output out.
6. **Health**: `/health` endpoint; orchestrator-level checks (compose `healthcheck` on the
   DB now, on the server once it has real dependencies).
7. **Planned:** SBOM + image signing (syft/cosign) in the release pipeline; Testcontainers
   for server integration tests; image build in CI on every PR (already scaffolded).

## 5. Server architecture

- **Ktor** with plain function routing (`Routes.kt`), one module function (`buildHoundModule`)
  usable by both `main()` and `testApplication` — keep it that way so every route is
  testable without a socket.
- **Persistence boundary**: all storage behind `BuildStore` (and future stores). The
  scaffold is in-memory; phase 1 replaces it with Postgres + TimescaleDB behind the same
  interface, migrations via Flyway, tested with Testcontainers.
- **Multi-tenancy from the first real table**: every row carries `project_id`; queries are
  always tenant-filtered; tokens hashed at rest; ingest **and** query rate-limited per
  token (spec §8, plan 013) — buckets keyed by the token's SHA-256. Per-token buckets
  alone cannot stop a rotating-token flood (each garbage token mints a fresh bucket and
  reaches token resolution), so an outer **per-source-host** limiter caps everything a
  single source can send to `/v1/*` — including bucket-minting and pre-auth DB lookups.
  Residual risk (recorded in plan 013): floods distributed across many source IPs get
  one host budget each — that's an infra/WAF concern, not an application one. The host
  key is the direct TCP peer; installing `XForwardedHeaders` would make it
  attacker-controlled — don't, without revisiting the limiter key.
- **Idempotency**: ingest dedupes on `buildId` — already part of the `BuildStore` contract.
- **Normalized `task_executions` for rollups** (plan 026): per-module/type/name aggregates would be
  O(builds × tasks) jsonb scans with no index, so each task ships a row into a normalized table
  written in the **same transaction** as its `builds` row — but only when the build was newly
  inserted (a duplicate adds zero task rows), so dedupe stays at the build level with no PK on task
  rows. `user_id` + `started_at` are denormalized onto each task row so windowing and
  `buildImpactedUsers` (a `count(distinct)` over the already-hashed `userId`) need no join back.
  The rollup math is a **pure** `RollupCalculator` the in-memory store runs directly and the SQL
  mirrors; a Testcontainers parity test asserts they agree byte-for-byte.
- **Post-ingest regression evaluation** (plan 025) runs inside `POST /v1/builds` after a fresh
  `save`, wrapped so it can never block or fail ingest (its own `runCatching`; a failure just
  leaves the verdict absent). It reads a rolling baseline over the extracted hot columns
  (`pipeline_name`, `requested_tasks_sig`, `mode`, default-branch `SUCCESS` builds) and persists a
  `build_verdicts` row. The regression math lives in a **pure** `RegressionEngine` (no I/O), plain
  unit-tested — the same "pure functions + tests" split the plugin uses.
- **Outbound webhooks are the server's only outbound call** (plan 025, alerts). Hard rules: URLs
  come **only from stored settings**, never from an ingested payload (an attacker cannot steer a
  request — no SSRF); `https://` only (loopback allowed just for tests); dispatch is
  **fire-and-forget** on a small bounded executor with a short per-request timeout, so an
  unreachable endpoint logs `warn` and never delays the `202`; bodies carry only pseudonymized
  verdict data (build id, baseline key, deltas, dashboard link) — no task detail, identity, tags,
  values, or tokens. A FAIL alert fires only when the previous verdict for the same baseline key
  was not already FAIL (no repeat-spam).
- **Stateless horizontally**: no local state outside the DB; the image can scale out.
  Deliberate exception: rate-limiter buckets are instance-local (a shared-store limiter
  adds a hot write per request), so N replicas mean an N× effective ceiling — revisit
  when the server actually scales out; the pilot runs one instance.

## 6. Security & privacy design rules

- Tokens: env-var providers only, never in DSL literals, hashed at rest server-side.
- Payloads never contain absolute paths outside the project, env dumps, or secrets; a
  scrubber strips secret-like patterns from execution reasons and failure text (spec §3.7).
- Local-build identity is pseudonymized by default (HMAC with per-project salt); `strict`
  mode sends nothing.
- The HTML artifact makes zero external requests (locked decision #4) — enforced by test.
- **Ingest tokens are wired from `providers.environmentVariable(...)` only.** A DSL
  literal (or `gradleProperty`) value would be serialized into the configuration-cache
  entry on disk (encrypted since Gradle 8.6, but still at rest); the env provider is
  stored as a reference and re-read at execution. Uploads over non-loopback plaintext
  http log a warning. Spool files carry only the (scrubbed) payload, never the token;
  anything that can write the spool dir already executes code in the build (same trust
  domain).
- **Payload budgets live in one place (`buildhound-commons` `PayloadCaps`/`PayloadCapper`)
  and are enforced in code, not docs** (plan 019): the plugin caps as the final assembly
  step (after the scrubber, so secret patterns see whole values), and the server re-caps
  defensively at ingest — clamping a hostile/buggy client's oversized `tags`/`values`/text
  rather than rejecting it, so the telemetry survives bounded. Overflow follows spec §3.9
  (drop execution reasons, then truncate the task array with `caps` counts; the build
  envelope always survives). Cap warn logs carry **counts only** — never tag keys or
  values, since a misconfigured build could put a secret in either. New payload fields must
  route through `PayloadCapper` when they land.
- Every feature PR gets a dedicated security **and** privacy review (see CLAUDE.md).

## 7. Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-07-02 | Version catalog + per-module plugin aliases; no `build-logic` yet | Three modules; convention plugins add classloader complexity before they pay off |
| 2026-07-02 | `buildhound-ci-assets` is not a Gradle module | Its consumers are CI steps without a JVM |
| 2026-07-02 | Flow API + `ServiceReference` validated against Gradle 8.14 + CC (incl. reuse) | TestKit functional tests green — riskiest assumption of the roadmap spike confirmed |
| 2026-07-02 | Wrapper `distributionUrl` kept on services.gradle.org | Standard, checksum-verifiable path |
| 2026-07-02 | JVM 21 floor for **all** modules, superseding spec §3.1's "Java 11+ runtime for the plugin" | Owner decision: build with at least Java 21. Plugin consumers must run Gradle on JDK 21+ |
| 2026-07-02 | Build toolchain is JDK 26 (foojay-provisioned), emitted bytecode/API stay Java 21 (`jvmTarget=21`, `-Xjdk-release=21`, plugin source/target 21); `buildhound.toolchain` property is the local escape hatch | Owner request (plan 011); consumer floor and JRE-21 server image unchanged |
| 2026-07-02 | Gradle support floor is 8.14 (JDK-21 requirement; `BuildFeatures` needs 8.5+), tested by a dedicated CI floor job | Supersedes spec §3.1's "Gradle 8.0+" |
| 2026-07-02 | Kotlin `apiVersion` pinned to 2.0 for commons/plugin/report | Plugin-classpath code executes on Gradle's embedded Kotlin stdlib (2.0 on Gradle 8.14); newer stdlib APIs are runtime `NoSuchMethodError`s |
| 2026-07-02 | Naming decision #6: product **BuildHound**, domain **buildhound.dev**, plugin id + Maven group `dev.buildhound`, modules `buildhound-*`, DSL `buildhound {}`, env prefix `BUILDHOUND_` | Owner decision; pre-release so renamed with no compatibility shim. Research doc + old plans keep the BTP working name as historical records |
| 2026-07-03 | Bare `CI` env var (set and not `false`/`0`) classifies a build as CI, provider `generic`, no mapped fields. Same truthiness rule for `BUILDHOUND_CI`: truthy activates the generic mapping, falsy is the generic provider's kill switch (overrides `BUILDHOUND_CI_PROVIDER` and bare `CI`; built-in providers unaffected) (plan 014) | CCUD-parity gap: CircleCI/GitLab/Travis/Jenkins set only generic `CI`, so AUTO resolved to `local` — wrong baselines and local-opt-in gating on CI. Diverges from CCUD's presence-only check to honor the ci-info `CI=false` opt-out convention |
| 2026-07-03 | Plugin subprocesses run via JDK `ProcessBuilder` with `waitFor(timeout)`/`destroyForcibly` (10 s default, `buildhound.vcs.timeout.ms` override), not `ExecOperations` (plan 015, §2 rule 11) | `ExecOperations.exec` cannot bound a hung git, which stalled the build forever; CCUD enforces the same 10 s hard kill. Supersedes plan 004's accepted "no exec timeout" residual risk |
| 2026-07-03 | Isolated-projects degradation contract for task metadata (plan 016, §2 rule 12): when `BuildFeatures.isolatedProjects.active` is true the `taskGraph.allTasks` walk is skipped, so `tasks[].type`/`cacheable`/`nonCacheableReason` are null and `derived.cacheableHitRate` is null. The plan-021 IP CI job asserts exactly this shape | `allTasks` from settings scope is an IP violation by design; degrading to empty (not failing, not violating) is the only correct behavior, and pinning the shape keeps the future IP job a real regression gate |
| 2026-07-03 | `derived.cacheableHitRate` is now over a **cacheable-only** denominator (plan 016): a task is cache-relevant iff `cacheable == true` or its outcome is FROM_CACHE (a cache hit proves cacheability past a static `cacheIf {}` miss); null when no task carries a non-null `cacheable` flag (IP degradation / legacy pre-016 payloads). Supersedes the v0 all-tasks denominator | The old number diluted the rate with non-cacheable work and was not comparable across builds; honest-nulls over a spliced two-definition trend line (plan 005). Server stores derived metrics as-sent, so no migration — pre-release step change accepted |
| 2026-07-03 | `buildhound-report` is the shared payload-rendering channel; the server may depend on it (not only the plugin), amending §1's "plugin and server never share code except through commons" for *rendering* code (plan 017). The task timeline is one JS renderer served at `/timeline.js` and inlined in the artifact | Duplicating the renderer per surface is permanent copy-drift with a sync test as the only guard; a dependency-free module shared by reference is a resources-plus-pure-functions edge with no transitive cost. Lanes are computed from start/end overlaps (max concurrency), deliberately not the unpopulated Gradle `worker` id |
| 2026-07-03 | Payload cardinality + size budgets (`PayloadCaps`/`PayloadCapper` in commons) enforced at plugin assembly (after scrub) **and** as a defensive server clamp at ingest; overflow follows spec §3.9 (reasons then task array), recording drops in an additive `caps` field; server clamps rather than rejects (plan 019) | The roadmap guardrail "cardinality and payload-size budgets enforced in code, not docs"; Talaiot's unbounded cardinality wrecked its backends. Clamping over rejecting keeps "degrade gracefully, never lose the envelope"; idempotency keys on `buildId`, which the capper never touches. Warn logs carry counts only (a tag/reason could hold a secret) |
| 2026-07-03 | CC-off is one **blocking** `functional-cc-off` leg (`-Pbuildhound.testkit.cc=off` via the `testkitCcFlag`/`runnerExplicit` harness seam), not a full {OS}×{Gradle}×{CC} cross-product (plan 021) | CC-off is the simpler execution model; its failure modes (mode-detection branches, `DaemonState` across daemon reuse) don't vary by OS or floor, so 12 jobs would buy nothing over one. The mode is a `providers.gradleProperty` CC input of the outer build (which keeps CC on); "never disable CC" governs the outer build, not TestKit inner builds |
| 2026-07-03 | Isolated projects: a **watched** (`continue-on-error`) `isolatedProjectsTest` job over a `@Tag`-separated suite; per-collector degradation enforced by **blocking** tests, promoted test-by-test, not by flipping the job (plan 021, §2 rule 13) | The IP flag is incubating (`unsafe.`-prefixed) — a watched job catches unknowns without letting a Gradle rename fail the workflow; real guarantees come from blocking degradation tests each collector owns (first: plan 016). Defines the contract plan 016's `BuildFeatures`-gated degradation satisfies |
| 2026-07-03 | macOS is a **blocking** `build-macos` leg (full suite, not a canary); Windows is a **watched** `build-windows` canary (plan 021) | Plan 007's only field bug was macOS-only (scrubber path handling) — a sample canary would have missed it, so macOS runs the same unit+functional coverage. Windows has known `@DisabledOnOs(WINDOWS)` gaps (hung-git, GitExec POSIX fixtures) and unknowns (path separators, CRLF); promote-or-defer: green ~2 weeks of PRs → blocking by plan 042, red → each failure becomes its own follow-up task. One job each — macOS bills 10×, Windows 2× |
| 2026-07-03 | Input fingerprints are **build-level, always salted** (HMAC-SHA256 with the shared per-project identity salt, `"fp:"` domain-separated from the `user:`/`host:` families, 16-hex+`…`), captured in a `ValueSource` and diffed by a pure server `BuildComparator` behind `GET /v1/builds/{a}/compare/{b}` (plan 022). **Per-`Test`-task capture is deferred** to a `dev.buildhound.fingerprints` add-on | Salting is strictly stronger than the unsalted Develocity sample and equality-within-a-project is all diffing needs; no plaintext (absolute `jdk.home`) leaves the machine. Per-task capture needs a `doFirst` action carrying a build service into every Test task, but the isolated-projects-safe `GradleLifecycle.beforeProject` hook cannot isolate an action holding a service/extension reference — so the risky, default-off boundary-crossing part ships separately (plan's sanctioned fallback), while build-level fingerprints + the compare endpoint + page (the roadmap-2b exit signal: same-sha builds with different JDK homes) land in core |

| 2026-07-03 | The KGP json build report is treated as an **unstable external format** (plan 023): parsed defensively by `KotlinReportParser` (a pure, name-keyed allowlist over kotlinx-serialization `JsonElement` — never `@Serializable` binding to KGP types, which aren't on our classpath and change shape across versions), tolerant of missing/renamed fields, and never fails the build. `KotlinReportBundler` does all file IO at Finalizer execution time (no config-phase reads), matches reports by a **modified-time window** (`startedAt − 60 s`) because KGP's write ordering vs. our FlowAction is unspecified and it appends timestamped files across builds, and injects its `warn` sink rather than referencing Gradle `Logging` (so the logic is unit-testable off the Gradle classpath). Only an allowlist of path-free fields is extracted; path-bearing KGP fields (`compilerArguments`, `changedFiles`, `icLogLines`, `startParameters.currentDir`) are never read (spec §3.7) | KGP exposes no stable public schema for `CompileStatisticsData`; binding to it would break on every Kotlin bump and risks leaking absolute paths. An allowlist + mtime-window + never-fail degrade is the only safe way to bundle it; the empirically captured 2.4 shape is pinned in plan 023 §4a and the parser fixture, not assumed |
| 2026-07-03 | Test telemetry is collected by **parsing each `Test` task's JUnit XML output** in the Flow action (public `Test.reports.junitXml` API + a StAX parser with DTD/external entities disabled), **not** via a `Test` listener (plan 024). The `Test` task's XML output directory is snapshotted at `taskGraph.whenReady` (config time) into the collector service's params — the plan-016 dictionary/replay mechanism — and read at execution time; only tasks with a this-build EXECUTED/FAILED outcome are ingested (a `FROM_CACHE`/`UP_TO_DATE` task's on-disk XML is prior-build, absent-over-wrong). The `module/class` join key is defined once as `TestUnitKey.of(module, classFqcn)` in commons | A listener requires mutating every `Test` task's configuration to attach it — the same "never silently mutate other tasks' config" rule that keeps quarantine/sharding in addons; XML parsing touches no task config, so test collection is **core** (the load-bearing reason). Pinning the join key in one place stops Tuist's bare-FQCN-vs-`module/class` degeneration (research §2.6): plans 036 (flaky), 037 (quarantine), 040 (sharding) all reference `TestUnitKey.of` verbatim. XXE fail-closed because the XML, though a build output, is untrusted input |
| 2026-07-04 | Regression verdicts use a **rolling median + MAD** baseline with a guarded robust-z rule (plan 025): `< 3` baseline builds ⇒ `INSUFFICIENT_DATA` (never a cold-start FAIL), zero MAD ⇒ a `>2× median` fallback, else `z = 0.6745·(value−median)/MAD` against per-project `warn`/`fail` thresholds; budgets are absolute ceilings, evaluated independently, always FAIL. Direction is metric-aware (duration up = bad, hit rate down = bad). Baselines key on `(pipeline, requestedTasks-sig, branchClass, mode)` and are always the **default-branch** window, so a PR is judged against main. v1 baselines cover duration + hit rate; custom metrics get budget checks (their rolling baselines wait for the rollup family, plan 026) | MAD over stddev for outlier resistance on noisy multi-modal CI durations (research §5.6, the roadmap's least-de-risked component); the ≥3 guard + INSUFFICIENT_DATA stop cold-start false alarms; thresholds in settings let the pilot tune without a redeploy. `requestedTasks-sig` is `md5(sorted tasks joined by \n)`, computed identically by the app and the V3 backfill SQL so old and new builds share a baseline |
| 2026-07-04 | The server's **first outbound network call** is alert dispatch (plan 025): https-only, URLs sourced only from stored settings (never ingested data → no SSRF), fire-and-forget on a bounded executor, pseudonymized bodies, no-repeat-spam (alert only on a FAIL that follows a non-FAIL for the same key) | Alerts must never block or fail ingest, and an ingested payload must never make the server issue an arbitrary request. A standing constraint for plan 036 (flaky), which reuses this dispatcher |
| 2026-07-04 | Rollups read a **normalized `task_executions`** table written on ingest in the build's transaction (plan 026), not jsonb scans of `builds`. Task rows are inserted only when the `builds` row was newly inserted (duplicate → zero rows), so dedupe stays build-level with no PK on task rows; `user_id`/`started_at` are denormalized so windowing + `buildImpactedUsers` need no join. `buildCostScalar` copies eBay's int-truncation of the executed percentage verbatim (their README hedges it "may change") so the number matches the reference. The aggregation rules live in a pure `RollupCalculator`; the SQL mirrors it and a Testcontainers parity test pins byte-for-byte agreement | Per-module/type/name aggregates over a window are O(builds × tasks) unindexed jsonb scans otherwise. This *is* spec §5's planned `tasks` hypertable, landed now (TimescaleDB conversion deferred like `builds`); historical builds have no task rows, so rollups cover post-upgrade builds (a jsonb backfill is a follow-up). `buildImpactedUsers` is a `count(distinct)` over the already-hashed `userId` — a number, never the ids, so §3.7 pseudonymization is intact |

*Add a row (or a docs/plans entry) whenever an architectural decision is made or reversed.*
