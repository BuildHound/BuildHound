# CI recovery roadmap — orchestrator runbook

**Goal:** every defect in `docs/ci-pipeline-research.md` fixed and one full
**review → staging → production** delivery proven live.
**Plans executed, in order:** [088](088-ci-staging-review-bugfixes.md) →
[089](089-review-cleanup-reconciler-authority.md) →
[090](090-promotion-chain-collapse.md) → [091](091-dokploy-client-shrink.md).
This file is written for an orchestrator agent with **no memory of the research
conversation**. Everything needed is here, in the four plan files, and in
`docs/ci-pipeline-research.md` (evidence + tradeoffs; read §2 and §5 first).

## Standing rules (from CLAUDE.md — binding)

- **Before Stage A:** commit the planning docs as their own `plan:` commit —
  `docs/ci-pipeline-research.md`, plans 088–091, this roadmap, and the
  `docs/plans/README.md` update (e.g. `plan: ci recovery — research, plans 088-091,
  roadmap`). CLAUDE.md requires the plan committed before implementation starts; the
  later `git mv` of plans to `implemented/` also depends on them being tracked.
- Per stage: implement on a feature branch → push → **open a PR** (`gh-ci-babysitter`
  only watches branches with an open PR; the publish qualify job deploys only commits
  that land as a merged PR into main, never direct pushes) → watch CI with
  `gh-ci-babysitter` (background agent) → run the two §3 reviews (below) → fix findings →
  merge the PR only green.
- Review routing for these diffs (workflows + `deploy/dokploy/` + shell):
  **`infra-reviewer`** (code & architecture) **and** the mandatory **§3.2 security &
  privacy review** in a fresh context, **plus `security-reviewer-infra`** for CI/deploy
  paths. Reviewers are report-only; the implementer fixes findings. Do not use the generic
  `code-reviewer` (mobile-scoped). Never set `CLAUDE_CODE_SUBAGENT_MODEL`.
- `isolation: worktree` agents branch from the default branch — read-only agents only;
  never for landing work on the feature branch. No git-touching background agents while a
  rebase is in progress (TaskStop `gh-ci-babysitter` first).
- After any rebase onto main: re-check `docs/plans/` numbering for collisions (088–091
  must stay unique) and Flyway V-numbers if migrations appear.
- Tokens/secrets only via GitHub environments/vars — never in code, logs, or images.
- Commits: imperative, scoped prefix (`deploy:`, `plan:`, `docs:`). One plan-update commit
  per divergence, in the same PR as the divergence.

## Human gates (orchestrator CANNOT do these — request via notification and WAIT)

| Gate | When | Who/what |
|---|---|---|
| H1 | before Stage B merge | Operator installs host-side GC timer on the Dokploy host (script shipped in Stage B; operator runs its `--install` once) and, after its first timed run, returns the script's report (remaining `bh-*-mr*` overlay networks + retired-anchor list) to the orchestrator — this is Stage B's "host GC pass" evidence. |
| H2 | before Stage C merge | Operator confirms GitHub `production` environment exists with **required reviewers** enabled, the GitHub `staging` environment holds the CI-side deploy secrets the deploy job reads (`DOKPLOY_TOKEN`, `DOKPLOY_COMPOSE_ID`, `BACKUP_AWS_*`, `BUILDHOUND_DB_INSTANCE`), and the **Dokploy** staging environment still holds the plan-087 DB/S3 credential values — those stay solely in Dokploy (plan 087); never copy them into GitHub. |
| H3 | Stage D | A human approves the first production deployment in the GitHub UI (per-run, SHA-bound). |
| H4 | after **any** failed staging deployment (no exception — Dokploy embeds the staging credentials in the failed deploy command, plan 087) | Operator rotates staging DB/S3 credentials. |

## Stage graph

```
A (088 bugfixes) ──► B (089 reconciler) ──► C (090 chain collapse) ──► D (first prod deploy) ──► E (091 client shrink)
      each stage: implement → CI green → reviews → merge → live-verify before the next stage starts
```

Stages are strictly sequential — B and C both rewrite `.github/workflows/` and
`deploy/dokploy/`; parallelizing them guarantees conflicts.

**Post-merge live-verification failure (any stage):** if a stage's live verification
fails on main and the stage's own fallback list (Stage A only) is absent or exhausted,
`git revert` the stage's merge commit on main — do not fix forward on a broken main. For
Stage C this restores `deploy-release.yml` + `publish-deploy-images.yml` (and the retired
resolver tests, same revert); note the plan-090 dispatch rollback runs through
`deploy.yml` and cannot recover from a broken `deploy.yml`. Confirm the pre-stage deploy
path is green on main, then fix on the feature branch and re-run the stage from
implement.

### Stage A — plan 088 (bug fixes)

Branch `ci/088-bugfixes`. Tasks + exact file/line targets: plan 088 §Design.
**Live verification (all three, in order, before calling A done):**
1. Merge the `ci/088-bugfixes` PR to main **with the `deploy-review` label applied at
   merge time** → staging auto-deploy green **via the bootstrap branch** (first deploy) —
   check the run log shows bootstrap engaged, not a lucky predecessor. Staging only
   auto-deploys when publish-deploy-images' `qualify` job finds exactly one merged
   same-repo PR for the pushed SHA AND that PR carries the label — an unlabeled PR or
   direct push yields `deploy=false` and **no staging run at all**; if 'Publish
   deployment images' completes with publish/release skipped and no staging run appears,
   the qualify gate rejected the merge — check the label and one-PR-per-SHA condition,
   do not debug the deploy itself.
   Bootstrap precondition (not directly inspectable — the orchestrator has no
   DOKPLOY_TOKEN; the deploy run itself is the probe): the staging compose's latest
   successful deployment must still be the plan-087 "Manual deployment" anchor with no
   successful release deployment after it. Negative cases: (a) run fails in backup
   selection with "no current successful deployment found" / "not the explicit manual
   deployment" → the anchor is missing/superseded — stop, request the operator redo the
   plan-087 manual staging deployment (treat as a human gate like H1–H4), re-run;
   (b) run log shows bootstrap did NOT engage → a successful release deployment already
   exists — accept only if the run that first deployed it shows bootstrap engaged and
   green; otherwise mark this check not live-verifiable (bootstrap is one-shot), rely on
   plan 088's resolver bootstrap test, record the divergence in plan 088, continue.
2. Open a same-repo test PR (any trivial change), label `deploy-review` → review env
   smoke passes (no 404 loop). This must run **after** 088 is on main:
   review-environment.yml is `pull_request_target` and executes the workflow and the
   `deploy/dokploy/` client **from main**, so a pre-merge labeled PR can never exercise
   the fix. Then unlabel → cleaned up (current mechanism acceptable; 089 hardens it).
3. Merge the test PR (labeled `deploy-review` at merge) → staging green via the
   non-bootstrap branch.
After a green staging deploy, run `review-env-verifier` (background) against the staging
URL with the smoke checklist from `verify-release.sh`.
**Failure handling:** if review smoke still fails after 088's rendered-stack fixes, apply
the remaining ranked root-cause candidates from `docs/ci-pipeline-research.md` §2.2
(`traefik.swarm.network` label / no dual `traefik.docker.network`, tmpfs long-form,
explicit router `service=` label) — in that order, one commit each; each fallback commit
must be **merged to main** before re-triggering the labeled test PR (the PR-triggered run
executes the client from main, so fallbacks applied only on a branch cannot take effect).

### Stage B — plan 089 (reconciler authority)

Branch `ci/089-reconciler`. Precondition: A merged + verified.
**Gate H1 before merge.** Include in the same PR: `docs/architecture.md` decision-log
entry for reconciler-authority cleanup.
**Live verification:** the three chaos checks in plan 089 §Exit criteria (cancel
mid-cleanup; close-PR-while-workflow-disabled; host GC pass — evidence via the H1
operator report). Staging/prod-untouched evidence: the converge run's logged summary
(counts + `bh-*-mr*` names, per plan 089 §Design 2 — read via `gh run view --log`; raw
Dokploy ids are secrets and never logged) plus the environment/name filter policy test.

### Stage C — plan 090 (promotion chain collapse)

Branch `ci/090-chain-collapse`. Precondition: B merged. **Gate H2 before merge.**
Delete `deploy-release.yml` + `publish-deploy-images.yml` only in the same PR that adds
`deploy.yml` — never a window with no deploy path. Update `dokploy-policy.yml` test list
in the same PR (resolver tests retire with the resolver). Include in the same PR:
`docs/architecture.md` decision-log entry for the native trust model replacing bespoke
attestations.
**Live verification:** merge to main (labeled, per the qualify gate) → `publish` +
`deploy-staging` green in ONE run, zero cross-workflow artifact downloads;
`deploy-production` visibly **waiting for approval** — do not approve it (the first prod
deploy runs via dispatch in Stage D; this waiting run gets rejected there).

### Stage D — first production deploy

No new code. Precondition: C merged, staging green on current main, production compose
carries a verified manual anchor (`dokploy.sh require-manual-current` passes — operator
confirms, orchestrator has no DOKPLOY_TOKEN), operator has chosen the backup object
matching that anchor. If any commit merged to main after Stage C, first reject/cancel the
stale waiting `deploy-production` run so the newest run's group is free, and verify
staging green on the newest run.
1. Reject/dismiss the pending `deploy-production` approval from the Stage C push run (it
   has no backup-object input and cannot bootstrap).
2. Trigger `deploy.yml` via `workflow_dispatch` with `target=production`, the bootstrap
   flag, and the backup-object input (staging redeploys first, per plan 090). Notify the
   operator to approve **that dispatch run's** `deploy-production` job (**Gate H3**).
3. After approval + deploy: run `verify-release.sh` checks against the production URLs;
   then `review-env-verifier` (background) with the production checklist.
4. Record the run URL + digests in `docs/plans/090-promotion-chain-collapse.md` (exit
   criterion evidence) and move plans 088–090 to `docs/plans/implemented/` (`git mv`).
**Rollback:** `workflow_dispatch` on `deploy.yml` with the previous digest (plan 090 §4);
staging redeploys first, prod requires fresh approval. If the first prod deploy fails
mid-migration, restore path is `select-backup.sh` + the restore runbook under
`deploy/dokploy/` — do not retry-loop deploys against a half-migrated database.

### Stage E — plan 091 (client shrink)

Branch `ci/091-client-shrink`. Precondition: D complete (prod path proven — refactor only
after the behavior it must preserve has been observed working).
Includes the timeboxed Dokploy CLI spike; verdict recorded in plan 091. Include in the
same PR: `docs/architecture.md` decision-log entry for the minimum-version Dokploy gate.
**Live verification (post-merge — the client executes from main, see plan 091):** one
full cycle on the shrunk client: review deploy → converge → staging → prod (needs a
second H3-style approval). Then
`git mv docs/plans/091-dokploy-client-shrink.md docs/plans/implemented/`.

## Stop conditions (halt and report, do not improvise)

- Any reviewer finding tagged security that the implementer cannot fix without widening
  scope → stop, surface to owner.
- Dokploy releases a version >0.29.12 mid-execution → pause, re-check
  `docs/ci-pipeline-research.md` §3.1 assumptions against its changelog (esp. network
  deletion, scoped tokens, preview deployments) before continuing; a native fix may
  obsolete parts of 089.
- Any staging deployment reaches error status → treat credentials as exposed (they are
  embedded in the failed Dokploy command per plan 087 — do not attempt to inspect the
  Dokploy error log to confirm) → Gate H4 before anything else.
- Plan-number or Flyway-number collision after a rebase → fix numbering first.
- Post-merge live verification red and the revert did not restore a green deploy path on
  main → stop, surface to owner.

## Definition of done

All four plans' exit criteria met; plans moved to `implemented/` (088–090 in Stage D,
091 in Stage E); one PR-labeled review env, one staging deploy, and one approved
production deploy all green on current main; `docs/architecture.md` decision log updated
with: reconciler-authority cleanup (Stage B PR), native trust model replacing bespoke
attestations (Stage C PR), minimum-version Dokploy gate (Stage E PR).
