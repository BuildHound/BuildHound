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
non-compliant or buggy client's unscrubbed absolute paths / secret-shaped strings in the fields
the scrubber actually covers never reach `store.save`, and therefore never reach any read
(`GET /v1/builds/{buildId}`, rollups, `Warnings.kt`'s `evidenceReason`, etc.).

**Scope of "never reach storage" — enumerated, not blanket (076 review fix, MED).** The line
above must not be read as "no unscrubbed free text of any kind ever reaches storage." It holds
only for the specific fields `PayloadScrubber.scrub` touches: `executionReasons`,
`nonCacheableReason`, `kotlin.{taskPath,nonIncrementalReasons,compilerTimesMs keys}`,
`tests[].{failedOrRetried,allCases}[].message`, `benchmark.{scenario,isolationMode,seedRef}`,
`failure.{message,stackTrace}`, and fingerprint key names — see `PayloadScrubber.kt`'s class
KDoc for the authoritative field list. By spec-§3.7 design, the scrubber does **not** touch (and
this plan does not extend it to) tag values, metric `text`/`value`, `ci.*`, `vcs.*`, `links`,
`requestedTasks`, `environment.*`, or the addon-owned `extensions` blob — those fields are
declared/structured data per the spec, not free text, and a value placed in one of them by a
misconfigured or hostile client reaches storage and every read path unscrubbed, exactly as
before this plan. Do not extend the scrubber to those fields as a "fix" for this note; if a real
need for it emerges, it is its own scoped plan (spec §3.7's field-scoping is a deliberate
boundary, not an oversight).

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
  **Coverage caveat (076 review fix, LOW):** the micro-benchmark only exercises the uniform
  20,000-task shape (many small execution-reason strings) — it says nothing about an
  adversarial *single*-field shape (one pathological string in one field). That gap is what
  the ReDoS finding below actually hit, and is now covered by the 8192-char input clamp
  (`PayloadScrubber.MAX_SCRUB_INPUT_CHARS`) rather than by the benchmark; see
  `PayloadScrubberTest`'s `redos_guard_*` cases for the adversarial-shape coverage the
  micro-benchmark itself does not provide.
- **ReDoS via unbounded scrub input (076 review fix, HIGH).** Wiring `PayloadScrubber.scrubText`
  into server ingest made it reachable on unbounded, attacker-controlled, gzip-amplified text
  for the first time — two of its regexes (`longBlob`, `secretPair`) are empirically O(n²) on a
  long run of matching-shape characters that never resolves the pattern (measured on JVM 21: a
  pure `/` run costs ~23s at 65536 chars; a pure word-character run, `secretPair`'s worse case,
  costs far longer). Fixed with a hard 8192-char clamp applied inside `scrubText` before any
  regex runs (both plugin- and server-side, since it is one shared KMP function) — see
  `PayloadScrubber.kt`'s `MAX_SCRUB_INPUT_CHARS` KDoc for the full measurement table and why
  the originally-proposed 65536-char clamp was rejected (it does not bound cost). This is a
  mitigation, not a fix for the regexes' underlying O(n²) shape — both stay quadratic, just
  capped to a fixed, small ceiling regardless of caller-supplied input size. A future rewrite
  of `longBlob`/`secretPair` to a genuinely linear form (e.g. character-class scanning instead
  of backtracking regex) would remove the mitigation's need but is out of scope here.
- **Scrub-error handling is `Exception`-scoped, not `Throwable`-scoped (076 review fix, MED).**
  The scrub-error fallback (see Divergences) deliberately catches `Exception` only, not
  `Throwable`/`Error`. Trade-off, stated explicitly: a `StackOverflowError` or
  `OutOfMemoryError` mid-scrub now propagates and fails the ingest request loudly (a 500,
  through Ktor's default handling) rather than being silently swallowed into storing the
  *unscrubbed* payload the old `runCatching` (which catches `Throwable`) would have done. This
  is judged the safer failure mode for a privacy-scrubbing defense: a loud failure on a
  near-unreachable pathological case beats a silent one that defeats the scrub entirely for
  that request. `Exception`-level fail-open (the documented "never fail ingest" bias) is
  otherwise unchanged — a normal `RuntimeException` from the scrubber still degrades to
  storing capped-but-unscrubbed with a warn log, exactly as before.
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
  stays under its loose bound. **Measured:** ~1835-1883ms across three cold-JVM runs on the
  reference dev machine (`IngestScrubTest.scrub cost on a 20,000-task payload logs under a loose
  bound`); the assertion bound is `< 5000ms` (widened from this doc's original `< 2000ms`
  example — the measured number left only ~7% headroom under 2000ms, too tight to avoid flaking
  on a slower CI runner or a colder JIT; see Divergences).
- `Warnings.kt`'s response-time `scrubText` guard (`8916dcf`) is left in place, with its KDoc
  updated to note it is now belt-and-braces for post-076 data and the sole protection for
  pre-076 stored rows.
- `./gradlew build` green; no commons/payload/golden change.

## Divergences (implementation notes)

- **Idempotency test relocated out of `PayloadScrubberTest` (commons).** This plan specified the
  idempotency unit test as a new case in `buildhound-commons`' `PayloadScrubberTest.kt`. The
  implementation session ran concurrently with another agent holding uncommitted edits across
  `buildhound-commons`/`buildhound-gradle-plugin`/`buildhound-ci-assets` and was under a hard
  constraint not to touch those modules. `PayloadScrubber` is KMP-pure and already a compile
  dependency of `buildhound-server` (`Warnings.kt` already imports it), so the identical case —
  scrub with a real root, then scrub the output again with `emptyList()`, assert byte-identical —
  is implemented instead in `buildhound-server`'s `IngestScrubTest` (`a client-scrubbed payload is
  byte-identical after the server's empty-root pass`). No scrubber behavior changed; only where
  the proof lives.
  - [x] Follow-up: port/duplicate this case into `PayloadScrubberTest` once commons is free, per
    the "one committed plan per feature" discipline this repo otherwise favors having the test
    live alongside the code it most directly documents. **Done** in the 076 review-fix pass —
    `PayloadScrubberTest.a_client_scrubbed_payload_is_byte_identical_after_a_second_pass_with_empty_roots`
    (commons is free of the concurrent-edit constraint that originally blocked this). The
    `IngestScrubTest` copy is left in place too — it also exercises the real `Routes.kt`
    composition end to end, which the commons-only case does not.
- **Scrub-error handling (plan was silent).** The plan's Design section does not say what happens
  if `PayloadScrubber.scrub` throws unexpectedly at ingest. Implemented as: a `try`/`catch (e:
  Exception)` around the scrub call, falling back to the raw (unscrubbed) payload — which still
  goes through `PayloadCapper.cap` and storage — with a `warn` log naming the project and
  exception type. This mirrors the plugin-side "never fail a build" bias (`CLAUDE.md`) applied to
  the server's own "never fail ingest" equivalent, and is a pure defense-in-depth measure:
  `PayloadScrubber` is pure functional code with no I/O, so this path is not expected to be
  reachable in practice. **Updated in the 076 review-fix pass (MED):** originally implemented
  with `runCatching { … }`, which catches `Throwable` (including `Error`); narrowed to
  `catch (e: Exception)` so a `StackOverflowError`/`OutOfMemoryError` propagates instead of being
  swallowed into storing unscrubbed data — see the Risks section's dedicated bullet for the
  trade-off.
- **Micro-benchmark bound widened from the doc's `< 2000ms` example to `< 5000ms`.** See Exit
  criteria above for the measured number and rationale.
