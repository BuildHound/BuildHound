# 089 — Review cleanup: reconciler becomes the source of truth

## Source

`docs/ci-pipeline-research.md` §3.2, §5 Phase 1. Verified findings: GitHub concurrency
semantics make event-driven cleanup structurally unreliable (cancel/replace/no ordering);
Argo CD's PR generator treats the polling reconciler as authoritative and webhooks as a
latency optimization. Prior art: full-rebuild's absolute-age sweep (INF-29).

## Scope

**In:** authority inversion, one idempotent converge entrypoint, event-handler slimming,
residue garbage collection. **Out:** dropping `isolatedDeployment` (rejected — review envs
run PR code; the lateral-network barrier stays), promotion-chain changes (090).

## Design

1. **One converge command** — `dokploy.sh reconcile-reviews` becomes the single cleanup
   authority: enumerate open PRs carrying `deploy-review` (GitHub API) → diff against live
   Dokploy composes in the review environment → retire anything unmatched (stop + anchor +
   `retired:true`, as today) and flag anything matched-but-missing for redeploy (report
   only; deploy stays event-driven). Fully idempotent; safe to run concurrently with
   itself via the existing `review-environment-global` group.
2. **Schedule** — `reconcile-reviews.yml` cron tightens from hourly to `*/15`. TTL logic
   (absolute-age) unchanged. Each converge run logs an evidence summary the orchestrator
   can read from run logs (`gh run view --log`): counts + `bh-*-mr*` names of retired /
   kept / skipped-non-review composes — **never raw Dokploy object ids** (this repo
   treats them as secrets, e.g. `DOKPLOY_COMPOSE_ID`).
3. **Event handlers become fast paths** — `review-environment.yml` close/unlabel path and
   `reconcile_failed_review` call the same converge command instead of bespoke
   exact-attempt scrub/retire choreography. Delete `compose.cleanQueues` +
   `getConvertedCompose` anchor-verification steps from the cleanup path (their failure
   modes are now covered by convergence, not prevented by choreography). A missed or
   cancelled event costs ≤15 min latency, never correctness.
4. **Residue GC (decision)** — Dokploy ≤0.29.12 cannot delete a compose + isolated
   network atomically (research §3.1); retired anchors + orphaned overlay networks
   accumulate. Chosen: **host-side GC timer** — a small script under `deploy/dokploy/`
   (installed once by the operator on the Dokploy host, systemd timer) that removes
   `retired:true` anchor composes' networks (`docker network rm` of unattached
   `bh-*-mr*` overlays) and prunes anchors older than N days via the local API. Rationale:
   no new CI-side SSH secret; CI keeps zero host access (trade-off: the anchor-prune half
   needs the instance-wide Dokploy token to gain a host-side residence — see Risks).
   Rejected alternatives: accept unbounded residue; SSH from Actions (new long-lived host
   credential in CI).

## Implementation divergences (Stage B PR)

1. **The converge entrypoint is `deploy/dokploy/reconcile-reviews.sh`, not a
   `dokploy.sh` subcommand.** The converge needs the GitHub API (`gh`) to
   enumerate open labelled PRs; `dokploy.sh` stays a pure Dokploy API client
   (which plan 091 will shrink). Single-authority semantics are unchanged —
   cron and both event fast paths run this one script.
2. **The summary reports kept/retired/missing/skipped-retired, with no
   separate skipped-non-review count.** `list-reviews` fails the whole run
   on any compose in the environment whose metadata is not a review record,
   so a non-review object in the review environment aborts converge instead
   of being counted — a stronger staging/prod-untouched guarantee than a
   skip counter, pinned by the policy test.
3. **A failed attempt on a still-open, still-labelled PR keeps its anchor
   and images** until the PR closes, unlabels, or times out — the previous
   exact-attempt scrub deleted them immediately. Accepted: the residue is
   bounded by TTL + converge, and correctness no longer depends on attempt
   evidence. Note the residual: for a failed attempt this residue is the
   credential-bearing stack composeFile (same kind as any live review),
   TTL-bounded; and with the anchor re-verification gone, scrub→retire
   ordering is enforced by converge's control flow plus a policy pin, with
   the host GC as the retention-bounded backstop — which exists only once
   Gate H1's timer is installed.
4. **Review-driven hardening (§3 reviews).** `retire_review` stamps
   `retiredAt` (additive metadata key, whitelisted in both validators) so
   the host GC retention window measures from actual retirement, not first
   deployment. The host GC gained: repository scoping (network prefix
   derived from sha256(repo) exactly like `review_provider_id`, and anchor
   deletion requires `metadata.repository` to match), lifecycle gating
   (networks of non-retired reviews are never removed, even while
   transiently unattached), a hard failure when `docker network ls` itself
   fails (a listing failure must never read as a clean H1 report), runtime
   enforcement of the env file's root ownership + 0600 mode on every run,
   `RETENTION_DAYS` bounds, URL/token shape validation, `--max-redirs 0`,
   and a GNU-date probe. `issues: read` was added to the three converge
   call sites for the issues-listing endpoint. Accepted as residual risk:
   the daily-timer network sweep is not serialized with in-flight deploys
   (lifecycle gating plus docker's in-use refusal bound the race;
   self-heals next tick), and the systemd unit is unsandboxed (any
   docker.sock client is root-equivalent anyway).

## Test strategy

`reconcile-reviews-test.sh` grows converge cases: open+labeled+missing → report; closed
but live → retire; retired+expired → GC-eligible list. Shell tests for the host GC script
(dry-run mode against fixture `docker network ls` output). Policy suite stays the gate.

## Risks

Reconciler holds the instance-wide Dokploy token on a cron — unchanged from today
(`review-cleanup` environment). Converge must never touch staging/prod composes: filter
strictly by review environment id + `bh-*-mr*` naming, pinned by a policy test. Host GC
script runs as operator on the host — dry-run default, explicit `--apply`. The
anchor-prune half requires the instance-wide Dokploy API token on the host (the API
rejects unauthenticated calls even on localhost; tokens are unscoped, research §3.1) — a
new residence for the token, acceptable only because the Dokploy host is already the
platform trust root. The shipped script must never embed the token: it reads it from a
root-owned `0600` EnvironmentFile or systemd `LoadCredential=`; the token stays out of
the committed script, unit file, repo, and logs.

## Live verification log (Stage B)

- First converge on merged main green (run 29460940750); the issues-listing
  endpoint works under the granted permissions (both reviews' open
  question). Summary: `kept=0 retired=0 missing=0 skipped-retired=6` — the
  six Stage A anchors skipped, staging/prod untouched.
- Failure alerting round-trip verified live: `simulate_failure` dispatch
  (run 29460976273) created marker issue #54; the next converge closed it
  with the recovery link.
- Chaos 1 (cancel mid-cleanup): run 29461071764 cancelled in flight; the
  next converge (run 29461086938) green with no manual action.
- Chaos 2 (close PR while the workflow is disabled): **PASSED, and caught a
  latent defect.** PR #55's review env went live (mr55 health 200);
  review-environment.yml was disabled; the PR closed (no cleanup event
  fired); the workflow was re-enabled. The next converge (run 29461440253)
  scrubbed mr55 (routes 404) but failed image cleanup and correctly opened
  marker issue #56: the `commits/{sha}/pulls` provenance check can never
  verify a closed or rebase-merged PR's head image (the endpoint omits
  non-open PRs for commits off the default branch — documented GitHub
  behavior), so every close-path image cleanup had been silently failing
  since inception. Fixed by PR #57 (PR-record provenance branch). PR #57's
  own merge close-event then exercised the fast path with the fix: mr55
  images deleted and anchor retired at 00:34:58Z, the dispatched converge
  one second later serialized behind it and reported `skipped-retired=7`,
  and issue #56 auto-closed — fast path, converge idempotence, and
  concurrency-group serialization demonstrated in one sequence.
- Chaos 3 (host GC pass): pending the Gate H1 operator report (merge
  proceeded ahead of H1 at the owner's direction; the report remains the
  outstanding exit-criterion evidence).

## Exit criteria

- Kill a cleanup run mid-flight (cancel in UI): environment is converged at the next tick
  with no manual action.
- Close a PR with Actions fully down (disable workflow temporarily): converged ≤15 min
  after re-enable.
- After GC timer runs on the host: no unattached review overlay networks, no retired
  anchors older than the retention window (evidence: the script's report, returned by the
  operator per roadmap Gate H1); staging/prod composes untouched (evidence: converge log
  summary's skipped-non-review count + the environment/name filter policy test).
- Net LoC in review cleanup paths decreases (choreography deleted > converge added).
