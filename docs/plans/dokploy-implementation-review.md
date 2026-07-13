# Dokploy plans 081–083 — overarching implementation review

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
  network-isolation gate. The trusted Stack pins UIDs/capabilities, the client waits for the
  exact deployment, and HTTPS smoke proves a fresh authenticated write/read. Close/unlabel
  and hourly reconciliation verify exact ownership in the fixed review environment; GHCR
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

## Environment gates still requiring operator evidence

Repository tests cannot claim success for installed-version or external-system facts. Keep
production and reviews disabled until `deploy/dokploy/README.md` records: the installed
Dokploy version/API responses and returned deployment IDs; secret scoping; GHCR pulls on
all eligible workers; concrete domains/certificates; review-to-long-lived negative network
tests; Hetzner versioning/lifecycle behavior; an encrypted fresh-volume restore; measured
RPO/RTO; authenticated persistence/ceiling/429 checks; and the V2 mark recognition/collision
decision. GitHub must also have protected-main-only `review`, `review-cleanup`, `staging`,
and `production` Environments; cleanup uses a no-approval list/delete-only review token.
These are fail-closed launch gates, not deferred code substitutions.

One reviewed residual is explicitly accepted within the reconciled plan boundary: the
server has no `BUILDHOUND_DB_PASSWORD_FILE` support, so its per-review random password is
manager-visible in Dokploy even though it is short-lived, masked in Actions, unique per
deployment, and deleted with the review resource. Eliminating that visibility requires the
separate server `*_FILE` plan named in the handoff; this PR does not smuggle that server
change into infrastructure work. Review deployment remains disabled behind the isolation
probe until the owner accepts both the installed-version secret scope and network result.

## Validation

The repository validation set is `./gradlew build`, long-lived and review Stack rendering,
Python delivery tests, volume-guard tests, shell/static site policy checks, container image
builds, a read-only non-root live-site header/render smoke, ShellCheck, and `git diff --check`.
