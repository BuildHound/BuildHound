# Dokploy deployment

This directory is the trusted deployment boundary for plans 081–083. All committed values
are variable names or immutable public image references; operators enter domains, IDs,
credentials, recipients, schedules, and node labels through protected Dokploy/GitHub
environments. Never use verbose HTTP tracing.

## Capability gate

The client targets Dokploy's documented `x-api-key` API (`compose.update`,
`compose.create`, `compose.isolatedDeployment`, `compose.deploy`, and Application update/
deploy; checked 2026-07-12). Before production, record the installed Dokploy version and verify in staging: Stack
`env_file`, external secret scoping, `deploy.labels`, digest pulls on every worker,
Compose/Application deploy endpoints, isolated review networking, and idempotent domain IDs.
Also verify separate least-privilege tokens, Hetzner versioning/lifecycle/noncurrent-version
behavior, and OCI artifact support for `release.json`. A failed row blocks that feature; it
does not authorize a weaker fallback. The current repository cannot truthfully pre-fill
these environment-specific results.

## Long-lived stack

1. Label exactly one database node and the Traefik node; create the encrypted ingress
   network, PGDATA volume, and scoped external secrets. Build and deploy the
   digest-addressed `deploy/dokploy/db/Dockerfile` image so the trusted volume guard is
   available on every worker; raw Dokploy delivery never depends on a checkout-local file.
2. Set `BUILDHOUND_DB_ALLOW_INIT=true` for the first deployment only. After the marker is
   written, wait for database readiness and prove authenticated restart persistence before
   removing it. The guard refuses empty, foreign, wrong-instance, and wrong-major volumes;
   even a marked-but-empty PGDATA requires explicit re-initialization.
3. Render and validate with `docker stack config -c deploy/dokploy/stack.yaml` and
   `deploy/dokploy/validate-stack.sh`. Deploy exact image digests explicitly.
4. Set `BUILDHOUND_ROBOTS_HEADER=noindex, nofollow` in staging and `all` in production.
   Reject `storage: IN-MEMORY` in logs. Verify authenticated DB read/write, restart
   persistence, a ceiling-sized public ingest returning 202, and a Traefik 429 response.
5. Bootstrap with `openssl rand -hex 32`, then remove bootstrap values after first boot.

The backup service streams `pg_dump --format=custom` through `age` to a non-eligible
`.partial` S3 key, then uses the AWS CLI's multipart-capable server-side copy to create the
final `.dump.age` key with an explicit completion marker and removes the staged object. Do
not replace this with the single-request `s3api copy-object`, which stops at 5 GiB. Only final objects carrying that marker
are eligible for deployment gates or restore. Only the public recipient is online. Mount
its `pgpass` secret for UID/GID 10001 with mode `0400` so libpq will accept it. Configure
bucket versioning and lifecycle to preserve at least 14 production or 7 staging recovery
points, delete abandoned `.partial` objects promptly, and delete current/noncurrent backup
versions no later than the 90-day raw-telemetry retention ceiling. Alert when the last
successful object exceeds 24 hours, a task fails, or disk
pressure rises. Production and staging use separate Hetzner projects and keys.

Quarterly, run `restore.sh` as UID/GID 10001 with the offline age key, S3 credentials, and
a `pgpass` mount whose mode is `0400`, restoring into a fresh, explicitly initialized
volume. The script downloads and decrypts to private temporary files, validates the custom
archive, and only then restores it in a single transaction. Validate SQL, start an unrouted
current-image canary so Flyway runs, verify through the API, run the retention sweep so
expired telemetry is not resurrected, then switch routing while retaining the old volume.
Record measured RPO and RTO; the provisional acceptance targets
are 24 hours and 4 hours. Take a fresh verified backup before migrations and roll forward
if backward compatibility is not proven.

## Site and delivery

Build `site/Dockerfile`, run it non-root with read-only root plus bounded tmpfs for `/tmp`
(which contains the rendered page), then create separate production/staging Dokploy Applications and deploy
the exact digest. `BUILDHOUND_SITE_DASHBOARD_URL` must be an HTTPS origin; staging sets
noindex. `security.txt` is intentionally absent until an owner supplies a monitored contact.

`render-release.py` creates canonical schema-2 `release.json`, binding the Stack,
volume-guard, and the numerically ordered `{migration ID, source checksum}` set; the release
artifact carries the exact Stack/guard source snapshots and `dokploy.py
release-id` gives the BOM's content digest. Delivery runs the current protected-base client,
verifies the successful main publish and snapshot checksums, and deploys the artifact's
manifest/config rather than whatever is currently in the checkout. Staging deploys one release ID. Production passes that same ID via
`--proven-release-id`, requires environment approval and a fresh backup, and never rebuilds.
Deployment evidence binds both the latest Flyway migration identity and the cumulative
ordered-history checksum. A forward deployment recomputes the candidate prefix through the
currently deployed migration and rejects any changed, removed, renamed, or inserted prior
migration before Dokploy mutation. A lower migration (or the one-time transition from
legacy evidence without a full history checksum) requires the protected
`rollback_compatibility_attested` input; same-version or forward history rewrites are always
rejected. Without proven backward compatibility, pause and roll forward.
The single `bootstrap_bom` exception accepts a fresh completed backup explicitly marked
`manual` only when Dokploy's latest successful deployment is its exact `Manual deployment`
sentinel. Staging uses it to move the verified plan-081 deployment onto its first BOM.
Production additionally requires the same staging-proven BOM, its protected Environment
approval, and `rollback_compatibility_attested`; this is the only first-production-BOM path.
Every later backup must match the currently successful compose release ID exactly.

Review deployment is default-off. A protected-base manual workflow must bind approval to
the exact current SHA, re-query the PR, reject forks, resolve its own image digests, and use
only ephemeral credentials. Configure the `review`, `staging`, and `production` GitHub
Environments to allow deployments only from protected `main`; each workflow also rejects a
dispatch whose `GITHUB_REF` is not the default branch. Private GHCR resolution authenticates in Actions, and every
eligible Dokploy worker must have pull credentials. Set a fixed `MAX_ACTIVE` and set
`BUILDHOUND_REVIEW_TTL_HOURS` to a base-10 value between 1 and 87600 in the review
environment. Prove
review cannot reach staging/production before enabling it. Cleanup removes the concrete
route before the Stack, rechecks PR state, verifies ownership metadata and exact returned
IDs, and the scheduled reconciler repeats this for closed, unlabelled, or expired reviews.

Use a separate protected-main `review-cleanup` Environment for close/unlabel and scheduled
teardown. It carries the same secret names as `review`, but its Dokploy token is restricted
to listing and deleting resources in the review environment and it has no human approval
gate, so teardown is immediate. The approval-gated `review` Environment remains deploy-only.

Trusted cleanup also removes superseded and closed-review GHCR versions. It considers only
the fixed server/site packages, requires the version to have exactly one full-SHA tag for the
named PR, and verifies a successful trusted review-image workflow run for that exact
repository/PR/SHA before deleting the numeric package-version ID. Shared, ambiguous, or unverifiable versions are preserved and
fail the job; prefix-only deletion is never used. Stack teardown remains immediate on
close/unlabel and is backstopped by the scheduled reconciler.
