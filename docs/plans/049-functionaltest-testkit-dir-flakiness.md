# 049 — Move the TestKit daemon dir out of `@TempDir` (functionalTest flakiness)

## Source

Not a spec/roadmap item — a CI reliability bug. The `buildhound-gradle-plugin:functionalTest`
suite is intermittently red on the macOS (**blocking**) and Windows (**watched**) legs with:

```
org.junit.jupiter.api.io.TempDirDeletionStrategy$DeletionException: Failed to delete temp
directory .../junit-XXX. The following paths could not be deleted: <root>, testkit
```

## Scope

**In:** relocate the per-test TestKit dir used by the "fresh daemon" env-detection tests so it
no longer lives inside the JUnit `@TempDir` project dir.
**Out:** no change to what the tests assert, to the plugin, or to daemon count/lifetime. No new
`@AfterEach`/daemon-stop logic.

## Design

**Root cause.** The env-detection / "fresh daemon" tests call
`withTestKitDir(File(projectDir, "testkit"))`, putting the TestKit daemon's working dir *inside*
`projectDir` (the `@TempDir`). The daemon lingers after `.build()` and holds files open under
`projectDir/testkit`; when JUnit deletes the `@TempDir` it fails on macOS/Windows (Linux's POSIX
unlink is immune, so Linux is always green). The victim test is whichever daemon happens to hold
a dir → flakiness across classes.

The error message is the proof of a complete fix: it names only `<root>` and `testkit` as
undeletable — never `.gradle` or `build`. So the daemon holds handles **only** in
`projectDir/testkit`; `<root>` fails purely because `testkit` sits inside it. Move `testkit`
out → nothing daemon-held remains inside `projectDir` → `<root>` deletes. Structural, not
probabilistic.

**Why a *unique* dir per call (not a shared/per-class one).** Fresh-daemon semantics are load
bearing: these tests inject env via `withEnvironment`, and TestKit daemon selection ignores env
differences, so a reused daemon would serve a **stale** environment (and, across a rebuild, a
stale plugin classpath). Today that freshness comes for free from `@TempDir` uniqueness. Once the
dir moves to a fixed root we must restore uniqueness explicitly — `Files.createTempDirectory`
(unique every call) keeps every test on a guaranteed-fresh daemon. A shared/per-class dir would
reuse a daemon across methods and reintroduce staleness — rejected.

**Change.**
- New shared helper `TestKitDirs.kt` (functionalTest source set, alongside the existing
  `TestKitCc.kt` top-level helper): `internal fun GradleRunner.freshDaemon()` →
  `withTestKitDir(newTestKitDir())`, where `newTestKitDir()` is
  `Files.createTempDirectory(root, "testkit-")` under a root read from the
  `buildhound.testkit.root` system property (falls back to `java.io.tmpdir`).
- `build.gradle.kts`: set `buildhound.testkit.root` on the `Test` tasks to the **absolute**
  `build/functionalTest-testkit` under the module build dir (`clean` reclaims them) rather than
  `@TempDir`. Absolute is required — TestKit's daemon starter rejects a relative testkit dir
  (`IdentityFileResolver` → `UnsupportedOperationException`, confirmed empirically). See Risks
  for the accepted cache-relocatability trade-off.
- Delete the two per-class `private fun GradleRunner.freshDaemon()` (Ci/Benchmark) — they now
  resolve to the shared one. Replace the 3 inline
  `withTestKitDir(File(projectDir, "testkit"))` in `BuildHoundSettingsPluginFunctionalTest` with
  `.freshDaemon()`.

## Test strategy

No new tests — this fixes existing ones. Verify by running the full suite repeatedly on macOS:
`./gradlew :buildhound-gradle-plugin:functionalTest --rerun-tasks` 3–5× with **no**
DeletionException. The env-detection tests (GitLab/Claude Code/`BUILDHOUND_MODE`/benchmark)
assert on injected-env-derived payload fields, so their green **is** the fresh-daemon proof — a
stale daemon would fail them.

## Risks

- **Daemon accumulation.** Each fresh-daemon test still leaves one lingering daemon — same count
  as today, just relocated under `build/` instead of `@TempDir`. Not a regression; `clean`
  reclaims the dirs. No `@AfterEach` delete (would reintroduce the very open-handle lock race we
  are removing) and no daemon-stop (unnecessary once the dir is outside `@TempDir`).
- **CC-safety.** `buildhound.testkit.root` is a `String` system property resolved from
  `layout.buildDirectory` at configuration time; no config-cache hazard in the outer build
  (empirically confirmed — the outer build stores a CC entry on every functionalTest run).
- **Cache relocatability (accepted §3.1 finding).** The absolute path enters the cacheable
  `functionalTest` task's `@Input` fingerprint, so the task is not relocatable across machines.
  Accepted, not fixed: this TestKit suite spawns real daemons and reads the live environment, so
  its result is machine-specific and must never be served from another machine's cache. A
  relative constant would poison the fingerprint less but breaks TestKit's daemon starter (it
  rejects a relative testkit dir — proven), so it is not an option; a proper
  `outputs.cacheIf { false }` is a possible follow-up if a shared build cache is ever adopted.
- **Security/privacy:** none — test-only; no token/payload/endpoint surface, no new collected
  data. Temp-dir creation under the build dir only.

## Exit criteria

- `functionalTest --rerun-tasks` green 3–5× consecutively on macOS with no DeletionException.
- All five `withTestKitDir(File(projectDir, "testkit"))` call sites route through the shared
  `freshDaemon()` helper; no TestKit dir is created inside a `@TempDir`.
- §3.1 kotlin-gradle review clean; §3.2 confirms no privacy/security surface.
