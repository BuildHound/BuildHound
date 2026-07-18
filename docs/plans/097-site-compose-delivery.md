# 097 — Site delivery via Dokploy Compose stacks

## Source

Owner decision (2026-07-18): remove the staging and production Dokploy Site
**Applications** and deliver the public site through Dokploy **Compose** apps instead,
one per environment. This supersedes plan 096's design bullet that "the long-lived site
remains a Dokploy Docker-image Application".

Trigger: deploy run 29620297919's staging leg failed fail-closed at the client's
`site Application lacks Dokploy v0.29 Docker-provider pull credentials` guard. The
Application path carries a second, legacy registry-auth model (per-Application
`username`/`password` Docker-provider fields) in parallel with the Compose path the
dashboard stacks already use, which pulls digest-pinned GHCR images without per-app
credentials. Two auth models for one image is operational surface with no benefit;
the Compose path is the proven one. Removing the Application path also deletes the
guard that broke the run and plan 091's outstanding staging live-verification leg —
the labeled merge run of this plan doubles as plan 091's staging + production cycle.

`site/compose.yml` stays what plan 096 made it: the minimal **standalone**
Docker Compose contract, pinned by `site/test/site-test.sh` (loopback port, exact
digest-pinned image line, `docker compose config` validity). It is not the shipped
artifact: Docker's stack conversion drops its short-form tmpfs
(`deploy/dokploy/README.md` "tmpfs" note), its loopback publish cannot be routed by
the swarm Traefik, and two environments deploying it verbatim would collide on the
host port. The shipped artifact is a stack-shaped derivative, parity-tested against
it (see Design 4) so the two cannot drift silently.

## Scope

**In:** a new `deploy/dokploy/site-stack.yaml` swarm stack file (both roles, one
parameterized file); swapping the site path of `dokploy.sh deploy-release` from
`application.*` to `compose.*` endpoints; workflow flag/secret swap
(`DOKPLOY_SITE_APPLICATION_ID` → `DOKPLOY_SITE_COMPOSE_ID`); policy-suite and
`validate-stack.sh` coverage; `deploy/dokploy/README.md` site section;
`docs/architecture.md` §7 decision-log row; a superseded-note in plan 096.

**Out:** review-environment site delivery (stays the trusted `review-stack.yaml`
service); the site image, `site/render.sh`, `site/nginx.conf`, and
`site/compose.yml` semantics; `verify-release.sh` site checks (delivery-independent);
dashboard stacks; flipping production `BUILDHOUND_SKIP_SITE_DEPLOY` (separate owner
call — this plan makes the production site path *deployable*, going indexable-live is
its own decision).

## Design

1. **`deploy/dokploy/site-stack.yaml`** — one file, both roles, client-rendered.
   Derived from `site/compose.yml`'s hardening contract plus `review-stack.yaml`'s
   proven swarm site service plus `stack.yaml`'s Traefik conventions:
   - `image: ${BUILDHOUND_SITE_IMAGE}` — digest reference substituted client-side
     exactly like the server image (BOM `siteImage`, `@sha256:` enforced).
   - Role-suffixed, swarm-globally disjoint Traefik names
     (`buildhound-${BUILDHOUND_APP_ROLE}-site`): `Host(${BUILDHOUND_SITE_HOST})`,
     `websecure`, `letsencrypt-dns-hetzner` cert resolver, load-balancer port 8080,
     `traefik.swarm.network=${DOKPLOY_INGRESS_NETWORK}`. **No published ports.**
   - Long-form `/tmp` tmpfs mount (short form is dropped by stack conversion).
   - `deploy.placement` constraint `node.labels.role==${BUILDHOUND_APP_ROLE}`,
     resource limits, restart policy; `user: "101:101"`, `read_only`, `cap_drop: ALL`
     carried over from the standalone contract.
   - Environment: `BUILDHOUND_SITE_HOST`, `BUILDHOUND_SITE_DASHBOARD_URL`,
     `BUILDHOUND_SITE_NOINDEX` — substituted client-side from CLI flags so the
     deployed values are exact and readback-verifiable, not dependent on Dokploy
     env layering.
   - No robots middleware: the `X-Robots-Tag` header is rendered by the site image
     itself; plan 095's Traefik robots middleware stays dashboard-only.
2. **Client** — `dokploy.sh deploy-release` replaces `--site-application-id` with
   `--site-compose-id`:
   - Site sequencing guarantees are preserved: dashboard Compose deploys and is
     verified first; the site deploy re-reads state to detect drift before
     triggering, exactly as the Application path did.
   - Site path becomes: `compose.one` readback (`composeType=stack`,
     `sourceType=raw`, `autoDeploy=false`), `compose.update` with the rendered
     stack file, `compose.one` re-read verifying the stored file matches what was
     sent, `compose.deploy` with the release title, `deployment.allByCompose`
     wait with the same terminal/uncertain semantics as the dashboard Compose.
   - `--site-url`, `--site-dashboard-url`, `--site-noindex` become required for
     **both** roles (production previously relied on operator-managed Application
     env; a client-rendered file needs explicit values). Production passes
     `--site-noindex false`, staging keeps `true`.
   - The v0.29 Docker-provider credential guard and all Application
     binding/drift checks are deleted with the Application path. Pull credentials
     ride the same instance-level registry path the dashboard Compose already
     proves on every deploy.
   - `--skip-site` retained, still mutually exclusive with site flags; the
     production job keeps honoring `BUILDHOUND_SKIP_SITE_DEPLOY`.
   - Release evidence keeps a non-null site deployment id (now a Compose
     deployment id) when the site is deployed.
3. **Workflow** — `deploy.yml` staging and production legs pass
   `--site-compose-id` from the per-environment secret `DOKPLOY_SITE_COMPOSE_ID`;
   production adds the three site value flags when not skipping. Verification
   environments unchanged.
4. **Tests** —
   - `delivery-cli-test.sh`: Application site mocks and binding-drift cases are
     replaced with Compose equivalents — update/readback checksum drift, deploy,
     wait terminal/uncertain, `--skip-site` paths, missing-flag validation for
     both roles, and the site-deploys-only-after-dashboard ordering.
   - `validate-stack.sh`: covers `site-stack.yaml` — Traefik name disjointness
     across all four stack files, long-form tmpfs (ban short form), no published
     ports, no `build:`, placement constraint present.
   - New parity check: the hardening fields shared by `site/compose.yml` and
     `site-stack.yaml` (image repo, `user`, `cap_drop`, `read_only`, tmpfs target,
     env var names) must match, so the standalone contract and the shipped stack
     cannot drift apart silently.
5. **Docs** — README site-delivery section rewritten for the Compose model;
   architecture decision-log row records Application → Compose and why; plan 096's
   superseded design bullet annotated in place.

## Operator actions (owner)

- Dokploy: delete the staging and production Site Applications. Create two Compose
  apps (one in each environment of the existing project): compose type **Stack**,
  source **raw**, auto-deploy **off**, placeholder content (the client overwrites
  the file on first deploy). No per-app registry credential.
- GitHub: set per-environment secret `DOKPLOY_SITE_COMPOSE_ID` (staging + production)
  to the new Compose ids; delete `DOKPLOY_SITE_APPLICATION_ID` from both
  environments.
- Production go-live (separate decision): remove `BUILDHOUND_SKIP_SITE_DEPLOY` from
  the production environment when ready to serve `buildhound.dev`.

## Exit criteria

- Policy suite green, including the new Compose site cases and stack validation.
- One labeled merge run green end-to-end: `qualify` → `publish` → `deploy-staging`
  with the site delivered via `compose.deploy`, `verify-release.sh` staging site
  tuple (page, dashboard link, robots header, robots.txt) green.
- `deploy-production` reaches Waiting and, after operator approval, goes green
  (site skipped while `BUILDHOUND_SKIP_SITE_DEPLOY=true` remains set).
- Decision-log row landed; plan 096 annotated.

## Implementation divergences

- **`--site-manifest` override flag added** (not in Design 2). The site stack is not part
  of the release BOM, so `deploy-release` defaults to the trusted workflow revision's
  `deploy/dokploy/site-stack.yaml`; the flag exists so the policy suite can render a
  fixture. The workflow passes no override.
- **The site Compose readback keeps a binding check.** Design 2 deletes the Application
  binding machinery; its Compose replacement still requires the site Compose to sit in
  the release Compose's `environmentId` on its exact `serverId` (and to carry no attached
  domains/mounts/backups) before any mutation — otherwise a wrong-environment site could
  be updated and deployed, and worker pull authorization would be unproven for a foreign
  manager. This is a retained guarantee, not new surface.
- **"Uncertain" site-wait coverage** (Design 4) is exercised through structurally invalid
  deployment evidence rather than a poll timeout: the real timeout is 10 minutes and
  cannot run inside the unit suite. The terminal path is covered exactly.

## Live verification log

(record after the merge run)
