# 044 — Build-failure detail + opt-in warning capture

## 1. Source

Feature request: *collect all build warnings; and when a build fails, add the error and the
stacktrace to the telemetry.* Touches spec §3.7 (privacy/scrubbing) and §4 (payload schema);
adds a decision-log row to `docs/architecture.md` §7.

## 2. Scope

**In.** (A) A failed build ships `failure.message` + `failure.stackTrace` (scrubbed, truncated) —
core, always-on. (B) Two warning catchers — Gradle deprecations and `WARN`-level log lines
(`logger.warn`) — in the opt-in `buildhound-internal-adapters` module, each an explicit toggle,
**off by default**.

**Out.** Per-task failure attribution (`failure.taskPath` stays null in v1). Every compiler
diagnostic — Gradle exposes no single "all warnings" stream; the catchers cover the two named
channels only. Configuration-phase-only failures (the Flow `buildWorkResult.failure` is an
execution-phase signal).

## 3. Design

**Track A — failure (core, `buildhound-gradle-plugin` + `buildhound-commons`).**
- `FailureInfo` (commons) gains additive `message` + `stackTrace` (nullable; v1, no version bump).
- New `FailureExtractor` + serializable `CollectedFailure` (Gradle-free): `Throwable → (exceptionClass,
  message, messageHash, stackTrace)`, flattening `MultipleBuildFailures` and preserving the `Caused by:`
  chain, raw trace bounded to 64 KiB. `messageHash` = SHA-256 over the **raw** message.
- `BuildHoundSettingsPlugin` sets a new Flow param from `flowProviders.buildWorkResult.map { … }`
  (extraction runs at finalization = execution time → CC-safe). `TelemetryFinalizerAction` threads it
  into `PayloadAssembler.assemble`, which populates `FailureInfo`.
- `PayloadScrubber.scrub` now covers `failure` (the hook its KDoc reserved): scrub then truncate
  `message` (≤512) and `stackTrace` (≤8 KiB). The local HTML artifact renders a fuller (still-scrubbed)
  trace via `TelemetryFinalizerAction.reportPayload`; the uploaded/written JSON keeps the 8 KiB cap. A
  failure card is added to the report template (all strings via `textContent` — XSS-safe).

**Track B — warnings (`buildhound-internal-adapters`).**
- Two `internalAdapters {}` toggles (`collectDeprecations`, `collectLogWarnings`), `.convention(false)`,
  read at config time via `InternalAdaptersState.configure()` (the DSL runs after `apply()`); daemon-
  static so they persist across CC hits.
- Deprecations: fill the empty `BuildOperationAdapter.progress()` — read `DeprecatedUsageProgressDetails
  .getSummary()/getAdvice()` reflectively. Log warnings: new `WarningLogListener : OutputEventListener`
  on `LoggingOutputInternal`, registered once per daemon (gated on the toggle), WARN filtered by
  `LogEvent.getLevel().name()`. Both reflection-guarded, deduped + count/length-capped in the
  accumulator, scrubbed in `InternalAdaptersCollector`, carried in the independently-versioned
  `extensions.internalAdapters` (`deprecations`, `logWarnings`, `droppedWarnings`) — **no core schema
  change**. All internal-API shapes verified against Gradle 9.6.1 before wiring.

## 4. Test strategy

- Golden: `build-payload-v1-failure-detail.json` (new; existing untouched) + `GoldenPayloadTest`.
- Commons unit: `PayloadScrubber` failure message/stacktrace (path relativized, out-of-project +
  secret redacted, truncation).
- Plugin unit: `FailureExtractor` (class/message/hash, multi-cause join, null-safety); `PayloadAssembler`
  (failure populated on fail, null on success).
- Plugin TestKit: a real failing build → scrubbed `failure.message` + `failure.stackTrace`, no path leak.
- Module unit: collector scrubs/emits warnings; accumulator dedups/bounds/counts.
- Module TestKit (real-signal): both plugins in one build → a real `logger.warn` + a real deprecation
  land scrubbed with toggles on; absent with toggles off.

## 5. Risks

- **Privacy (precedent reversal).** Plaintext message + stacktrace expands past the hash-only
  `FailureInfo` and the plan-024 no-stacktrace-body choice. Mitigated by scrub-then-cap; §3.2 review
  must rule on the plan-007 scrubber gaps (space-separated flag secrets, sub-32-char keyless tokens,
  out-of-project space-path tails) now that failure text lands.
- **CC-safety.** Failure extraction is inside the provider `map{}` (execution-time), output is a
  plain serializable holder — no Gradle types in Flow params.
- **Internal-API drift (Track B).** Reflection-guarded + version-gated (degrade to no capture, never a
  throw); the real-signal TestKit test is the proof the wiring is live, not dead.

## 6. Exit criteria

`./gradlew build` green (new golden + all functional tests). A failed build's payload carries scrubbed
`failure.{message,stackTrace}`; with both toggles on, `extensions.internalAdapters.{deprecations,
logWarnings}` carry scrubbed entries; with toggles off, neither. Spec §3.7/§4 + architecture §7 updated.
Two clean-context reviews (kotlin-gradle + §3.2 security/privacy) pass or findings are addressed.
