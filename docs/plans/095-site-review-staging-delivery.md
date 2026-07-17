# 095 — Site review and staging delivery

## Source

Follow-up to plan 082's digest-pinned public-site delivery and plan 088's live staging
decision. Review environments already deploy the site; staging deliberately uses
`BUILDHOUND_SKIP_SITE_DEPLOY=true` because its separate Dokploy Application was not
provisioned. Enable that existing path without changing production delivery.

## Scope

**In:** preserve and regression-test the review site's exact-image deployment; provision and
enable the existing staging Dokploy site Application path; prove staging's site and dashboard
release together. **Out:** production changes, release-BOM/schema/client redesign, a new
site service topology, and copying `site/compose.yml` into a Swarm Stack.

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
  with registry pull access and no source build/auto-deploy. Configure its protected values:
  `BUILDHOUND_SITE_DASHBOARD_URL` as the exact staging HTTPS origin,
  `BUILDHOUND_SITE_HOST` as the staging host, and `BUILDHOUND_SITE_NOINDEX=true`; record the
  Application ID in the protected `staging` Environment as `DOKPLOY_SITE_APPLICATION_ID` and
  set the exact `BUILDHOUND_SITE_URL` variable. Confirm the staging role placement contract.
- Remove the temporary staging-only `BUILDHOUND_SKIP_SITE_DEPLOY=true` variable (do not set
  it to `false`). The existing workflow must then supply `--site-application-id`, and
  `dokploy.sh deploy-release` must retain its fail-closed Application update/readback:
  `sourceType=docker`, the BOM's `siteImage`, `autoDeploy=false`, null registry-transform
  fields, and the sole `node.labels.role==staging` constraint before `application.deploy`.
  The release evidence must contain a non-null site deployment ID.
- Keep production's Environment, Application configuration, manual approval, and exact
  release path unchanged. No fallback from a missing Application ID to `--skip-site` is
  permitted.

## Test strategy

- Extend deployment-policy tests to pin the review image digest/proof, review Stack site
  hardening/routing/noindex values, and the post-deploy public review site check before its
  dashboard ingest/read attestation.
- Pin staging workflow selection: an absent skip variable uses the site Application ID,
  performs exact state readback plus deploy, emits a non-null `siteDeploymentId`, and runs
  the site probe. Preserve the explicit skip-mode tests only as the historical mechanism,
  and preserve production's current behavior.
- Run `sh site/test/site-test.sh`, including the hardened image mode; the complete Dokploy
  policy suite, Stack rendering, ShellCheck, shell syntax, workflow YAML/actionlint checks,
  and `git diff --check`.

## Risks and rollback

The staging Application is a supply-chain boundary: only the BOM image digest may be set,
and Dokploy registry/build/rollback fields must remain isolated so it cannot retag or push.
Bad URL or role configuration must fail before deployment; do not log environment values.
If the site rollout fails after the dashboard Stack succeeds, stop promotion, retain the
evidence, fix the staging Application/configuration, and rerun the same immutable BOM. Do
not restore the skip variable as a silent success path; an emergency exception requires an
explicit owner decision and recorded divergence. Production credentials and configuration
never enter staging or review.

## Exit criteria

Prerequisites: staging Environment protections are active; the staging Dokploy Application,
registry pull relation, site-domain/TLS route, Application ID, and exact site/dashboard URL
variables are configured; its dashboard origin and host pass the site renderer's narrow
HTTPS/host contract.

A labeled same-repository PR proves review site `200`, `X-Robots-Tag: noindex, nofollow`,
the review-local dashboard link, and the existing authenticated ingest/read smoke from
digest-pinned images. Then a merge deploys staging without the skip: Dokploy readback matches
the exact staging Application contract, its deployment evidence has a site deployment ID,
the public staging site is `200` and noindex with the staging-local dashboard link, and the
dashboard health/ingest/read smoke passes. Production is not changed or deployed. Complete
fresh infrastructure and security/privacy reviews before merge.
