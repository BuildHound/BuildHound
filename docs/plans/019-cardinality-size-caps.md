# Plan 019 — Tag/value cardinality + payload size caps in assembler and scrubber

**Status: planned — roadmap phase 2a** · 2026-07-03

## 1. Source

- [Roadmap phase 2a](../build-telemetry-roadmap.md): "tag/value cardinality + size caps in
  assembler/scrubber", and the cross-phase guardrail "cardinality and payload-size budgets
  enforced in code, not docs".
- [Spec §3.9](../build-telemetry-spec.md): the locked overflow strategy — "hard cap with
  overflow strategy (drop per-task execution reasons first, then truncate task array with
  summary counts — never drop the build envelope)"; [spec §5](../build-telemetry-spec.md)
  caps only the metric CLI today (100 measures/run, key+value ≤ 300 chars); [spec §8](../build-telemetry-spec.md)
  promises "payload schema validated + size-capped".
- Research: Talaiot ships `generateBuildId = false` by default because unbounded cardinality
  wrecked its InfluxDB backends ([research/repos/Talaiot.md](../research/repos/Talaiot.md)) —
  the scar this guardrail exists to avoid. [research/comparison-to-spec.md §4](../research/comparison-to-spec.md)
  item 7 names the gap directly: "spec §3.4's `tags.put(...)`/`value(...)` DSL has no caps at
  all. Mirror the Datadog-style limits on the plugin side and enforce them in the
  scrubber/assembler." [research/plugin-ecosystem-gap-analysis.md](../research/plugin-ecosystem-gap-analysis.md)
  independently demands bounded volume (Talaiot's min-duration filters, §1 context).

## 2. Scope

**In:**

- A single source of truth for all payload budgets in `buildhound-commons`
  (`PayloadCaps` limits + `PayloadCapper` pure enforcement), used by the plugin at assembly
  and by the server as a defensive clamp at ingest.
- Cardinality caps on the `tags` and `values` maps (entry count, key length, value length).
- Free-text caps on `executionReasons` (count per task, length per reason).
- Task-array cap and a staged byte-size budget implementing spec §3.9's overflow order.
- Additive schema field `caps: CapsSummary?` recording exactly what was dropped/truncated
  ("truncate + count what was dropped"), plus a new golden file.
- One warn log line (counts only) on the plugin side; warn + clamp on the server side.

**Out:** the `upload { maxPayloadMb }` / cap-override DSL knobs (arrive additively with the
upload-DSL work, plan 027 territory) · metric CLI caps at `POST /v1/metrics` (plan 025) ·
rendering caps info in dashboard/HTML views (plans 017/018 own those surfaces; the `caps`
field is available to them) · capping fields that do not exist yet — fingerprint maps
(plan 022), test arrays (plan 024), env-breadth fields (plan 027) must route through
`PayloadCapper` when they land, and `nonCacheableReason` joins the free-text caps when
plan 016 populates it.

## 3. Design

**Current behavior (verified in source).** `BuildHoundExtension.tags` is an unbounded
`MapProperty<String, String>` (BuildHoundExtension.kt:20) wired verbatim into the Flow
parameters (BuildHoundSettingsPlugin.kt:81), through the finalizer
(TelemetryFinalizerAction.kt:122), into the payload untouched (PayloadAssembler.kt:70).
`BuildPayload.values` exists in schema v1 (BuildPayload.kt:27) but `assemble()` has no
values parameter — plugin-side it is always empty, yet the server ingests it from any
client. `TaskEventCollector.onFinish` appends every task with verbatim, unbounded
`executionReasons` (TaskEventCollector.kt:28–36). `PayloadScrubber` rewrites free text but
never bounds sizes (PayloadScrubber.kt:28–34). The uploader gzips whatever it is given with
no size check (PayloadUploader.kt:54); worse, a spooled payload over 8 MiB gzip is silently
deleted on the next drain (PayloadUploader.kt:75–78, `MAX_SPOOL_FILE_BYTES`
PayloadUploader.kt:144) — today an oversized payload doesn't degrade, it vanishes. The
server enforces only byte ceilings (32 MiB compressed / 64 MiB decompressed, Routes.kt:34–35)
and stores the whole document as jsonb (PostgresStores.kt:61–62): a 64 MB tags map would be
accepted and persisted as-is.

**New commons pieces** (next to the scrubber, `dev.buildhound.commons.payload`):

- `PayloadCaps(maxTags, maxValues, maxKeyChars, maxValueChars, maxReasonsPerTask,
  maxReasonChars, maxTasks, maxPayloadBytes)` with `DEFAULT` = (100, 100, 100, 300, 10, 500,
  20 000, 20 MiB). Entry counts and key/value lengths mirror the spec-§5 metric-CLI numbers;
  20 MiB uncompressed JSON gzips comfortably under the 8 MiB spool ceiling. Limits are a
  parameter (tests use tiny values); the defaults are the only production configuration.
- `PayloadCapper.cap(payload, caps = DEFAULT): BuildPayload` — pure, deterministic,
  idempotent. Order:
  1. **Maps** (`tags`, `values`): drop entries with keys over `maxKeyChars` (truncating a
     key could collide with another key), truncate values to `maxValueChars`, keep the
     first `maxTags`/`maxValues` entries in map order, count the rest.
  2. **Reasons**: per task keep the first `maxReasonsPerTask`, truncate each to
     `maxReasonChars`.
  3. **Task array**: over `maxTasks`, retain all FAILED tasks then the longest by
     `durationMs` (ties by path); summarize the dropped remainder as per-outcome counts.
  4. **Byte budget** (spec §3.9 stages): encode with `BuildHoundJson.payload`; if over
     `maxPayloadBytes`, drop all remaining `executionReasons` and re-encode; if still over,
     iteratively halve the retained task set (same FAILED-first ranking) until it fits.
     The build envelope always survives.
- Additive schema field on `BuildPayload`: `caps: CapsSummary? = null` with
  `droppedTags`, `droppedValues`, `truncatedValues`, `droppedExecutionReasons`,
  `truncatedExecutionReasons`, `droppedTasks`, `droppedTaskOutcomes: Map<String, Int>`.
  Non-null exactly when something was capped; `explicitNulls = false` (BuildHoundJson.kt)
  keeps existing payload bytes and golden files unchanged. Re-capping merges counts
  (server clamp adds to the plugin's summary instead of overwriting it).

**Plugin flow.** `PayloadAssembler.assemble` gains a final step: derived metrics stay
computed over the **full** task list first (PayloadAssembler.kt:72 — hit rate and
utilization must not shift when rows are dropped), then scrub (PayloadAssembler.kt:74–75 —
scrubbing before truncation so secret patterns see whole values, never a sliced token),
then `PayloadCapper.cap`. One capped payload everywhere — local file, HTML artifact, upload
— same principle as the scrubber. `TelemetryFinalizerAction` logs one warn line with the
counts when `payload.caps != null` (counts only; keys and values never reach logs).

**Server mirror.** In `ingestRoutes` after decode and the schemaVersion check
(Routes.kt:64–74), run `PayloadCapper.cap` on the decoded payload before `store.save`. A
compliant plugin makes this a no-op; a hostile or buggy client gets clamped, not rejected —
the telemetry survives, bounded, and a warn logs the counts. The existing byte ceilings
remain the outer wall; the capper bounds what actually reaches jsonb.

## 4. Implementation steps

1. `buildhound-commons`: add `CapsSummary` and the `caps` field to
   `src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt` (additive,
   default null).
2. `buildhound-commons`: create
   `src/commonMain/kotlin/dev/buildhound/commons/payload/PayloadCapper.kt` containing
   `PayloadCaps` (+ `DEFAULT`) and `PayloadCapper` per §3, KDoc'd with the drop rules and
   the merge semantics.
3. `buildhound-commons`: `PayloadCapperTest` in `commonTest` (cases in §5).
4. `buildhound-commons`: add golden file
   `src/jvmTest/resources/golden/build-payload-v1-caps.json` (a v1 payload with a populated
   `caps` block) and a new `GoldenPayloadTest` case; the existing
   `build-payload-v1.json` is not touched (GoldenPayloadTest.kt:8–10).
5. `buildhound-gradle-plugin`: call `PayloadCapper.cap` as the last step of
   `PayloadAssembler.assemble` (after `PayloadScrubber.scrub`, PayloadAssembler.kt:74–75);
   extend `PayloadAssemblerTest`.
6. `buildhound-gradle-plugin`: warn log in `TelemetryFinalizerAction.execute` when
   `payload.caps != null`, counts only, inside the existing `runCatching`
   (TelemetryFinalizerAction.kt:94).
7. `buildhound-gradle-plugin` functionalTest: oversized-tag scenario (§5) in
   `BuildHoundSettingsPluginFunctionalTest`.
8. `buildhound-server`: clamp in `ingestRoutes` between payload decode and `store.save`
   (Routes.kt:64–83), warn on nonzero counts; extend `ApplicationTest`.
9. Docs, same PR: spec §4 documents the `caps` field; spec §3.4 notes the tags/values caps;
   spec §8's "size-capped" gains the concrete budgets. `docs/architecture.md` §6 gets a
   bullet (budgets live in commons, enforced at assembly and defensively at ingest) and a
   decision-log row dated 2026-07-03.
10. If implementation diverges (e.g. different constants after measuring real payloads),
    update this plan in the same PR and say why.

## 5. Test strategy

- **commons unit (`PayloadCapperTest`,** tiny limits via the `caps` parameter**):** excess
  tags dropped and counted; over-long value truncated and counted; over-long key drops the
  entry; `values` map capped identically; reasons capped per task (count + length);
  task-array cap retains FAILED first then longest, `droppedTaskOutcomes` sums correctly;
  byte stage 1 drops reasons before any task; byte stage 2 halves tasks until fit, envelope
  intact; compliant payload returns the **same instance/equal object with `caps == null`**;
  idempotence (capping a capped payload changes nothing); count merging on re-cap.
- **Golden files:** new `build-payload-v1-caps.json` deserializes with expected counts;
  existing v1 golden and its lossless round-trip stay untouched.
- **Plugin unit (`PayloadAssemblerTest`):** assemble with an oversized tags map → capped
  payload with summary; derived metrics equal those computed from the full task list even
  when tasks were truncated; scrub-then-cap order pinned (a secret at the truncation
  boundary is redacted, not sliced).
- **TestKit functionalTest:** `tags.put("big", "x" * 400)` → build succeeds, payload file
  carries the 300-char value and a `caps` block, warn line present, HTML artifact embeds the
  same capped payload. Failure-injection (phase guardrail): pathological inputs (hundreds of
  tags, huge values) never fail the build — asserted via build success + payload presence.
- **Server (`ApplicationTest`, in-memory store):** POST a payload exceeding the map caps →
  202 Accepted, stored build (via GET) is clamped with merged counts; compliant payload
  stored byte-identical.

## 6. Risks

- **CC:** the capper is pure commons code running inside the Flow action at execution time —
  no providers, no file access, no new CC inputs. Isolated-projects behavior unchanged
  (nothing here touches the configuration phase).
- **Schema compatibility:** `caps` is additive with a null default; `ignoreUnknownKeys` on
  `BuildHoundJson` means old servers ignore it (BuildHoundJson.kt) — pinned by the existing
  contract tests plus the new golden file. Existing golden files are never edited.
- **Data loss by design:** builds beyond 20 000 tasks lose per-task rows. Mitigated:
  derived metrics computed pre-truncation, dropped rows summarized per outcome, FAILED tasks
  always retained, and the budget constants live in one place if the pilot proves them wrong.
- **Scrub/truncate interplay:** truncating before scrubbing could slice a secret so the
  pattern no longer matches — the order is fixed (scrub first) and pinned by a test.
- **Privacy/security:** warn logs carry counts only — never tag keys or values (a
  misconfigured build could put a secret in either). The server clamp is also a defensive
  privacy bound: a third-party client cannot persist megabytes of arbitrary text through
  `values`. No new data is collected; fields only shrink.
- **Server clamping vs. rejection:** clamping mutates what the client sent. Accepted — it
  matches "degrade gracefully, never lose the envelope", and idempotency keys on `buildId`,
  which the capper never touches.

## 7. Exit criteria

- `./gradlew build` green, including the new commons, plugin, functional, and server tests.
- A build configured with >100 tags or a 400-char tag value succeeds, produces a payload
  within every budget, records the drops in `caps`, and logs a single warn line.
- A synthetic payload larger than 20 MiB JSON degrades in spec-§3.9 order (reasons first,
  then tasks) and uploads/spools under the 8 MiB gzip spool ceiling instead of being
  silently deleted.
- `POST /v1/builds` with an over-cap payload returns 202 and stores a clamped document;
  a compliant payload is stored unchanged.
- Spec §3.4/§4/§8 and `docs/architecture.md` (incl. decision log) updated in the same PR;
  the roadmap guardrail "cardinality and payload-size budgets enforced in code" is
  factually true.

## 8. Divergences from the plan (recorded during implementation)

- **`nonCacheableReason` is now capped too.** Plan §2 deferred it "when plan 016 populates
  it"; 016 shipped first, so the capper truncates `nonCacheableReason` to `maxReasonChars`
  and `CapsSummary` gained a `truncatedNonCacheableReasons` counter (additive).
- **`truncatedValues` counts value truncations across *both* the `tags` and `values` maps.**
  The plan's `CapsSummary` field list has a single `truncatedValues` (no `truncatedTags`),
  so it is documented as "over-long map values (in tags or values) truncated". `droppedTags`
  and `droppedValues` stay per-map, as listed.
- **`PayloadAssembler.assemble` gained a `caps: PayloadCaps = DEFAULT` parameter.** The plan
  said "call `PayloadCapper.cap` as the last step"; the added parameter (defaulting to the
  production `DEFAULT`) lets the plugin unit test pin the derived-metrics-vs-truncation
  invariant with tiny caps without constructing 20 000 tasks. Production is unchanged.
- **Server clamp warns when `capped.caps != payload.caps`** — i.e. only when the ingest-side
  cap changed the summary this pass (a compliant or already-capped-and-still-compliant
  payload logs nothing). Counts only, never keys/values.
- **Known limitation surfaced by review: `CiInfo.attributes` (a client-supplied map) is not
  run through `capMap`.** Only `tags`/`values` are. The §3.9 byte budget still bounds the
  *total* payload (it halves the task array to offset non-task bloat), but if the envelope
  alone exceeds 20 MiB the capper cannot shrink it — absolute storage stays bounded by the
  outer 64 MiB decompressed ceiling. Per-field capping of env-breadth fields (incl.
  `attributes`) belongs to plan 027; routing them through `PayloadCapper` is already the
  stated forward contract (§2 Out).
- **Byte sizing strips the `caps` block** (review fix): `encodedSize` measures the payload
  without its own summary, so the recorded summary can never tip a re-cap back over budget —
  the byte path is now provably idempotent, pinned by a test.
