# 071 — Agent-facing surface: first-party SKILL.md + machine-readable diagnosis endpoint

**Status: implemented** · 2026-07-08 (landed c48e2c5, review fixes d86c512)

## Source

Research finding **F21 — Agent-facing surface: first-party SKILL.md + machine-readable diagnosis
endpoint** (`docs/research/ingest-corpus-analysis.md`), from the corpus source: the 880-star
`awesome-android-agent-skills` repo (`Github_projects.md`) — which trains agents to run
`./gradlew --scan` and read the Develocity scan as the primary diagnostic, i.e. **defaulting agents
to upload customer build data to `scans.gradle.com`**. Spec §5 (tenant-scoped read routes), §3.7
(privacy/no-egress positioning); cross-cutting rec §7.5 (a positioning play on live controversy).
This is the **agent surface** that plan [054](054-recommendations-rules-engine.md) (F4 rules engine)
explicitly defers to a separate plan, and it extends the MCP surface shipped in
plan [042](implemented/042-oss-launch-hardening.md).

## Scope

**In**

- `GET /v1/builds/{buildId}/diagnosis` — a read-scope, tenant-scoped endpoint that **synthesises**
  already-collected signals into one agent-consumable object: dominant phase, hit-rate-vs-target,
  top hotspots, and deltas vs the comparable baseline. Pure serialisation over existing data — no
  new collection, no new store method.
- A read-only `diagnose` MCP tool (buildhound-mcp) mapping to that GET — F21's sanctioned single-call
  agent surface, the strongest-novelty half (plan 042's six-tool set deliberately excluded even
  verdict/compare).
- A first-party **`buildhound-ci-assets/agent-skill/SKILL.md`** (markdown, not a Gradle module)
  teaching agents to diagnose *privately* from three no-egress sources, positioned as the counter to
  `--scan`-to-`scans.gradle.com`.
- `docs/api/openapi.yaml` entry for the new route (`OpenApiContractTest` requires it).

**Out (deferred)**

- **Configurable hit-rate target.** F21 allows "a default constant **or** a new additive
  `ProjectSettings` field"; this slice ships the constant `DEFAULT_CACHE_HIT_TARGET = 0.8`. A
  per-project `cacheHitTargetPct` is deferred because `ProjectSettings` persists as **real columns**
  in `project_settings` (`PostgresStores.kt:1067,1087`), so the field would need an additive
  `V__.sql` migration + Testcontainers parity — out of proportion to this synthesis slice.
- Input-level cache-miss explanation — stays in `/compare` (plan 022); `diagnosis` links to it, never
  duplicates it.
- The `#/diagnosis` dashboard SPA view, and MCP `verdict`/`compare` tools (natural follow-ups).
- Any payload schema change or plugin code. `schemaVersion` stays **1**.

## Design

Modules: **buildhound-server** (new `Diagnosis.kt` + one route in `Routes.kt`), **buildhound-mcp**
(one tool in `Tools.kt`), **buildhound-ci-assets** (`agent-skill/SKILL.md`), **docs** (openapi).

- **Pure synthesiser** `object BuildDiagnoser` in `Diagnosis.kt` (the `BuildComparator` /
  `BottleneckCalculator` discipline — no I/O, plain-unit-testable). `diagnose(payload, verdict)` →
  `@Serializable data class Diagnosis`, all analytic fields nullable so absent signals degrade rather
  than fabricate. Four signals, each mapped to a real source:
  1. `dominantPhase: PhaseBreakdown?` ← `derived.configurationMs` (config) vs
     `DerivedMetricsCalculator.wallClockMs(tasks)` (execution wall — **not** summed task durations,
     which parallelism would overcount). A **two-way** config-vs-execution classification, explicitly
     *not* a full decomposition (there is no separate dependency-resolution phase to invent).
     Honest-null-degrade: `dominantPhase = null` when `configurationMs == null` (unmeasurable). On a
     CC hit `configurationMs` is **0** (not null — see Risks correction), so execution is honestly
     dominant.
  2. `cacheHitRate: HitRateAssessment?` ← `derived.cacheableHitRate` vs `DEFAULT_CACHE_HIT_TARGET`
     (`belowTarget: Boolean`, `target`). Null when `cacheableHitRate` is null (isolated-projects /
     pre-016 payloads — inherited honest-null, not a new gap).
  3. `topHotspots: List<Hotspot>` ← this build's `EXECUTED` tasks ranked by `durationMs`, top-N
     (path/module/type/durationMs). Empty under IP (no tasks) — never fabricated.
  4. `deltas: MetricDeltas?` ← the **stored** `Verdict` (`VerdictStore.find`): its `durationMs` and
     `cacheableHitRate` `MetricVerdict`s already carry `value`, `baselineMedian`, `z`, `status`. Read
     "deltas vs previous comparable build" as *headline metric vs the comparable baseline median* —
     the verdict's baseline window **is** the comparable set (plan 025), so this is reuse, not a new
     query. Null when no verdict was evaluated; per-metric `baselineMedian` may be null
     (INSUFFICIENT_DATA) and is surfaced honestly.
- **Route** in `queryRoutes` (`Routes.kt`) — it already receives `store` + `verdicts`, so no
  signature/wiring change: `store.findById(project.id, buildId)` (404 when null, exactly like
  `/verdict` and `/compare`), `verdicts.find(project.id, buildId)` (nullable), then
  `BuildDiagnoser.diagnose(...)`. Wrapped in `runQuery {}` (503 on storage outage). Mounted under the
  existing query rate-limiter block (`Application.kt`).
- **MCP tool** `diagnose` in `Tools.all` — required `buildId`, path `/v1/builds/{enc(buildId)}/diagnosis`,
  reusing `get_build`'s `'/'`-reject-then-encode guard. Read-only GET → fits `QueryClient` unchanged;
  the "no mutating verb" invariant still holds.
- **SKILL.md** teaches, in privacy-preference order: (1) the report's embedded `<script>` payload JSON
  (`ReportAssets.render`, plan 006 — zero network); (2) the query API `/v1/builds/{id}/diagnosis`
  (+ `/verdict`, `/compare`, `/rollups/bottlenecks`) with a `read` token; (3) the `buildhound-mcp`
  `diagnose`/`get_build` tools. Preamble: no build data leaves the user's infrastructure — the
  differentiator vs agents defaulting to `scans.gradle.com`.

## Test strategy

- **Unit (`BuildDiagnoserTest`, new):** dominant-phase config-vs-execution both directions;
  `configurationMs == null` → `dominantPhase == null`; CC-hit (`configurationMs == 0`) → EXECUTION
  dominant; `cacheableHitRate == null` → `cacheHitRate == null`; below/above `DEFAULT_CACHE_HIT_TARGET`;
  hotspots ranked EXECUTED-only; deltas populated from a supplied `Verdict` and null without one.
- **Route (`ApplicationTest`/new `DiagnosisRoutesTest`):** 200 shape; unknown/foreign build → 404
  (cross-tenant peek denied); missing token 401, wrong scope 403; no-verdict build still 200 with
  `deltas == null`.
- **MCP (`ToolsTest`):** update the exact-set assertion (six → **seven**, add `diagnose`) and its
  "must stay exactly these" message; assert the path/`'/'`-reject; the read-only-verb invariant passes.
- **Contract (`OpenApiContractTest`):** already fails on any undocumented `/v1` route — add the
  openapi entry so it stays green. No Testcontainers/golden work (no store method, no schema change).

## Risks

- **Additive-schema / golden files (constraint honoured, N/A to payload):** no wire `BuildPayload`
  change — `Diagnosis` is a *response* type synthesised on read, so no new golden and `schemaVersion`
  stays 1. Contrast sibling plan 054, which *does* add a payload field. The deferred
  `cacheHitTargetPct` is server-internal config, never the wire schema.
- **Stale-cite correction (`configurationMs`):** F21 says "null on CC hits"; the code
  (`DerivedMetricsCalculator` doc + F2's narrowing) is **0 on CC hit, null only when unmeasurable**
  (plan 016). So honest-null-degrade fires on `== null`, and a CC hit yields EXECUTION-dominant, not a
  null phase. The plan follows the code; the finding line is corrected here.
- **Isolated-projects:** not a new hazard — `diagnosis` *inherits* already-honest nulls
  (`cacheableHitRate` null, `tasks[].type`/tasks absent under IP), so it surfaces gaps rather than
  fabricating a phase, hit rate, or hotspot list.
- **Multi-tenancy:** `/diagnosis` is `allowsRead` + tenant-scoped via the token's project; a
  foreign/unknown build reads as 404 (identical to `/verdict`, `/compare`). The MCP tool inherits the
  read-only, `Redirect.NEVER`, header-only-token posture of `QueryClient` (plan 042) — a leaked read
  token can only read this tenant.
- **Privacy (§3.7):** the endpoint returns only declared fields (task paths, module names, durations,
  hit rate) already present in the payload — no absolute paths, env, or secrets. The SKILL.md's
  premise *is* the privacy win (no `scans.gradle.com` egress); it ships no telemetry and touches no
  payload.
- **Never-fail-the-build / CC-safety (N/A, stated):** this slice is server + MCP + markdown only — no
  plugin/config-phase code, so neither constraint is engaged.

## Exit criteria

- `GET /v1/builds/{id}/diagnosis` returns dominant-phase / hit-rate-vs-target / top-hotspots / deltas,
  degrading each to null on absent signals; unknown/foreign build → 404; documented in `openapi.yaml`
  (`OpenApiContractTest` green).
- `buildhound-mcp` exposes a read-only `diagnose` tool; `ToolsTest` updated and green.
- `buildhound-ci-assets/agent-skill/SKILL.md` exists, referencing the three no-egress diagnosis paths
  and the no-`scans.gradle.com` positioning.
- `./gradlew build` green.
