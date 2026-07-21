# 099 — Owner-provisioned review dashboard token

## Source

Owner request (2026-07-20), closing the human-access gap left by plan 094: the review
env's `BUILDHOUND_REVIEW_TOKEN` is minted per deploy run and exists nowhere else, so no
human can ever open `https://mr<PR>.dashboard.review.buildhound.dev` — the dashboard's
token bar has nothing to paste. Owner decision: source the token from a CI secret they
provision, so one known value opens every review dashboard.

## Scope

**In:** `review-environment.yml` deploy step prefers a `review`-environment secret
`BUILDHOUND_REVIEW_TOKEN` when present; the plan-094 per-run `openssl rand` value stays
as the fallback (merge-safe before the secret exists, and the old zero-persistence model
remains available by deleting the secret). Doc updates: plan 094 §6's "no owner action"
note, the in-workflow comment, and `deploy/dokploy/README.md`'s environment-secret
documentation (review addition). A regression test pins the `:-` fallback form — GitHub
materializes an unprovisioned secret's env mapping as an empty string, so a bare `-`
would silently break the unprovisioned path (review addition).

**Out:** server changes; a read-scope mint flow (rejected alternative — plan-098 endpoint
extension + summary publishing is more machinery than the owner wants); the per-run DB
password (stays random per deploy); review-stack wiring (unchanged —
`BUILDHOUND_BOOTSTRAP_TOKEN: ${BUILDHOUND_REVIEW_TOKEN}`).

## Design

- Deploy step gains env `BUILDHOUND_REVIEW_TOKEN_SECRET: ${{ secrets.BUILDHOUND_REVIEW_TOKEN }}`
  (the deploy path binds `environment: review`, so the secret lives there, next to
  `DOKPLOY_ENVIRONMENT_ID`).
- Shell: `BUILDHOUND_REVIEW_TOKEN=${BUILDHOUND_REVIEW_TOKEN_SECRET:-$(openssl rand -hex 32)}`;
  the unconditional `::add-mask::` stays (secrets are auto-masked; the generated fallback
  needs the explicit mask).
- **Value contract:** must match `^[0-9a-f]{64}$` — `deploy/dokploy/lib/review.sh`
  (`_review_valid_secret`) hard-fails the deploy otherwise. Provision with
  `openssl rand -hex 32`.
- Step output `review_token` and the plan-094 publish step are unchanged: step outputs
  survive masking inside a job (only job-level outputs are dropped).
- The token remains the review server's *bootstrap* token: ingest+read+admin on that
  throwaway env. Pasting it into the dashboard token bar gives the human read path; the
  same value keeps serving the smoke POST and payload replay.

## Test strategy

- Policy suite pins on the workflow (`deploy/dokploy/test/test_review_lifecycle.py`,
  `test_review_regressions.py`) — run locally; add/keep the no-echo assertion.
- `actionlint` in CI covers the YAML edit.
- Live proof: next `deploy-review`-labeled PR deploys, owner opens the review dashboard
  with the stored value; a deploy with the secret absent still works (fallback).

## Risks

- **Long-lived shared credential in PR-reachable context — accepted (owner decision).**
  The deployed server runs the PR's own code with the token in its container env, so any
  same-repo `deploy-review`-labeled PR can exfiltrate it once and retain admin over all
  *future* review envs until the secret is rotated. Bounded: the token grants nothing
  outside review envs, which are TTL'd and hold only this repo's own CI telemetry.
  Rotation is a secret update **for future deploys only** — running review envs keep
  accepting the value they were deployed with, so a suspected leak means rotate *and*
  tear down (unlabel) active review envs. The fallback preserves the plan-094
  zero-persistence model wherever the secret is absent.
- Same token across concurrent review envs: cross-env access among them — accepted, same
  trust domain.

## Exit criteria

A labeled PR's review deploy uses the secret-sourced token and the owner can browse that
review dashboard; smoke + plan-094 replay still pass; deploy without the secret still
succeeds on the generated fallback; no token value in any log (masked).
