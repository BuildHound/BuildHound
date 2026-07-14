# Dokploy plans 081–083 — implementation handoff

## Authority and provenance

This handoff accompanies the authoritative reconciled plans:

1. [`081-dokploy-long-lived-stack.md`](081-dokploy-long-lived-stack.md)
2. [`082-buildhound-main-site.md`](082-buildhound-main-site.md)
3. [`083-dokploy-environment-delivery.md`](083-dokploy-environment-delivery.md)

They were authored on `codex/dokploy-deployment-plans-reconciled`, based on `main` at
`d4cde88593ed19fa561487461869633f09e55c45`, after comparing:

- `codex/dokploy-deployment-plans` at `fd5353fbbdb09bb5a6a0668514a0d35f72477975`
- `claude/dokploy-deployment-plan-e6fc99` at `9a576e710500f4a8b4182381d180e7f3dedf54a8`

The reconciled files supersede both competing 081–083 sets. Do not cherry-pick either old
set or reintroduce its asserted owner decisions. Recheck plan numbers and the latest Flyway
migration after every rebase onto `origin/main`.

## Accepted planning decisions

- PostgreSQL is the authoritative live store for the pilot. Object storage is for encrypted
  backup/recovery only. App-level payload offload is deferred.
- Backups are client-side encrypted with `age`; only the public recipient is online. The
  private key is escrowed outside Dokploy, GitHub, and Hetzner.
- Provisional targets are RPO ≤24 hours and RTO ≤4 hours, confirmed by a staging restore.
- Production and staging use separate Hetzner Cloud projects/credential domains. Review gets
  no S3 credential or backup.
- PostgreSQL uses native `POSTGRES_PASSWORD_FILE`. The server temporarily receives only its
  own manager-visible environment secret. If Dokploy cannot scope that value, 081 waits for
  a separate server `*_FILE` plan; it never shares one merged secret environment by default.
- Migration-bearing deploys take a fresh backup and pause on failure. Roll-forward is the
  default once a schema change is not proven backward-compatible.
- Review environments are lean: ephemeral DB, fixed limits, a constant active ceiling, exact
  ownership, and TTL reconciliation. No capacity allocator/global lock is needed.
- Plan 086 supersedes the pilot colocation: review uses `role=review`, staging applications use
  `role=staging`, production applications use `role=prod`, and DB/backup use `role=db`.

The implementation must add these decisions and measured targets to `docs/architecture.md`
§7. They are absent from `main` because this branch intentionally commits only plans and
this handoff.

## Ownership and order

Implement one plan at a time and update its plan if implementation diverges:

1. **081 first:** production/staging server + DB, manual exact-digest deploy, encrypted
   backup/restore, alerts, and persistence gates.
2. **082 second:** standalone V2 site, production/staging Applications, and manual deploy.
3. **083 last:** immutable BOM automation, staging-to-production promotion, then review
   lifecycle after exact-SHA and persisted Dokploy-isolation gates pass.

081 and 082 must each work manually before 083. Review automation is never allowed to block
shipping safe long-lived environments.

Deferred work needs a new next-free plan and separate plan commit:

- fail-closed server `*_FILE` support and secret rotation tests;
- app-level payload offload, only after an explicit storage ADR and demonstrated capacity
  need. That plan must cover object state/checksums, backfill, reconciliation/outbox,
  WRITE → READ → EVICT phases, bounded failures, retention, privacy/encryption, and rollback.

## What to retain from each source branch

From Claude: Stack-mode/placement constraints, Dokploy environment-delivery staging probe,
GHCR private-by-default handling, explicit deploy API calls, DNS-01/Traefik mechanics,
edge 429 test, per-environment Hetzner-project isolation, nginx-unprivileged integration,
and mandatory V2 visual direction.

From Codex: client-side backup encryption, empty/foreign-volume fail-closed behavior,
in-memory persistence gate, digest/BOM promotion, stop-first plus pause behavior, read-only
roots, public payload-ceiling test, strict site URL validation, protected-base review
manifests, exact ownership checks, and tracked review retirement/reconciliation.

Discard: the JVM probe and `/dev/tcp`; three-mode DB entrypoint; Restic writer/pruner unless
already operationally standard; moving-tag deployment; shared whole-`.env` injection;
persistent review DBs/backups; S3 credentials in review; capacity allocator/global lock;
large tuning matrices; bucket-scoped Hetzner assumptions; and Claude's payload-offload plan.

## Early implementation probes

Before committing to manifests or workflows, verify against the installed Dokploy version:

1. Stack `env_file`, external `secrets:`/`configs:`, domain labels, and exact digest syntax.
2. Project/environment-scoped token permissions and separate review/staging/production users.
3. Private GHCR pulls on every eligible Swarm worker.
4. Compose/Application deploy API endpoints and the fact that registry pushes do not deploy.
5. Isolated-deployment support and its narrower per-application boundary. Treat outbound
   review → public staging/production denial as separate network-policy work.
6. Idempotent domain listing/creation/deletion and returned resource IDs.
7. Hetzner versioning, lifecycle, access-policy, failed multipart, and noncurrent-version
   behavior; credentials are Cloud-project-wide, not bucket-scoped.
8. GHCR support for the proposed digest-addressed `release.json` OCI artifact. If unavailable,
   choose another immutable, durable BOM store and update plan 083 before implementation.

Record probe results and fallbacks in `deploy/dokploy/README.md`; a failed load-bearing probe
blocks production rather than silently weakening a security property.

## Operator inputs still required

Commit names/placeholders only; the owner supplies values out of band:

- Dokploy base URL, project ID, production/staging/review environment IDs, and distinct
  least-privilege API credentials for those roles;
- production/staging site and dashboard FQDNs, review DNS suffixes, and the Traefik-node DNS
  target served by the pre-warmed Hetzner DNS-01 wildcard certificates;
- the `role=db` DB-node label, its immutable Swarm node ID, unique DB instance identities,
  and eligible Ready/Active `role=review`, `role=staging`, and `role=prod` workers, verified
  through Dokploy API/UI;
- GHCR server/site/backup/guarded-PostgreSQL package names and worker pull credential if
  packages remain private;
- separate production/staging Hetzner project, endpoint, region, bucket, and backup-service
  credentials;
- production/staging age recipients plus the offline private-key escrow owner/procedure;
- production/staging DB/bootstrap values, GitHub Environment reviewers, alert destination,
  backup schedule/retention, and final RPO/RTO acceptance.

## Verified repository facts

- `.github/workflows/ci.yml` builds the server image but does not publish it and applies
  `cancel-in-progress: true`; publish/deploy/cleanup workflows need explicit non-cancelling
  concurrency where interruption could leave partial external state.
- Current code defaults rate limits to ingest/query/host `60/120/600`; the `0 (off)` row in
  `docs/self-hosting.md` is stale and should be corrected by 081.
- Missing `BUILDHOUND_DB_URL` starts the in-memory store and logs
  `storage: IN-MEMORY (no BUILDHOUND_DB_URL) — data is lost on restart`.
- `/health` returns `ok` without touching PostgreSQL. It is liveness, not readiness.
- The current server reads `BUILDHOUND_DB_PASSWORD` and bootstrap values only from ordinary
  environment variables; no server `*_FILE` support exists.
- PostgreSQL supports `POSTGRES_PASSWORD_FILE` without application changes.
- The reviewed pinned Temurin JRE contains curl, wget, and bash. Use `curl -fsS`; recheck only
  if its digest changes.
- The DB stores telemetry and unsalted SHA-256 token hashes. Identity salts are plugin-side,
  not a backup-dump secret inventory item.
- A tag named for a SHA is still mutable. Deploy `tag@sha256` or a digest directly.
- `docker stack deploy` has no `depends_on` ordering and cannot build images.
- `docs/brand/v2/assets/` contains the V2 tokens, fonts, Trace H assets, and provenance.
  The fixture HTML is not a production component.

## Expected implementation touch points

### 081

- `deploy/dokploy/stack.yaml`, backup-toolbox/guarded-PostgreSQL image files,
  validation/smoke scripts, and runbook;
- server/backup/guarded-PostgreSQL image publish jobs and explicit manual deployment
  instructions;
- `docs/self-hosting.md` and `docs/architecture.md` decision/log updates;
- no server Kotlin/configuration change.

### 082

- top-level `site/` only, plus its image publish/smoke wiring and Dokploy instructions;
- production-owned V2 assets with OFL/provenance; no Gradle settings/module change;
- a narrow startup URL validator/renderer, hardened nginx config, and visual/accessibility
  tests. The local theme controller is the only client script.

### 083

- trusted scripts under `deploy/dokploy/` with unit tests against a fake Dokploy API;
- separate unprivileged image-build and protected privileged deployment workflows;
- immutable BOM publishing, staging deployment, production approval, review reconciliation,
  and PR image garbage collection;
- no application or database schema change.

## Security gates that fail closed

- No production launch before an encrypted backup is restored with the escrowed key into a
  fresh volume and verified through an unrouted server canary.
- No long-lived deploy that logs in-memory mode, silently initializes an unexpected volume,
  fails restart persistence, or places DB on a non-unique node.
- No review deploy unless approval names the exact current head SHA, a push invalidates it,
  the privileged job uses only protected-base code, and Dokploy isolation persists on readback.
- No fork review, PR-controlled manifest, host bind, Docker socket, privileged/host network,
  S3 secret, production data, or shared long-lived credential in review.
- No cleanup by prefix alone. Scope to the fixed review environment, verify repository/PR
  ownership metadata, mutate returned IDs, and recheck PR state immediately before retirement.
- Do not call v0.29.12 `compose.delete` for isolated reviews: stop and scrub the exact Compose,
  deploy the pinned zero-replica anchor, verify its manager-side materialized file and
  `isolatedDeployment:true`, stop again, remove exact-owned images, then retain a tracked
  `retired:true` Compose/network anchor for reuse.
- Before reviews, use only Dokploy's UI/admin API to set and read back Traefik
  `api.insecure:false` (preferably `api.dashboard:false` with no unprotected `api@internal`
  router), reload it, and set the protected review attestation. Never grant automatic review CI
  the owner/admin token needed to inspect global Traefik configuration.
- No secret, environment ID, domain, bucket, password, token, or generated value in committed
  files, workflow output, job summaries, PR comments, or verbose HTTP traces.

## Verification and review

At minimum, the implementing agent should run:

```bash
./gradlew build
for app_role in staging prod; do
  BUILDHOUND_APP_ROLE="$app_role" deploy/dokploy/validate-stack.sh
done
git diff --check
```

Add fixture-driven manifest validation, image/container smoke tests, hostile site-input tests,
fake-Dokploy API tests, and staging rehearsals described in each plan. A green `/health` alone
never satisfies deployment verification.

After each implementation, follow `CLAUDE.md` with fresh-context reviews: infrastructure or
frontend quality as appropriate, `security-reviewer-infra` for Docker/compose/CI, and the
dedicated security/privacy review for telemetry, credentials, retention, and review trust.
Fix findings or record explicit owner acceptance before merge.

## Kickoff prompt for the next agent

> Implement plan 081 from `docs/plans/081-dokploy-long-lived-stack.md`. Treat that plan and
> `docs/plans/dokploy-implementation-handoff.md` as authoritative over the two superseded
> source branches. Start with read-only Dokploy/Hetzner capability probes and current CI/code
> inspection. Do not implement the public site, delivery/review automation, server `*_FILE`
> support, or S3 payload offload. Keep secrets and concrete operator values out of the repo,
> prove encrypted restore and fail-closed persistence, run the required tests/reviews, and
> update the committed plan plus architecture decision log if reality forces a divergence.
