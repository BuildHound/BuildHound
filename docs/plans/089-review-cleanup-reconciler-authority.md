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
