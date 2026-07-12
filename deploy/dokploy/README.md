# Dokploy deployment

This directory is the trusted deployment boundary for plans 081–083. All committed values
are variable names or immutable public image references; operators enter domains, IDs,
credentials, recipients, schedules, and node labels through protected Dokploy/GitHub
environments. Never use verbose HTTP tracing.

## Capability gate

The client targets Dokploy's documented `x-api-key` API (`compose.update`,
`compose.create`, `compose.isolatedDeployment`, `compose.deploy`, and Application update/
deploy; checked 2026-07-12). Before production, record the installed Dokploy version and verify in staging: Stack
`env_file`, external secret/config scoping, `deploy.labels`, digest pulls on every worker,
Compose/Application deploy endpoints, isolated review networking, and idempotent domain IDs.
Also verify separate least-privilege tokens, Hetzner versioning/lifecycle/noncurrent-version
behavior, and OCI artifact support for `release.json`. A failed row blocks that feature; it
does not authorize a weaker fallback. The current repository cannot truthfully pre-fill
these environment-specific results.

## Long-lived stack

1. Label exactly one database node and the Traefik node; create the encrypted ingress
   network, PGDATA volume, and scoped external secrets.
2. Set `BUILDHOUND_DB_ALLOW_INIT=true` for the first deployment only. After the marker is
   written, remove it. The guard refuses empty, foreign, wrong-instance, and wrong-major
   volumes.
3. Render and validate with `docker stack config -c deploy/dokploy/stack.yaml` and
   `deploy/dokploy/validate-stack.sh`. Deploy exact image digests explicitly.
4. Reject `storage: IN-MEMORY` in logs. Verify authenticated DB read/write, restart
   persistence, a ceiling-sized public ingest returning 202, and a Traefik 429 response.
5. Bootstrap with `openssl rand -hex 32`, then remove bootstrap values after first boot.

The backup service streams `pg_dump --format=custom` through `age` to S3. Only the public
recipient is online. Configure bucket versioning and lifecycle to preserve at least 14
production or 7 staging recovery points. Alert when the last successful object exceeds 24
hours, a task fails, or disk pressure rises. Production and staging use separate Hetzner
projects and keys.

Quarterly, run `restore.sh` with the offline key into a fresh, explicitly initialized
volume; validate SQL, start an unrouted current-image canary so Flyway runs, verify through
the API, then switch routing while retaining the old volume. Record measured RPO and RTO;
the provisional acceptance targets are 24 hours and 4 hours. Take a fresh verified backup
before migrations and roll forward if backward compatibility is not proven.

## Site and delivery

Build `site/Dockerfile`, run it non-root with read-only root plus bounded tmpfs for `/tmp`
(which contains the rendered page), then create separate production/staging Dokploy Applications and deploy
the exact digest. `BUILDHOUND_SITE_DASHBOARD_URL` must be an HTTPS origin; staging sets
noindex. `security.txt` is intentionally absent until an owner supplies a monitored contact.

`render-release.py` creates canonical `release.json`; `dokploy.py release-id` gives its
content digest. Staging deploys one release ID. Production passes that same ID via
`--proven-release-id`, requires environment approval and a fresh backup, and never rebuilds.
Migration-incompatible rollback is refused operationally: pause and roll forward.
The single `bootstrap_bom` exception is staging-only: it accepts a fresh backup explicitly
marked `manual` when moving the already-verified plan-081 deployment onto its first BOM.
Every later backup must match the currently successful compose release ID exactly.

Review deployment is default-off. A protected-base manual workflow must bind approval to
the exact current SHA, re-query the PR, reject forks, resolve its own image digests, and use
only ephemeral credentials. Set a fixed `MAX_ACTIVE` in the review environment. Prove
review cannot reach staging/production before enabling it. Cleanup removes the concrete
route before the Stack, rechecks PR state, verifies ownership metadata and exact returned
IDs, and the scheduled reconciler repeats this for closed, unlabelled, or expired reviews.
