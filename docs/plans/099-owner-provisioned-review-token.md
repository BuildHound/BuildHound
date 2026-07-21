# 099 — Owner-provisioned review dashboard token

## Source

Owner request (2026-07-20), closing the human-access gap left by plan 094: the review
env's `BUILDHOUND_REVIEW_TOKEN` is minted per deploy run and exists nowhere else, so no
human can ever open `https://mr<PR>.dashboard.review.buildhound.dev` — the dashboard's
token bar has nothing to paste. Owner decision: source the token from a CI **variable**
they provision (revised from the initially implemented secret, same day): a variable is
readable back from the UI/API, so the owner can always recover the value to paste —
recoverability is the point; a write-only secret would recreate the problem the moment
the value is misplaced.

## Scope

**In:** `review-environment.yml` deploy step prefers a `review`-environment **variable**
`BUILDHOUND_REVIEW_TOKEN` when present; the plan-094 per-run `openssl rand` value stays
as the fallback (merge-safe before the variable exists, and the old zero-persistence model
remains available by deleting the variable). Doc updates: plan 094 §6's "no owner action"
note, the in-workflow comment, and `deploy/dokploy/README.md`'s environment-provisioning
documentation (review addition). A regression test pins the `:-` fallback form — GitHub
materializes an unprovisioned variable's env mapping as an empty string, so a bare `-`
would silently break the unprovisioned path (review addition) — and pins the explicit
`::add-mask::`, which for a variable is the only log masking there is.

**Out:** server changes; a read-scope mint flow (rejected alternative — plan-098 endpoint
extension + summary publishing is more machinery than the owner wants); the per-run DB
password (stays random per deploy); review-stack wiring (unchanged —
`BUILDHOUND_BOOTSTRAP_TOKEN: ${BUILDHOUND_REVIEW_TOKEN}`).

## Design

- The review job maps `BUILDHOUND_REVIEW_TOKEN_VAR: ${{ vars.BUILDHOUND_REVIEW_TOKEN }}`
  at **job level** (the deploy path binds `environment: review`, so the variable lives
  there, next to the environment's `DOKPLOY_ENVIRONMENT_ID` secret) and registers
  `::add-mask::` in the job's **first step**. Job-level on purpose: the runner echoes
  every *step-level* env mapping — variables unmasked — into the step's log header
  before any script line runs, which leaked the raw value once (see Risks). Policy tests
  pin both the job-level placement and the mask-first step.
- Shell in the deploy step:
  `BUILDHOUND_REVIEW_TOKEN=${BUILDHOUND_REVIEW_TOKEN_VAR:-$(openssl rand -hex 32)}`; the
  in-step `::add-mask::` stays to cover the generated fallback branch.
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
  with the stored value; a deploy with the variable absent still works (fallback).

## Risks

- **Long-lived shared credential in PR-reachable context — accepted (owner decision).**
  The deployed server runs the PR's own code with the token in its container env, so any
  same-repo `deploy-review`-labeled PR can exfiltrate it once and retain admin over all
  *future* review envs until the value is rotated. Bounded: the token grants nothing
  outside review envs, which are TTL'd and hold only this repo's own CI telemetry.
  Rotation is a variable update **for future deploys only** — running review envs keep
  accepting the value they were deployed with, so a suspected leak means rotate *and*
  tear down (unlabel) active review envs. The fallback preserves the plan-094
  zero-persistence model wherever the variable is absent.
- Same token across concurrent review envs: cross-env access among them — accepted, same
  trust domain.
- **Incident (2026-07-21, remediated same day):** the first live deploy with the
  variable (run 29831846143, PR #90) printed the raw value into the **public** Actions
  log — the runner echoes step-level env mappings before any script line executes, so
  the in-script `add-mask` registered too late; secrets survive this because they are
  auto-masked, variables are not. Fix: job-level env + mask-first step (this revision).
  Owner remediation: delete that run's logs, rotate the variable, redeploy active review
  envs. The leaked value is compromised regardless of log deletion.
- **Variable, not secret — accepted (owner decision, the revision that defines this
  plan).** The value is plaintext at rest in GitHub and readable via UI/API by anyone
  with Actions read access to the repository, and it is exempt from automatic log
  masking. Plan 094 §6 item 4's "secrets, never variables" rule is deliberately NOT
  overturned — it continues to govern the prod/staging ingest tokens, whose compromise
  pollutes durable telemetry; this variable governs only throwaway review envs, where
  recoverability for human dashboard access outweighs plaintext storage.

## Exit criteria

A labeled PR's review deploy uses the variable-sourced token and the owner can browse that
review dashboard; smoke + plan-094 replay still pass; deploy without the variable still
succeeds on the generated fallback; no token value in any log (masked).
