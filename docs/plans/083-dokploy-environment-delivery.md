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
- A successful protected review smoke records exact PR-head status and deployment evidence.
  After a qualifying labelled PR merges, the successful main-branch publisher completion starts
  staging through `workflow_run`. Staging accepts only review proof for that unchanged PR head
  whose GitHub merge commit is the candidate BOM source, then records the review proof with its
  digest/returned deployment IDs. Production remains `workflow_dispatch`-only, accepts only that
  staging-proven BOM and transitive review proof, requires protected-environment approval and a
  fresh backup, and never rebuilds. An optional first-BOM staging bootstrap may remain manual.
  Review, publisher, and release mutation groups use `queue: max` so concurrent lifecycle events
  are not silently replaced. Review mutation serialization starts only after Environment
  protection checks pass, leaving teardown live while a deployment is queued.
  Since queue order is not authoritative, schema-2 deployment evidence also records the source
  commit. Staging and production prove the candidate is on current-main lineage and compare it
  with the currently deployed source immediately before mutation. Staging accepts only identical
  or forward movement; production rollback requires explicit protected attestation and diverged
  history always fails closed. Backup evidence names the exact current release and the client
  reasserts that predecessor immediately before its first mutation.
  Use distinct narrowest-available review, staging,
  and production Dokploy credentials. Supply the common non-secret Dokploy HTTPS origin as
  the repository variable `DOKPLOY_URL`, while every protected GitHub Environment supplies
  its own `DOKPLOY_TOKEN` secret. A forward deployment must reproduce the current
  migration-history prefix exactly; previous-BOM rollback requires explicit migration
  compatibility attestation. Otherwise pause and roll forward. Long-lived delivery also
  clears and verifies every Dokploy field that could replace or transform the protected raw
  Stack, rejects attached Dokploy domains, mounts, or backups, and leaves the digest-based
  site Application's registry relations unset so v0.29.12 cannot re-tag and push it.
- Keep the PR-triggered image job CI-only with content-read permission, no registry login, and
  no package, Dokploy, Environment, backup, or object-store credential. After label authorization,
  protected default-branch workflow code checks out the exact same-repository head without
  persisted Git credentials, builds the two untrusted Dockerfiles before registry login,
  revalidates PR/head/label, constrains output tags to `pr-<N>-<full-head-sha>`, and publishes a
  run-scoped manifest binding PR, head SHA, protected workflow run, names, and push digests.
  Fork PRs receive CI only.
- Adding `deploy-review` to a same-repository PR triggers the privileged protected-base review
  workflow. `synchronize` and `reopened` redeploy the new/current head while the label remains;
  removing the label or closing the PR selects deletion. The `review` Environment remains the
  authorization boundary for every deployment. The workflow re-queries state and aborts on a
  fork, closed PR, missing label, or changed head before publishing and again before mutation.
- The privileged deploy job never executes PR workflows, scripts, manifests, or arbitrary
  artifacts on the runner; only the two PR Dockerfiles execute inside credential-free builds.
  It uses protected-base manifest/render logic, performs tagging/pushing only after those builds,
  verifies its strict run-scoped digest manifest, generates
  and masks ephemeral credentials, and deploys only trusted fields. It reasserts Dokploy's
  raw Stack source and disables Dokploy-side randomization/isolation transforms before every
  deployment, including partial-create retries.
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
  those certificates through Lego's Hetzner DNS-01 provider using a root-only mounted
  `HETZNER_API_TOKEN_FILE`; the DNS-write credential never enters GitHub or a review Stack.
  Review Stacks create ordinary concrete routers with TLS enabled but no certificate resolver
  or ACME domain labels, so they select the pre-warmed wildcard certificates instead of
  issuing per-PR certificates. Explicitly render a dedicated review-only external encrypted,
  attachable Swarm overlay named `buildhound-review-ingress` containing Dokploy's standalone
  Traefik and review workloads
  but no Dokploy control-plane, staging, or production services. Bind the enablement gate to a
  manager-side check of its live ID, overlay driver, Swarm scope, attachability, encrypted
  option, and membership; reject every other
  network name and select the dedicated network on each multi-network Traefik service. Keep
  the public name stable while deriving a separate repository-scoped internal prefix for
  Traefik router/service/middleware keys and Dokploy's suffixed application name, preventing
  another repository's same-numbered PR from colliding on a shared provider. Because the
  public host intentionally omits repository identity, require a review DNS suffix unique to
  this repository on any shared ingress
  provider. Treat Dokploy as source of truth: list only the review environment, verify
  repository/PR/SHA ownership metadata, then mutate exact returned IDs.
- Each review deployment persists a validated `<run ID>.<run attempt>` in its ownership metadata
  and deployment title. Failure cleanup acts only on that exact attempt, same-SHA redeploys are
  rejected before mutation, and scheduled reconciliation validates the GitHub run attempt so
  force-cancelled or otherwise failed attempts cannot remain routed indefinitely.
- Close/unlabel removes waiting jobs, requires a settled non-running Compose plus either
  exact-SHA terminal evidence or extended quiescence when no deployment row exists, then
  stops the exact-owned Stack after a final PR-state
  check and waits for both
  public routes to converge to 404, and only then removes the Dokploy ownership record. A non-cancellable
  reconciler deletes verified closed, unlabelled, expired, failed, or cancelled reviews and old
  PR image tags.
  Deployment rollback revokes public execution but preserves the record as a reconciliation
  anchor. An always-evaluated dependent job uses the separate `review-cleanup` Environment,
  reconciles exact failed or cancelled publish/deploy attempts, removes images, and only then deletes
  the exact attempted-SHA record. A different persisted SHA and its image are preserved;
  ambiguous active state leaves the anchor intact so a late worker cannot
  recreate an unowned Stack. Operators remove the label when that handler cannot quiesce the
  deployment, allowing the scheduled reconciler to retry (TTL remains the backstop).
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
- Rehearse one PR through label-triggered create, synchronize/reopened redeploy, wildcard TLS +
  authenticated ingest/read, unlabel/close cleanup, scheduled cleanup, and negative network
  checks. After cleanup, verify directly on the Swarm manager that the exact Stack/services
  are absent and the dedicated ingress contains only Traefik; an application-level 404 is not
  sufficient absence evidence. Rehearse automatic post-merge staging followed by a manual
  production dispatch with one identical BOM and all 081/082 gates.

## Risks

Dokploy credentials may retain broad authority; verify and record scope. PR images are
untrusted and receive only ephemeral review authority. SHA tags remain mutable, so only
protected-base delivery code may publish them, records the push digests immediately, and
deploys only the run-scoped digest manifest. Approval/cleanup races require final checks,
idempotence, non-cancellable teardown,
and scheduled reconciliation. The network isolation variable is still a point-in-time manual
attestation, so inspect the encrypted overlay immediately before the first label-triggered smoke
and reset the enablement gate after cleanup; review deployment remains gated until a live
network-identity check replaces that control. Same-SHA reruns are mechanically rejected; the
controlled rehearsal uses a new SHA for its update. Dokploy v0.29.12 also guards the safer `compose.stop`
cleanup call with deployment-create permission, so the cleanup token must be a review-scoped
custom role with service read/delete and deployment read/create—not the repository-wide
administration token—before that broader enablement. Because v0.29.12 assigns a newly created
service to the creator member, deploy and cleanup tokens must belong to the same least-privilege
member or deployment must hand the exact service to the cleanup member before relying on a
separate principal.

## Exit criteria

- A qualifying labelled merge starts staging automatically from the successful main publisher;
  production receives that staging-tested BOM/digests only after manual dispatch, approval,
  fresh backup, explicit deploy, and site/application/data-path checks.
- No rebuild/moving tag participates; migration-incompatible rollback fails closed and uses
  roll-forward.
- Once exact-SHA/network gates pass, one same-repository PR completes label-triggered creation,
  synchronize/reopened redeploy, and unlabel/close cleanup using Hetzner DNS-01 wildcard TLS,
  with scheduled reconciliation leaving no surviving state.
- No concrete ID, domain, credential, token, or generated value is committed or printed.
