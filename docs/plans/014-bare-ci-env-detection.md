# 014 — Classify bare `CI` environments as generic CI

## Source

Plugin-ecosystem gap analysis vs Gradle's Common Custom User Data plugin (CCUD), §4.3:
CircleCI, GitLab CI, Travis, Jenkins (and most other CI systems without a built-in
provider) export the conventional `CI` environment variable, which CCUD's
`CiUtils#isGenericCI` uses as its fallback. BuildHound's generic provider only activates
on `BUILDHOUND_CI=true` / `BUILDHOUND_CI_PROVIDER`, so on such systems detection returns
null and `TelemetryMode.AUTO` (spec §3.4) resolves to `local` — wrong baselines, wrong
upload semantics, local opt-in gating applied to CI builds, and no `ci` payload block.
Spec sections touched: §3.3 (discovery), §3.4 (`auto` mode).

## Scope

- **In**: bare-`CI` fallback inside `GenericCiEnvironmentProvider` (commons), docs, tests.
- **Out**: new built-in provider mappings (GitLab/CircleCI/… field mapping stays future
  work), schema changes, DSL changes, plugin-side changes.

## Design

- `GenericCiEnvironmentProvider.detect` gains a second branch: when no
  `BUILDHOUND_CI_*` markers are active, classify the build as CI iff the `CI` variable is
  set to anything other than an explicit falsy value (`false`/`0`, case-insensitive).
  Result is a minimal `CiContext(provider = "generic")` — there are no standard variables
  to map fields from.
- **Truthiness decision (documented here, in KDoc, and in spec §3.3)**: CCUD's check is
  presence-only. We diverge in one detail: `CI=false` / `CI=0` count as *not* CI,
  following the wider `ci-info` ecosystem convention and giving an opt-out on machines
  where tooling exports `CI` spuriously. An empty value (`CI=`) still counts as CI
  (presence semantics, matches CCUD).
- Escape hatch: `BUILDHOUND_CI` set to any non-`true` value (with no
  `BUILDHOUND_CI_PROVIDER`) suppresses the bare-`CI` fallback — explicit beats heuristic.
- Ordering unchanged (first non-null wins): built-ins → ServiceLoader extras → generic
  provider, whose internal order is BUILDHOUND markers first, bare `CI` last. GitHub
  Actions / Azure DevOps also set `CI=true` and keep resolving to their own providers.
- No plugin change: `PayloadAssembler.resolveMode` already maps AUTO→`ci` when a context
  exists. No new schema fields → no golden-file changes.

## Test strategy

- Commons unit tests: `CI=true`, `CI=` (empty), `CI=false`, `CI=0`, `CI` absent;
  `BUILDHOUND_CI=false` suppression; `BUILDHOUND_CI_*` still wins over bare `CI`;
  built-ins still win over generic when both match (`CI=true` + provider env).
  Update `returns_null_when_no_provider_matches` (needs a CI-free env now) and
  `does_not_detect_without_buildhound_markers` accordingly.
- functionalTest (TestKit, fresh daemon per the existing env-injection pattern):
  injected `CI=true` with `GITHUB_ACTIONS`/`TF_BUILD`/`BUILDHOUND_*` cleaned →
  `mode=ci`, `ci.provider=generic`; injected `CI=false` → `mode=local`.

## Risks

- Behavior change, not purely additive detection: builds that previously ran as `local`
  on unsupported CIs become `ci` — that is the fix itself (CI builds must not sit behind
  the local opt-in marker, spec §3.7). Payload gains only `ci.provider="generic"`; no new
  data collected, so privacy surface is unchanged.
- False positives (developer shells exporting `CI`): mitigated by `CI=false`,
  `BUILDHOUND_CI=false`, or DSL `mode = local`.
- Plugin must never fail a build: change is pure map-lookup code inside the existing
  never-throw detection path (`CiValueSource` already wraps in `runCatching`).

## Exit criteria

- `./gradlew build` green (commons tests + plugin functionalTest included).
- Spec §3.3 amended with the fallback sentence; architecture decision-log row added.
- Clean-context code/architecture review and security/privacy review completed, findings
  addressed.
