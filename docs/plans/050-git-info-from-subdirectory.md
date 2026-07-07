# 050 — VCS info from a subdirectory / included build

## Source

Feature request: the dashboard *test & compare* build dropdown shows **"no branch"** for
local builds, even though the VCS pipeline (collect → send → show) already exists end to end
(plans [004](implemented/004-vcs-collector.md), [015](implemented/015-vcs-exec-timeout.md),
[027](implemented/041-ci-connectors-gha-gitlab.md) links, and the server/report/dashboard
readers). Root cause: `GitExec` sets `GIT_CEILING_DIRECTORIES=<rootDir parent>` (a plan-004
review finding — "never discover an enclosing, unrelated repository"), which also blocks the
**legitimate** case where the Gradle root is a *subdirectory* of the repository (included /
composite builds, monorepos with a nested Gradle root, an `androidApp/` inside a larger repo).
Git resolves repo context from any subfolder; the plugin should not diverge from that.

## Scope

**In**

- Drop the artificial ceiling by default so the git probes discover the enclosing repository
  from any subdirectory (branch/sha/dirty/remoteUrl now populate for nested Gradle roots).
- Add `buildhound.vcs.searchParents` (boolean, **default `true`**) — an escape hatch that,
  when `false`, restores the old fail-closed ceiling (git confined to `rootDir`). Precedence
  matches every other knob: explicit → override → default (plan 027).

**Out**

- No schema change (`vcs` block unchanged — the data was always defined, just never resolved
  here). No dashboard/report markup change — both already render `vcs.branch`/`vcs.sha`.
- Not giving `samples/nowinandroid` its own `.git`. Post-fix it reports **BuildHound's** branch
  (it sits inside this repo with no `.git` of its own) — acceptable demo behavior and the
  sharpest illustration of the trade-off below; anyone who wants it confined sets
  `searchParents=false`.

## Design

- `GitExec.run(...)` gains `searchParents: Boolean = true`. When `true`, `GIT_CEILING_DIRECTORIES`
  is **omitted** (git walks up to the enclosing `.git`, stopping at a repo boundary or the
  filesystem root — its native behavior). When `false`, it is set to `rootDir.parentFile`
  (the pre-050 behavior). `GIT_OPTIONAL_LOCKS=0` / `GIT_TERMINAL_PROMPT=0` and the bounded
  timeout (§2 rule 11) are unchanged — the reversal touches only discovery scope.
- `VcsValueSource.Parameters` gains `searchParents: Property<Boolean>`; `obtain()` passes it to
  every probe (one `GitProbe`, one env). `BuildHoundSettingsPlugin` wires it from
  `providers.gradleProperty("buildhound.vcs.searchParents")` (`.toBoolean()`, default `true`),
  alongside `buildhound.vcs.timeout.ms`. Still execution-time only — no new CC input, no
  config-phase file read; isolated-projects unaffected.
- Doc comments on `VcsValueSource`/`GitExec` updated; `docs/architecture.md` decision log gets a
  superseding entry for the plan-004 ceiling finding.

## Test strategy

- **Unit (`GitExecTest`, real git, POSIX/`@DisabledOnOs(WINDOWS)`):** `git init` a temp repo,
  create a nested subdir, then `rev-parse --abbrev-ref HEAD` from the subdir — `searchParents=true`
  resolves the branch, `searchParents=false` is confined (NonZeroExit → null). Skips cleanly when
  no `git` on PATH.
- **Functional (`BuildHoundSettingsPluginFunctionalTest`):** new test — a git repo at the
  TestKit `projectDir` with the Gradle build in a `nested/` subdir resolves `vcs.branch` by
  default; the same layout with `-Pbuildhound.vcs.searchParents=false` yields a null `vcs` block.
- **Hermeticity:** pin the existing `non git projects degrade to a null vcs block` test with
  `-Pbuildhound.vcs.searchParents=false` so its `assertNull(payload.vcs)` no longer depends on
  where `@TempDir` lands relative to any enclosing repo.

## Risks

- **Privacy (§3.2, named — not hand-waved):** a Gradle project checked out *inside an unrelated
  enclosing repo* (classic: `$HOME` under git) now attributes that repo's branch/sha and
  **redacted** remote URL to the build. Accepted trade-off: it is exactly what `git branch` would
  report from that directory, matches every other dev tool, and is what the requester wants;
  `searchParents=false` restores fail-closed. Remote-URL redaction (all-scheme, fail-closed,
  plan 027) is unchanged, so no credential regression.
- **Attribution (not a leak):** with discovery on, `vcs.dirty` and `links` describe the *discovered*
  enclosing repository, not the Gradle root, when they differ. `git status` output is still reduced
  to the `dirty` boolean and discarded (spec §3.7); only the attribution target shifts.
- **Reversal of a review decision:** documented in the decision log so the "why" survives.
- No CC / schema / hang-safety regression — the timeout and env locks are untouched.

## Exit criteria

- Nested-Gradle-root build (repo above the root) populates `vcs.branch`/`vcs.sha` by default;
  `searchParents=false` restores the confined behavior — both pinned by tests.
- Existing VCS tests stay green (hung-git, CI-fills-gaps, non-git-degrade now hermetic).
- Decision log + doc comments updated; `./gradlew build` green.
