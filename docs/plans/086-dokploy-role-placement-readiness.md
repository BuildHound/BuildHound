# 086 — Dokploy role placement and review readiness

## Source

Owner direction (2026-07-14) after PR 24's first isolated review deployment: review
workloads belong on `role=review`; staging application workloads on `role=staging`;
production application workloads on `role=prod`; and both long-lived database/backup
workloads on `role=db`. The failed review returned 404 even though Dokploy reported a
successful deployment because every service still required the nonexistent
`buildhound.traefik=true` label.

Owner follow-up (2026-07-14): `api.insecure` is not a routing prerequisite. Keep the
installed Dokploy/Traefik setting unchanged, remove BuildHound's blocking attestation, and
accept the unauthenticated configuration-read exposure from isolated review containers.

## Scope

**In:** role-based Swarm constraints for review, staging, and production; exact placement
of the separate site Application; least-privilege review readiness and exact failed-attempt
cleanup; removal of the `api.insecure` workflow gate with explicit risk acceptance; policy
tests, operator docs, and architecture updates.

**Out:** staging or production deployment before the preceding environment passes; changes
to the manual production trigger; Dokploy/Traefik host mutation; and Dokploy upgrades.

## Design

- Replace every review service and retained anchor constraint with
  `node.labels.role == review`. Isolated deployments may span review nodes; document that
  their Dokploy-created overlay is not encrypted between hosts.
- Render the long-lived Stack from a trusted `staging` or `production` workflow target.
  Map it only to `role=staging` or `role=prod` for the server. Keep database and backup on
  `role=db` plus the existing immutable database node ID.
- Set the separate site Application's `placementSwarm.Constraints` through
  `application.update`, then require exact readback before deployment. Reject missing,
  additional, or mismatched constraints.
- Treat Dokploy's successful Compose deployment row as submission success, not workload
  readiness. The protected workflow's bounded public site and dashboard health probes plus
  authenticated ingest/read smoke are the readiness proof; no success attestation is written
  before all pass. A failed or cancelled smoke invokes the existing exact-attempt reconciler.
  Do not grant the automatic token Dokploy v0.29.12's `docker:read`: upstream incorrectly uses
  that permission for container restart, stop, kill, removal, and file upload as well as reads.
- Supersede plans 081, 083, and 085 wherever they require application/Traefik colocation.
  Keep all Dokploy mutations inside its API; SSH remains read-only diagnosis only.
- Remove `BUILDHOUND_REVIEW_TRAEFIK_API_INSECURE_DISABLED` and its blocking workflow step.
  `api.insecure: true` does not affect routing or certificate selection, but it exposes
  Traefik API/dashboard configuration to untrusted code on each attached review network.
  Record that disclosure as owner-accepted rather than representing a false attestation.

## Test strategy

- Assert three `role=review` constraints and no `buildhound.traefik` constraint in review
  manifests, anchors, and exact readback fixtures.
- Assert URL smoke follows exact Dokploy deployment evidence, covers both public services plus
  authenticated ingest/read, and gates the attestation. Preserve the exact failed/cancelled
  attempt cleanup path and require no Docker-wide permission.
- Assert the review workflow no longer reads or gates on the Traefik insecure-API variable.
- Render and validate the long-lived Stack for staging and production. Reject any untrusted
  role value and assert server, DB, and backup constraints independently.
- Verify the exact site Application placement mutation and persisted readback, including
  missing and drifted placement.
- Run the complete Dokploy policy suite, ShellCheck, shell syntax checks, workflow YAML
  parsing, Stack rendering, and `git diff --check`.

## Risks

Incorrect or removed node labels make workloads unschedulable and surface as a bounded URL-smoke
failure before exact cleanup; the diagnostic is less specific than manager-wide task inspection
but preserves least privilege. Dokploy v0.29.12's over-broad `docker:read` permission must not be
added merely for readiness. Review traffic may cross the unencrypted isolated overlay when
Traefik and the selected review worker differ. With `api.insecure: true`, untrusted review
containers can read the unauthenticated Traefik API/dashboard configuration on that network;
the owner accepts this disclosure risk, not arbitrary Traefik or Docker mutation authority.

## Exit criteria

- Repository policy encodes the owner-provided role map for every application and database
  workload and verifies it after Dokploy persistence.
- A review cannot record success until both URLs and authenticated ingest/read smoke pass;
  failure reconciles the exact owned attempt.
- No review deployment depends on the removed insecure-API attestation, and operator docs
  state the accepted exposure accurately.
- Clean-context infrastructure and security/privacy reviews have no unresolved blocker.
- PR 24 deploys successfully and both review URLs pass before staging is attempted;
  production remains manual and is not attempted until staging passes.
