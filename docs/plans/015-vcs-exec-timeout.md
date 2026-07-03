# 015 — Bounded wait for git probes (VcsValueSource timeout)

## Source

Plan 004's accepted residual risk ("no exec timeout and stdout capture is unbounded —
revisit with a `waitFor(timeout)` variant if it ever bites") now bites: CCUD gap
analysis showed `gradle/common-custom-user-data-gradle-plugin` bounds every git exec at
10 seconds with `destroyForcibly()` (`Utils#execAndGetStdOut`) precisely because a hung
git — fsmonitor daemon, network worktree, stuck credential helper — otherwise stalls the
build indefinitely. Architecture §2 rule 3: the plugin must never fail (or hang) a build.

## Scope

**In:**

- Replace `ExecOperations` in `VcsValueSource` with a JDK `ProcessBuilder` runner
  (`GitExec`, free of Gradle types): per-probe hard timeout (default **10 s**, CCUD
  parity), `destroyForcibly()` on expiry, probe degrades to null with a single `warn`
  log (fixed message — probe args and millis only, never process output or paths).
- Short-circuit: the first timed-out probe skips the remaining ones (a hung git hangs
  all three probes; one timeout budget is enough, not 3×).
- Bounded stdout capture (64 KiB, output past the cap is drained and discarded) —
  closes the other half of 004's residual risk and prevents pipe-full stalls.
- stderr → `Redirect.DISCARD` (may contain paths; never read), stdin closed immediately
  (a prompt-happy git sees EOF instead of hanging).
- Gradle property `buildhound.vcs.timeout.ms`: test seam + escape hatch for repos where
  a healthy git legitimately needs longer (precedent: `buildhound.optin.file`).
  Absent/invalid/non-positive → default.

**Out:** retries, async/parallel probes, per-probe-type timeouts, Windows coverage for
the fake-script fixtures (CI is ubuntu; script-based tests are `@DisabledOnOs(WINDOWS)`).

## Design

`GitExec` internal object, pure JDK — same rationale as `VcsParsing`: the unit `test`
source set has no `gradleApi()` on Gradle 9, so timeout behavior is unit-testable only
if the runner has no Gradle types. `ProcessBuilder` with inherited env plus the existing
`GIT_TERMINAL_PROMPT=0`, `GIT_OPTIONAL_LOCKS=0`, `GIT_CEILING_DIRECTORIES` (moved from
the exec spec); daemon reader thread captures ≤64 KiB and keeps draining; main thread
`waitFor(timeout)`; `destroyForcibly()` in `finally`. Returns a small result ADT
(`Success(stdout)` / `NonZeroExit` / `TimedOut` / `Failed(exceptionClass)`); logging
stays in `VcsValueSource` (warn on timeout, info otherwise). ValueSource contract is
unchanged: `CollectedVcs`, obtained only via FlowAction parameters → subprocess starts
at execution time, CC-safe; `ExecOperations` injection is removed.

## Test strategy

- Unit (`GitExecTest`, POSIX-only fixtures): fake executables — echo → `Success`;
  `exit 3` → `NonZeroExit`; missing binary → `Failed`; `exec sleep 300` with a 250 ms
  timeout → `TimedOut` with elapsed time far below the sleep; a 2 MiB emitter →
  `Success` capped at 64 KiB, terminates (drain, no pipe deadlock).
- Functional (TestKit): fake `git` that sleeps 300 s, prepended to `PATH` via
  `withEnvironment` + fresh TestKit dir (pattern from the generic-CI test), with
  `-Pbuildhound.vcs.timeout.ms=1000`: build SUCCESS, payload written with null `vcs`,
  **exactly one** `[buildhound] git timed out` line (pins the short-circuit), wall
  clock < 120 s (vs. a 300 s hang if the bound fails).

## Risks

- CC: `ProcessBuilder.start()` inside `ValueSource.obtain()` executes at execution time
  here (FlowAction-only obtain); existing cc=MISS_STORED/HIT tests catch any violation.
- Flakiness: no sub-second assertions; bounds are 120 s vs. 300 s — generous margins.
- Privacy: data shape unchanged; stderr now discarded unread (before: captured then
  dropped); timeout log carries static args + millis only.
- Behavior change: a pathologically slow but alive git (>10 s per probe) now yields
  null vcs where it used to (eventually) succeed — `buildhound.vcs.timeout.ms` is the
  escape hatch, and null vcs is spec-legal (payload assembly falls back to CI context).

## Exit criteria

`./gradlew build` green including the new unit + functional tests; architecture.md §2
gains the bounded-subprocess rule (+ decision log row); no schema change.
