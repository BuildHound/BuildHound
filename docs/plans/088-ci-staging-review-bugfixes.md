# 088 — CI staging & review-environment bug fixes

## Source

`docs/ci-pipeline-research.md` §2 (run-log forensics + root-cause analysis, 2026-07-15).
Fixes the three observed failures blocking the review → staging → prod chain. No
architecture change — that is plans 089–091.

## Scope

**In:** the defects below, plus `actions/checkout` pinning. **Out:** cleanup-authority
inversion (089), workflow_run chain removal (090), client shrink (091).

## Design

1. **`resolve` job artifact downloads** — already fixed on main by `1c88738`
   (`fix(deploy): bind artifact downloads to repo`), which added
   `--repo "$GITHUB_REPOSITORY"` to both `gh run download` calls
   (deploy-release.yml:172,240) with a regression test. Do **not** add checkout to
   `resolve`. Remaining action: confirm via a fresh staging run (roadmap Stage A).
2. **First-staging-deploy bootstrap** — same file: the workflow_run branch (lines 308–329)
   leaves `bootstrap_bom=false` (line 304), so backup selection (line 478) queries
   `current-release-id` against an empty history and fails. In `resolve`, detect "no
   successful prior release deployment on the staging compose" (existing
   `dokploy.sh current-release-id` exit path) and emit `bootstrap_bom=true`; deploy job
   already honors it.
3. **Review 404** — verify on the rendered review stack: long-form tmpfs volumes
   survived, every Traefik-routed service carries
   `traefik.swarm.network=<its ingress-reachable network>` (without it Traefik may
   balance to an unreachable task IP — 504s), and no service defines **both**
   `traefik.docker.network` and `traefik.swarm.network` (Traefik v3 skips such services
   and the routes 404 — prior-art quirks, headless-wordpress-v2 README). Note: the
   "site-Application placement" hypothesis was refuted — the review site is a compose
   service with `role == review` placement already set (`review-stack.yaml:19-21`);
   plan 086's `application.update` targets only the staging/prod site Application and is
   already implemented in `dokploy.sh cmd_deploy_release`.
4. **Checkout pinning** — bump remaining `actions/checkout@v4` uses (deploy-release,
   review-environment, review-images, publish-deploy-images, reconcile-reviews,
   dokploy-policy) to `@v7`. The 2026-07-16 backport makes v4 behave like v7 for fork
   refusal anyway; pinning removes the drift. Same-repo gate
   (`review-environment.yml:51,132,209`) is unaffected and must not be weakened.

## Implementation divergences (Stage A PR)

1. **Bootstrap detection lives in the deploy job, not `resolve`.** Design 2
   assumed the resolver could detect "no successful prior release deployment",
   but `resolve` is deliberately secret-free (deploy-release.yml:59) and never
   calls `dokploy.sh`. Detection moved to the deploy job's backup-selection
   step, which already holds `DOKPLOY_TOKEN`: a new
   `dokploy.sh staging-bootstrap-state` command reports `established` /
   `bootstrap`, accepting empty release history only behind the plan-087
   manual anchor (fail-closed otherwise, matching roadmap Stage A negative
   case (a)). The step publishes the effective flag as a step output the
   deploy step consumes; resolver semantics are unchanged.
2. **The swarm-network label had to be added, not merely verified.** The
   template carried no `traefik.swarm.network` at all, and
   `test_review_lifecycle.py` explicitly banned it. The label (valued
   `${BUILDHOUND_REVIEW_NETWORK}`) names the ingress-reachable network — it
   does not attach one — so the policy test now requires it on both routed
   services and bans `traefik.docker.network` instead, while the isolation
   bans (`networks:` blocks, `dokploy-network`, `external: true`) remain.
   Because Dokploy names the isolated network after the server-side suffixed
   appName, `deploy_review` reads the appName back after `compose.create` and
   renders the trusted manifest a second time before `compose.update`.
3. **Materialized-stack verification runs after `compose.deploy`, not before.**
   Discovered live (PR #42, runs 29415802776/29418873892):
   `compose.getConvertedCompose` reflects the manager-side file materialized
   **on deploy** — before the first deploy it returns stale or absent content
   (the second attempt returned the cleanup anchor from the first attempt's
   retirement). This is the same reason the pre-existing anchor verifier runs
   post-deploy. A defective stack is therefore briefly deployed before being
   retired through exact-owned cleanup — equivalent to the smoke-failure
   path. Verifier failures now log redacted parser diagnostics and a
   structural summary (shape only, no environment values, 64-hex redacted).

## Test strategy

Extend `deploy/dokploy/test/` (dokploy-policy.yml runs them): resolver test for the
bootstrap branch; rendered-stack assertion that `traefik.swarm.network` is present AND
`traefik.docker.network` is absent on every Traefik-routed review service. Live
verification per roadmap Stage A (real labeled PR + staging run).

## Risks

All changes are deploy-path only; no plugin/schema surface. Security: every `checkout`
keeps `persist-credentials: false`; no new secrets. Behavior of same-repo gate unchanged.

## Live verification log (Stage A)

- **Review env (check 2): PASSED** — PR #42 labeled deploy green after the
  post-deploy verifier fix (run 29419762276); site and dashboard URLs 200
  with correct content; unlabel converged and both URLs returned 404.
- **Staging bootstrap (check 1), run 29422015761:** resolve accepted the
  test-PR lineage; bootstrap detection engaged against the manual anchor
  (attempts 1-2); provisioning gaps fixed operator-side during the attempts:
  backup bucket versioning enabled (Hetzner supports
  `put-bucket-versioning`), fresh versioned backup produced, versioned
  backup selection passed (attempt 3+).
- **Owner decision (2026-07-15): staging deploys the dashboard stack only.**
  The staging site Application is not provisioned (no registry pull
  credentials); site delivery will be fixed in a separate session.
  Implemented as `deploy-release --skip-site` (mutually exclusive with
  `--site-application-id`), gated solely by the environment-scoped
  `BUILDHOUND_SKIP_SITE_DEPLOY` variable (set on `staging`, absent on
  `production`), with the smoke's site probe skipped via
  `BUILDHOUND_SKIP_SITE_CHECKS` from the same variable. Dashboard smoke
  checks remain mandatory. Stage D precondition added: production site
  Application must be provisioned (or the owner extends the skip decision to
  production explicitly) before the first production deploy.
- The 088 merge itself (PR #41, main `8b2028c`) could not produce the
  bootstrap staging deploy: `resolve` requires a successful
  `buildhound/review-deployed/pr-N` status on the merged PR's head, and
  PR #41's own review deploy necessarily ran the pre-fix client from main.
  Roadmap check 1 therefore executes via this labeled test PR's merge
  (check order becomes 2 → 1 → 3); recorded here as the divergence rule
  requires. No Dokploy command was issued by the failed run (resolve is
  secret-free) — Gate H4 not triggered.

## Exit criteria

- A labeled same-repo PR produces a review env whose smoke test passes (no 404 loop).
- A merge to main auto-deploys staging green **from empty history** (bootstrap) and again
  green on the next merge (non-bootstrap). (If history is no longer empty by execution
  time, the resolver bootstrap test plus one green non-bootstrap deploy satisfies this
  criterion — note the divergence here.)
- `dokploy-policy` suite green; no `checkout@v4` left in `.github/workflows/`.
