# Plan 020 — Doc repairs: spec upload text, Gradle-floor drift, plan 003 CC wording

**Status: planned — roadmap phase 2a** · 2026-07-03

## 1. Source

- Roadmap [phase 2a, "Doc repairs" bullet](../build-telemetry-roadmap.md): spec §3.9 rewritten to
  synchronous-with-spool reality; §1/§8 Gradle-floor drift fixed to 8.14; plan 003's CC-detection
  wording aligned with the shipped `DaemonState` observer.
- [research/README.md §5](../research/README.md) (2026-07-03 reconciliation): all three repairs
  confirmed against source; only these doc texts remain to fix.
- [research/comparison-to-spec.md](../research/comparison-to-spec.md) §4 item 6 (the §3.9
  background-thread text is the documented Talaiot `publishOnNewThread` trap), item 12 (docs drift
  is the ecosystem's most universal defect), §2.1a and
  [research/repos/Talaiot.md](../research/repos/Talaiot.md) item 2 (`ConfigurationPhaseObserver`
  pattern — which the shipped code already implements).
- Divergences originally recorded in [plan 008](implemented/008-upload-spool.md) ("Divergences
  from spec, recorded up front") and [plan 003](implemented/003-environment-collector.md).

## 2. Scope

**In — three documentation edits, no code, no schema:**

1. Rewrite [spec §3.9 "Upload semantics"](../build-telemetry-spec.md) from the never-built local
   background-upload thread to the shipped synchronous-with-spool behavior, with explicit
   "planned, not shipped" markers for the forward-looking pieces.
2. Fix the Gradle-floor drift: spec §1 says "Gradle 8.x+" and §8's TestKit matrix starts at
   "Gradle 8.0"; both contradict §3.1 and the architecture decision log (8.14 floor,
   2026-07-02 rows). Fix to 8.14.
3. Append a dated amendment to [plan 003](implemented/003-environment-collector.md): its
   CC-detection wording ("config-vs-execution counter comparison") does not describe the shipped
   mechanism (static `AtomicBoolean` observer + public `BuildFeatures`).

**Out (owned elsewhere):** payload size cap + overflow enforcement → plan 019 (the rewritten §3.9
keeps the cap as normative future text and names the plan) · `uploadInBackground` opt-out knob for
local builds → plan 027 · CI matrix changes the corrected §8 text alludes to (isolated-projects
job, macOS/Windows, CC-off axis) → plan 021 · populating `configurationMs` from the observer →
plan 016 · any code or KDoc change (`DaemonState`'s KDoc already describes the shipped mark
mechanism accurately) · extending the "living document" workflow rule to the spec
(comparison-to-spec.md item 12) — worth adopting, but a CLAUDE.md/workflow change outside this
roadmap bullet.

## 3. Design

### 3.1 What spec §3.9 says vs what shipped

Current text ([build-telemetry-spec.md:123](../build-telemetry-spec.md)): CI uploads
synchronously, "Local: background thread with JVM-exit flush". The background thread was never
built — plan 008 diverged deliberately, and the reconciliation confirmed the divergence is the
*better* design (Talaiot's `publishOnNewThread` during `BuildService.close()` silently drops data;
comparison-to-spec.md §2.9). Shipped reality, verified in source:

- One synchronous attempt per payload for **every** mode, inside the Flow-API finalizer:
  `PayloadUploader` (`buildhound-gradle-plugin/.../PayloadUploader.kt:19-24`), 15 s per-attempt
  timeout (connect and request each), gzip JSON `POST <url>/v1/builds` with
  `Authorization: Bearer`, redirects never followed (`PayloadUploader.kt:26-30`, 95-102);
  non-loopback plaintext http warns (`PayloadUploader.kt:32-40`).
- Failure classification (`PayloadUploader.kt:103-111`): transport errors, 5xx, 408/429 →
  spool to `build/buildhound/spool/<buildId>.json.gz`; other 4xx are permanent rejections,
  dropped with a warning, never retried (`PayloadUploader.kt:53-61`).
- The next build's finalizer drains up to 10 spooled files oldest-first *before* uploading its
  own payload (`TelemetryFinalizerAction.kt:160-161`, `PayloadUploader.kt:68-93`); a rejected
  file is deleted and never blocks younger files; the spool is trimmed to 20 files
  (`PayloadUploader.kt:131-137`); files over 8 MB are dropped unread (`PayloadUploader.kt:144`).
- Upload gating (`UploadGate.kt:31-37`): CI/benchmark mode uploads; local mode only with
  `localBuilds.enabled` and (by default) the `~/.buildhound/optin` marker (spec §3.4/§3.7).
- Idempotency: build UUID, server dedupes — unchanged from the current text.

Never built, must not be presented as shipped: the gzip hard cap + overflow strategy (drop
execution reasons → truncate task array; lands with plan 019) and the "optional CI
logging-command annotation" (no such code exists; future `buildhound-ci-assets` work). The
rewrite also states that a background/deferred local upload returns only as plan 027's opt-in
`uploadInBackground` knob (Tuist parity) — an opt-out from blocking local builds, not the default.

### 3.2 Gradle-floor drift

`build-telemetry-spec.md:11` ("Gradle 8.x+ builds") and `:206` ("{Gradle 8.0, 8.14, 9.latest}")
contradict `:53` (§3.1, correct: "Gradle 8.14+ and 9.x") and the two 2026-07-02 decision-log rows
in [architecture.md §7](../architecture.md) (8.14 floor from the JVM-21 owner decision; the
decision log is the current truth). A grep confirms these are the only stale floor mentions.

### 3.3 Plan 003's CC wording vs the shipped observer

Plan 003 describes CC detection as "a per-daemon-JVM execution counter … and config-vs-execution
counter comparison". The shipped code is the Talaiot `ConfigurationPhaseObserver` pattern
instead: a static `AtomicBoolean` (`DaemonState.kt:22`, `configuredSinceLastExecution`) set
**only** from configuration-phase code — the single call site is `BuildHoundSettingsPlugin.apply`
(`BuildHoundSettingsPlugin.kt:43`, after the included-build guard at `:38-41`) — and consumed
exactly once per build by the finalizer via `getAndSet(false)` (`DaemonState.kt:29-32`,
consume-first-unconditionally at `TelemetryFinalizerAction.kt:95-97`). Combined with the public
`BuildFeatures.configurationCache.requested` (`BuildHoundSettingsPlugin.kt:87`), the mapping is:
not requested → `DISABLED`; requested + configured this build → `MISS_STORED`; requested +
configuration skipped → `HIT` (`TelemetryFinalizerAction.kt:209-214`). The execution *counter*
(`DaemonState.kt:21`) survives only for `daemonReused`. Amendments to note alongside:
`INCOMPATIBLE` remains deferred, `configurationMs` remains hardcoded null
(`DerivedMetricsCalculator.kt:23`; plan 016 populates it from this same observer), and the
shared-daemon misattribution caveat still applies to `daemonReused`. Per plans/README.md the
original text stays intact; the correction is an appended, dated amendment.

## 4. Implementation steps

1. **Spec §1** (`docs/build-telemetry-spec.md:11`): replace "from Gradle 8.x+ builds" with
   "from Gradle 8.14+ builds" (floor per the architecture decision log; §3.1 already states it).
2. **Spec §8** (`docs/build-telemetry-spec.md:206`): change the TestKit matrix
   "{Gradle 8.0, 8.14, 9.latest}" to "{Gradle 8.14 (floor), 9.latest}". Touch nothing else in the
   sentence — the actual CI-axis expansion (isolated-projects, OS legs, CC-off) is plan 021.
3. **Spec §3.9** (`docs/build-telemetry-spec.md:121-123`): rewrite per §3.1 of this plan —
   synchronous single-attempt upload in the finalizer for all modes (15 s per attempt, gzip,
   Bearer token, no redirects), transient-vs-permanent failure classification, spool path and
   bounds (10 drained/build oldest-first, 20-file cap, 8 MB per-file cap), local-mode opt-in
   gating, buildId idempotency; then a short "planned" tail: payload hard cap + overflow strategy
   (plan 019), `uploadInBackground` local opt-out (plan 027), CI logging-command annotation
   (future CI-assets work). One parenthetical records why the background thread was dropped
   (no flush guarantee at daemon shutdown — the Talaiot trap; plan 008).
4. **Consistency sweep**: `grep -n "8\.x\|Gradle 8\.0\|background thread" docs/build-telemetry-spec.md`
   must return nothing stale; check §2's diagram line "Uploader (spool + retry)" still matches
   (it does) and that §3.4's aspirational `upload {}` DSL sketch is untouched (plans 019/027 own it).
5. **Plan 003 amendment** (`docs/plans/implemented/003-environment-collector.md`): append an
   "Amendment (2026-07-03)" section stating the shipped mechanism as described in §3.3 above,
   with the file:line citations, explicitly superseding the "counter comparison" sentence and
   noting what it does *not* change (daemonReused counter, deferred `INCOMPATIBLE`, null
   `configurationMs` → plan 016). Original text stays untouched.
6. **Architecture doc check, no edit expected**: the decision log already carries the 8.14-floor
   rows (2026-07-02) and §2 rule 6 is accurate; this plan changes no decision, so no new log row.
7. Commit as `docs: repair spec §3.9/§1/§8 drift, amend plan 003 CC wording (plan 020)` — staging
   exactly the three edited files plus this plan (shared worktree; stage by explicit file list).

## 5. Test strategy

Docs-only, so the "tests" are verification checks rather than new test code:

- Grep gates from step 4 (no `8.x+`/`Gradle 8.0`/local-background-thread text remains in the spec).
- Every present-tense claim in the new §3.9 must be pinned by an **existing** test — reviewers
  check against `UploadFunctionalTest` (`ci mode uploads a gzip payload with the bearer token`,
  `unreachable server spools and the next build drains`, `server errors spool but rejections
  drop`, `a poisoned spool file does not block younger ones`, the three local opt-in cases) and
  `BuildHoundSettingsPluginFunctionalTest` CC assertions (`cc=MISS_STORED` → `cc=HIT` across
  store/reuse) for the plan 003 amendment. Any §3.9 sentence without a pinning test or an
  explicit plan-number marker fails review.
- `./gradlew build` stays green (sanity: no source touched).
- The standard clean-context reviews (CLAUDE.md §3) verify the rewritten §3.9 sentence-by-sentence
  against `PayloadUploader.kt`/`TelemetryFinalizerAction.kt`/`UploadGate.kt` and the amendment
  against `DaemonState.kt`/`BuildHoundSettingsPlugin.kt`.

## 6. Risks

- **Laundering unshipped features as shipped.** The main failure mode of a "make the spec honest"
  pass is present-tense text for the size cap or the CI annotation. Mitigation: the "planned
  (plan NNN)" markers are part of the required text, and the review rule in §5 makes untraceable
  sentences a blocking finding.
- **Batch collisions.** Plans 019/021/027 (same batch) touch adjacent spec territory (§3.4 caps,
  §8 CI matrix, upload DSL). This plan deliberately edits only §1/§3.9/§8's floor number and
  defers everything else by plan reference; commits stage explicit file lists because concurrent
  sessions share this worktree.
- **History integrity.** Editing an implemented plan could obscure what was originally intended;
  mitigated by the append-only dated-amendment format (plans/README.md convention).
- **CC / schema / security-privacy:** no code, no schema, no new data collected. The rewritten
  §3.9 restates shipped token handling (Bearer-header only, never logged, redirects never
  followed, plaintext-http warning) — it must not weaken those statements; architecture §6 stays
  the binding source.

## 7. Exit criteria

- `grep -rn "8\.x+\|Gradle 8\.0" docs/build-telemetry-spec.md` is empty; §1, §3.1, and §8 all
  state the 8.14 floor consistently with the architecture decision log.
- Spec §3.9 contains no background-thread/JVM-exit-flush prescription; every behavioral sentence
  in it is either verifiable at the cited plugin source lines or carries an explicit plan marker
  (019 / 027 / CI-assets).
- `docs/plans/implemented/003-environment-collector.md` ends with a dated 2026-07-03 amendment
  describing the static-AtomicBoolean + `BuildFeatures` mechanism with file:line citations; the
  pre-existing text is byte-identical.
- `./gradlew build` green; both clean-context reviews pass with no unresolved
  spec-vs-source discrepancy.

## 8. Divergences from the plan (recorded during implementation)

- **The payload size cap is now presented as shipped, not "planned (plan 019)".** This plan
  was written expecting §3.9 to name the cap as normative-future text; plan 019 landed first,
  so the rewritten §3.9 states the gzip + hard-byte-budget + cardinality/free-text caps as
  shipped behavior (citing §4 `caps` and plan 019 as where it landed) and adds that the server
  re-clamps defensively at ingest. Only `uploadInBackground` (plan 027) and the CI
  logging-command annotation remain marked "planned, not shipped".
- **Plan 003 amendment additionally notes `configurationMs`**: since plan 016 shipped, the
  amendment records that `configurationMs` is now populated from this same configuration-phase
  observer (paired with a task-graph `whenReady` end mark), rather than "hardcoded null" as
  plan 020 §3.3 anticipated.
