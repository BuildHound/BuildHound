# 081 — Dokploy long-lived stack and encrypted recovery

> Placement update (2026-07-14): plan 086 supersedes the application/Traefik colocation
> decision. Staging application workloads use `role=staging`, production application
> workloads use `role=prod`, and database/backup workloads remain on `role=db`.
>
> Credential update (2026-07-14): plan 087 permits protected environment credentials in
> staging because pinned Dokploy cannot create the required external Swarm secrets through
> its supported control plane. Production remains external-secret-backed.

## Source

Deployment request (2026-07-10), reconciled direction accepted on 2026-07-12,
[`docs/self-hosting.md`](../self-hosting.md), and architecture §§4–6. PostgreSQL remains
the pilot's authoritative live store; Hetzner Object Storage holds client-side-encrypted
backups only. Implementation records this decision, secrets/migration posture, and measured
RPO/RTO in architecture §7.

[082](082-buildhound-main-site.md) owns the site and
[083](083-dokploy-environment-delivery.md) owns automation/reviews. This plan must exit by
manual deployment without either.

## Scope

**In:** production/staging Dokploy Stacks for server/dashboard and PostgreSQL; immutable
images; exact DB placement and volume guards; TLS, rate/resource/log controls; encrypted
backup, alerts, and recovery.

**Out:** site, review environments, automated promotion, app-level S3 payload offload,
HA/PITR, and new server `*_FILE` support. The server password remains a documented scoped
Dokploy environment secret pending a separate server plan.

## Design

- Add a trusted Stack manifest and runbook under `deploy/dokploy/`. Use no `build:`,
  `container_name`, host ports, `depends_on`, floating images, or top-level `restart:`.
  Digest-addressed single replicas use fixed pilot resource/log bounds, stop-first updates,
  and `failure_action: pause`. Publish tested server/backup images plus a minimal guarded
  PostgreSQL wrapper image in a non-cancelling job, handle GHCR worker authentication, and
  prove an explicit manual Dokploy deploy. Baking the guard into that digest avoids remote
  raw-Stack dependence on a checkout-local config file.
- Treat installed-version behavior as an early staging gate: verify Stack `env_file`, external
  secrets/configs, domain labels, digest references, and registry pulls; do not assume `${VAR}`
  interpolation. Scope secrets to consumers. If Stack mode exposes only one merged secret
  environment, production waits for server `*_FILE` support.
- Pin each DB to the sole node labelled `role=db` and also constrain it by immutable Swarm
  node ID; `PGDATA` is the only durable local volume. A
  minimal marker guard allows one explicit initialization, then rejects empty, foreign,
  mismatched-instance, or wrong-major volumes. Restore is a one-shot runbook operation, not
  a persistent DB start mode.
- Put server/DB/backup on an encrypted private overlay and only server on ingress. Pin server
  with Traefik for the pilot; deviations require accepted risk. One TLS origin serves the
  dashboard and `/v1/*`, DNS targets the Traefik node, and staging receives
  `X-Robots-Tag: noindex`. Keep application token limits and a nonzero host-limit backstop;
  Traefik adds a per-client-IP limit verified by a 429 test.
- Run server and backup read-only with bounded tmpfs. Use `curl -fsS /health` for liveness,
  but gate rollout on an authenticated PostgreSQL read; staging also writes/reads. Require
  `BUILDHOUND_DB_URL`, reject the `storage: IN-MEMORY` log, prove restart persistence, and
  require a public ceiling-sized ingest to return `202`.
- Use native `POSTGRES_PASSWORD_FILE` for DB and `PGPASSFILE` for backup; only server receives
  its temporary manager-visible password value. Generate bootstrap tokens with
  `openssl rand -hex 32`, remove them after first boot, and never use `BUILDHOUND_DEV_TOKEN`
  in DB mode.
- A small non-root toolbox streams `pg_dump --format=custom | age -r <recipient>` directly
  to S3. Only the public age recipient is online; escrow the private key elsewhere. Give
  production/staging separate Hetzner projects/keys and only backup S3 access. Enable
  versioning and tested lifecycle rules preserving at least 14 production/7 staging recovery
  points. Schedule with margin for RPO ≤24 h, alert on failed/stale backup and disk pressure,
  and target RTO ≤4 h.
- Quarterly drills restore into a fresh volume, verify SQL, start an unrouted server canary
  to run Flyway, verify through the API, then switch routing while retaining the old volume.
  Take a verified backup before migrations; never auto-rollback an old binary across an
  incompatible schema—pause and roll forward.

## Test strategy

- Validate fixtures with `docker stack config`; assert digests, service-specific placement,
  update policy, private networks, read-only/tmpfs, no dangerous mounts/ports, and no
  rendered secrets. Before every deployment, fail unless exactly one Ready/Active Swarm node
  carries `role=db` and its ID matches the configured placement constraint.
- In ephemeral Swarm, initialize once, ingest, redeploy/restart, and prove persistence;
  empty/foreign/mismatched/wrong-major volumes fail closed and startup races recover.
- Stage through public TLS: verify assets, liveness, authenticated read/write, restart
  persistence, noindex, 429, and ceiling-sized ingest.
- Exercise backup and DB/S3/upload failures, alerting, offline-key decryption, and fresh-volume
  recovery; failed runs preserve the last good backup. Run infrastructure and security/privacy
  reviews over credentials, networks, ciphertext, lifecycle, and logs.

## Risks

Local volumes require exact placement and restore after node loss. Dokploy Stack behavior is
version-specific and blocks production if its staging probe fails. `/health` is not readiness.
If dump/restore growth misses RPO/RTO, revise targets explicitly or plan encrypted WAL/PITR.

## Exit criteria

- A clean operator manually deploys exact digests to staging/production; DB placement,
  restart persistence, fail-closed volume identity, and non-in-memory storage are proven.
- TLS, noindex, rate-limit, proxy-ceiling, liveness, and authenticated DB checks pass; only
  server is ingress-routable.
- Both environments create encrypted/versioned backups, failures alert, and a fresh-volume
  drill meets accepted RPO/RTO.
- No payload adapter, server code, review environment, or site implementation is introduced.
