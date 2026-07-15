# BuildHound CI/CD — deep research report

**Date:** 2026-07-15 · **Scope:** GitHub Actions + Dokploy pipeline (build, test, review envs, staging, prod) · **Objective:** reduce complexity, stay secure, explain tradeoffs.

**Method:** repo/workflow audit + live Actions run-log forensics + root-cause code analysis + prior-art review (two earlier Dokploy+GitLab projects) + 103-agent web research pass (104 claims extracted, 25 verified 3-0 against primary sources, 0 refuted).

---

## 1. TL;DR

1. **Your three reported symptoms have concrete, fixable root causes** — none of them require redesign to fix (§2). Staging failed on `gh run download` lacking `--repo` in the checkout-free `resolve` job — already fixed on main by `1c88738` — and still lacks a first-deploy bootstrap path. Review-env smoke tests 404 with the root cause unconfirmed; the leading candidate is the `traefik.swarm.network` label problem on the isolated review network (§2.2 — the review site's `role==review` placement is already set in `review-stack.yaml`, and plan 086's `application.update` applies only to the staging/prod site Application and is implemented in `dokploy.sh cmd_deploy_release`). Cleanup *jobs* actually succeed every time — what you perceive as "cleanup not working" is by-design residue: every retired PR env leaves a stopped zero-replica anchor plus an orphaned isolated network in Dokploy, forever.
2. **Upgrading Dokploy buys nothing today.** v0.29.12 (your pin, released 2026-07-13) is the newest release in existence. Preview deployments for compose (#2028), scoped API tokens (#4502), the compose.delete network-orphan fix, and API-managed Swarm secrets are all still unshipped. Re-check the releases page before acting — the pin is only 2 days old and Dokploy ships frequently.
3. **The big simplification wins are on the GitHub Actions side**, all vendor-sanctioned patterns:
   - Promote the hourly reconciler to the *authoritative* cleanup mechanism (the Argo CD model); demote PR-event cleanup to a best-effort fast path. GitHub's own concurrency semantics make event-driven cleanup structurally unreliable — no amount of compensation logic fixes it.
   - Collapse the `publish-deploy-images → deploy-release` workflow_run chain into one workflow with sequential jobs gated by GitHub Environments. This deletes most of the 334-line `resolve` job, whose main purpose is re-deriving trust that a single workflow has natively — and it deletes the exact class of bug (cross-run context and artifact plumbing, cf. `1c88738`) that has been breaking staging.
   - Replace the custom schema-2/schema-3 attestation chain with GitHub-native primitives (SHA-bound per-run environment approvals + digest pinning, optionally `attest-build-provenance`). Your prod deploy has never run; the bespoke proof chain is protecting nothing yet and is the single largest complexity center.
4. **Prior art supports aggressive shrinkage:** your two previous Dokploy projects ran the same platform on ~100 lines (bare curl in GitLab CI) and ~1,064 lines (shared shell lib) respectively, vs BuildHound's ~6,800-line bash client + 1,573 lines of workflow YAML (all workflows, ci.yml included). They also documented two Traefik multi-network quirks: without `traefik.swarm.network`, Traefik's swarm provider may balance to a task IP on a network it cannot reach (504s); defining both `traefik.docker.network` and `traefik.swarm.network` makes Traefik v3 skip the service and its routes 404.
5. **Security posture after simplification is *better*, not worse** — details and explicit tradeoffs in §5. The one unresolvable risk at any Dokploy version is the instance-wide API token (#4502: open, zero maintainer response); mitigation stays architectural (dedicated restricted member-user token, protected environments).

---

## 2. What is actually broken (run-log evidence + root cause)

### 2.1 Staging deploy — CONFIRMED failing (4 of last 20 runs)

Two independent bugs:

**(a) `resolve` job: `fatal: not a git repository`** (2 runs, 2026-07-14).
Root cause: `gh run download` at deploy-release.yml:172/240 originally ran without `--repo` in the checkout-free `resolve` job (lines 40–373; only the `deploy` job checks out, line 382). **Already fixed on main by `1c88738`** (`fix(deploy): bind artifact downloads to repo`, 2026-07-14 19:35 — after the observed 17:27/17:43 failures), which added `--repo "$GITHUB_REPOSITORY"` to both calls and pinned it with a regression test. No checkout is needed in `resolve`; remaining action is to confirm with a fresh staging run — if staging still fails there, the cause is elsewhere (see (b)). Structural fix: single-workflow collapse (§5.2) removes the job entirely.

**(b) `deploy` job: `no current successful release deployment found`** (1 run).
First-ever automatic staging deploy: the workflow_run branch of `resolve` (lines 308-329) never sets `bootstrap_bom=true` (initialized false, line 304), so the backup-selection step (line 478) unconditionally queries `current-release-id` — which cannot exist before the first deploy. Chicken-and-egg. Minimal fix: detect empty deployment history → engage the bootstrap path that already exists for manual runs.

### 2.2 Review environments — deploys fail, cleanup actually works

Run history: recent failures are all in the **deploy** path — smoke test loops `curl: (22) ... 404` × 30 against the fresh env. The `reconcile_failed_review` cleanup job **succeeded in every observed failure**, and reconcile-reviews.yml is 20/20 green.

Root-cause candidates, in order of evidence (note: the earlier "site-Application placement never set" hypothesis was **refuted** on verification — plan 086's `application.update` requirement targets the long-lived staging/prod site Application and *is* implemented in `dokploy.sh cmd_deploy_release`; the review env has no separate site Application, and its site compose service already carries `role == review` placement in `review-stack.yaml:19-21`):
1. **Traefik multi-network routing.** Your previous project's README (headless-wordpress-v2, `new/deploy/dokploy/README.md:~565-573`) documents: services attached to multiple networks need `traefik.swarm.network=<network>` or Traefik may route to an unreachable task IP (504s); its documented 404 case is defining **both** `traefik.docker.network` and `traefik.swarm.network`, which Traefik v3 skips. Isolated deployments put review services on a non-default network — check the rendered stack labels for both conditions.
2. Secondary: short-form `tmpfs:` being dropped by the Swarm converter (plan 086 regression), missing explicit `traefik.http.routers.<id>.service=` labels.

### 2.3 "Cleanup not correct" — by design, and invisible from Actions

Every cleanup path (close/unlabel, failed-attempt reconcile, hourly TTL) intentionally avoids `compose.delete` (orphans isolated networks on 0.29.x) and instead leaves per-PR residue in Dokploy: a stopped zero-replica anchor compose marked `retired:true` **plus** the isolated overlay network. These accumulate unboundedly (plan 085 defers real deletion until Dokploy can delete compose+network atomically — which no released version can, see §3.1). If "cleaned up correctly" means "gone from the Dokploy UI", the current design can never deliver that.

### 2.4 Production — confirmed never executed

All 20 recent deploy-release runs are staging/workflow_run. The prod path (workflow_dispatch + staging-attestation validation + external Swarm secrets) is code-complete but has zero operational history. It is unverified infrastructure, and it depends on the staging attestation chain that is itself failing.

---

## 3. Verified research findings (all 3-0 adversarial verification, primary sources)

### 3.1 Dokploy platform ceiling (as of 2026-07-15)

- **v0.29.12 is the newest Dokploy release; nothing newer exists.** None of v0.29.3→v0.29.12 ship: compose preview deployments (issue [#2028](https://github.com/Dokploy/dokploy/issues/2028) open, last updated 2026-06-19; official docs still scope preview deployments to GitHub-integrated *applications* only), scoped API tokens, a compose.delete/isolated-network fix, or API-managed Swarm secrets. ([releases](https://github.com/Dokploy/dokploy/releases))
- **Instance-wide API tokens are unresolvable by upgrade.** [#4502](https://github.com/Dokploy/dokploy/issues/4502) (2026-05-28): open, zero comments, no milestone, no linked PR. Tokens grant read/write on every project + admin ops (org-scoped since v0.19.0 ≡ instance-scoped on a single-org install). Mitigation is architectural only.
- **An official Dokploy CLI exists** (github.com/Dokploy/cli, latest v0.29.4, May 2026) but its endpoint coverage vs your 18 REST calls was not established — evaluate before betting on it (open question).

### 3.2 Review-env cleanup: reconciler-as-source-of-truth is the industry answer

- **Argo CD ApplicationSet PR generator**: polling loop (default 30 min) is the *authoritative* discovery/cleanup mechanism; an app is removed when the PR no longer matches criteria (state/labels) — declarative desired-state recomputation. Webhooks exist only "to eliminate this delay from polling" — a latency optimization on top of the reconciler. ([docs](https://argo-cd.readthedocs.io/en/latest/operator-manual/applicationset/Generators-Pull-Request/))
- **Cloud Posse preview-environment-controller** (design reference; lightly maintained, last release Aug 2024): recomputes deploy/destroy sets purely from current label+open state on every PR event — no event-type dependence — but has *no reconciler backstop*, which is exactly the failure shape you observe. ([repo](https://github.com/cloudposse/github-action-preview-environment-controller))
- **GitHub concurrency semantics make event-only cleanup structurally unreliable** ([docs](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)): (1) `cancel-in-progress: true` can kill a cleanup mid-run; (2) default one-pending-per-group silently cancels+replaces a queued cleanup; (3) "ordering is not guaranteed" — deploy and cleanup for the same PR can run out of order. Note: `queue: max` (which you already use) softens (2) but cannot combine with cancel-in-progress and does not restore ordering.

### 3.3 pull_request_target security (GitHub docs + Security Lab)

- Executing PR-author-controlled build code (Gradle, Dockerfiles) inside a `pull_request_target` job is the canonical ["pwn request"](https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/) pattern; GitHub's guidance: checked-out untrusted code "only ever inspected as data and never executed". CodeQL ships a dedicated critical query for it.
- **Label-gating is officially a partial, race-prone mitigation** — attacker can push after labeling but before the run starts. The 2025 Security Lab follow-up accepts label gates *only* when the workflow pins the exact SHA vetted at label time. ([Part 4, 2025-01-16](https://securitylab.github.com/resources/github-actions-new-patterns-and-mitigations/))
- **checkout v7 (GA 2026-06-18) refuses fork-PR code checkout in pull_request_target/workflow_run contexts; enforcement backported to ALL supported majors on 2026-07-16** — i.e. tomorrow, floating tags pick it up automatically. ([changelog](https://github.blog/changelog/2026-06-18-safer-pull_request_target-defaults-for-github-actions-checkout/))
  **BuildHound impact: none in practice** — review-environment.yml hard-gates same-repo PRs (`head.repo.full_name == $GITHUB_REPOSITORY`, lines 51/132/209), and same-repo checkouts are unaffected. Your `@v4` floating tags will gain the new refusal behavior tomorrow, which for you is a free extra guardrail, not a breakage. Same-repo authors already hold write access, so the residual pull_request_target risk is materially lower than the generic warning implies.
- Recommended decomposition (GitHub docs, first-line advice): unprivileged `pull_request` job builds/tests with zero secrets; a separate privileged consumer (workflow_run or environment-gated job) pushes/deploys without executing untrusted code.

### 3.4 Promotion-chain reliability

Research coverage here was partial (see caveats), but the failure you already hit — a `workflow_run` consumer job assuming repo context it doesn't have — is on the documented pitfall list for chained workflows, and GitHub's Environments docs recommend the simpler shape: **one workflow, sequential jobs, `environment:` gates with required reviewers for prod** (approval is per-run and SHA-bound — a fresh push requires its own approval and never inherits a prior one, which replaces a chunk of your custom ancestor-checking; note an older waiting run is not auto-cancelled and stays approvable).

---

## 4. Prior art: your own GitLab+Dokploy projects

| | headless-wordpress-v2 | full-rebuild | BuildHound today |
|---|---|---|---|
| CI config | 818 lines | 1,369 lines | 1,573 lines (all workflows) |
| Deploy client | ~100 lines, bare `curl` | 1,064 lines (`_lib.sh` + 6 scripts) | **~6,800 lines**, 18 endpoints |
| Review envs | per-MR, auto_stop 1w | per-MR upsert + absolute-age TTL sweep | per-PR isolated + anchor + hourly reconcile |
| Cleanup | `compose.delete` directly (no isolation → no orphan issue) | `delete-compose.sh` + scheduled sweep | stop+anchor+retire (residue by design) |
| Prod gate | manual per-service jobs | auto staging on main | attestation chain, never run |

Transferable lessons already proven in those repos:
- **Upsert pattern** (`compose.search` → create-or-update) beats create+anchor bookkeeping.
- **Absolute-age TTL sweep** as independent cleanup authority (INF-29) — same conclusion as Argo research, already field-tested by you.
- **`resource_group`/serialization per MR** prevented force-push races — equivalent to per-PR concurrency groups without cancel-in-progress on cleanup.
- **Traefik v3 `traefik.swarm.network` label quirks** — documented cause of 504s when the label is missing; the documented 404 case is defining both `traefik.docker.network` and `traefik.swarm.network` on one service.
- **Per-MR secrets generated once, preserved across redeploys** (INF-27/28).

The delta between ~1,000 and ~6,800 lines is almost entirely: version-gate (`settings.getDokployVersion` pinned to exactly one release), `getConvertedCompose` anchor-verification choreography, `cleanQueues` drain logic, and attestation schema construction/validation.

---

## 5. Recommended plan, with tradeoffs

### Phase 0 — bug fixes (unblock what exists; small diffs)

1. `resolve` job: verify the already-landed fix `1c88738` (`--repo` at deploy-release.yml:172,240) via a staging re-run; do **not** add checkout to `resolve`.
2. Bootstrap: on the workflow_run staging path, if no successful prior release deployment exists, set `bootstrap_bom=true` instead of failing.
3. Review 404: add/verify `traefik.swarm.network` labels on the rendered review stack (and that no service defines both `traefik.docker.network` and `traefik.swarm.network`); confirm long-form tmpfs survived.
4. Pin `actions/checkout@v7` everywhere (ci.yml already is; the rest are @v4).

### Phase 1 — reconciler becomes the cleanup source of truth

Invert authority: the hourly reconcile enumerates open+labeled PRs, diffs against live Dokploy state, converges (create missing, retire stale). PR-event jobs become optional fast paths that run the *same* idempotent converge script. Delete the event-handler compensation logic (`reconcile_failed_review`'s exact-attempt bookkeeping, queue-drain choreography).

- **Tradeoff:** worst-case cleanup latency = cron interval (run every 15 min if that matters). In exchange: cleanup correctness no longer depends on event delivery, ordering, or concurrency-group survival — the three documented failure modes. Net code deletion.
- **Residue decision needed:** either accept anchors+networks as permanent cost of isolation, have the reconciler additionally `compose.delete` retired anchors and garbage-collect orphaned networks over SSH/`docker network rm` on the host (one small script, prior-art style), or drop `isolatedDeployment` and use plain `compose.delete` like headless-wordpress-v2 did. Dropping isolation removes the lateral-network barrier between review envs and prod — **not recommended** given review envs run PR code; the SSH network-GC script is the better trade.

### Phase 2 — collapse the promotion chain

One `deploy.yml`: `build+publish` (digests as job outputs) → `deploy-staging` (`environment: staging`, auto) → `deploy-production` (`environment: production`, required reviewers, manual approval). Trigger: push to main (label-qualified as today) + workflow_dispatch for redeploys/rollback (input: SHA/digest).

- Deletes: the workflow_run trigger, most of the 334-line `resolve` lineage re-derivation, cross-run artifact plumbing (digests flow as job outputs), schema-2/schema-3 attestation construction and validation.
- Replaces trust chain with GitHub-native equivalents: same-run provenance (staging and prod deploy literally the same job-output digests), SHA-bound per-run prod approval (a fresh push requires its own approval and never inherits a prior one — note an older waiting run is *not* auto-cancelled and stays approvable), optional `attest-build-provenance` for supply-chain provenance on the images.
- **Tradeoff (what the bespoke chain covered that native doesn't):** the ancestor-check ("candidate is ancestor of main") and "prod only deploys a staging-proven release even across separate runs". In the single-workflow shape the first is implied by the trigger (runs on main), and the second is implied by job ordering within the run. What you genuinely lose: the ability to prove, months later and offline, that a given prod deploy descended from a specific reviewed artifact via your own schema — GitHub run logs + native attestations cover ~90% of that audit trail with ~0 custom code. For a project whose prod has never deployed once, the bespoke 10% is not paying rent. Revisit if/when BuildHound hosts paying tenants.
- Keep: digest pinning everywhere, environment-scoped secrets, the release BOM *file* if you want a human-readable record — just stop making workflows validate custom schemas of it.

### Phase 3 — shrink the delivery client

Target: dokploy.sh + libs from ~6,800 → ~800-1,200 lines (your full-rebuild footprint).
- Drop the exact-version gate (fails on every Dokploy patch release; replace with a minimum-version check or none).
- Drop `getConvertedCompose` anchor verification and `cleanQueues` choreography once the reconciler owns cleanup.
- Deduplicate the `DOKPLOY_URL`/`DOKPLOY_TOKEN` token-env boilerplate repeated across workflows via one composite action (composite over reusable-workflow here: you need steps inside existing jobs, not whole-job reuse).
- Evaluate the official Dokploy CLI (v0.29.4) for the remaining calls; adopt only if it covers compose create/update/deploy/stop cleanly — otherwise bare curl + jq, prior-art style.
- **Tradeoff:** the deleted verification steps were defense-in-depth against Dokploy misbehaving (silently rendering a different compose, queue reordering). Field evidence from two prior projects: these failure modes never materialized; the checks did, however, add the brittleness that pinned you to exactly v0.29.12.

### What NOT to change

- Same-repo-only gate on review deploys (your strongest security control; keep it — it is why tomorrow's checkout enforcement doesn't hurt you).
- SHA-pinned label gating with per-run re-validation (matches Security Lab's accepted pattern).
- Digest-pinned GHCR images, provenance/sbom on publish.
- Staging plaintext-env credential decision (plan 087): remains the only option at any released Dokploy version; owner-accepted with rotation mitigations. No new information changes it.
- Dedicated restricted Dokploy member-user token (only available mitigation for #4502).

---

## 6. Open questions (from the verified research)

1. Dokploy CLI coverage of your 18 endpoints — worth a 30-minute spike before Phase 3.
2. Fork-PR strategy: today forks get no review env (same-repo gate). If you ever want fork previews, the only doc-sanctioned shape is unprivileged build → privileged consumer that never executes fork code; note `packages: write` cannot exist in the unprivileged job, so the privileged consumer must push from an uploaded artifact. Defer until actually needed.
3. Whether to keep a human-readable release BOM file after the schema-validation machinery goes.

## 7. Source index

Primary sources verified 2026-07-15 (all findings passed 3-0 adversarial verification; 0 claims refuted):
- Dokploy: [releases](https://github.com/Dokploy/dokploy/releases) · [#2028](https://github.com/Dokploy/dokploy/issues/2028) · [#4502](https://github.com/Dokploy/dokploy/issues/4502) · [preview-deployments docs](https://docs.dokploy.com/docs/core/applications/preview-deployments) · [API docs](https://docs.dokploy.com/docs/api)
- GitHub: [pull_request_target security](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target) · [secure use](https://docs.github.com/en/actions/reference/security/secure-use) · [concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency) · [checkout v7 changelog](https://github.blog/changelog/2026-06-18-safer-pull_request_target-defaults-for-github-actions-checkout/) · [checkout v7.0.0](https://github.com/actions/checkout/releases/tag/v7.0.0)
- Security Lab: [Preventing pwn requests (2021)](https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/) · [New patterns & mitigations (2025)](https://securitylab.github.com/resources/github-actions-new-patterns-and-mitigations/)
- Argo CD: [PR generator](https://argo-cd.readthedocs.io/en/latest/operator-manual/applicationset/Generators-Pull-Request/)
- Cloud Posse: [preview-environment-controller](https://github.com/cloudposse/github-action-preview-environment-controller)
- Repo evidence: Actions run logs 2026-07-14 (run IDs 29374349724, 29354942848, 29353852392, 29347749720, review-env failures 29343374567 et al.); deploy-release.yml, review-environment.yml, deploy/dokploy/lib/review.sh, select-backup.sh; docs/plans/081–087
- Prior art: `~/DynaDroid/projects/bettyskitchen/rebuild/headless-wordpress-v2` (.gitlab-ci.yml, new/deploy/dokploy/README.md), `.../full-rebuild` (.gitlab-ci.yml, ops/dokploy/)
