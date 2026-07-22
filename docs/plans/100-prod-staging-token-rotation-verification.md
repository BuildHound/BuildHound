# 100 — Prod/staging token rotation verification

## Source

Owner request (2026-07-21): confirm that publishing — deploy plus verified build-report
ingest/read-back — works end to end on `staging` and `production` using the GitHub Actions
secrets `BUILDHOUND_INGEST_TOKEN` / `BUILDHOUND_READ_TOKEN`. The owner has verified both
token values directly against the live dashboards (login and the admin section), so a 401
from CI now means a delivery-chain problem — stale secret, wrong environment binding,
unactivated token — not a bad credential.

The chain broke twice, for two unrelated reasons:

- **Stale secrets.** Deploy run 29808261642 (main push "Fix Detekt findings", 06:49Z):
  publish succeeded, but deploy-staging's "Verify public site and authenticated data path"
  step failed with five `ingest attempt returned 401` from
  `deploy/dokploy/verify-release.sh`; deploy-production was skipped. The env-scoped secrets
  in the `staging`/`production` GitHub Environments were stale at that point. The owner
  rotated all four: `BUILDHOUND_INGEST_TOKEN` (staging 18:49:16Z, production 18:50:04Z) and
  `BUILDHOUND_READ_TOKEN` (staging 18:49:20Z, production 18:50:07Z). The rotated values
  have not yet been exercised by any CI run.
- **A non-promoting merge.** Separately, PR #90 (plan-099 live-proof docs) never promoted.
  Its pre-merge review deploy failed on a transient Dokploy `502` (run 29857518663, 18:33Z
  — the cleanup run five minutes later reached Dokploy fine), so the Deploy qualify gate on
  the merge commit hard-failed (run 29858145944) — by design, since a
  `deploy-review`-labeled merge without a green review proof is a broken promotion. A
  post-merge rerun can't regenerate that proof: `review-environment.yml` requires the PR to
  still be open, and the rerun failed that guard. Main tip `2b86705` is unpromotable
  retroactively; the next qualifying merge has to carry the chain instead.

This plan's PR is that next qualifying merge: a green review deploy on an open PR, and a
promotion that exercises the rotated tokens on staging and production, in one pass.

## Scope

**In:** this plan document as the qualifying-PR vehicle. Its merge drives the existing
promotion chain — publish, then deploy-staging (automatic), then deploy-production
(environment approval) — using the already-rotated `BUILDHOUND_INGEST_TOKEN` /
`BUILDHOUND_READ_TOKEN` in each GitHub Environment. `verify-release.sh`'s ingest POST
(expect `202`) plus its authenticated read-back is the machine proof that publishing works
on each environment.

**Out:** any server or plugin change. The repo-level dogfood ingest secrets
`BUILDHOUND_PROD_INGEST_TOKEN` / `BUILDHOUND_STAGING_INGEST_TOKEN` are a different slot
(dogfood ingest vs. env-scoped deploy-verify), last rotated 2026-07-17; checked separately,
after this deploy lands.

## Design

No production code changes. This PR's merge commit runs the existing collapsed
Deploy-qualify workflow (plans 090/097), which gates production promotion on a green review
deploy for the same, still-open PR.

Once qualified, deploy-staging runs unconditionally and deploy-production runs behind its
environment approval gate. Each stage's verify step reads that environment's
`BUILDHOUND_INGEST_TOKEN` / `BUILDHOUND_READ_TOKEN` (staging and production are separate
GitHub Environments with independent values) and calls `deploy/dokploy/verify-release.sh`,
which POSTs a smoke build (expect `202`) and reads it back with the read token. A green
verify step is direct evidence the rotated secret is the one the live server accepts, and
it doubles as the first CI use of each rotated value — relevant to the sweep risk below.

## Test strategy / Verification

Operational verification, not a code change — no unit/functional test additions. The
pipeline run is the verification:

- Review deploy on this PR reaches Dokploy and comes back green (retry via re-label/rerun
  while the PR is open if the transient `502` recurs).
- Merge triggers Deploy-qualify; confirm it passes (green review proof present).
- deploy-staging and deploy-production verify steps each return `202` on ingest and
  succeed on the authenticated read-back, using the 18:49Z/18:50Z-rotated tokens.
- Manual spot-check of both public dashboards for the smoke build via the read token, as a
  sanity check beyond the scripted read-back.

## Risks

- **Plan-098 unactivated-token sweep.** Dashboard-minted tokens are deleted if never used
  within the sweep window. The rotated values were stored ~18:49–18:50Z, so this chain
  needs to run promptly — first CI use is what activates each token. If verify still
  returns 401 with the rotated values in place, the next suspect is the server-side token
  store on the target environment, not the GitHub secret.
- **Transient Dokploy 502 on the review deploy.** Already observed once (run 29857518663);
  retryable by re-labeling or rerunning while the PR stays open, but a recurrence delays
  the merge that carries this verification.

## Exit criteria

Review deploy on this PR is green (also satisfies plan-099's live-proof exit criterion,
left open by PR #90's failed promotion). The merge qualifies. Staging verify passes (`202`
ingest + authenticated read-back) with the rotated tokens. Production verify passes after
environment approval, also with the rotated tokens. Both environments serve the ingested
smoke build back via the read token.

## Status (2026-07-21, verified)

All exit criteria met the same evening, in one pass (Deploy run 29860869556):

- Review deploy on PR #93 green on the first attempt (run 29860213688, no 502 recurrence);
  proof status `buildhound/review-deployed/pr-93` posted — plan-099's live-proof exit
  criterion is now also satisfied.
- Merge qualified; publish green; deploy-staging green **including** "Verify public site
  and authenticated data path" — the rotated staging tokens' first CI use (202 ingest +
  authenticated read-back).
- deploy-production approved by the owner and green, including the same verify step with
  the rotated production tokens. No sweep expiry or retries were needed.

Follow-up closed (2026-07-22): the repo-level dogfood ingest secrets had also gone dead —
CI run 29857466414 attempt 1 showed the prod in-build upload spooling and the staging
publish step dropping both payloads with HTTP 401. The owner minted fresh tokens and
updated `BUILDHOUND_PROD_INGEST_TOKEN` / `BUILDHOUND_STAGING_INGEST_TOKEN` (06:56Z); a
rerun of the same CI run (attempt 2) is the proof: the build job's prod upload logged
"payload uploaded (4930 bytes gzip)" and the staging publish step logged both payloads at
HTTP 202 ("2 published, 0 dropped/skipped"). The sample job's own in-build upload still
spools by design — its demo `server.url` points at localhost and the job carries no
credential (ci.yml comment); its payload reaches staging via the replay job.
