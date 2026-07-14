# 085 — Dokploy-managed isolated review deployments

## Source

Owner direction (2026-07-14) after the first PR review smoke: mutate Dokploy only through
its API or web UI and use Dokploy's documented **Isolated Deployments** option instead of a
manually managed review ingress overlay. This corrects plan 083's provisional network gate.

## Scope

**In:** review Compose isolation through Dokploy v0.29.12's API; removal of the custom
review-ingress network, host-side attestation variable, and manager mutation instructions;
persisted-state and manager-file verification; same-node placement; Traefik API hardening gate;
tracked retirement/reuse; workflow, tests, operator docs, and architecture decision.

**Out:** changes to long-lived staging/production isolation, direct Docker/Swarm/SSH
operations, automated global Traefik mutation from review CI, outbound-egress filtering, and certificate issuance.
The pre-warmed Hetzner DNS-01 wildcard certificates remain unchanged.

## Design

- Keep review Composes as trusted raw Stack deployments. Their manifest declares no networks
  and never names `dokploy-network`, an external ingress overlay, or a Traefik network
  override. Dokploy's injected application-name network is therefore every service's single
  network, avoiding Traefik's undefined selection behavior for multi-network services.
- After create (or when updating an exact-owned review), call Dokploy's supported
  `compose.update` API with `isolatedDeployment: true`. Dokploy v0.29.12's UI uses this field
  for the toggle; `compose.isolatedDeployment` is a preview operation, not the setting
  mutation. Read `compose.one` back and require the entire protected state, including the
  true isolation flag, before `compose.deploy`.
- Before any review mutation, require `settings.getDokployVersion` to return exactly
  `v0.29.12`; a different or malformed response blocks deploy and cleanup because retained
  anchor reuse relies on this release's exact isolated-deployment shell behavior.
- Remove `DOKPLOY_REVIEW_INGRESS_NETWORK` and
  `BUILDHOUND_REVIEW_NETWORK_ISOLATION_VERIFIED` from the workflow contract. All review
  create/update/deploy/stop/retire changes continue through the Dokploy API client only.
- Constrain site, server, and DB to `node.labels.buildhound.traefik == true`. Through Dokploy's
  API or web UI, operators must verify exactly one Ready/Active node has that label and that it
  hosts standalone Traefik. This prevents review traffic from traversing Dokploy v0.29.12's
  unencrypted isolated overlay between hosts without introducing host-side mutation.
- Before enabling the label trigger, use Dokploy's UI or admin API to set and read back
  `api.insecure: false`, preferably `api.dashboard: false`, remove any unprotected
  `api@internal` router, and reload Traefik. Dokploy v0.29.12 otherwise exposes its
  unauthenticated API to every isolated review network. Protect review deployment with the
  `BUILDHOUND_REVIEW_TRAEFIK_API_INSECURE_DISABLED=true` operator attestation, resetting it
  whenever Dokploy or Traefik configuration/version changes. Do not grant the automatic review
  token owner/admin access for a live global-config read.
- Do not call v0.29.12 `compose.delete` for an isolated review: it removes the Stack and
  disconnects Traefik but can leave the external application-name network orphaned. Close,
  unlabel, TTL, and failed-attempt cleanup instead stop the Stack, wait for both routes to return
  404, update it to the pinned zero-replica `review-anchor.yaml`, and verify the full isolated
  database state. Because `compose.update` is database-only in v0.29.12, deploy the inert anchor
  under a unique title, wait for one exact successful deployment, semantically verify the
  manager-side file through `compose.getConvertedCompose`, then stop it again before cleaning
  exact-owned images and finally setting and verifying `retired:true`. Preserve `activatedAt`
  throughout cleanup. Reuse that exact Compose/network anchor for a later deployment of the same PR.
- Exclude retired anchors from `MAX_ACTIVE` and scheduled reconciliation. Record a fresh
  `activatedAt` on every deployment and use it for TTL so reusing an old anchor does not cause
  immediate retirement. Active same-SHA redeploys remain forbidden; a retired anchor may be
  reactivated at the same SHA.
- Amend plan 083 and the implementation/operator records to replace the provisional overlay
  model with Dokploy-owned per-application networking. Record honestly that this isolates
  the review Compose from other Dokploy applications but is not an outbound firewall or, by
  itself, proof that public staging/production endpoints are unreachable.

## Test strategy

- Extend the fake-Dokploy lifecycle test to require `isolatedDeployment: true`, verify API
  create/update/readback/deploy order, and fail closed on persisted isolation drift.
- Cover materialized secret-scrubbing retirement, inert deployment failure and drift, legacy records without an attempt ID, reuse of a
  retired same-SHA anchor, fresh activation timestamps, active-count exclusion, reconciler skip,
  and the absence of `compose.delete` or host Docker/SSH mutation.
- Assert the review Stack contains no network declaration, `dokploy-network`, or
  `traefik.swarm.network` selection and still renders as a valid Stack.
- Preserve exact GHCR ownership checks across force-pushes by accepting only the same PR's
  paginated `HeadRefForcePushedEvent.beforeCommit` history when the commit-to-PR endpoint no
  longer proves a displaced head. Re-read exact numeric-version tags immediately before DELETE
  and share the main publisher/review lifecycle concurrency lock so protected workflows cannot
  retag between verification and deletion. Keep only post-success superseded-image GC
  warning-only; teardown and reconciliation remain strict.
- Update CLI/workflow policy tests, run the complete Dokploy policy suite, ShellCheck, shell
  syntax checks, workflow YAML parsing, and `git diff --check`.
- After merge, retrigger PR 24 and require both wildcard-TLS review URLs plus authenticated
  ingest/read smoke to pass before proceeding to staging; production remains manual.

## Risks

Dokploy's transform adds its one application network to every Compose service, so site,
server, database, and Traefik are not segmented from one another inside a review. A Dokploy
version/API drift could silently ignore the toggle; exact post-update readback prevents
deployment in that case.
Dokploy's v0.29.12 isolated deploy reconnects an already-attached Traefik container without an
idempotence guard. In this exact release the surrounding shell brace/`||` structure masks that
nonzero connect after a successful Stack deploy; the strict installed-version gate is therefore
required for retained-anchor reuse. Removing the custom overlay narrows the claimed isolation guarantee, so documentation must
not retain the earlier negative-network attestation claim.
Zero nodes carrying the placement label leave tasks pending; more than one permits cross-node
traffic. The exactly-one-node prerequisite is therefore an operator gate verified through
Dokploy's API/UI.
Retirement deliberately leaves one stopped, scrubbed Compose/network anchor per historical PR.
This accumulation is excluded from the active ceiling but remains visible and operator-accounted;
final deletion is deferred until Dokploy provides a supported exact operation that also removes
the isolated network.

## Exit criteria

- No review workflow, manifest, client, or runbook requires or creates a custom ingress
  network or a host-side isolation attestation.
- Every review deploy persists and verifies `isolatedDeployment: true` through Dokploy's API
  before deployment, with tests covering drift and call order.
- Cleanup leaves no running or publicly routed review workload, retains no review credential in
  either Dokploy's database definition or manager-side materialized file, and verifies one tracked reusable retired anchor instead of orphaning
  an external network. Retired anchors neither count as active nor re-enter reconciliation.
- Operator evidence confirms exactly one Ready/Active `buildhound.traefik=true` node colocated
  with standalone Traefik, and confirms the insecure Traefik API is disabled, before the label
  trigger is enabled.
- Clean-context infrastructure and security/privacy reviews have no unresolved blocker.
- PR 24's review deploy and both public smoke URLs pass before staging is attempted.
