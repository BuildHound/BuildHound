# 086 — Dokploy role placement and review readiness

## Source

Owner direction (2026-07-14) after PR 24's first isolated review deployment: review
workloads belong on `role=review`; staging application workloads on `role=staging`;
production application workloads on `role=prod`; and both long-lived database/backup
workloads on `role=db`. The failed review returned 404 even though Dokploy reported a
successful deployment because every service still required the nonexistent
`buildhound.traefik=true` label.

## Scope

**In:** role-based Swarm constraints for review, staging, and production; exact placement
of the separate site Application; post-deploy review task convergence through Dokploy
v0.29.12's supported read API; policy tests, operator docs, and architecture updates.

**Out:** staging or production deployment before the preceding environment passes; changes
to the manual production trigger; Dokploy/Traefik host mutation; Dokploy upgrades; and the
separate decision whether BuildHound should require `api.insecure: false`. That setting did
not cause this routing failure.

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
  readiness. Poll `docker.getStackContainersByAppName` after that row succeeds. Ignore
  historical desired-state `shutdown` tasks; fail immediately when a desired-running task
  has an error; and continue only when site, server, and DB each have exactly one current
  desired-running task in `Running` state with a valid container ID. Empty, malformed, extra,
  or persistently transitional state fails closed before public HTTP smoke.
- Supersede plans 081, 083, and 085 wherever they require application/Traefik colocation.
  Keep all Dokploy mutations inside its API; SSH remains read-only diagnosis only.

## Test strategy

- Assert three `role=review` constraints and no `buildhound.traefik` constraint in review
  manifests, anchors, and exact readback fixtures.
- Exercise review readiness for healthy, pending, terminal task error, malformed/empty,
  historical shutdown, missing service, and unexpected desired-running service responses.
- Render and validate the long-lived Stack for staging and production. Reject any untrusted
  role value and assert server, DB, and backup constraints independently.
- Verify the exact site Application placement mutation and persisted readback, including
  missing and drifted placement.
- Run the complete Dokploy policy suite, ShellCheck, shell syntax checks, workflow YAML
  parsing, Stack rendering, and `git diff --check`.

## Risks

Incorrect or removed node labels make workloads unschedulable; task polling turns that into
an explicit deployment failure. Dokploy's task endpoint can collapse command errors to an
empty response, so sustained empty state must never be accepted as ready. Old Swarm task
history can coexist with the current task and must not be mistaken for failure. Review traffic
may cross the unencrypted isolated overlay when Traefik and the selected review worker differ.

## Exit criteria

- Repository policy encodes the owner-provided role map for every application and database
  workload and verifies it after Dokploy persistence.
- A review deployment cannot reach URL smoke until its three current Swarm tasks converge.
- Clean-context infrastructure and security/privacy reviews have no unresolved blocker.
- PR 24 deploys successfully and both review URLs pass before staging is attempted;
  production remains manual and is not attempted until staging passes.
