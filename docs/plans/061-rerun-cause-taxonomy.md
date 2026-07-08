# 061 — Rerun-cause taxonomy over `executionReasons`

## Source

Research finding **F11** (`docs/research/ingest-corpus-analysis.md`) — *"Rerun-cause
taxonomy over `executionReasons`."* Corpus sources: `Modular vs Monolithic Architectures
Build Performance Insights.md` (Pocket Casts depth-12 ABI cascade, 10.8 s vs 13.8 s),
`Best Practices for Dependencies.md` / `Improve the Performance of Gradle Builds.md`
(`api`→`implementation` "one of the most impactful changes"), `Best Practices for
Structuring Builds.md` (buildSrc → included `build-logic`). Spec §6 (server-side derived
views); server rules §5. Precedent for *other* reason rollups: Kotlin incremental reasons
(plan [023]) and the CC-miss-reason capture attempt (plan [035]).

Every task already carries `executionReasons` in the v1 payload
(`BuildPayload.kt:331`, collected via public `TaskExecutionResult.getExecutionReasons()`
at `TaskEventCollector.kt:105-106`). BuildHound stores them opaquely and never interprets
them. This is a **server-side-only** slice: no plugin change, no payload schema change.

## Scope

**In**

- A pure `RerunCauseClassifier` (server) mapping each reason string to a fixed `RerunCause`
  enum via **version-tolerant substring patterns**, with an explicit `UNCLASSIFIED` bucket.
  Buckets: `SOURCE`, `IMPL_CLASSPATH`, `UPSTREAM_OUTPUT`, `OUTPUT_MISSING`,
  `CACHING_DISABLED`, `FORCED`, `UNCLASSIFIED`.
- A windowed `rerunCauses(projectId, days, nowMs)` rollup on `BuildStore` (in-memory +
  Postgres, both feeding the shared classifier — the plan-[026]/[036] parity discipline) +
  `GET /v1/rollups/rerun-causes` (read-scope, tenant-scoped, `days` clamped like the other
  rollups). Output: per-bucket **coverage** shares of executed task-hours + counts.
- **Detector 1 — build-logic invalidation storm:** the `IMPL_CLASSPATH` coverage share of
  executed task-hours, surfaced as a **ranked candidate** ("N % of executed task-hours were
  classpath/impl rebuilds — consider migrating buildSrc to an included `build-logic` build"),
  never a confirmed fix.
- **Detector 2 (build-level only) — cascade-vs-contained:** classify each build `CASCADE`
  when its `IMPL_CLASSPATH`/`UPSTREAM_OUTPUT` coverage exceeds a threshold, `CONTAINED`
  otherwise; report the fleet cascade rate.
- Additive DB migration `V11` adding a nullable `execution_reasons text[]` column to
  `task_executions`, populated on ingest; threaded into the server-internal `TaskRow`.
- `docs/api/openapi.yaml` entry for the new route (OpenApiContractTest drift guard).

**Out / deferred**

- **Per-module ABI `api`-overuse ranking.** Attributing which *upstream* module caused a
  cascade needs the dependency edge-list, which finding **F12** established is captured
  (plan [038]) but **not serialized** in the payload. A co-occurrence heuristic would be a
  confounded, authoritative-looking ranking — deferred until F12 ships the additive edge
  field. Only the build-level cascade signal is in this slice.
- No plugin/collector change — reasons already ship. No payload type or golden-file change;
  `schemaVersion` stays 1.
- Internal-adapters `cachingDisabledReason` (`InternalAdaptersModel.kt`) as an optional
  sharpener of the `CACHING_DISABLED` bucket — noted, not built; classifier reads the
  **core** field only.
- No dashboard SPA markup (the JSON endpoint lands first, like every rollup); a Bottlenecks
  "rerun causes" family is a follow-up.

## Design

Modules touched: **buildhound-server only.**

- **`RerunCauseClassifier`** (new, mirrors `FlakyDetector`/`RollupCalculator` — pure object,
  thresholds in code). `classify(reason: String): RerunCause` matches version-tolerant
  substrings ("is not up-to-date because … has changed" → `SOURCE`; "implementation … has
  changed"/"classpath" → `IMPL_CLASSPATH`; "output … removed/no longer" → `UPSTREAM_OUTPUT`;
  "no history"/"output … does not exist" → `OUTPUT_MISSING`; "Caching has not been
  enabled"/"caching is disabled" → `CACHING_DISABLED`; "--rerun"/"rerun requested"/"forced"
  → `FORCED`; else `UNCLASSIFIED`). Tolerates empty/null/unknown strings without throwing.
- **Coverage attribution (the key data-model decision).** `executionReasons` is a *list* per
  task and one task can hit multiple buckets. Per task, dedupe its reasons to a **set** of
  buckets; a bucket's numerator = Σ `durationMs` of executed tasks whose bucket-set contains
  it; denominator = total executed task-hours. **Shares therefore overlap and do not sum to
  100 %** — "task-hours *touched by* bucket X," not a partition. Bucket membership is per-task
  (order-invariant), so in-memory and Postgres folds agree byte-for-byte.
- **`UNCLASSIFIED` carries two distinct realities** — unrecognized strings (Gradle version
  drift) *and* reasons shed by `PayloadCapper` (stage-1 drops all reasons under byte
  pressure; per-task caps 10 reasons / 500 chars, `PayloadCapper.kt:16-17,141-146`). An
  executed task with an empty reason list also lands here. The rollup exposes the unclassified
  share as an explicit **honesty signal** — never folded silently into a real bucket.
- **Store plumbing.** `TaskRow` (`Rollups.kt:50`) gains `executionReasons: List<String> =
  emptyList()`. In-memory `taskRowsOf` (`BuildStore.kt:633`) copies `task.executionReasons`;
  Postgres `insertTaskRows` (`PostgresStores.kt:147`) writes the new `text[]` column and
  `taskRowsBetween` (`PostgresStores.kt:658`) reads it back. New `BuildStore.rerunCauses(...)`
  interface method + both impls flatten the window's executed rows through the classifier +
  a small `RerunCauseRollup` calculator (bucket coverage + cascade rate). Route added beside
  `/rollups/toolchain` (`Routes.kt:485`) via `authenticatedProject(tokens,
  TokenScope::allowsRead)` + `respondQuery`.
- Migration `V11` (next free after `V10__retention.sql`): `ALTER TABLE task_executions ADD
  COLUMN execution_reasons text[]` — additive, nullable, **no backfill**; pre-V11 rows read
  NULL → `UNCLASSIFIED`. Column inherits plan-[042] raw-row retention (`rawCutoffMs`), so the
  taxonomy window is bounded exactly like project-cost/bottlenecks.
- The classifier is reusable substrate a future F10 "warnings" family can share (avoids
  duplicating reason pattern-matching) — mentioned, not scoped.

## Test strategy

- **Unit (`RerunCauseClassifierTest`):** pin each bucket against real Gradle 9.6.1 reason
  strings; assert unknown/empty/null → `UNCLASSIFIED`; assert a multi-reason task contributes
  its duration to every distinct bucket it touches (overlap, non-summing shares); assert an
  all-capped build reads as `UNCLASSIFIED` coverage, not a spurious real bucket.
- **Unit (rollup calculator):** cascade-vs-contained threshold; build-logic-storm candidate
  fires only above its share threshold; type-null (IP) rows still classify (reason taxonomy
  is execution-time; only a would-be type grouping degrades to module/name).
- **Store parity (`RerunCauseStoresIntegrationTest`, Testcontainers, beside
  `RollupStoresIntegrationTest`/`BottleneckStoresIntegrationTest`):** identical builds into
  both stores yield byte-for-byte-equal `rerunCauses` output (freshly inserted rows populate
  the column in both). One case with a NULL-reason (pre-V11-style) row confirms it degrades to
  `UNCLASSIFIED` without error.
- **Route (`RerunCauseRoutesTest`):** read-scope + tenant isolation (a token for tenant A
  never sees tenant B's taxonomy); `days` clamping; empty-project honest-empty response.
- **Contract (`OpenApiContractTest`):** stays green — new route documented in
  `docs/api/openapi.yaml`.

## Risks

- **Overlapping-share misread (named):** because shares don't sum to 100 %, a naive reader
  could treat them as a partition. Mitigation: label the field "task-hours touched by cause"
  in the response docs + openapi description; the calculator never normalizes to 100 %.
- **Version-brittle reason strings:** reasons are human-readable Gradle output, **not an API
  contract** (F11 narrowing). Mitigation: version-tolerant substrings + `UNCLASSIFIED`
  fallback; unit tests document the pinned 9.6.1 phrasings but a drift only inflates
  `UNCLASSIFIED`, never crashes or misattributes.
- **PayloadCapper truncation (named):** capped builds lose reasons → their tasks land in
  `UNCLASSIFIED`. Mitigation: expose the unclassified share explicitly so a high value reads
  as "data opaque here," not "no rerun causes."
- **Additive DB schema:** `execution_reasons` is a new nullable column, no golden/payload
  change (`schemaVersion` = 1), no backfill; pre-V11 rows degrade to `UNCLASSIFIED`. Both
  stores keep parity on fresh data.
- **Privacy (§3.7):** reasons are already scrubbed pre-storage (`PayloadScrubber.kt:39-44` —
  paths relativized, secret-shaped tokens redacted). The rollup emits **enum buckets +
  counts/ms + Gradle module paths + task-type FQCNs only** — never raw reason text (even
  scrubbed) leaves the server. No new PII surface.
- **Multi-tenancy:** the route is token + tenant-scoped exactly like every `/rollups/*`
  endpoint (`authenticatedProject(..., TokenScope::allowsRead)`); the new column is
  `project_id`-scoped in every query — no cross-tenant read path.
- **Isolated projects:** `TaskExecution.type` is null under IP (plan [016] dictionary empty),
  so any type-keyed view degrades to module/name; the reason taxonomy is execution-time and
  **unaffected** — bucket coverage still computes under IP.
- **Never-fail / CC:** no plugin or configuration-cache surface is touched (server-only). The
  sole robustness rule is that the classifier tolerates empty/null/unrecognized reasons
  without throwing on a malformed payload.
- **ABI cascade confound:** per-module upstream attribution is deferred (Out) precisely
  because reason co-occurrence without dependency edges is correlation, not causation; only
  the build-level cascade signal ships now.

## Exit criteria

- `GET /v1/rollups/rerun-causes` returns per-bucket coverage shares + counts + a build-level
  cascade rate + a build-logic-storm candidate, read-scoped and tenant-scoped; `UNCLASSIFIED`
  share is a first-class field.
- `RerunCauseClassifier` buckets the pinned Gradle 9.6.1 reason strings; unknown/empty/capped
  reasons degrade to `UNCLASSIFIED` without error.
- In-memory and Postgres `rerunCauses` agree byte-for-byte (Testcontainers parity), including
  the NULL-reason degradation case.
- `V11` migration is additive; payload `schemaVersion` unchanged; no golden file edited.
- `docs/api/openapi.yaml` updated; `OpenApiContractTest` green; `./gradlew build` green.
