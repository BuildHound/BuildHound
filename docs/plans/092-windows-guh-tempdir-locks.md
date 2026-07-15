# 092 — Windows: daemon-locked Gradle User Home breaks `@TempDir` deletion

## Source

CI recovery: the `Build & test (Windows, watched)` job fails on every recent run (main +
PRs). Example: Actions run 29411179466 / job 87338363141 — `128 tests completed, 17
failed`, and **all 17** failures are
`org.junit.jupiter.api.io.TempDirDeletionStrategy$DeletionException`, none are assertion
failures. Follow-up to plan 049 (fresh-daemon TestKit dirs outside `@TempDir`).

## Root cause

The 17 failures are exactly the tests of the three Windows-enabled classes that pass
`-g <@TempDir>` — `InvocationFunctionalTest` (6), `BuildCacheConfigFunctionalTest` (3),
`WrapperFunctionalTest` (8). The fourth `-g` class, `ChangedModulesFunctionalTest`, is
`@DisabledOnOs(WINDOWS)` and never ran; it shares the pattern and is relocated for
consistency. Plan 049 moved the *TestKit dir* out of `@TempDir`
because the lingering TestKit daemon holds open handles under it; the per-test *Gradle
User Home* `@TempDir` reintroduced the same hazard — the daemon keeps GUH cache files
(journal, file hashes, Kotlin DSL caches) open after `.build()` returns, and TestKit
offers no daemon-stop API (verified against Gradle 9.6.1: `DefaultGradleRunner` has no
`stopTestKitDaemons`; daemons stop via a JVM-exit shutdown hook only). Windows cannot
delete open files (POSIX unlink can), so JUnit's post-test `@TempDir` deletion throws.
Classes without `-g` pass — `projectDir` deletion is not affected.

## Scope

- **In:** relocate the per-test GUH out of `@TempDir` (root fix); add a best-effort
  JUnit temp-dir deletion strategy as a safety net for future daemon-lock classes;
  promote the Windows job out of watched status once the run is green.
- **Out:** deterministic TestKit daemon shutdown (no public API; internal daemon
  clients are off-limits per architecture §2), any change to what the tests assert.

## Design

1. `TestKitDirs.kt`: new `internal fun newGradleUserHome(): File` — a fresh `guh-`
   prefixed dir under the existing `buildhound.testkit.root` (plan 049 root, under the
   module `build/` dir; `clean` reclaims it after the daemons' JVM-exit shutdown). The
   four classes replace `@field:TempDir lateinit var guhDir: File` with
   `private val guhDir: File = newGradleUserHome()` — default per-method test lifecycle
   keeps the "fresh GUH per test, stable across runs within a test" semantics
   (store→hit CC pairs need one GUH).
2. `buildhound-gradle-plugin/build.gradle.kts`: on the TestKit-spawning test tasks only
   (`functionalTest`, `isolatedProjectsTest` — review deviation from the original
   "all Test tasks": on the unit `test` task, which spawns no daemons, a deletion
   failure is a genuine cleanup-bug signal and must stay a failure),
   set `junit.jupiter.tempdir.deletion.strategy.default` to JUnit 6.1's built-in
   `org.junit.jupiter.api.io.TempDirDeletionStrategy$IgnoreFailures` (delegates to the
   standard delete, logs failures instead of failing the test). A locked-file
   `DeletionException` is never this suite's signal — assertions are; this keeps the
   Windows canary readable if a future fixture re-introduces a daemon-held path.
3. `ci.yml`: once the branch run shows the Windows job green, drop
   `continue-on-error: true` and the `(watched)` suffix (promote-or-defer criterion,
   plan 021 / architecture decision log — record the promotion there).

## Test strategy

- Full local `:buildhound-gradle-plugin:functionalTest` run (macOS) — no regression;
  the relocation must not change any test outcome.
- Windows proof is CI itself: the previously-red job must go green on this branch
  (the job runs on `pull_request`). The 17 failing tests are the acceptance set.
- Known-safe details preserved: GUH paths reach Gradle via the `-g` CLI arg only —
  never written into `gradle.properties` (Windows backslash mangling,
  `KotlinReportFunctionalTest` precedent).

## Risks

- Leftover per-test GUHs accumulate under `build/functionalTest-testkit` until `clean`
  — same accepted trade-off as plan 049's TestKit dirs (CI runners are ephemeral).
- `IgnoreFailures` can mask a genuine cleanup bug (it logs a warning instead). Accepted:
  in a suite that spawns real daemons, deletion failures are environmental noise, and
  the root fix — not the strategy — is what keeps dirs deletable.
- Promoting the Windows job to blocking may surface unrelated Windows flakiness later;
  it can be re-demoted with a one-line revert if that happens.

## Exit criteria

- `Build & test (Windows, watched)` job green on this branch with the full
  functionalTest suite executed (no skips beyond the pre-existing `@DisabledOnOs` set).
- No new failures on the Linux/macOS/floor/CC-off legs.
- Windows job promoted to blocking (watched suffix + `continue-on-error` removed) in
  the same PR, with the architecture decision log updated.
