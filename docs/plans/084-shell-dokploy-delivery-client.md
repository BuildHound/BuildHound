# 084 — Shell Dokploy delivery client

## Source

Owner request (2026-07-13) to replace the Python Dokploy deployment client with shell,
using the supplied Betty's Kitchen staging, review, CI client, and bootstrap scripts as
style references.

## Scope

- Replace `deploy/dokploy/dokploy.py` with a Bash entrypoint and sourced libraries while
  preserving every release, review, cleanup, listing, and bootstrap-check subcommand.
- Update GitHub workflows, the review reconciler, deployment documentation, and tests to
  invoke the shell client.
- Keep `render-release.py`: it renders a release artifact and is not a deployment client.
- Do not change release schemas, Dokploy permissions, review eligibility, ownership,
  networking, raw-Stack delivery, or the registry/Git-provider model. In particular, raw
  review Stacks remain provider-free and use Dokploy's `--with-registry-auth` login state.

## Design

- Use portable Bash strict mode without relying on command-substitution `errexit`; build
  every JSON request with `jq`, never `eval`, interpolation into JSON, or `set -x`.
- Centralize API transport: require an exact HTTPS origin, forbid credentials/path/query in
  it, disable redirects, bound requests, send only `x-api-key`, and never print response
  bodies on HTTP failures.
- Validate release JSON, canonical hashes, migration history, deployment evidence, review
  metadata, IDs, images, hosts, and persisted Compose state with explicit shell predicates
  and `jq -e` before each mutation.
- Preserve exact deployment-title polling and its distinct terminal-failure/uncertain-state
  outcomes. Cleanup must still drain queues, prove quiescence, stop the Stack, wait for both
  public 404s, retain the ownership anchor through package cleanup, and verify deletion.
- Keep stdout machine-readable for commands consumed by workflows; diagnostics go to stderr
  and contain no secrets, rendered manifests, request bodies, or API response bodies.

## Test strategy

- Port Python client unit coverage to shell integration tests using fake `curl`/`sleep`
  executables and JSON fixtures; cover every command plus malformed data, redirects, API
  failures, ambiguous evidence, hidden provider state, failed-terminal cleanup, and uncertain
  deployment preservation.
- Retain Python tests only for Python artifact-rendering code. Add policy assertions that no
  workflow or reconciler invokes the removed Python client.
- Run all Dokploy tests, `bash -n`, ShellCheck, workflow YAML parsing, and `git diff --check`.
  Re-run the controlled private-registry review rehearsal after merge-ready review.

## Risks

Shell quoting and pipeline semantics can turn rejected input into commands or swallow API
errors. JSON must stay in `jq`, secret-bearing values must stay out of argv diagnostics and
temporary files, and every expected failure path needs an explicit checked status. A narrower
fail-closed rejection is acceptable; silently weakening an existing invariant is not.

## Exit criteria

All existing Dokploy CLI behavior and security properties pass through the shell client; no
deployment workflow invokes `dokploy.py`; the Python client is removed; clean-context infra
and security/privacy reviews have no unresolved blocker; the controlled review smoke succeeds
and cleans up with the isolation gate reset.
