# 090 — Collapse the promotion chain into one gated workflow

## Source

`docs/ci-pipeline-research.md` §3.4, §5 Phase 2. The workflow_run chain
(`publish-deploy-images` → `deploy-release`) re-derives cross-run trust in a 334-line
`resolve` job; both current staging failures live in that re-derivation. GitHub's
recommended shape: one workflow, sequential jobs, Environment gates with per-run
SHA-bound required-reviewer approval.

## Scope

**In:** new `deploy.yml` replacing `publish-deploy-images.yml` + `deploy-release.yml`;
retiring custom schema-2/3 attestation validation; rollback path. **Out:** review-env
workflows (088/089), client internals beyond what the collapse deletes (091).

## Design

1. **`deploy.yml`** — triggers: `push` to main (port today's `qualify` job from
   `publish-deploy-images.yml:25-48` **in full**: exactly one merged PR with
   `merge_commit_sha == GITHUB_SHA`, same-repo head and base
   (`.head.repo.full_name`/`.base.repo.full_name == $GITHUB_REPOSITORY`), `base.ref ==`
   default branch — and only then the `deploy-review` label; this same-repo/exactly-one
   gate also blocks direct pushes to main and must not be weakened) and
   `workflow_dispatch` (inputs: `target`, optional `image_digest`/`sha` for
   redeploy/rollback, bootstrap flag + backup-object for the first prod deploy). Jobs:
   - `publish` — build server/site/backup/db images → GHCR, `provenance: true`,
     `sbom: true`, digests as **job outputs** (no artifact plumbing);
   - `deploy-staging` — `environment: staging`, needs `publish`; renders staging manifest,
     `dokploy.sh deploy-release --app-role staging`, smoke verify; bootstrap path per 088;
   - `deploy-production` — `environment: production` (**required reviewers** — approval
     is per-run and SHA-bound; a later push never inherits it, but also does not cancel
     an older waiting run), needs `deploy-staging`; deploys the *same job-output digests*
     staging just proved; renders and deploys the **production manifest** (`stack.yaml`,
     plan-081/087 external-Swarm-secret model) — never `staging-stack.yaml`; the
     role→manifest binding formerly enforced by the schema-3 per-role checksum recheck
     now lives solely in this job wiring, pinned by test. Explicit backup-selection input
     retained for dispatch runs; deploy-production also retains the bootstrap path
     (dispatch-only bootstrap flag; verifies the manual anchor on the prod compose via
     `dokploy.sh require-manual-current` and validates the operator-supplied backup
     object against it, mirroring today's production-dispatch semantics) — the
     **first-ever prod deploy therefore runs via workflow_dispatch, not the push run**.
   Least-privilege carries over from the replaced workflows: workflow-level
   `permissions: {contents: read}`; `publish` job
   `permissions: {contents: read, packages: write, id-token: write, attestations: write}`
   (the last two for `attest-build-provenance`); deploy jobs get
   `permissions: {contents: read}` plus only what they demonstrably use; every
   `actions/checkout` sets `persist-credentials: false`.
2. **Trust model swap** — delete schema-2/schema-3 attestation construction + validation,
   ancestor checks, review-run lineage resolution. Same-run job ordering now proves
   "prod == staging-proven digests"; trigger-on-main proves ancestry; add
   `actions/attest-build-provenance` on `publish` for offline provenance. `release.json`
   BOM stays as a generated human-readable record (uploaded artifact), but no workflow
   validates its schema. Accepted tradeoff (owner, research §5 Phase 2): bespoke offline
   proof-chain audit is dropped; run logs + native attestations cover the audit trail.
   This also removes plan 087's checksum-enforced guarantee that production cannot select
   the staging (plaintext-credential) manifest; the replacement control is same-workflow
   job wiring, pinned by a policy test (see Test strategy).
3. **Deletions** — `deploy-release.yml` (596 lines), `publish-deploy-images.yml`
   (~90), `render-release.py` schema machinery, `resolve`-side jq schema predicates, the
   14-day artifact-retention coupling. `verify-release.sh` smoke stays.
4. **Rollback** — `workflow_dispatch` with prior digest redeploys through the same gated
   jobs (staging first, then approved prod). Dispatch-supplied digests are **not**
   trusted as-is: before rendering, the workflow verifies each digest resolves in GHCR
   and passes `gh attestation verify oci://<image>@<digest> -R <repo>`, asserting
   provenance from this repo's deploy workflow on `refs/heads/main` (i.e. a digest a
   prior push-triggered publish job produced); the run fails otherwise. This replaces the
   rollback-attestation flags.

## Implementation divergences (Stage C PR)

1. **`publish` is one sequential job, not a matrix.** Matrix jobs cannot
   reliably expose distinct outputs (each leg overwrites the shared output
   set); four build steps in one job give clean digest outputs, which is the
   whole point of the same-run trust model.
2. **Dispatch takes a `sha` input, not raw digests.** The published images
   are tagged by commit; `gh attestation verify oci://<image>:<sha>` both
   resolves and verifies each digest against this repository's default
   branch — one input, still nothing trusted as-is. The exact
   `--source-ref` behavior and GHCR auth for `gh attestation verify` get
   their first live exercise at Stage D; the path fails closed if either
   assumption is off.
3. **Every dispatch requires a backup object** (staging always redeploys
   first and dispatch staging always uses the operator-chosen object, per
   the retired dispatch semantics).
4. **Push-run production uses `--latest` backup selection** against the
   current production release id, mirroring automatic staging — the retired
   workflow had no push-path production at all (dispatch-only).
5. **Skip-site applies to both deploy jobs** via the environment-scoped
   variable (staging sets it today; production must either provision the
   site Application or explicitly set the variable before Stage D).
6. **`deploy-release-backup-step-test.sh` extracts both backup steps** from
   deploy.yml (staging + production) rather than the retired resolver
   script; the resolver test is deleted with the resolver.
7. **The ≥50% YAML-shrink criterion lands at 31% in this stage**
   (713 → 490 lines): the resolve/attestation machinery is gone, but the
   two environment-gated deploy jobs each repeat their render/backup/deploy
   /verify steps and env blocks. The remaining dedup is exactly plan 091's
   composite-action work and is deferred there, where the combined target
   still holds. `render-release.py` is retained in full — the delivery
   client still validates the schema-3 BOM it produces (client changes are
   091's scope); only the workflow-side schema predicates are deleted.

8. **Review-driven hardening (§3.2, Stage C).** Three review findings the
   plan's tradeoffs had not accepted, all fixed: (a) both deploy jobs check
   out the **candidate** commit (== `github.sha` on push; the
   attestation-bound sha on dispatch) so a rollback's BOM, migration
   history, and manifests are the candidate's own — rendering from current
   main made the migration-compatibility gate vacuous (the infra security
   review independently rated the dispatch variant of this CRITICAL: a
   rollback would pair historical images with current-main manifests and
   migrations). Deliberate consequence: a dispatch run's *tooling*
   (dokploy.sh, select-backup.sh, verify-release.sh) is also the
   candidate's — the whole tree is the coherent, once-deployed state; if a
   tooling regression ever blocks a rollback, dispatching a newer sha
   recovers. (b) the
   deployment-progress backstop is restored in both deploy jobs (a
   behind/diverged candidate — e.g. an approved stale waiting run — needs
   the explicit rollback attestation). Accepted looseness vs the retired
   `require_deployment_progress`: `behind` and `diverged` share one
   attestation-overridable bucket for both targets, where the old function
   hard-failed staging backward moves and all diverged candidates —
   `diverged` cannot occur while main forbids force pushes, and the
   attestation is an explicit operator act; (c) dispatch verification also binds
   the attestation's source commit to the dispatched sha (GHCR tags are
   mutable) and pins `--signer-workflow` to deploy.yml. Additionally: the
   qualify job regained `pull-requests: read`, and a production-targeted
   dispatch's staging safety leg selects staging's own latest backup (the
   operator object belongs to production and cannot validate cross-
   environment). **Gate H2 gains one item (§3.2 finding): both `staging`
   and `production` environments must restrict deployment branches to
   `main`** — environment protection is the sole barrier around deployment
   secrets for dispatched refs.

9. **The Stage C merge landed unlabeled; live verification runs on a
   follow-up labeled merge.** PR #59 was rebase-merged on 2026-07-16
   (merge tip `b1394fd`) without the `deploy-review` label. The qualify
   gate behaved exactly as designed: the merge push run (29479666439)
   produced `deploy=false` — publish and both deploy jobs skipped — so the
   Stage C merge itself deployed nothing and left no waiting
   `deploy-production` run. The roadmap's Stage C live verification
   (one-run `qualify`→`publish`→`deploy-staging` green, `deploy-production`
   Waiting) is therefore decoupled from the Stage C merge and runs on the
   first labeled merge after the `production` environment gains required
   reviewers (Gate H2 remainder — without reviewers, a labeled merge would
   *run* `deploy-production` instead of holding it). The PR carrying this
   divergence entry is that vehicle. `deploy.yml` is unchanged on main
   since `b1394fd`; intervening merges touched only `ci.yml`,
   `publish-gradle-plugin.yml`, actionlint config, plugin/server code, and
   docs.

10. **Traefik object names were swarm-global-colliding between the two
    long-lived stacks (found at the first production anchor, 2026-07-17).**
    `stack.yaml` and `staging-stack.yaml` both declared router `buildhound`,
    middlewares `buildhound-ratelimit`/`buildhound-robots`, and service
    `buildhound`. Traefik's swarm provider treats these names as global, so
    the first time the production stack coexisted with staging, both
    dashboards 404'd (the review stacks were always safe — their names carry
    the `${BUILDHOUND_REVIEW_PROVIDER_ID}` prefix). The broken production
    stack was removed to restore staging. Names are now role-suffixed
    (`buildhound-prod-*` / `buildhound-staging-*`) and a regression test pins
    the two stacks' Traefik object-name sets as disjoint. Consequences:
    the production manual anchor must be pasted from the fixed `stack.yaml`,
    and the Stage D dispatch sha must contain this fix (the dispatch deploys
    the candidate tree's own manifests).

11. **Secret file modes were group-writable in production (found at the
    re-anchor after divergence 10, 2026-07-17).** `stack.yaml` declared the
    backup service's `pgpass`/`s3_credentials` mounts as `mode: 0400`; docker's
    YAML 1.2 loader parses that bare leading-zero literal as **decimal** 400 =
    `0o620` (group-writable), so libpq rejected the pgpass file ("password file
    has group or world access") and `pg_dump` got no password. Same latency
    class as divergence 10: only the production stack mounts Swarm secrets, so
    the bug could not surface before the first prod anchor. Fixed as
    `mode: 0o400`; a regression test allowlists file modes (bare `0o` octal,
    no group/other bits — a leading-zero *ban* alone is bypassable by bare
    decimal `mode: 400`, which reproduces the identical 0o620 result). The
    production anchor must again be re-pasted from the fixed manifest, and
    this fix's labeled merge supersedes `480008c0` (merged unlabeled — no
    published images) as the Stage D dispatch candidate. §3.2 exposure
    assessment: the 0o620 window was fail-closed and availability-only — the
    stray bit was group-*write*, gid 10001's sole member is the owning uid,
    and libpq refused the file rather than using it — so no confidentiality
    widening occurred and **no credential rotation was warranted** for this
    specific bug.

## Test strategy

`deploy-release-resolver-test.sh` retires with the resolver; replacement tests assert:
render+deploy invocation per role, bootstrap branch, dispatch-rollback digest handling,
dispatch with an unattested/foreign digest fails before deploy, and a policy test pinning
that `deploy-production` invokes `dokploy.sh` with `--app-role prod` + the
`stack.yaml`-derived manifest and `deploy-staging` with `staging-stack.yaml` (port the
manifest-binding assertions from `test_review_regressions.py`).
`dokploy-policy.yml` still validates both stacks. First live prod deploy follows the
roadmap's human gate (environment reviewers configured before merge).

## Risks

Approval UX is the control point: `production` environment **must** have required
reviewers before this merges (operator step — roadmap Gate H2, before Stage C merge).
Staging keeps plan 087's plaintext-env credential decision (unchanged; no released
Dokploy version offers API-managed Swarm secrets). Production retains the
external-Swarm-secret model; the staging plaintext-env decision must not propagate to
the production job or manifest. Concurrency: **per-job groups** — `deploy-staging` uses
group `deploy-staging`, `deploy-production` uses group `deploy-production` (both
`cancel-in-progress: false`). Never one shared group: a run waiting on prod approval
holds its concurrency group (GitHub semantics), so a shared group would block every
later run's staging jobs behind the unapproved prod gate. The waiting prod run still
holds the `deploy-production` group: a newer run's prod job queues behind it and
proceeds only after an operator rejects (or approves) the waiting run; the newer run's
staging pass proceeds independently.

## Live verification log (Stage C)

- Vehicle: PR #66 (the divergence-9 entry above), rebase-merged 2026-07-17 with
  `deploy-review` applied at merge time; merge tip `625cd6f0`.
- Run 29564505571 (`deploy.yml`, push): `qualify` success (`deploy=true` — exactly
  one merged labeled same-repo PR for the pushed SHA), `publish` success in the
  same run. Digests: server `sha256:b6073d5a…ac3a53c`, site
  `sha256:d1727d15…1726a522`, backup `sha256:8bafd341…c0a0a00e`, db
  `sha256:05cf3d8d…ee4081bf`; BOM artifact `release-625cd6f0…` uploaded same-run.
- `deploy-staging` success (09:00–09:02Z); staging `/health` →
  `{"status":"ok"}` after the deploy. Zero cross-workflow artifact downloads
  anywhere in the run — all five run artifacts are its own uploads (four buildx
  traces + the BOM); no `download-artifact` step exists in any job.
- `deploy-production` entered **Waiting** (required reviewers active on the
  `production` environment) — the exit-criterion state. Two operator deviations,
  both in the GitHub UI, both benign:
  1. The `staging` environment had also been given required reviewers while the
     production gate was being enabled, so `deploy-staging` waited ~63 min for a
     manual approval before running (plan design: staging is automatic). Revert:
     `staging` keeps `branch_policy` only.
  2. The waiting `deploy-production` run was approved instead of rejected
     (roadmap: reject; Stage D deploys via dispatch). The job failed closed in
     8s inside the read-only backup-selection step (`no current successful
     release deployment found`) before rendering or any Dokploy call —
     live-proving the fail-closed no-anchor path that divergence 4 relies on.
     No Dokploy deployment was attempted, so no credential exposure (H4
     boundary). Consequence: no stale waiting run remains for Stage D step 1.
- Exit criteria: one-run promotion green ✓; zero cross-workflow artifact
  downloads ✓; prod job blocks pending approval ✓ (approval semantics beyond
  the block stay unexercised until Stage D).

## Exit criteria

- Merge to main: staging deploys green with **zero** cross-workflow artifact downloads.
- Prod job blocks pending approval; approving deploys the identical digests; a run for a
  newer push requires its own fresh approval (approval never carries across runs). Note:
  GitHub does **not** auto-cancel an older waiting run on a new push — if approved it
  would deploy its own older digests; superseded waiting runs must be rejected/cancelled
  explicitly (operator step, per roadmap Stage D).
- `deploy.yml` declares explicit workflow- and job-level permissions; no checkout
  persists credentials.
- `git grep -l 'workflow_run' .github/workflows/` → only workflows that legitimately keep
  it (expected: none).
- Workflow YAML total for the promotion path shrinks ≥50% (from ~690 lines).
