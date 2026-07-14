# Dokploy plans 081–085 — overarching implementation review

## Scope and ordering

The implementation preserves the plan boundaries and order. Plan 081 owns the long-lived
Stack, volume identity, backup image, recovery runbook, and stack policy checks. Plan 082
owns the standalone non-Gradle site image and runtime renderer. Plan 083 consumes both via
canonical release BOMs and adds protected delivery/review workflows. Neither 082 nor 083 is
required for an operator to deploy the long-lived Stack manually.

## Cross-plan invariants

- Every deployable image in a release is digest-addressed; moving tags are rejected. The
  PostgreSQL wrapper image carries the volume guard, so raw remote Stack delivery has no
  checkout-local file dependency. The BOM artifact carries checksum-bound Stack/guard
  snapshots and uses the current protected-base client to deploy them.
- PostgreSQL remains authoritative. Backup egress exists only on the backup service, dumps
  stream through `age`, and the online environment contains no private age key.
- PGDATA identity is a durable sibling marker so first initialization remains compatible
  with the official entrypoint while empty, foreign, wrong-instance, and wrong-major
  volumes fail closed.
- The site and server/DB Stack are separate Dokploy resources. The site validates a narrow
  HTTPS origin before atomic rendering and runs non-root with a read-only root and tmpfs.
- Production consumes a BOM from a successful main-branch publish run, verifies the same
  release ID from a staging-run attestation, and heads a recent encrypted backup object.
  Deployment evidence binds the complete ordered migration manifest and its cumulative
  checksum; forward candidates must match the deployed history prefix exactly. Rollback
  requires explicit compatibility attestation, while same-version and forward-history
  rewrites are rejected.
- Review images build without deployment/object-store secrets. A protected-base dispatch
  revalidates repository/label/exact SHA, resolves image digests, generates masked ephemeral
  credentials, enforces a global serialized active ceiling, and requires the recorded
  Dokploy isolated-deployment setting. The trusted Stack pins UIDs/capabilities, the client
  verifies that setting through the API before deployment and waits for the
  exact deployment, and HTTPS smoke proves a fresh authenticated write/read. Close/unlabel
  and hourly reconciliation verify exact ownership, stop public execution, scrub the stored
  Compose, and retain one idle isolated `retired:true` anchor in the fixed review environment; GHCR
  cleanup proves the full-SHA tag's PR association before deleting an unshared numeric
  version. Review has no volume, backup, or S3 credential.

## Independent review results

Fresh-context infrastructure/frontend and security/privacy reviews were run over the whole
diff. Their initialization-marker, backup atomicity/restore, remote-config delivery,
pipeline-integrity, URL/redirect validation, cache/accessibility, API-contract,
promotion-provenance, migration, ephemeral-credential, cleanup-race, runtime-confinement,
active-ceiling, deployment-evidence, and reconciler findings were corrected. The
implementation now targets the documented Dokploy `x-api-key` endpoints rather than an
inferred API shape.
The final plan-085 pass found and corrected two additional ordering races: the exact Dokploy
version gate now precedes the registry credential test that refreshes manager login state, and
GHCR cleanup re-reads the exact numeric version immediately before deletion while the trusted
main publisher shares its concurrency lock. Follow-up review found no blocker. The remaining
duplicated jq ownership parser is accepted as a low-priority maintainability issue; both copies
are covered by the lifecycle tests and must remain schema-identical until extracted.

## Environment gates still requiring operator evidence

Repository tests cannot claim success for installed-version or external-system facts. Keep
production and reviews disabled until `deploy/dokploy/README.md` records: the installed
Dokploy version/API responses and returned deployment IDs; secret scoping; GHCR pulls on
all eligible workers; concrete domains/certificates; Dokploy-isolated review routing;
Ready/Active `role=review` workers and successful URL plus authenticated ingest/read smoke;
Hetzner versioning/lifecycle behavior; an encrypted fresh-volume restore; measured
RPO/RTO; authenticated persistence/ceiling/429 checks; and the V2 mark recognition/collision
decision. GitHub must also have protected-main-only `review`, `review-cleanup`, `staging`,
and `production` Environments. Cleanup remains approval-free, but its dedicated token needs
service read/create plus deployment read/create for the guarded Dokploy v0.29.12 update,
materialized scrub deployment, stop, and retire flow; it must not reuse a broader deployment token. Verify the node prerequisite only through
Dokploy's API/UI; no host Docker/SSH mutation is part of this workflow.
These are fail-closed launch gates, not deferred code substitutions.

One reviewed residual is explicitly accepted within the reconciled plan boundary: the
server has no `BUILDHOUND_DB_PASSWORD_FILE` support, so its per-review random password is
manager-visible in Dokploy while the review is active even though it is masked in Actions and
unique per deployment. Retirement updates the Compose to a pinned zero-replica credential-free
definition, deploys it, verifies the manager-side materialized file through Dokploy's API, and
stops it again before registry cleanup. Eliminating active-time visibility requires the
separate server `*_FILE` plan named in the handoff; this PR does not smuggle that server
change into infrastructure work. Review deployment remains fail-closed on isolation readback
and installed-version secret scope. Dokploy's isolated application network is not recorded as
an outbound-egress firewall. Because v0.29.12 `compose.delete` can orphan that external network,
one tracked stopped Compose/network anchor remains per historical PR until Dokploy supports an
exact network-aware deletion lifecycle; retired anchors do not count toward active capacity.
Plan 086 supersedes the earlier same-node placement decision: review services use
`role=review`, long-lived application services use `role=staging` or `role=prod`, and DB/backup
remain on `role=db`. The isolated review overlay may cross hosts and is not encrypted in this
Dokploy version. Readiness stays with bounded URL plus authenticated ingest/read smoke because
v0.29.12's `docker:read` permission also authorizes Docker mutations outside the exact review.
Dokploy v0.29.12 also defaults Traefik to `api.insecure:true`; review labels remain disabled until
an operator uses Dokploy's UI/admin API to set and read back `api.insecure:false` (preferably
`api.dashboard:false` with no unprotected `api@internal` router), reloads Traefik, and records the
protected attestation. The automatic review token remains least privilege rather than receiving
owner/admin access for live global-config reads.

## Validation

The repository validation set is `./gradlew build`, long-lived and review Stack rendering,
Dokploy shell-client tests, volume-guard tests, shell/static site policy checks, container image
builds, a read-only non-root live-site header/render smoke, ShellCheck, and `git diff --check`.
