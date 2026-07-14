# 085 — Dokploy-managed isolated review deployments

## Source

Owner direction (2026-07-14) after the first PR review smoke: mutate Dokploy only through
its API or web UI and use Dokploy's documented **Isolated Deployments** option instead of a
manually managed review ingress overlay. This corrects plan 083's provisional network gate.

## Scope

**In:** review Compose isolation through Dokploy v0.29.12's API; removal of the custom
review-ingress network, host-side attestation variable, and manager mutation instructions;
persisted-state verification; workflow, tests, operator docs, and architecture decision.

**Out:** changes to long-lived staging/production isolation, direct Docker/Swarm/SSH
operations, custom Traefik lifecycle, outbound-egress filtering, and certificate issuance.
The pre-warmed Hetzner DNS-01 wildcard certificates remain unchanged.

## Design

- Keep review Composes as trusted raw Stack deployments. Their manifest declares only the
  review's internal application network and never names `dokploy-network`, an external
  ingress overlay, or a Traefik network override.
- After create (or when updating an exact-owned review), call Dokploy's supported
  `compose.update` API with `isolatedDeployment: true`. Dokploy v0.29.12's UI uses this field
  for the toggle; `compose.isolatedDeployment` is a preview operation, not the setting
  mutation. Read `compose.one` back and require the entire protected state, including the
  true isolation flag, before `compose.deploy`.
- Remove `DOKPLOY_REVIEW_INGRESS_NETWORK` and
  `BUILDHOUND_REVIEW_NETWORK_ISOLATION_VERIFIED` from the workflow contract. All review
  create/update/deploy/stop/delete changes continue through the Dokploy API client only.
- Amend plan 083 and the implementation/operator records to replace the provisional overlay
  model with Dokploy-owned per-application networking. Record honestly that this isolates
  the review Compose from other Dokploy applications but is not an outbound firewall or, by
  itself, proof that public staging/production endpoints are unreachable.

## Test strategy

- Extend the fake-Dokploy lifecycle test to require `isolatedDeployment: true`, verify API
  update precedes readback and deploy, and fail closed on persisted isolation drift.
- Assert the review Stack contains no external ingress network, `dokploy-network`, or
  `traefik.swarm.network` selection and still renders as a valid Stack.
- Update CLI/workflow policy tests, run the complete Dokploy policy suite, ShellCheck, shell
  syntax checks, workflow YAML parsing, and `git diff --check`.
- After merge, retrigger PR 24 and require both wildcard-TLS review URLs plus authenticated
  ingest/read smoke to pass before proceeding to staging; production remains manual.

## Risks

Dokploy's transform adds its application network to every Compose service, so it does not
provide service-level segmentation inside one review. A Dokploy version/API drift could
silently ignore the toggle; exact post-update readback prevents deployment in that case.
Removing the custom overlay narrows the claimed isolation guarantee, so documentation must
not retain the earlier negative-network attestation claim.

## Exit criteria

- No review workflow, manifest, client, or runbook requires or creates a custom ingress
  network or a host-side isolation attestation.
- Every review deploy persists and verifies `isolatedDeployment: true` through Dokploy's API
  before deployment, with tests covering drift and call order.
- Clean-context infrastructure and security/privacy reviews have no unresolved blocker.
- PR 24's review deploy and both public smoke URLs pass before staging is attempted.
