# BuildHound — Roadmap

**Revised 2026-07-03** after phases 0–1 shipped (plans 000–013, now in
`docs/plans/implemented/`) and the research pass in [docs/research/](research/README.md)
(nine cdsap-repo deep dives, Tuist/eBay/CCUD feature analysis, dashboard UX review, cache-miss
fingerprint design, and a cross-set reconciliation verified against source). The original
MVP-first roadmap is preserved in git history; phase numbers are kept stable because the
research documents anchor to them. Phases still end in something usable on the pilot project;
sizes are relative (S < M < L < XL), not calendar promises.

## Phase 0 — Foundations ✅ shipped

Scaffold, naming (BuildHound / `dev.buildhound`), schema v1 in `buildhound-commons` with
golden-file tests, CI skeleton. (Plans 000, 001, 011.)

## Phase 1 — MVP: see every build ✅ shipped

Settings plugin with TaskEventCollector, EnvironmentCollector (machine/identity/toolchain/
daemon/CC state), VCS collector, CI SPI (Azure DevOps + GitHub Actions + generic), payload
assembly + scrubber, standalone HTML artifact v0, gzip upload with spool/retry, server with
Postgres persistence + tenancy/token auth + rate limiting, query API + rollups, dashboard v0
(builds list, build detail, trends). (Plans 002–010, 012, 013.)

**Known v0 honesty gaps carried into phase 2** (verified in the 2026-07-03 reconciliation,
[research/README.md §5](research/README.md)): `TaskExecution.type`/`cacheable` unpopulated,
`cacheableHitRate` denominator includes non-cacheable work, `avoidedMs`/`criticalPathMs`/
`configurationMs` hardcoded null, bare `CI` env not honored for mode classification, no timeout
on git execs, spec §3.9 upload text stale, no isolated-projects CI job, single-OS CI.

## Phase 2 — Honest metrics, fingerprints, first intelligence (L)

Two workstreams; the correctness one lands first because everything later trends on its numbers.

**2a. Correctness & hardening (debt from reconciliation):**

- Task **type + cacheable** capture via Talaiot's configuration-time `taskGraph.allTasks`
  dictionary with `BuildFeatures`-gated isolated-projects degradation
  ([research/repos/Talaiot.md](research/repos/Talaiot.md)) → fix `cacheableHitRate` to
  cacheable-only denominator; populate `configurationMs` from the existing configuration-phase
  observer; `avoidedMs`/`criticalPathMs` stay null until cache-origin/graph data exist (phase 4).
- Mode classification: honor bare `CI` env (in flight as a spun-off task) · git exec timeout
  (in flight) · tag/value cardinality + size caps in assembler/scrubber.
- Doc repairs: spec §3.9 rewritten to synchronous-with-spool reality; spec §1/§8 Gradle-floor
  drift fixed to 8.14; plan 003's CC-detection wording aligned with the shipped
  `DaemonState` observer.
- CI: add the spec-promised non-blocking isolated-projects job; add macOS (and evaluate Windows)
  legs — plan 007's only field bug was macOS-only; decide the CC-off matrix axis.
- Dashboard v0 quick wins from the UX research: contextual empty states, work-avoidance ledger
  (count/percentage/duration triples with explicit zeros), natural-language count-summary
  headers, and the **task timeline** (per-task `startMs` already ships in schema v1; lanes
  computed greedily from start/end overlaps — no schema change; also reuse in the HTML artifact).

**2b. Depth + first intelligence (original phase-2 scope + research additions):**

- **Input fingerprints tier (a) + build comparison tier (c)-lite**
  ([research/cache-miss-input-fingerprints.md](research/cache-miss-input-fingerprints.md)):
  build-level fingerprint map + allowlist DSL + opt-in per-Test-task sysProps capture (salted
  hashes), `GET /v1/builds/{a}/compare/{b}` with differing-key ranking, comparisons page.
- Kotlin build-report bundling (json dir) + Kotlin dashboard panel.
- Test collection per locked granularity (per-class rollups + failure/retry detail) + tests page.
- Regression engine v1: rolling default-branch baselines, PR-vs-baseline verdict endpoint,
  budgets, Slack/Teams alerts. Metric CLI + `POST /v1/metrics`.
- **eBay-style server rollups** ([research/plugin-ecosystem-gap-analysis.md §3](research/plugin-ecosystem-gap-analysis.md)):
  per-module Project Cost family (`buildCostScalar`, `buildImpactedUsers` over hashed ids),
  task duration by name and by type (unblocked by 2a), top-25 rankings, negative-avoidance-
  savings metric.
- CI/environment breadth: the 10-provider CCUD detection matrix, IDE + AI-agent detection
  fields, redacted git remote URL + source/PR links + GHA run-attempt, `uploadInBackground`
  knob, `BUILDHOUND_*` env / `buildhound.*` sysprop config overrides.

**Exit:** hit rate is cacheable-only and by-type rollups render; a deliberately slowed PR gets
flagged against baseline; two same-sha builds diffed on the comparisons page name the changed
input; a CircleCI build ingests as `mode=ci`; timeline visible on build detail and in the HTML
artifact; isolated-projects + macOS CI legs green.

## Phase 3 — Context, process health, benchmark mode (M/L)

- AzureDevOpsConnector (Timeline pull, optional service hooks) → CI span tree + queue time,
  "Gradle share of pipeline".
- **Process probe** (spec §3.6) on InfoKotlinProcess's ValueSource-in-BuildService recipe with
  the measurement math pinned per research: jstat GCT total (not YGCT+FGCT), `ps -o rss=` for
  portable RSS, one `jinfo` per PID, jstat columns mapped by header
  ([research/repos/InfoKotlinProcess.md](research/repos/InfoKotlinProcess.md),
  [build-process-watcher.md](research/repos/build-process-watcher.md)).
- Benchmark mode: gradle-profiler scheduled pipeline + Telltale's cache-isolation/tag-correlated
  methodology + the build-validation-scripts experiment recipes (same-sha CI↔CI, CI↔local,
  two-checkout relocatability) as documented recipes feeding the comparison engine.
- APK/AAB size capture using AndroidArtifactsSizeReport's `onVariants`/`toListenTo` mechanics
  ([research/repos/AndroidArtifactsSizeReport.md](research/repos/AndroidArtifactsSizeReport.md))
  → spec §4 `artifacts` field + trend view.
- Bottlenecks/"what regressed" landing page; toolchain-version adoption view.
- **Lost-build accounting**: daemonitor-style INTERRUPTED detection (start-marker
  reconciliation or connector-side expected-build check) so OOM-killed builds surface.
- Plugin-overhead budget + self-benchmark harness (build-process-watcher precedent).
- Config-cache **miss-reason** capture, best-effort (report-file read in the Flow action;
  eBay-style per-reason frequency rollup server-side).

**Exit:** nightly benchmark series on the pilot; queue time + non-Gradle steps visible; a
daemon-killed build appears as INTERRUPTED instead of vanishing; process panel shows
configured-vs-used memory; bottlenecks page answers "what got worse this week".

## Phase 4 — Differentiators & addon ecosystem (XL, order flexible)

1. **Flaky detection** (Tuist's two signals: intra-run retry divergence + cross-run divergence
   keyed on (commit, module, test)) → flaky page. **Detection + page delivered plan 036**
   (server-only over the plan-024 `tests` block: pure `FlakyDetector`, `GET /v1/flaky`,
   edge-triggered `FLAKY` alert, `#/flaky` page; same-sha confounder guard; no decay in v1).
   After precision is validated on the pilot (labelled flagged-set precision **≥ 0.90** —
   plan 037's entry gate): **`dev.buildhound.test-quarantine` addon** (skipped/muted modes via
   `excludeTestsMatching` / `ignoreFailures` + re-fail) — addon because it mutates Test tasks
   (locked gate #3 stands).
2. **Internal-adapters module**: cache origin local/remote + per-task cache keys + tier-(b)
   input fingerprints via `SnapshotTaskInputsBuildOperationType`,
   `ExecuteWorkBuildOperationType` (execution reasons, caching-disabled reason, origin cache
   key ≥8.7), and the `BuildCache{Local,Remote}{Load,Store}` ops — feature-flagged per Gradle
   version, degrading to "unknown". Upgrades the comparison page to per-property cause ranking
   and unlocks `avoidedMs`/`criticalPathMs`.
3. **Addon foundation + `dev.buildhound.test-sharding`**: reserved `extensions` payload map,
   `BuildHoundCollectorRegistry` in commons, `/v1/addons/<id>/…` namespace
   ([research/plugin-ecosystem-gap-analysis.md §6](research/plugin-ecosystem-gap-analysis.md));
   sharding addon per [research/test-distribution-addon.md](research/test-distribution-addon.md)
   (server LPT plan over 30-day p90 per-class timings, `BUILDHOUND_SHARD_INDEX` interface,
   run-all-on-failure fallback, pinned `module/class` join key).
4. **GitHub Actions + GitLab CI connectors**; composite action / CI includes; public
   "write your own provider/connector" docs.
5. OSS-launch hardening: self-host docs, API docs, optional MCP surface, retention config UI,
   Marketplace extension for Azure if adoption warrants.

**Exit:** an outside team can self-host, connect an unsupported CI via the SPI, apply an addon
without forking core, and explain a cache miss down to the changed input property.

## Cross-phase guardrails

Additive-only schema within a major version (contract tests enforce) · the plugin never fails a
build — every phase adds failure-injection tests · isolated-projects job blocking-optional until
phase 2a lands it, then watched · cardinality and payload-size budgets enforced in code, not
docs · plugin overhead measured against the phase-3 budget from then on · each phase ends with a
retro against the research risk register ([build-telemetry-research.md §6](build-telemetry-research.md))
and the reconciliation findings ([research/README.md §5](research/README.md)) · when a phase
invalidates an architectural assumption, `docs/architecture.md` + its decision log change in the
same PR.

## Suggested next implementation steps

1. Land the two in-flight fixes (bare-`CI` mode, git timeout).
2. Plan 014: task type + cacheable capture + honest hit rate (unblocks most of 2b's rollups).
3. Plan 015: timeline view (dashboard + HTML artifact) — highest UX value per effort, zero
   schema work.
4. Plan 016: fingerprints tier (a) + compare endpoint — the feature the research rates the
   product's biggest near-term differentiator.
