# 096 — Site review and staging delivery

## Source

Follow-up to plan 082's digest-pinned public-site delivery and plan 088's live staging
decision. Review environments already deploy the site; staging deliberately uses
`BUILDHOUND_SKIP_SITE_DEPLOY=true` because its separate Dokploy Application was not
provisioned. Enable that existing path without changing production delivery.

## Scope

**In:** preserve and regression-test the review site's exact-image deployment; provision and
enable the existing staging Dokploy site Application path; prove staging's site and dashboard
release together. **Out:** production workflow/environment/approval/deploy changes, a
production deployment, release-BOM/schema/client redesign, a new site service topology, and
copying `site/compose.yml` into a Swarm Stack. The next shared site image intentionally
disables Nginx access logging: this narrow privacy hardening applies wherever that image runs,
but does not otherwise change production delivery.

## Design

- Keep review as the trusted `review-stack.yaml` site service: its PR-head image is built
  without registry credentials, pushed by digest, constrained to `role=review`, rendered
  with its bounded long-form `/tmp` tmpfs, and routed through its isolated review network.
  Its dashboard origin stays the review dashboard and its robots header stays `noindex,
  nofollow`.
- Treat `site/compose.yml` as the minimal standalone Docker Compose contract only: exact
  `BUILDHOUND_SITE_SHA256` image, validated dashboard origin/host/noindex inputs, loopback
  port, and container hardening. It is not a Swarm template; the long-lived site remains a
  Dokploy Docker-image Application and review remains the trusted combined Stack.
- Before changing repository configuration, create the staging Application through Dokploy
  with registry pull access and no source build/auto-deploy. Bind its exact public hostname to
  the TLS-enabled staging route, the Application's runtime image/port contract, and its sole
  `node.labels.role==staging` placement. Configure protected values:
  `BUILDHOUND_SITE_DASHBOARD_URL` as the exact staging HTTPS origin,
  `BUILDHOUND_SITE_HOST` as the staging host, and `BUILDHOUND_SITE_NOINDEX=true`; record the
  Application ID in the protected `staging` Environment as `DOKPLOY_SITE_APPLICATION_ID` and
  set the exact `BUILDHOUND_SITE_URL` variable. Confirm the staging role placement contract.
- Remove the temporary staging-only `BUILDHOUND_SKIP_SITE_DEPLOY=true` variable (do not set
  it to `false`). The existing workflow must then supply `--site-application-id`, and
  `dokploy.sh deploy-release` must retain its fail-closed Application update/readback:
  `sourceType=docker`, the BOM's `siteImage`, `autoDeploy=false`, null registry-transform
  fields, no direct or advanced-Swarm published ports, no mounts, and the sole
  `node.labels.role==staging` constraint before `application.deploy`. Bind the configured
  dashboard origin independently to the selected Compose environment's dashboard host.
  Deploy and attest the Compose before starting the site Application so a failed Compose
  cannot leave an unrecorded split release.
  Promotion must validate the pending, failure, and success deployment states and the
  run-scoped attestation, rather than treating optional PR metadata as evidence. The release
  evidence must contain a non-null site deployment ID; retain partial deployment evidence as
  an attempt-scoped artifact when a rollout fails or is rerun.
- Keep workflow-dispatch rollback inputs candidate-bound while running delivery controls from
  the trusted workflow revision: render the candidate manifests, migrations, and volume guard
  with the current renderer, then deploy them with the current Dokploy client and verifier.
  Historical candidates must not need to understand flags added by this plan.
- Bootstrap the protected `pull_request_target` producer/consumer schema change explicitly.
  The producer used for this PR comes from pre-change `main`, so only PR 38 on its exact head
  branch may qualify with the exact legacy schema; that proof must name the current run
  attempt, use digest-pinned images, and come from a protected run created strictly after the
  latest review-request event. Every other PR requires the new attestation's exact lifecycle
  event ID. Remove the one-shot compatibility branch after this rollout reaches staging.
- Keep production's Environment, Application configuration, manual approval, and exact
  release path unchanged, and authorize no production deployment. The shared image disables
  Nginx access logging to eliminate unbounded visitor-metadata retention. No fallback from a
  missing Application ID to `--skip-site` is permitted.

## Test strategy

- Extend deployment-policy tests to pin the review image digest/proof, review Stack site
  hardening/routing/noindex values, and the post-deploy public review site check before its
  dashboard ingest/read attestation.
- Pin staging workflow selection: an absent skip variable uses the site Application ID,
  performs exact state readback plus deploy, emits a non-null `siteDeploymentId`, and runs
  an exact public probe (`200`, `X-Robots-Tag: noindex, nofollow`, and the staging-local
  dashboard link). Cover pending, failure, and success deployment outcomes plus the
  run-scoped attestation; assert partial evidence is uploaded on failure. Preserve the
  explicit skip-mode tests only as the historical mechanism, and preserve production's
  current workflow behavior.
- Cover the protected-workflow schema bootstrap, dispatching an older candidate through the
  current delivery controls, advanced Swarm/direct port and mount rejection, cross-environment
  dashboard-origin rejection, sequential Compose/site submission, partial evidence, and
  bounded site-probe connection/response timeouts.
- Pin the site image's Nginx configuration so access logging is disabled; this prevents
  unbounded retention of visitor metadata without enabling a production deploy.
- Run `sh site/test/site-test.sh`, including the hardened image mode; the complete Dokploy
  policy suite, Stack rendering, ShellCheck, shell syntax, workflow YAML/actionlint checks,
  and `git diff --check`.

## Risks and rollback

The staging Application is a supply-chain boundary: only the BOM image digest may be set,
and Dokploy registry/build/rollback fields must remain isolated so it cannot retag or push.
Bad route/TLS/runtime, URL, or role configuration must fail before deployment; do not log
environment values. If the site rollout fails after the dashboard Stack succeeds, stop
promotion, retain and upload the partial deployment evidence, fix the staging
Application/configuration, and rerun the same immutable BOM. Do not restore the skip variable
as a silent success path; an emergency exception requires an explicit owner decision and
recorded divergence. Production credentials and configuration never enter staging or review.

## Exit criteria

Prerequisites: staging Environment protections are active; the staging Dokploy Application,
registry pull relation, site-domain/TLS route, Application ID, and exact site/dashboard URL
variables are configured; its dashboard origin and host pass the site renderer's narrow
HTTPS/host contract.

A labeled same-repository PR proves review site `200`, `X-Robots-Tag: noindex, nofollow`,
the review-local dashboard link, and the existing authenticated ingest/read smoke from
digest-pinned images. Then a merge deploys staging without the skip: Dokploy readback matches
the exact staging Application contract, its deployment evidence has a site deployment ID,
the public staging site is exactly `200` with `X-Robots-Tag: noindex, nofollow` and the
staging-local dashboard link, and the dashboard health/ingest/read smoke passes. Promotion
has verified pending/failure/success states and the run-scoped attestation; failed rollouts
retain their partial evidence artifact. Production workflow/environment/approval/deploy
behavior is unchanged and no production deployment is authorized, apart from the next shared
image having access logging disabled. Complete fresh infrastructure and security/privacy
reviews before merge.
