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
- Do not change release schemas, review eligibility, ownership, networking, or raw-Stack
  delivery. Raw review Stacks remain provider-free, while protected workflows supply
  `DOKPLOY_REGISTRY_ID` and `DOKPLOY_GIT_PROVIDER_ID`: the client validates both integrations,
  refreshes registry login state on the exact local or remote manager targeted by the Compose,
  and leaves the Docker-source site Application's registry relations unset to avoid Dokploy
  v0.29.12's re-tag/push path for registry-bound source images.
  Because Dokploy v0.29.12 authenticates `sourceType: docker` from the Application's legacy
  Docker-provider fields rather than its registry relation, require and preserve those
  preconfigured pull credentials without copying them into GitHub secrets. Its read endpoints
  return those credentials to the protected runner, so confine those responses to trapped
  private files and never print, resend, or store them in shell variables.
- Enforce review -> staging -> production: `deploy-review` label events create/update reviews,
  unlabel/close events retire them, a qualifying labelled merge starts staging automatically
  from the successful main publisher through `workflow_run`, and production remains manual.
  Staging requires a successful reviewed-PR smoke attestation bound to the release source merge
  commit. Its schema-2 Dokploy title persists that source commit, and the pre-mutation gate
  rejects candidates outside current-main lineage or older than the deployed staging source.
  Production requires the same-BOM staging attestation carrying that review proof, compares the
  candidate to its current deployed source, and requires explicit rollback attestation for any
  regression. Backup evidence carries the exact predecessor release ID and the client reasserts
  it immediately before the first mutation.
- Route long-lived certificate requests through Traefik's
  `letsencrypt-dns-hetzner` resolver. Review routers select the separately pre-warmed wildcard
  certificates and must not request per-PR certificates.

## Design

- Use portable Bash strict mode without relying on command-substitution `errexit`; build
  every JSON request with `jq`, never `eval`, interpolation into JSON, or `set -x`.
- Centralize API transport: require an exact HTTPS origin, forbid credentials/path/query in
  it, disable redirects, bound requests, send only `x-api-key`, and never print response
  bodies on HTTP failures.
- Validate release JSON, canonical hashes, migration history, deployment evidence, review
  metadata, IDs, images, hosts, and persisted Compose state with explicit shell predicates
  and `jq -e` before each mutation.
- Clear and reassert every raw-Compose field that can replace or transform the protected Stack;
  reject attached Dokploy domains, mounts, or backups while preserving required environment
  values exactly. Clear and reassert all three site Application registry relations so its
  digest source is pulled directly with the preconfigured legacy Docker credentials, and force
  Application auto-deploy off so refresh hooks cannot bypass the promotion workflow.
- Preserve exact deployment-title polling and its distinct terminal-failure/uncertain-state
  outcomes. Cleanup must still drain queues, prove quiescence, stop the Stack, wait for both
  public 404s, update the ownership anchor to a pinned zero-replica manifest, deploy and poll that
  inert definition, verify the manager-side materialized file through Dokploy's API, stop again,
  then run package cleanup and verify the anchor's idle isolated `retired:true` state. Preserve
  `activatedAt` throughout cleanup. Dokploy v0.29.12 `compose.delete` is not used
  because it can orphan the external isolated network.
- Persist the exact GitHub run-attempt identity in review ownership and deployment evidence.
  Failure and scheduled cleanup may revoke only that attempt; active same-SHA reruns fail before
  mutation, while retired anchors may be reactivated and scheduled reconciliation retires exact
  failed/cancelled attempts left by force-cancellation.
- Keep stdout machine-readable for commands consumed by workflows; diagnostics go to stderr
  and contain no secrets, rendered manifests, request bodies, or API response bodies.
- Keep API headers, canonical request/response JSON, and rendered manifests in private
  mode-`0700` workspaces under the runner temporary directory. Traps remove those workspaces on
  normal exit and signals; this avoids exposing secrets in process arguments while retaining
  exact manifest bytes.
- Keep each mutation Environment-protected. A successful label-triggered review writes exact-SHA
  status and a run-scoped artifact only after end-to-end smoke; completion of the trusted
  main-branch publisher starts staging automatically, which verifies workflow/run/PR/merge
  identity and carries the proof into the staging artifact. Production alone remains
  `workflow_dispatch`-only; an optional staging bootstrap may also remain manual.
- Remove package write from PR CI. After label authorization, protected-base delivery builds the exact untrusted
  head without registry credentials, then let default-branch delivery code tag/push and bind
  the exact PR/head/push digests into a run-scoped manifest. Review deployment consumes that
  manifest and never resolves a mutable tag as authority.

## Test strategy

- Port Python client unit coverage to shell integration tests using fake `curl`/`sleep`
  executables and JSON fixtures; cover every command plus malformed data, redirects, API
  failures, ambiguous evidence, hidden provider state, failed-terminal cleanup, and uncertain
  deployment preservation.
- Retain Python tests only for Python artifact-rendering code. Add policy assertions that no
  workflow or reconciler invokes the removed Python client.
- Run all Dokploy tests, `bash -n`, ShellCheck, workflow YAML parsing, and `git diff --check`.
  Re-run the label-driven private-registry review lifecycle first; verify its qualifying merge
  starts staging automatically, then dispatch production manually with the same BOM.

## Risks

Shell quoting and pipeline semantics can turn rejected input into commands or swallow API
errors. JSON must stay in `jq`; secret-bearing values must stay out of process arguments and
diagnostics and may exist only in the trapped private runner workspaces described above. Every
expected failure path needs an explicit checked status. A narrower fail-closed rejection is
acceptable; silently weakening an existing invariant is not. Dokploy v0.29.12's `registry.one`
and `application.one` responses expose stored credentials to an authorized caller, so access to
the protected Environment runner and deploy token is part of the credential trust boundary.

## Exit criteria

All existing Dokploy CLI behavior and security properties pass through the shell client; no
deployment workflow invokes `dokploy.py`; the Python client is removed; clean-context infra
and security/privacy reviews have no unresolved blocker; the controlled review smoke succeeds
and cleans up with Dokploy's persisted isolated-deployment setting plus scrubbed tracked retirement
verified.
