# 077 — Server-side bounds for hot-column payload strings

## Source

§3.2 security review of plan 076 (follow-up finding): payload-derived strings other than
`projectKey` are written unclamped into btree-indexed columns; a hostile ingest-token
holder can exceed the ~2704-byte btree tuple limit → SQLSTATE 54000. Plan 076 already
contains the blast radius (54xxx → permanent 400, no spool loop) and established the
store-boundary clamp pattern (`boundProjectKey`, `MAX_PROJECT_KEY_CHARS`); this plan
closes the class.

## Scope

**Audited inventory (what actually feeds a btree index):**

| Field (payload) | Column / index | Action |
|---|---|---|
| `buildId` | `builds UNIQUE(project_id, build_id)`, `test_class_outcomes` PK, `apk_sizes` FK | clamp 256 |
| `ci.provider`, `ci.runId` | `builds_ci_run_idx` (V3) | clamp 256 |
| `ci.pipelineName` | `builds_baseline_idx` (V3) | clamp 256 |
| `tasks[].module`, `tasks[].type` | `task_exec_project_module_idx` / `_type_idx` (V4) | clamp 256 / 512 |
| `tests[].module`, class `className` | `test_class_outcomes` PK + crossrun idx (V7) | clamp 256 / 512 |
| `vcs.sha` | `test_class_outcomes_crossrun` (V7) | clamp 64 |
| `vcs.branch` | **no index today** | clamp 256 anyway (defense-in-depth; future index) |
| `requestedTasks` → `requested_tasks_sig` | `builds_baseline_idx` | **none** — already md5 hex (32 chars) |
| `apk_sizes` module/variant/type | unindexed (only `(project_id, started_at)`) | none beyond `buildId` above |
| `POST /v1/metrics` `correlation.{buildId,provider,runId}` | `custom_metrics_uniq` btree | **route validation** 422 (scope/name/text/unit already capped; correlation is the gap) |

**Out:** `buildhound-commons` / `PayloadCapper` / golden files (server storage concern,
same rationale as 076); plugin changes; **no DB migration** — oversized values cannot
exist in these columns: the indexes have existed since each column's creation, so any
oversized write would already have failed at insert time.

## Design

- **One helper, one call path.** Generalize 076's `boundProjectKey` into
  `boundForStorage(payload: BuildPayload): BuildPayload` in `BuildStore.kt` (absorbs the
  projectKey clamp; `boundProjectKey` goes away). Constants next to it:
  `MAX_HOT_STRING_CHARS = 256` (buildId, branch, provider, runId, pipelineName, modules),
  `MAX_FQCN_CHARS = 512` (task `type`, test `className`), `MAX_SHA_CHARS = 64`. Shared
  private `String.truncateSafely(max)` carrying 076's surrogate-pair guard. Copies only
  when something exceeds a bound — the compliant-plugin path allocates nothing new.
- **Applied**: (1) first line of both stores' `save()` (replacing the `boundProjectKey`
  call — parity by construction, covers direct-save writers incl. `InterruptedBuild`);
  (2) once at ingest after `PayloadCapper.cap`: `val bounded = boundForStorage(capped)`
  passed to `save`, `evaluator.evaluate`, `flakyAlerter.evaluate`, `enrichment.submit` —
  so baseline keys (`pipelineName`) and correlation keys are computed from the same
  clamped instance that was stored. Idempotent, so the double application is harmless.
- **`buildId` clamp semantics:** two distinct hostile ids sharing a 256-char prefix
  collide post-clamp (idempotent dedupe merges them). Accepted — deterministic, reachable
  only by a hostile client, and strictly better than today's 54000.
- **Residual (documented, accepted):** clamps are UTF-16-char-based like 076. A payload
  stuffing several 4-byte-char fields of a multi-column index (V7 PK worst case) can in
  theory still exceed the tuple limit; the 54xxx → 400 classification from 076 is the
  designed backstop (payload dropped, never spooled). Byte-accurate clamping is not worth
  the complexity for a hostile-only residue.
- **Routes:** `validateMetric` gains length checks on `correlation.buildId/provider/runId`
  (≤ `MAX_HOT_STRING_CHARS`, 422 — matches sibling messages). The `/v1/builds` `branch`
  filter param gets the same ≤256 → 400 validation as `projectKey` (also bounds the
  Azure hook's `runId` correlation path if unvalidated — implementer greps
  `resolveBuildId` call sites). openapi.yaml: note the caps in the affected descriptions
  (no path/method changes).

## Test strategy

- Route test: one ingest POST with 3000-char values in **every** clamped field → 202;
  subsequent reads (`/v1/builds` list, detail, `/v1/flaky`, one rollup) return the clamped
  values and don't error. Metrics POST with 3000-char `provider` → 422; oversized
  `branch` filter param → 400.
- Testcontainers: save the same hostile payload against real Postgres — no
  `SQLException` (proves every index accepted it, incl. the V7 PK), hot columns clamped,
  in-memory ↔ Postgres parity for `flaky` + `taskDuration` over the clamped fields.
- Evaluator consistency: an oversized-`pipelineName` build still evaluates (no crash;
  baseline key = clamped value).
- Existing suites unchanged — compliant payloads are untouched by construction.

## Risks

- Behavior changes only for out-of-bounds values (hostile/buggy clients); plugin-side
  values (root project names, task paths, FQCNs, git refs) sit far below every bound.
- Baseline-key continuity: historical rows can't hold oversized keys (see "no migration"
  argument), so clamped new keys never mismatch stored old ones.
- Parity drift — single helper used by both stores; integration tests assert it.
- CC/plugin: untouched (server only).

## Exit criteria

- The hostile-payload ingest test passes against real Postgres (202 + clamped storage,
  no 54000).
- `./gradlew :buildhound-server:test` green incl. Testcontainers; full `./gradlew build`
  green.
- No commons/golden/migration changes in the diff.
