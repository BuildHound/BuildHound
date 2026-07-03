# 004 — VCS collector: git branch, sha, dirty

## Source

Roadmap Phase 1 (EnvironmentCollector scope). Spec §3.2: "ValueSources for git
(branch, sha, dirty)"; §4 `vcs` payload block (`branch`, `sha`, `dirty`).

## Scope

**In:**

- `VcsValueSource` (CC-safe `ValueSource` + injected `ExecOperations`) running
  `git rev-parse --abbrev-ref HEAD`, `git rev-parse HEAD`, and
  `git status --porcelain` in the root project directory; returns a Serializable
  `CollectedVcs(branch, sha, dirty)`.
- Degradations (never fail the build): git binary missing, not a repository → null
  fields; empty repo (no HEAD) → null branch/sha, `dirty` still reported (status
  succeeds there). `GIT_CEILING_DIRECTORIES=<rootDir parent>` prevents discovering an
  enclosing, unrelated repository (review finding). Detached HEAD (`HEAD` from abbrev-ref, typical on CI)
  → `branch = null`; payload assembly (next chunk) fills branch from the CI context.
- Gated on `buildhound { enabled }` like the environment source.
- Finalizer logs a `[buildhound] vcs: …` summary line; sha is public build metadata
  (spec §4), fine to log.
- Pure output-parsing helpers unit-tested; TestKit test against a real `git init`
  fixture plus a no-git-directory fixture.

**Out:** CI-context branch/sha merging (payload assembly chunk), non-git VCS,
`git describe`/tags.

## Design

Same pattern as plan 003: ValueSource obtained only via FlowAction parameters →
executes at execution time, not a CC fingerprint input, re-runs on CC reuse (sha/dirty
must be fresh per build). Exec output capture with exit-code checks; each of the three
probes individually guarded. Working directory passed as an absolute-path String.

## Test strategy

- Unit: parsing (`HEAD` → null branch, porcelain output → dirty flag, blank → clean,
  whitespace trimming).
- Functional: fixture with `git init` + commit → log line carries branch and a 40-char
  sha; `dirty=false` after commit, `dirty=true` after touching a file; fixture without
  `.git` → build still green, vcs fields null.

## Risks

- `git` exec could theoretically hang → no interactive prompts
  (`GIT_TERMINAL_PROMPT=0`), no optional index locks (`GIT_OPTIONAL_LOCKS=0`); no exec
  timeout and stdout capture is unbounded — accepted residual risk (review finding),
  revisit with a `waitFor(timeout)` variant if it ever bites.
- Spawning three processes per build is negligible next to any real build; measured
  stance can change if profiling says otherwise.
- Privacy: branch names and shas are declared in spec §4 (`vcs` block); no paths, no
  remotes, no author info collected (`git status --porcelain` output is never stored,
  only its emptiness).

## Exit criteria

`./gradlew build` green including new tests; no schema change; non-git projects
unaffected.
