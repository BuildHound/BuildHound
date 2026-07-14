# 087 — Staging environment credentials

## Source

Owner decision on 2026-07-14 after the first manual staging Stack could not be
bootstrapped through Dokploy v0.29.12's supported API/UI: staging may use ordinary
service environment variables for database and backup credentials. Production keeps
the external-secret boundary from plan 081.

## Scope

**In:** a staging-only long-lived Stack variant; immutable release binding for both
staging and production manifests; target-safe manifest selection; policy, delivery,
runbook, and architecture updates.

**Out:** production credential weakening, review-environment changes, host-side secret
creation, Dokploy upgrades, application code, and schema migrations.

## Design

- Add a staging Stack with the same images, topology, placement, networks, read-only
  roots, tmpfs mounts, limits, routing, update policy, and volume guard as the
  production Stack. Staging passes PostgreSQL, libpq, and staging-only Hetzner
  credentials through service environment variables and declares no external Swarm
  secrets. The existing `stack.yaml` remains the production secret-backed manifest.
- Publish a schema-3 release BOM that hashes both manifests. Carry both exact source
  snapshots in the release artifact. Deployment chooses the staging hash/manifest
  only for trusted `app_role=staging` and the production hash/manifest only for
  `app_role=prod`, then rechecks the selected checksum before any Dokploy mutation.
  Both targets retain one canonical release ID and identical image digests.
- Keep credential values solely in the protected staging Dokploy environment. Disable
  xtrace and never print the environment or rendered Stack. Accept explicitly that
  staging credentials are visible to principals able to read Dokploy environment or
  Swarm service specifications; they must be staging-only, least-privilege, and
  independently rotatable. No production credential may enter this path.
- Use the staging manifest for the one-time manual `Manual deployment` anchor and for
  every automated staging release. Production remains manual-only and secret-backed.

## Validation

- Render both Stacks with representative values and run `validate-stack.sh` against
  their correct roles.
- Pin that staging contains environment credentials and no secret mounts/declarations,
  while production retains its external secret files and contains no backup credential
  environment variables.
- Test schema-3 exact keys, both manifest checksums, target-specific checksum rejection,
  release-ID stability, artifact contents, and workflow target selection.
- Run every Dokploy shell harness, Python policy suite, ShellCheck, shell syntax checks,
  YAML parsing, and fresh infrastructure plus security/privacy reviews.

## Exit criteria

- A raw manual staging deployment needs no pre-created Swarm secret objects.
- The staging backup emits a completed encrypted object marked `manual` without
  exposing credential values in repository files or deployment logs.
- Automatic staging uses only the BOM-bound staging manifest; production cannot select
  it and retains the plan-081 external-secret model.
