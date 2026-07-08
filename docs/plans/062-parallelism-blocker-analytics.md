# 062 — Parallelism-blocker analytics: gating-task detection + graph centrality

## Source

- Research finding **F12** (`docs/research/ingest-corpus-analysis.md` §4): `parallelUtilization` is a
  scalar that can't answer "which task serializes my build." Two composable analytics — (1) public-data
  gating-task detection over the `startMs`/`durationMs` timeline; (2) internal-adapters-edge weighted
  **degree** centrality — plus a GEXF/DOT export.
- Source articles: Talaiot deep-dives — `docs/research/processed/Graphs, Gradle and Talaiot.md`
  (the Plaid 335-node/1106-edge example where weighted-**degree** centrality surfaced
  `generateDebugFeatureTransitiveDep`, the hub the single longest chain misses; `TaskDependencyGraphPublisher`
  GEXF prior art) and `Talaiot at Scale.md`.
- Builds on plans [038](implemented/038-internal-adapters.md) (edge list + `criticalPathMs`),
  [039](implemented/039-addon-foundation.md) (`extensions` map + core/addon decoupling),
  [022](implemented/022-input-fingerprints-compare.md) (per-build sibling-endpoint precedent),
  [017](implemented/017-task-timeline.md) (task `startMs`), [032](implemented/032-bottlenecks-landing-page.md)
  (Bottlenecks families), [026](implemented/026-server-rollups-project-cost.md) (rollup parity),
  [019](implemented/019-cardinality-size-caps.md) (`PayloadCapper`). Spec §5 (query read routes), §6
  (dashboard pages).

## Scope

**Server-side only.** No plugin/commons collection change: the timeline (`tasks[].startMs/durationMs`)
and the dependency edge list are *already* collected and serialized.

**In**

- `GatingAnalyzer` (server-pure): per-build sweep-line over `payload.tasks[]` → per-task **serialized
  ("gating") ms** for tasks that ran alone while work remained. Needs no opt-in module.
- `GraphCentrality` (server-pure): duration-weighted **degree** centrality over the already-serialized
  `extensions["internalAdapters"].dependencyEdges` + core `tasks[].durationMs`; null when edges absent.
- `GET /v1/builds/{buildId}/parallelism` (token + tenant scoped): gating blockers (always available from
  the timeline) + a centrality ranking (null when edges absent) — framed as ranked *candidates*.
- `GET /v1/builds/{buildId}/graph?format=gexf|dot` (token + tenant scoped): dependency-graph export,
  label-escaped; 404 when no edges; bounded by the ≤2000-edge cap plan 038 already enforces.
- Additive golden `build-payload-v1-internal-adapters-edges.json` populating `dependencyEdges` (never
  edit the existing `-internal-adapters.json`) to pin round-trip + the server read path.

**Out / deferred**

- The **fleet-wide "parallelism blockers" Bottlenecks family** + `/v1/rollups/parallelism-blockers` —
  F12's headline, but a fleet ranking needs ingest-time persistence of per-build gating (a
  `parallelism_blockers` table + Postgres/in-memory parity, plan-026 discipline). Separable follow-up.
- Any payload/`derived` change: correcting F12's stale "edges **not serialized**" narrowing (they are —
  see Design). Fleet-wide centrality aggregation, cross-build/union graphs, and **betweenness**
  centrality (finding: weighted *degree* is what surfaced the Plaid hub).
- Dashboard "Parallelism" panel on Build detail (spec §6) — thin follow-up.

## Design

- **Correction (load-bearing).** F12 assumes edges need a new additive field. They do not:
  `InternalAdaptersPayload.dependencyEdges` (`InternalAdaptersModel.kt:41`) already ships inside
  `extensions["internalAdapters"]` (`InternalAdaptersCollector.kt:73`), capped to ≤2000 via
  `Caps.capMap` and covered by `PayloadCaps.maxExtensionsBytes` (256 KiB). No new payload field, no new
  `PayloadCapper` cap. This plan only *reads* it.
- **Server↔adapter decoupling (the one thing most likely to break).** `buildhound-server` depends on
  `buildhound-commons` **only** and must never import `InternalAdaptersPayload` (it lives in the
  Gradle-API module `buildhound-internal-adapters`; importing it drags Gradle onto the server classpath
  — an architecture-§5 break). Plan 038's would-be comparator reader was *deferred*, so no server decoder
  exists yet. Decode `payload.extensions["internalAdapters"]` through a **server-local minimal**
  `@Serializable InternalAdaptersView(dependencyEdges: Map<String, List<String>> = emptyMap())` via the
  commons `BuildHoundJson.payload` (`ignoreUnknownKeys`), and take per-task durations from **core**
  `payload.tasks[]` — the plan-039 decoupling principle applied to the read side.
- **`GatingAnalyzer`** (`buildhound-server`, pure). Input: `tasks` with a real interval (`durationMs > 0`,
  outcome EXECUTED/FROM_CACHE — a 0 ms NO_SOURCE/SKIPPED never occupies a slot). Emit start/end boundary
  events, sweep left→right; in each inter-boundary interval where **exactly one** task runs **and** ≥1
  task starts at/after the interval end ("work remained"), attribute the interval length to that sole
  task. Output `Map<path, serializedMs>` → top-N `BlockerRow(taskPath, module, serializedMs, durationMs)`.
  A heuristic *hint*: timeline-only gating cannot separate inherent dependency-serialization from missed
  parallelism — which is precisely why analytic #2 uses the edges.
- **`GraphCentrality`** (`buildhound-server`, pure). Build the reverse adjacency (dependents) from
  `dependencyEdges`; `weightedDegree(t) = Σ durationMs over deps(t) ∪ dependents(t)`,
  `degree = |deps ∪ dependents|`. Rank descending → `CentralityRow(taskPath, module, durationMs, degree,
  weightedDegree)`. A task referenced only as a dependency (absent from `tasks`) weighs 0.
- **`ParallelismView`** (`@Serializable`): `gatingBlockers`, `centrality: List<CentralityRow>?`
  (null → edges absent), `centralityAvailable: Boolean`, `topN`. New route mirrors `/verdict`,`/ci-run`,
  `/compare`: `authenticatedProject(...allowsRead)` → `store.findById(project.id, buildId)` → compute →
  respond; unknown/foreign build → 404.
- **`GraphExporter`** (pure): `.gexf`/`.dot` emit nodes (task-path label + `durationMs` weight/size attr)
  + directed edges, every label through an XML/DOT escaper. `/graph` sets `Content-Type`
  (`application/xml` / `text/vnd.graphviz`), defaults `gexf`; no edges → 404.

## Test strategy

- **`GatingAnalyzerTest` (unit):** empty → empty; single task → empty (no work remained); a sole-runner
  *tail* task → not penalized; fully parallel build → zero gating; two sequential + one overlapping;
  simultaneous start/end boundaries deterministic; all-zero durations → empty.
- **`GraphCentralityTest` (unit):** diamond DAG → the fan-in/out hub outranks the longest single chain
  (the Plaid property); reverse-adjacency correctness; dependency-only node weighs 0; empty edges → null.
- **`GraphExporterTest` (unit):** GEXF is well-formed XML, DOT parses; a task path containing
  `<`/`&`/`"`/`\` is escaped in both.
- **Route tests:** `/parallelism` — foreign/unknown build → 404 (tenant scoping); payload with the new
  edges golden → non-null centrality; core-only payload → `centrality=null`,
  `centralityAvailable=false`, blockers still present; no/wrong token → 401/403. `/graph` — edges → 200 +
  escaped body; absent → 404.
- **Commons `GoldenPayloadTest`:** `build-payload-v1-internal-adapters-edges.json` deserializes/round-trips
  with non-empty `dependencyEdges`; existing goldens untouched; `schemaVersion` stays 1.

## Risks

- **Server↔adapter classpath break (named).** Importing `InternalAdaptersPayload` pulls Gradle onto the
  server. Mitigation: server-local `InternalAdaptersView` decoded via commons `BuildHoundJson`
  (`ignoreUnknownKeys`), durations from core `tasks[]`; server `build.gradle.kts` keeps
  `implementation(projects.buildhoundCommons)` only (no internal-adapters dep — asserted).
- **Isolated projects (the finding's whole point).** Gating reads the *execution-time* timeline
  (TaskFinishEvent `startMs/durationMs`), **not** the plan-016 config-time `whenReady` dictionary, so it
  survives IP and needs no opt-in module ("most critical-path insight without `criticalPathMs`").
  Centrality/graph inherit plan 038's `whenReady` IP gate → `dependencyEdges` empty under IP →
  `centrality=null`, `/graph` 404. Same null-degrade when the adapters module is off, **or** when
  `PayloadCapper` byte-dropped the whole `internalAdapters` blob on a pathological fan-out (a real third
  absent-case). No 500 in any absent-case.
- **Additive-schema / goldens.** No payload type edited, `derived` untouched, `schemaVersion` stays 1;
  only a NEW golden added. Edge list already ships (plan 038) — no new field, no new cap.
- **Multi-tenancy.** Both routes are `allowsRead` + `store.findById(project.id, …)`; a foreign/unknown
  build reads 404, never a cross-tenant peek — same discipline as `/compare`.
- **Privacy (§3.7).** Outputs are task paths/modules + ms — the same class already shipped in `tasks[]`
  and surfaced by `/tasks`; no absolute paths (paths are `:a:b`), no new PII. GEXF/DOT labels are
  XML/DOT-escaped so a crafted label can't inject markup into the export (defensive; ingest already scrubs).
- **Never-fail.** Server-only; the plugin is untouched, so its never-fail contract is unaffected. The
  endpoints degrade (null/empty/404), never 500, on absent edges or a degenerate timeline; the
  ≤2000-edge cap bounds export size.
- **Heuristic honesty.** Blockers + centrality are ranked *candidates* ("investigate this serialization"),
  not confirmed fixes — labeled as such in the response/UI, per the finding's framing.

## Exit criteria

- `GET /v1/builds/{buildId}/parallelism` returns gating blockers for any build with a task timeline, and a
  non-null weighted-degree centrality ranking when the build carries
  `extensions["internalAdapters"].dependencyEdges`; `centrality=null`/`centralityAvailable=false`
  (no 500) when edges are absent (adapters off / IP / capped).
- `GET /v1/builds/{buildId}/graph?format=gexf|dot` exports a well-formed, label-escaped graph when edges
  exist, 404 otherwise; both routes are token + tenant scoped (foreign build → 404).
- The server has **zero** dependency on `buildhound-internal-adapters`; edges decode via commons
  `BuildHoundJson` + the server-local view.
- New `build-payload-v1-internal-adapters-edges.json` golden added, existing goldens unedited,
  `schemaVersion` 1.
- Fleet "parallelism blockers" family + persistence explicitly deferred with a note; spec §5 lists the
  two new read routes; a decision note records that F12's "edges not serialized" was stale.
- `./gradlew build` green.
