# 005 — Payload assembly, CI wiring, derived metrics

## Source

Roadmap Phase 1: "payload assembly + derived metrics (hit rate, avoided time, critical
path)", "CI env SPI … providers" (plugin side). Spec §3.2 (Finalizer), §3.3 (discovery
incl. `ServiceLoader`), §3.4 (mode), §4 (payload).

## Scope

**In:**

- `CiValueSource`: reads `System.getenv()` at execution time, runs
  `CiEnvironment.detect(env, ServiceLoader extras)` (plugin classloader sees the
  settings classpath), returns a Serializable `CollectedCi` mirror of `CiContext`.
- Mode resolution (spec §3.4): explicit `ci`/`local` respected; `auto` → `ci` when a CI
  context was detected, else `local`; `disabled` behaves like `enabled=false`.
- `PayloadAssembler` (plugin, deliberately Gradle-free → plain unit tests, see plan 004
  retro): merges task events + environment + vcs + ci + tags + requested tasks into
  `BuildPayload`. `buildId` = random UUID. `startedAt`/`finishedAt` from first task
  start / last task end (fallback: assembly time) — configuration time is not included
  yet (`configurationMs` stays null, spec allows refinement).
- VCS fallback: when git returned nulls (detached HEAD on CI), `vcs.branch`/`sha` come
  from the CI context.
- CI payload mapping (decisions, see plan 002/003 notes):
  - `CiInfo` carries the §4-declared fields (provider, runId, pipelineName, jobId,
    buildUrl); `pullRequestId`/`targetBranch` go into `attributes` (declared map;
    needed for PR-vs-baseline in phase 2).
  - **`agentName` is dropped from the payload for now** — quasi-PII on self-hosted
    runners and §4 doesn't declare a field for it; revisit with the pseudonymization
    machinery if a real need appears.
  - With `pseudonymize=false` the plaintext values ride in the `hostnameHash`/`userId`
    fields — spec §3.7 explicitly sanctions plaintext mode; field names stay (schema is
    fixed, additive-only).
- `projectKey` = root project name (until tenancy provides a server-issued key).
- `requestedTasks` from `startParameter.taskNames` (captured at apply, CC-safe value).
- Derived metrics in **commons** (`DerivedMetrics.compute(tasks)` — pure schema logic,
  server rollups can reuse): `cacheableHitRate` = (FROM_CACHE + UP_TO_DATE) /
  (those + EXECUTED) over all tasks (task `cacheable` flags are not yet collected);
  `parallelUtilization` = Σ durations / (wall × cores) clamped to [0,1];
  `avoidedMs`/`criticalPathMs` stay null — honest nulls: avoided time needs origin
  timings (v1.x cache-origin work), critical path needs the dependency graph.
  Divergence from the roadmap's one-liner recorded here on purpose.
- Finalizer writes the payload as pretty JSON to
  `<rootDir>/build/buildhound/build-payload.json` (the HTML-artifact chunk consumes
  it next) and logs a one-line summary. No upload yet — local file only, so the
  local-build **upload** opt-in (spec §3.4 localBuilds) lands with the upload chunk.

**Out:** upload/spool, HTML artifact, `FailureInfo` (needs the scrubber), task
`cacheable`/type capture, `configurationMs`, server salt/tenancy.

## Test strategy

- commons: `DerivedMetricsCalculatorTest` (hit rate over mixed outcomes, empty build,
  utilization clamping, zero-wall guard).
- plugin unit: `PayloadAssemblerTest` (mode resolution matrix, vcs/CI fallback, CI
  attribute mapping incl. agentName dropped, timestamps from tasks, tags passthrough).
- functional: payload file exists after a build, deserializes as `BuildPayload` with
  schemaVersion 1 and the fixture's task; generic-provider CI detection pinned via
  `withEnvironment` (inherited env minus real CI markers plus `BUILDHOUND_CI_*`);
  `mode = disabled` writes nothing.

## Risks

- Schema stays untouched (assembly only) — golden files unaffected.
- CC: `CollectedCi` Serializable via provider params only; env read inside ValueSource.
- Privacy: payload content is exactly the §4-declared fields; agentName dropped;
  no paths (task paths are project-relative task identifiers, declared in §4).

## Exit criteria

`./gradlew build` green; a real build of this repo produces a valid
`build-payload.json` with tasks, environment, vcs, derived metrics.
