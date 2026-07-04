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
    drained stdout, discarded stderr, closed stdin. The mechanics live in the shared
    `BoundedExec` (plan 029); `GitExec` adds the git-specific env, the process probe
    (`ProcessMetrics`) calls it for `jps`/`jstat`/`jinfo`/`ps` — so this rule now covers
    JDK tools, not just git. Runners stay free of Gradle types so plain unit tests pin the
    timeout behavior.

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

14. **The plugin has a measured overhead budget (binding, plan 034).** "Never fail the build"
    (rule 3) is necessary but not sufficient — "never slow the build noticeably" is the adoption
    bar. A stated, calibrated budget across four axes (configuration / per-task / finalizer /
    upload) is enforced by the `overhead-budget` CI job: gradle-profiler drives a synthetic fixture
    with the plugin toggled on/off, and `OverheadCalculator` (`buildhound-commons`, the one
    unit-tested place "breach" is defined) turns the two `benchmark.csv` outputs into a per-axis
    pass/fail. Each cap is the *looser* of an absolute floor or a percentage, and a percentage
    breach counts only when the on/off means are statistically separated (combined-stddev guard) —
    same-machine noise must not mint false breaches. The job is **watched** (non-blocking) until
    runner variance is characterised, then promoted (criterion in `docs/overhead-budget.md`), same
    discipline as the macOS/Windows/IP jobs. A change that breaches the budget must optimize the
    plugin or, with justification, recalibrate the budget in the same PR (decision-log row).

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

### 5.1 Addon architecture (binding, plan 039)

An addon extends BuildHound without forking core. Five conventions, owned by plan 039 and
consumed verbatim by plans 037 (quarantine) and 040 (sharding):

1. **Separate plugin.** An addon is its own settings plugin id `dev.buildhound.<addon>` (module +
   artifact `buildhound-addon-<name>`, group `dev.buildhound`), applied *alongside* core. Applying
   it is the user's explicit consent to whatever it does (e.g. mutating `Test` tasks — the thing
   core's "never silently mutate other tasks' config" rule forbids core from doing).
2. **One coupling point.** An addon depends on `buildhound-commons` only, never on
   `buildhound-gradle-plugin`. It attaches through the commons `BuildHoundCollectorRegistry` /
   `BuildHoundExtensionContributor` SPI (`ServiceLoader`, mirroring `CiEnvironmentProvider`), which
   core's Flow finalizer discovers at **execution time** — no config-phase file read, no CC input.
3. **Opaque payload channel.** A contributor returns one `extensions[addonId]` JSON section carrying
   its **own** `schemaVersion`; core reads it as an opaque `JsonElement`, so an addon evolves with no
   core schema bump. Core does **not** deep-scrub addon JSON (it cannot know the shape) — the addon
   carries the same §3.7 bar as core (no paths/PII/secrets). The plan-019 byte budget bounds it
   (`PayloadCapper` drops the largest offending entries, never the envelope).
4. **Namespaced, scope-gated APIs.** Server endpoints live under `/v1/addons/<id>/…`, gated by a
   dedicated `ADDON` token scope (walled off from ingest/read tokens); `<id>` is validated against a
   server-side **allowlist** (empty until a consumer ships → unknown id is a flat 404, never a
   dynamic table/route name); storage is tenant-scoped jsonb (`addon_data`) so ingest stays
   schema-stable; the namespace reuses the query rate limiter.
5. **Never-fail inherited.** Discovery/contribution are individually `runCatching`-guarded (one bad
   addon can't fail the build or suppress a sibling); an addon applied **without** core logs `warn`
   and no-ops. Core boots and serves builds with zero addons registered.

## 6. Security & privacy design rules

- Tokens: env-var providers only, never in DSL literals, hashed at rest server-side.
- Payloads never contain absolute paths outside the project, env dumps, or secrets; a
  scrubber strips secret-like patterns from execution reasons and failure text (spec §3.7).
- Local-build identity is pseudonymized by default (HMAC with per-project salt); `strict`
  mode sends nothing.
- The HTML artifact makes zero external requests (locked decision #4) — enforced by test.
- **Config overrides never carry the token** (plan 027). `buildhound.<key>`/`BUILDHOUND_<KEY>`
  overrides exist for every DSL knob *except* `server.token` — a token override would serialize
  into the on-disk CC entry, so tokens stay env-provider-only (above). The exclusion is a testable
  invariant (`ConfigOverrides.isOverridable`), and apply() never wires an override for the token.
- **Git remote redaction is all-scheme and fail-closed** (plan 027, §4.5). `SourceLinks.redactRemoteUrl`
  strips userInfo for every scheme (fixing CCUD's http-only leak of `ssh://user:pass@host`) and drops
  the value entirely when it can't confidently parse it. Composed source/commit/PR links are
  github/gitlab-host-gated and always `https://`, so an env-controlled `javascript:` origin can never
  become a hyperlink. `git status` porcelain output (paths) stays discarded — only the redacted
  remote URL is added. IDE/agent fields are coarse PII-free strings (no session ids/usernames),
  distinct from the dropped `agentName`.
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
| 2026-07-04 | CI/environment breadth (plan 027): the built-in provider matrix grows to the CCUD 10 (Azure/GHA + Jenkins/TeamCity/CircleCI/Bamboo/GitLab/Travis/Bitrise/GoCD/Buildkite) + generic, first-match-wins, most-specific markers first. Additive fields: `environment.{ide,ideVersion,ideSync,aiAgent}`, `vcs.remoteUrl` (redacted, all-scheme, fail-closed), top-level `links` (commit/PR, github/gitlab, https-gated), GHA `runAttempt` (+ `/attempts/N`). `uploadInBackground` opts a *local* build out of blocking on the inline send (spools; no new thread — plan 020). `buildhound.<key>`/`BUILDHOUND_<KEY>` overrides for every knob **except the token**, precedence explicit-DSL > override > default via a `convention()` fallback | The generic-`CI` fallback misclassified 8 of the 10 CCUD providers as `local`/`generic`; per-provider detection fixes baselines/upload/opt-in. All detection is execution-time in existing ValueSources (env/sysprop + one bounded git probe) — no new CC input, no config-phase file read, isolated-projects unchanged. Redaction is all-scheme (CCUD's http-only guard leaked `ssh://` creds); agent attribution is positive-only with ambient subtraction (only `CLAUDECODE` confirmed) so a miss is silent, never wrong |
| 2026-07-04 | Rollups read a **normalized `task_executions`** table written on ingest in the build's transaction (plan 026), not jsonb scans of `builds`. Task rows are inserted only when the `builds` row was newly inserted (duplicate → zero rows), so dedupe stays build-level with no PK on task rows; `user_id`/`started_at` are denormalized so windowing + `buildImpactedUsers` need no join. `buildCostScalar` copies eBay's int-truncation of the executed percentage verbatim (their README hedges it "may change") so the number matches the reference. The aggregation rules live in a pure `RollupCalculator`; the SQL mirrors it and a Testcontainers parity test pins byte-for-byte agreement | Per-module/type/name aggregates over a window are O(builds × tasks) unindexed jsonb scans otherwise. This *is* spec §5's planned `tasks` hypertable, landed now (TimescaleDB conversion deferred like `builds`); historical builds have no task rows, so rollups cover post-upgrade builds (a jsonb backfill is a follow-up). `buildImpactedUsers` is a `count(distinct)` over the already-hashed `userId` — a number, never the ids, so §3.7 pseudonymization is intact |
| 2026-07-04 | **Process probe vendors the recipe, not the code** (plan 029). Spec §3.6's daemon/Kotlin/worker JVM snapshot could reuse `io.github.cdsap:commandline-value-source`/`:jdk-tools-parser` (InfoKotlinProcess's exec/parse logic), but a self-contained `ProcessMetrics`+`ProcessParsing` was written instead: (a) those libs run on Gradle's embedded Kotlin stdlib with an un-auditable `apiVersion` pin (§2 rule 10 hazard); (b) they emit stringly `"1.2 GB"`/`"minutes"`, losing the structured numbers the schema needs; (c) our math diverges. Pinned measurement traps (research §4.1, unit-tested): heap **used** includes survivors (`EU+OU+S0U+S1U`), GC time reads jstat `GCT` **total** (never `YGCT+FGCT`, which omits `CGCT`), heap **max** is `-gccapacity` `NGCMX+OGCMX` (≠ configured `-Xmx`, which comes from jinfo `-XX:MaxHeapSize`); jstat columns keyed **by header name**, not position. RSS via `ps -o rss=` (portable, replaces spec's Linux-only `/proc`), uptime via `ps -o etime=`. No PID or command line in the payload (host-local noise; jinfo/ps args can embed secrets, §3.7) — `role` is the only key. Bounded exec is the shared `BoundedExec` (§2 rule 11); the probe is a `ValueSource` obtained only through FlowAction params (CC-safe, IP-safe — no per-project state), degrading to `processes: []` on any failure | Reuse would import a stdlib-pin hazard and a lossy string model for ~4 short execs of proven-simple logic; vendoring the *recipe* keeps one bounded-exec path and the structured schema. The GCT-total/survivors/capacity≠Xmx traps are the research's headline undercounts, so each is pinned by a unit test a refactor can't silently reintroduce |
| 2026-07-04 | **AGP-optional Android artifact-size collector** (plan 031, spec §4). BuildHound is a *settings* plugin but AGP is a *project* plugin, so AGP (`com.android.tools.build:gradle-api`, Google Maven, **compileOnly** — never shipped) is on the project classpath, not the settings plugin's. The collector is wired from a `settings.gradle.lifecycle.beforeProject` reaction (the isolated-projects-safe per-project hook) registered by a **top-level** function so the `IsolatedAction` captures only the artifacts `File`, never the non-serializable plugin instance (a real CC/IP-serialization failure caught in test). `AndroidArtifactCollector.install` references **no** AGP symbol itself — it only registers `pluginManager.withPlugin("com.android.application"/"library")` reactions whose AGP-touching delegates each run inside `runCatching(Throwable)`, so a non-Android build links nothing and even an unresolvable-AGP `NoClassDefFoundError` degrades to no artifacts, never a failed build. Per variant a read-only size task is wired `variant.artifacts.use(task).wiredWith(..).toListenTo(SingleArtifact.APK/BUNDLE/AAR)` (AGP's non-destructive listen; APK dir enumerated via `BuiltArtifactsLoader`, sizes from `File.length()` — never `substringAfterLast`), writing JSON lines the Flow finalizer reads at build end; AGP's own outputs are never deleted. Server projects the payload's `artifacts.android` into an `apk_sizes` hot table (spec §5) for `GET /v1/artifacts/trends`. The **`artifacts` field is additive nullable at schema v1** (no version bump; v1 golden untouched, new golden added) — the `android`-keyed record shape supersedes spec §4's earlier `apk`-keyed example (corrected in the same PR). The Android functional test is SDK-gated (`ANDROID_HOME`), so it runs only where an SDK is present; inertness + never-fail are verified without one | Spec §4's `artifacts` field never carried in v1; landing it plus the AGP mechanics (AndroidArtifactsSizeReport's proven `onVariants`/`toListenTo`) is the roadmap phase-3 APK-size deliverable. The settings-vs-project classloader boundary is the load-bearing risk, so all AGP linkage is behind `runCatching` + `withPlugin` and the plugin never *requires* AGP — the inertness test pins it. `compileOnly` + Google-Maven with a content filter keeps AGP out of the shipped artifact and off every non-Android resolve |
| 2026-07-04 | **Benchmark mode is env-driven activation + query-layer fleet exclusion** (plan 030). A scheduled gradle-profiler pipeline runs the pilot's *real* build; a `BenchmarkValueSource` reads `BUILDHOUND_BENCHMARK_{SCENARIO,ITERATION,ISOLATION,SEED_REF}` at execution time (no CC input, no DSL edit per invocation — the pilot's `buildhound {}` stays invocation-independent) and forces `mode=BENCHMARK` over AUTO/CI/LOCAL (DISABLED still wins). `scenario`/`isolationMode` are validated against fixed allowlists in `BenchmarkActivation` (Gradle-free, unit-tested) so a typo can't mint a spurious series; a *present* but malformed benchmark env fails closed (null + warn, mode falls back). A typed `benchmark` block rides the payload (robust server grouping/percentiles) **and** the keys mirror into `tags` (user tags win the clash). Server-side, benchmark builds are **excluded from fleet trends/`/v1/builds` by default** (`BuildFilter.excludeModes`, bound param) so a benchmark series never pollutes p50/p95 — opt in with `mode=benchmark`/`includeBenchmark=true`; they get a dedicated `GET /v1/benchmark/series` grouped by `(scenario, isolationMode)`, percentiles from a shared commons `BenchmarkSeriesCalculator` (nearest-rank, parity-tested in-memory↔Postgres jsonb). The series shows p50/p90/min over N iterations, never a single run; the view + recipe never compare across isolation modes | Spec §7's scheduled-profiler methodology (Telltale/Bagan). Env activation over a DSL flag is the only way a shared pipeline can benchmark an unmodified pilot; allowlist-in-code bounds series cardinality. Fleet exclusion at the *query* layer (not ingest) keeps benchmark builds first-class in their own view while never skewing the fleet — the reason the server change ships with the producing pipeline. No K8s cartesian matrix (Bagan) — same-machine gradle-profiler scenarios per the spec |
| 2026-07-04 | **CI connector framework** (plan 028): the server-side `CiConnector` SPI (`fetchRun`/`parseWebhook`/`buildLink`/`refFrom`) + `ConnectorRegistry` with a `NoopConnector` fallback ships as the extension point; `AzureDevOpsConnector` is the first instance (Build + Timeline REST pull → normalized `CiRun`/`CiSpan` tree + `queuedMs`, PAT via Basic auth). Enrichment is **strictly additive**: on ingest of a build whose `ci.provider` a connector handles, a bounded **single-worker in-process `EnrichmentQueue`** (instance-local, same posture as the rate limiter) polls until the build's normalized `finishedAt` is set (else `PENDING`) and upserts the tree into a new `ci_runs` table (jsonb tree, idempotent on `(project,build)`); every failure degrades to a stored `FAILED`/`UNCONFIGURED` + `warn` — a connector **never** fails ingest. Outbound HTTP posture: **https-only + per-config host allowlist** (`allowedHosts`), so the ingested/attacker-controlled `ci.buildUrl` selects *which* configured org but never a new host; the outbound client **follows no redirects** (a 3xx must never carry the `Authorization` PAT to an unvalidated host — review finding); a **per-project enrichment cap** bounds request amplification from a tenant flooding fabricated `ci.provider=azure-devops` ingests against the shared PAT (review finding); **PAT env-only** (`BUILDHOUND_CONNECTOR_AZURE_PAT`, `Credential` is deliberately not `@Serializable`), never logged/in jsonb/in image layers. `workerName` from the timeline is kept server-side, read back only within the owning tenant (treated like the dropped plugin `agentName`, plan 005). `GET /v1/builds/{id}/ci-run` (read scope) returns status + spans + `queuedMs` + `gradleSharePct` (build wall ÷ pipeline wall, clamped `[0,1]`); a `build.complete` service hook (`POST /v1/connectors/azure-devops/hook`, ingest scope) short-circuits the poll, tenant from the token never the body | Spec §5's connector SPI, landed as the interface [plan 041] (GHA/GitLab) and [plan 033] (lost-build accounting) build on. jsonb tree over a flat `ci_spans` hypertable keeps a ci-run read a single row and matches the `extensions`-as-jsonb precedent; a span-level hypertable waits for cross-build span queries. SSRF is the load-bearing risk (`ci.buildUrl` is ingested), so the host allowlist + https + env-only PAT are the framework's standing outbound contract |

| 2026-07-04 | **Lost-build accounting** (plan 033): a build that dies before the Flow finalizer (OOM/`kill -9`/agent eviction) surfaces as an additive `BuildOutcome.INTERRUPTED` instead of vanishing. **Primary — execution-time start-marker.** The `TaskEventCollector` build service mints the buildId (`by lazy`, shared with the finalizer via its existing `@ServiceReference`) and writes a tiny `build/buildhound/started/<buildId>.json` marker on its **first task event**, `AtomicBoolean`-guarded, inside `runCatching` — execution-time IO only (§2 rule 9: a config-phase file touch is a CC fingerprint input). The *next* build's finalizer deletes its own marker, then `MarkerReconciler` (pure, bounded ≤20/build, TTL ~14 d) selects stale markers, each synthesized by `PayloadAssembler.assembleInterrupted` (`finishedAt==startedAt`, empty tasks, no derived, scrubbed) and routed through the same `UploadGate`/`PayloadUploader` — the whole path best-effort, never fails/hangs the build. **The marker deliberately omits ci/vcs:** a build-service parameter value bakes into the CC entry and replays *stale* on a hit (the same reason `taskMetadata` goes empty under IP), so a ci/vcs value source there would be unreliable exactly on the CC-enabled CI builds that matter; `mode` is resolved with no CI context (an `AUTO` build's marker is `LOCAL`). **Fallback — connector expected-build check** (Azure-only, opt-in): a `build.complete` hook for a run with no ingested payload triggers an async `checkExpectedBuild` that fetches the Timeline and records a deterministic-id `interrupted:<provider>:<runId>` build (idempotent, tenant-scoped) — covering the ephemeral-agent case the marker can't reach. `/v1/trends` counts `INTERRUPTED` separately and excludes it from the duration/hit-rate/failure aggregates (synthetic duration) | Roadmap phase-3 exit "a daemon-killed build appears as INTERRUPTED instead of vanishing". The marker is the always-on baseline (any consumer, no server/connector needed); the connector check is additive precision for the successor-on-a-fresh-agent blind spot. Marker-from-execution-code (never `apply()`) with a functional CC-reuse assertion is the load-bearing CC risk; the stale-replay property of service params is why ci/vcs are dropped rather than captured unreliably — a documented divergence from the plan's richer marker |

| 2026-07-04 | **Plugin overhead budget + self-benchmark harness** (plan 034). Four axes budgeted — configuration (`cc_hit`), per-task (`incremental`), finalizer (`no_op`), upload (`no_op_upload` vs `no_op`) — measured as `mean(plugin-on) − mean(plugin-off)` by gradle-profiler over a synthetic 3-module Kotlin/JVM fixture toggled via `-Pbuildhound.overhead.plugin=on\|off` (composite `includeBuild` of this repo; plugin config via plan-027 property overrides, no DSL in the fixture settings). The verdict math is a pure `OverheadCalculator`/`ProfilerCsv` in **buildhound-commons** (unit-tested every build; name-keyed CSV parse tolerant of profiler-version column drift); a thin `buildhound-overhead` shell launcher over a `:buildhound-commons:overheadVerdict` JavaExec exits non-zero on breach. Caps are the *looser* of an absolute floor or a percentage, with a combined-stddev separation guard so same-machine noise can't mint false breaches. The `overhead-budget` CI job is **watched** (non-blocking) initially — a toggle self-test (plugin-on emits `build/buildhound/`, plugin-off does not) guards against fixture rot. **The committed `OverheadBudget.DEFAULT` caps are provisional** (plan §3 shapes) pending calibration from the first green reference-runner run — that calibration updates the caps here in a follow-up row | Roadmap phase-3 "plugin-overhead budget + self-benchmark harness"; "never fail the build" ≠ "never slow it noticeably". Verdict-in-commons keeps plugin/CI/docs agreeing on "breach" in one unit-tested place. Watched-then-promoted matches the noisy-shared-CI reality (plan 021 precedent). The harness measures the *shipped* plugin (no plugin code changed); an optimization that a breach motivates is follow-up work |

| 2026-07-04 | **Flaky detection is server-only, two-signal, same-sha-guarded** (plan 036, spec §5). No plugin or schema change — it reads the plan-024 `tests` block already ingested. A pure `FlakyDetector` groups class rollups by the commons `TestUnitKey.of(module, classFqcn)` join key (the same key plans 037/040 use — pinned once, per the plan-024 row) and emits **retry** (a case `FAILED`/`ERROR`-then-`PASSED` inside one build, from the per-case detail carried only on failure/retry) and **cross-run** (the same `(sha, module/class)` reaching both a passed-only build and a failed build). The **same-sha requirement is the confounder guard**: a class that fails on commit A and passes on commit B is a *regression-then-fix*, not flake, and is silently excluded — the single most important correctness property, unit-pinned. Thresholds `minSamples=3`/`minFlakeRate=0.05` and `MAX_AFFECTED_BUILDS=20` live in code; **no decay/half-life in v1** (a fixed window is validatable before the pilot has labelled ground truth — decay is a follow-up once precision is measured). On ingest, `save()` projects each `tests` row into a narrow `test_class_outcomes` hot table (PK `(project_id, build_id, module, class_fqcn)`, `module` stored `''` for null since `TestUnitKey` treats null==`''`; read back `''`→null) — the same project-on-write pattern as `task_executions`/`apk_sizes`, so **the detector was folded into `BuildStore.flaky()` rather than a separate `FlakyStore`** (a documented divergence from the plan's proposed store): both stores fetch raw rows and defer to the one pure `FlakyDetector`, byte-for-byte parity pinned by a Testcontainers test (the plan-026/032 rollup-parity discipline). `GET /v1/flaky?days=N` is read-scope + tenant-scoped + `daysParam`-clamped, ranked by flake rate; `#/flaky` renders it. A newly-flaky class fires **exactly one edge-triggered `FLAKY` alert** via an instance-local `newKeySet()` (same posture as the rate limiter), reusing plan 025's redacted/https-only dispatcher — which required refactoring `AlertContext` into a **sealed interface** (`summary()`/`webhookJson()`) with `VerdictAlert`/`FlakyAlert` subtypes, leaving the SSRF/host-check delivery loop untouched. The alerter `runCatching`s the whole path — a thrown detector **never** fails ingest (pinned). Class/module/case names are already-scrubbed plaintext (§3.7), so no new PII/paths enter the payload | Roadmap phase-4 item-1; spec §5's flaky signals landed server-side ahead of the plugin-side quarantine loop that gates on it. Detector-in-one-pure-function + project-on-write is the only way in-memory and Postgres agree by construction (parity test); folding into `BuildStore` avoids a second store that would duplicate the windowing/projection already there. The same-sha guard is the reason this is worth shipping before quarantine — cross-commit regressions dominate a naive divergence count (research §2.6). The 0.90 labelled-precision gate (export `/v1/flaky?days=30`, human-label, precision = truly-flaky ÷ flagged ≥ 0.90 on the pilot) is plan 037's entry condition, so quarantine can't enforce on an unvalidated detector |

| 2026-07-04 | **Addon foundation** (plan 039, §5.1): a reserved `extensions: Map<String, JsonElement>` on `BuildPayload`, a commons `BuildHoundCollectorRegistry` / `BuildHoundExtensionContributor` `ServiceLoader` SPI, and a `/v1/addons/<id>/…` server namespace gated by a new `ADDON` token scope over a tenant-scoped jsonb `addon_data` table (V8). Core **observes**, addons **mutate**: the SPI is the single coupling point, so an addon depends on commons only and attaches without forking core; contributions are opaque per-addon JSON (own `schemaVersion`) that core does not deep-scrub (the addon owns the §3.7 bar) and the plan-019 `PayloadCapper` bounds to a 256 KiB extensions budget (largest-first drop, `caps.droppedExtensions`, runs on the server ingest re-cap too). Discovery is execution-time in the Flow finalizer (no CC input — functional test asserts reuse), each contributor `runCatching`-guarded (one bad addon never fails the build or suppresses a sibling), applied-without-core ⇒ warn+no-op. `{addonId}` is allowlist-validated (empty here ⇒ every id 404) so it never names a table/route dynamically | Roadmap phase-4 item-3, and the hard prerequisite for plans 037/040 (both pure consumers). `JsonElement` keeps commons decoupled from addon types; jsonb keeps ingest schema-stable (no per-addon DDL, mirroring the connector `extensions`-jsonb precedent); the dedicated `ADDON` scope walls addon APIs off from a leaked ingest/read token (spec §5 least-privilege). Capping in the shared `PayloadCapper` (not the assembler) means a hostile ingest's oversized `extensions` is bounded server-side, exactly like the artifacts array. The `extensions` field is additive (new golden `build-payload-v2ext.json`; v1 golden untouched) |

| 2026-07-04 | **Internal-adapters module — the one sanctioned exception to "no internal Gradle APIs"** (plan 038, spec §3.1). Cache origin (local/remote), per-task cache keys, and the task dependency graph are reachable **only** through internal build operations (gradle/gradle#9456 refused a public API; Tuist reaches for the same internal types — research §2.1/§2.4). So they live in a **separate, opt-in, separately-shipped** module `buildhound-internal-adapters` (plugin id `dev.buildhound.internal-adapters`) that is **never on the core plugin's classpath** — applying it *is* the consent to use internal APIs. `gradleApi()` (via `java-gradle-plugin`) exposes the internal types at compile time; a `Plugin<Settings>` obtains `BuildOperationListenerManager` from `(gradle as GradleInternal).services` and registers a `BuildOperationListener` for `SnapshotTaskInputs` (cache key), `ExecuteTask` (task-path correlation), `ExecuteWork` (caching-disabled + origin), and `BuildCache{Local,Remote}{Load,Store}`. **Spike-proven** the listener fires even under configuration cache (the manager is daemon-scoped, so the listener persists across builds — hence a register-**once-per-daemon** guard + the collector **reads-and-clears** each build, since core's Flow finalizer runs every build incl. CC hits). Every listener body is `runCatching`-guarded and every uncertain getter goes through reflection, so a Gradle-version mismatch degrades a field to "unknown" and **never fails the build**; a `>9.x`/unparseable version bucket degrades rather than mis-reads (the Tuist repo is the breakage canary). Data leaves as salted `16-hex…` HMACs of the already-opaque Gradle keys (own per-project salt file, no salt ⇒ omitted, never raw). The module contributes `extensions["internalAdapters"]` via the plan-039 `ServiceLoader` registry; **core stays internal-API-free** — its finalizer reads two well-known optional keys (`avoidedMs`, `dependencyEdges`) out of that opaque JSON and threads them into `DerivedMetricsCalculator.compute`, which computes `criticalPathMs` as a cycle-guarded DAG longest-path. This **populates the long-null `derived.avoidedMs`/`criticalPathMs`** (superseding the plan-005 honest-null note) whenever the module is applied; without it, core behaves exactly as before | Roadmap phase-4 item-2. The isolation is the whole safety story: the always-on core path can never be taken down by an internal-API break because it never loads any of it; the opt-in module carries the risk, degrades to "unknown", and is version-gated. `criticalPathMs`-in-the-shared-calculator keeps plugin, server, and the HTML artifact computing one number. **Deferred to a 038 follow-up (v1.x):** per-input-**property** value hashes + the comparison-page per-property cause ranking + origin lane (server `BuildComparator` + dashboard) — the coarse "which task's cache key changed" signal is already reachable from the captured keys; per-property *naming* waits on the property-hash capture |

| 2026-07-04 | **`dev.buildhound.test-sharding` addon** (plan 040, roadmap phase-4 item-3): an opt-in settings plugin that **mutates `Test.filter`** to split suites across CI shards — kept out of core by the no-silent-mutation rule (§2), applying it *is* the consent (like the quarantine/internal-adapters addons). It **inverts Tuist's failure semantics**: every plan-fetch failure (no server / timeout / non-2xx / no index / no timings) runs **all** tests + one `warn` + `appliedFilter=false`, never a `GradleException` (never-fail §2.3 — correctness over speed). The server balances with a pure `LptBalancer` (greedy longest-processing-time over 30-day p90 CI class timings; unknown suite → median, no history → 5 s floor) behind an **idempotent** `POST /v1/addons/test-sharding/plan` memoized on `(project, reference, total)` — so all parallel shards of one run read the same plan and inter-job discovery drift can't reshuffle them; the last shard runs any drift-unassigned class (catch-all). Interface is env (`BUILDHOUND_SHARD_INDEX`/`_TOTAL`/`_REFERENCE`) read via providers; **no index ⇒ fully inert**. Both CC defects Tuist hit are avoided by construction: the HTTP fetch lives in a `BuildService` at execution time (no shard slice baked into the CC entry) and the filter is applied in a `doFirst` reading its own task argument (never `Task.project`, never a captured `Task`) — a functional test asserts CC reuse. Join key `TestUnitKey.of(module, classFqcn)` is pinned in commons (plan 024) and shared by the ingest, the request suite list, and the balancer — the contract Tuist's Gradle path got wrong (bare FQCN vs `module/class` → round-robin degeneration) | Server-balanced-per-CI-job (not intra-task fan-out, which needs the Develocity broker) is the OSS-reachable model. Idempotent-plan-per-reference is the correctness core: without it, each parallel shard would discover suites independently and disagree. The balancer lives in commons-adjacent server code as a pure function so the plan is reproducible and unit-tested. The addon needs core only for the `extensions["testSharding"]` feedback block — the *filter* works standalone, a deliberate divergence from the strict "warn+no-op without core" addon contract (sharding's value is the filter, not the telemetry) |

*Add a row (or a docs/plans entry) whenever an architectural decision is made or reversed.*
