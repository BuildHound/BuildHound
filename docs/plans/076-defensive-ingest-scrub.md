# 076 — Defensive server-side scrub at ingest

## Source

- **Plan [007](implemented/007-scrubber.md)'s own "Out" scope** deferred this explicitly:
  *"server-side scrubbing wiring"* was named and punted the day `PayloadScrubber` shipped —
  this plan closes that gap.
- **`PayloadScrubber.kt:13` KDoc**: *"KMP-pure so the server can run it as a defensive second
  pass"* — the class was written to support this from day one; nothing server-side has ever
  called it over a whole payload.
- **Two §3.2 review findings** converged on the same gap from different angles: plan
  [060](060-build-analyzer-warning-taxonomy-rules.md)'s security/privacy review (commit
  `8916dcf`) added a *response-time* `PayloadScrubber.scrubText` guard to `Warnings.kt`'s
  echoed `evidenceReason`, with the reviewer noting *"the server does not re-scrub stored
  payloads, but this route echoes the string back out"* — the same reasoning applies to
  **every** free-text field `GET /v1/builds/{buildId}` (`Routes.kt:~362`) echoes verbatim,
  not just `evidenceReason`. Plan [061](061-rerun-cause-taxonomy.md)'s review reached the
  identical conclusion from the `RerunCauseRollupCalculator` angle (reasons are "already
  scrubbed pre-storage" — an assumption that only holds for a *compliant* client).
- **`PayloadCapper.kt:42-45` KDoc**: documents the plugin order (*"after the scrubber, so
  secret patterns see whole values"*) and that the server already re-caps defensively at
  ingest — the capper half of "defensive second pass" is live; the scrubber half is not.

## Scope

**In:** one call to `PayloadScrubber.scrub(payload, emptyList())` in the `buildhound-server`
ingest path (`Routes.kt`, `POST /v1/builds`), before the payload is capped and stored — so a
non-compliant or buggy client's unscrubbed absolute paths / secret-shaped strings never reach
`store.save`, and therefore never reach any read (`GET /v1/builds/{buildId}`, rollups,
`Warnings.kt`'s `evidenceReason`, etc.).

**Out (explicitly deferred):**

- **Retroactive scrub of already-stored payloads.** Every row ingested before this plan lands
  keeps whatever a non-compliant client sent. A one-off backfill job (read → scrub → rewrite)
  is a natural follow-up but is its own migration-shaped risk (mutating historical rows,
  `messageHash`/idempotency-key implications) — not bundled here. This is why the
  `Warnings.kt` response-time guard from `8916dcf` is **kept**, not removed (see Design).
- **Plugin-side changes.** `PayloadAssembler`/`TelemetryFinalizerAction` are untouched;
  client-side scrub-then-cap ordering and behavior are unaffected.
- **Any new schema field, golden file, or DTO.** `PayloadScrubber.scrub` returns the same
  `BuildPayload` shape; nothing new is serialized.

## Design

**Insertion point — scrub before cap (mirrors the plugin's own invariant).** `Routes.kt:102`
today reads `val capped = PayloadCapper.cap(payload)`. This becomes
`val capped = PayloadCapper.cap(PayloadScrubber.scrub(payload, emptyList()))`. Two reasons,
in order of weight:

1. **Correctness — the codebase already has a documented invariant for this exact ordering,
   and reversing it breaks it.** `PayloadCapper.kt`'s KDoc and `architecture.md:395` both
   state the plugin caps *"after the scrubber, so secret patterns see whole values."*
   `PayloadCapper.cap` truncates free-text fields mid-string (`maxReasonChars=500`,
   `MAX_FAILURE_MESSAGE_CHARS=512`, …). Several of `PayloadScrubber`'s secret rules require
   the **whole** value to recognize the shape — the JWT rule needs all three dot-segments,
   the AWS key rule needs the full 20 chars, the long-blob rule needs 32+ contiguous chars —
   so a cap-then-scrub order risks truncating a secret exactly at a char boundary and handing
   the scrubber an unrecognizable fragment that survives unredacted. `PayloadScrubber`'s own
   class KDoc states the governing bias: *"over-redaction of free text is acceptable;
   under-redaction is not."* Cap-then-scrub can under-redact; scrub-then-cap cannot (the
   scrubber always sees complete values). Doing the opposite of the plugin's own documented
   order server-side would be exactly the kind of inconsistency the §3.2 review routinely
   flags.
2. **Minimal, surgical diff.** Because `capped` is the value already threaded through
   `store.save`, `evaluator.evaluate`, `flakyAlerter.evaluate`, and `enrichment.submit`,
   wrapping it at one call site means every downstream consumer inherits the scrubbed payload
   for free — no other line in `Routes.kt` changes. (Checked: none of those consumers depends
   on unscrubbed free text — `flakyAlerter`/`evaluator` key on class/method names and
   `messageHash`, both declared fields the scrubber never touches; `enrichment.submit` reads
   `ci.buildUrl`, which the scrubber does not touch either.)

**Latency risk, named rather than dismissed.** Scrub-before-cap means the scrubber runs over
the *pre-cap* (larger, possibly adversarial) payload rather than the already-shrunk one. The
mitigating fact: the input is already bounded by `receiveBounded` (`MAX_COMPRESSED_BYTES` = 32
MiB / `MAX_DECOMPRESSED_BYTES` = 64 MiB, `Routes.kt:47-48` — the "outer wall" per
`architecture.md:395`) regardless of scrub/cap order, so the worst case is a fixed, already-
accepted ceiling, not an unbounded one. See Test strategy for the concrete number.

**Fields covered / idempotency.** `PayloadScrubber.scrub` already covers every free-text
surface (`executionReasons`, `nonCacheableReason`, Kotlin `taskPath`/`nonIncrementalReasons`/
`compilerTimesMs` keys, test `message`, `benchmark.{scenario,isolationMode,seedRef}`,
`failure.{message,stackTrace}`, fingerprint key names) — nothing new to add. Idempotency is
required (a compliant client's already-scrubbed payload must come out byte-identical from a
second pass with empty roots) and holds by construction: every scrub output shape
(`<redacted>`, `<path>`, `.`, or a relativized relative path with no leading `/`) fails to
match any of the input-matching regexes on a second pass — none has a leading `/` or `\`
(the path regexes require one), and `<redacted>`/`<path>` are far below the 32-char blob floor
and match no secret-keyword shape. No idempotency test exists in `PayloadScrubberTest.kt`
today — this plan adds one (see Test strategy).

**`Warnings.kt`'s response-time `scrubText` (commit `8916dcf`) is kept, not removed.** It is
now redundant *only* for freshly-ingested (post-076) payloads — but retroactive scrub is
explicitly out of scope (above), so every pre-076 stored row still echoes unscrubbed evidence
through that route until a future backfill lands. The response-time guard is the only
protection for that field on old data, and — per the idempotency property above — it is a
true no-op on new (already server-scrubbed) data, so keeping it costs nothing and covers the
gap this plan deliberately leaves open.

## Test strategy

- **Unit (`PayloadScrubberTest`, commons):** already covers regex correctness; no new field
  coverage needed here (see Fields covered above).
- **Unit — idempotency (new, `PayloadScrubberTest`):** scrub a payload **with real project
  roots** (client-side simulation — in-project paths already relativized to e.g. `src/A.kt`,
  secrets already `<redacted>`), then scrub that output again with `emptyList()` (the server's
  ingest call) — assert byte-identical (`equals` on `BuildPayload`). This is the load-bearing
  case, not "scrub-empty-roots twice" (which passes trivially and proves nothing about the
  real client→server flow).
- **Unit (`buildhound-server`, e.g. `IngestScrubTest`):** a deliberately unscrubbed payload
  (absolute path in `executionReasons`, a `token=...` shaped string in `failure.message`) run
  through the same `scrub → cap` composition used in `Routes.kt` — assert the composed result
  carries no absolute path / secret-shaped value, matching what `PayloadScrubberTest` already
  proves for the scrubber alone; this test is about the **composition**, not the regexes.
- **Integration (ingest route, `testApplication` + `InMemoryBuildStore`):** POST a payload with
  an unscrubbed absolute path in a reason string; `GET /v1/builds/{buildId}` on the same build
  returns the scrubbed (relativized/redacted) string, never the raw one — proving the fix at
  the observable boundary the two §3.2 reviews actually flagged.
- **Micro-benchmark note (no perf harness):** a single test builds a capped-size synthetic
  payload (20,000 tasks, each with a handful of execution reasons — the `maxTasks` ceiling from
  `PayloadCaps.DEFAULT`) and logs `System.currentTimeMillis()` before/after
  `PayloadScrubber.scrub`, asserting only a loose upper bound (e.g. `< 2000ms`) so the test
  isn't flaky — the logged number is the artifact, reviewed by eye once, not re-derived per
  run.

## Risks

- **Ingest latency.** Addressed above: bounded by the existing `receiveBounded` ceiling
  regardless of scrub/cap order; the micro-benchmark test pins a concrete number for the
  realistic capped-payload case rather than leaving this as an unverified assumption.
- **Double-scrub / client-server scrubber version drift.** Both plugin and server run the
  *same* `buildhound-commons` `PayloadScrubber` (KMP-pure, one artifact) — there is no
  separate server copy to drift, and both sides are versioned together in this monorepo. The
  only drift risk is a client running an **older plugin version** against a **newer server**
  with a stricter scrubber; that is strictly safer (the server catches what an older client
  missed), never worse. Idempotency (above) guarantees a compliant client's payload is
  unaffected either way.
- **False-positive redaction of legitimate data.** Pre-existing risk of the scrubber itself
  (the 32-char blob floor, space-tailed out-of-project paths — documented accepted
  limitations in `PayloadScrubber.kt`'s class KDoc); this plan does not change the scrubber's
  rules, only where it runs, so no new false-positive surface is introduced. The long-blob
  rule already requires a digit specifically so routine camelCase task/identifier names
  survive (`PayloadScrubberTest.long_camel_case_identifiers_survive`) — unaffected by this
  plan.
- **Forward-only.** Restated from Scope: `GET /v1/builds/{buildId}` still echoes unscrubbed
  free text for every build ingested before this lands. This is a known, accepted gap — the
  retroactive-scrub follow-up is the closure path, not this plan.

## Exit criteria

- `Routes.kt`'s ingest path scrubs before capping (`PayloadCapper.cap(PayloadScrubber.scrub(payload,
  emptyList()))`); every downstream ingest consumer (`store.save`, `evaluator`, `flakyAlerter`,
  `enrichment`) sees the scrubbed payload.
- A payload with an unscrubbed absolute path / secret-shaped string, POSTed by a non-compliant
  client, is stored scrubbed and reads back scrubbed via `GET /v1/builds/{buildId}` — pinned by
  an integration test.
- A compliant (already client-scrubbed) payload is byte-identical after the server's pass —
  pinned by the new idempotency unit test.
- The micro-benchmark test logs a concrete per-ingest scrub cost on a 20,000-task payload and
  stays under its loose bound.
- `Warnings.kt`'s response-time `scrubText` guard (`8916dcf`) is left in place, with its KDoc
  updated to note it is now belt-and-braces for post-076 data and the sole protection for
  pre-076 stored rows.
- `./gradlew build` green; no commons/payload/golden change.
