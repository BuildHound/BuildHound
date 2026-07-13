# 083 — Dokploy release delivery and review lifecycle

## Source

Deployment request (2026-07-10), reconciled on 2026-07-12. Plans 081–082 own manually
deployable long-lived resources. This plan adds trusted promotion and, only after security
gates pass, ephemeral per-PR reviews.

## Scope

**In:** immutable release BOMs; staging/production delivery; exact-SHA approval; trusted
review manifests; ephemeral routes/resources; active ceiling; teardown/TTL reconciliation.

**Out:** long-lived resource creation, application/storage changes, payload offload,
moving-tag deployment, fork reviews, persistent review DBs/backups/S3 credentials, and a
capacity allocator/global inventory service.

## Design

- Keep Dokploy API behavior in tested scripts under `deploy/dokploy/`; workflows orchestrate
  them. Trusted CI resolves digests and publishes digest-addressed `release.json` containing
  source commit, server/site/backup/PostgreSQL digests, manifest/config checksums, and the
  complete numerically ordered Flyway migration IDs/source checksums. Its digest is the
  release ID; mutable tags are aliases only. Plans 081/082 remain
  manually deployable.
- Staging explicitly deploys one BOM and records its digest/returned deployment IDs.
  Production accepts only that staging-proven BOM, requires protected-environment approval
  and a fresh backup, and never rebuilds. Use distinct narrowest-available review, staging,
  and production Dokploy credentials. Supply the common non-secret Dokploy HTTPS origin as
  the repository variable `DOKPLOY_URL`, while every protected GitHub Environment supplies
  its own `DOKPLOY_TOKEN` secret. A forward deployment must reproduce the current
  migration-history prefix exactly; previous-BOM rollback requires explicit migration
  compatibility attestation. Otherwise pause and roll forward.
- Build same-repository PR images in an unprivileged job with no Dokploy, environment,
  backup, or object-store secret and only package permission for
  `pr-<N>-<full-head-sha>`. Fork PRs receive CI only.
- A label means eligibility, not authorization. A privileged `workflow_dispatch` defined and
  run from protected base names repository, PR, and exact head SHA, and requires GitHub
  `review` Environment approval for every SHA. It then re-queries state and aborts on fork,
  closed PR, missing label, or changed head; every push requires reapproval.
- The privileged job never executes PR workflows, scripts, manifests, or artifacts. It uses
  protected-base manifest/render logic, resolves full-SHA tags to digests itself, generates
  and masks ephemeral credentials, and deploys only trusted fields.
- A trusted review Stack contains site, server, and container-local DB: no named volume,
  backup/S3 credential, production data, privilege, host bind/socket/network, or arbitrary
  external network. Site/server are read-only with tmpfs. Fixed reservations and a constant
  `MAX_ACTIVE` replace allocation machinery. Reviews stay disabled until negative network
  tests prove isolation from staging/production while retaining ingress.
- Derive the deterministic review name `mr<PR number>`. Its exact site FQDN is
  `mr<PR number>.<review DNS suffix>` and its dashboard FQDN is
  `mr<PR number>.dashboard.<review DNS suffix>`. Wildcard DNS and two pre-warmed wildcard
  certificates are prerequisites: one covers a single label below the review suffix and one
  covers a single label below its dashboard subdomain. Global Traefik configuration requests
  those certificates through Lego's Hetzner DNS-01 provider using `HETZNER_API_TOKEN`.
  Review Stacks create ordinary concrete routers with TLS enabled but no certificate resolver
  or ACME domain labels, so they select the pre-warmed wildcard certificates instead of
  issuing per-PR certificates. Explicitly render the trusted external ingress-network name and
  select that network on each multi-network Traefik service. Keep the public name stable while
  deriving a separate repository-scoped internal ID for Dokploy's application name and
  Traefik router/service/middleware keys, preventing another repository's same-numbered PR
  from colliding on a shared provider. Because the public host intentionally omits repository
  identity, require a review DNS suffix unique to this repository on any shared ingress
  provider. Treat Dokploy as source of truth: list only the review environment, verify
  repository/PR/SHA ownership metadata, then mutate exact returned IDs.
- Close/unlabel removes routes before Stack after a final PR-state check. A non-cancellable
  reconciler deletes verified closed, unlabelled, or expired reviews and old PR image tags.
  Reviews use synthetic data, noindex on site and dashboard, a nonzero host-limit backstop,
  and no object-storage credentials.

## Test strategy

- Test BOM parsing/checksums, staging-equality promotion, migration compatibility, and
  rejection of mutable/mismatched references.
- Against a fake Dokploy API, test derivation, partial-retry idempotence, ownership, active
  ceiling, stale events, exact-ID teardown, and orphan reconciliation.
- Prove fork/unprivileged jobs receive no deploy secret; privileged jobs use protected content,
  revalidate SHA, suppress secrets, and cannot clean outside the review environment.
- Assert the trusted review manifest cannot select an ACME resolver or leave its external
  ingress network unresolved. Before deployment, verify the live certificate store serves a
  publicly trusted certificate matching both review wildcard depths.
- Rehearse one PR through create, push rejection, reapproval/update, wildcard TLS +
  authenticated ingest/read, immediate cleanup, scheduled cleanup, and negative network
  checks. Rehearse staging→production with one identical BOM and all 081/082 gates.

## Risks

Dokploy credentials may retain broad authority; verify and record scope. PR images are
untrusted and receive only ephemeral review authority. SHA tags remain mutable, so privileged
jobs deploy resolved digests. Approval/cleanup races require final checks, idempotence,
non-cancellable teardown, and scheduled reconciliation.

## Exit criteria

- Production receives the staging-tested BOM/digests after approval, fresh backup, explicit
  deploy, and site/application/data-path checks.
- No rebuild/moving tag participates; migration-incompatible rollback fails closed and uses
  roll-forward.
- Review stays default-off until exact-SHA/network gates pass, then one same-repository PR
  completes reapproval/update using Hetzner DNS-01 wildcard TLS and immediate/scheduled
  cleanup with no surviving state.
- No concrete ID, domain, credential, token, or generated value is committed or printed.
