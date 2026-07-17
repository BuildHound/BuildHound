# 094 — Multi-environment build-data publication (prod + staging + review)

## 1. Source

Owner feature request (2026-07) following the multi-server ingest investigation: internal
CI builds must land in every environment at-or-above the change's maturity, so prod tracks
all builds over time while new telemetry features are validated on review/staging first.
**Internal-only**: implemented entirely in workflow YAML + secrets — the published plugin
and the server keep their single-server contract (the investigation rejected a multi-URL
plugin DSL; Develocity ships none either, and the retry spool is keyed by `buildId` only,
so a second in-plugin destination would corrupt retry state). Depends on plan 093
(payload artifacts).

## 2. Scope

**In:** routing matrix below; direct plugin upload to production; artifact-consuming
publish jobs for staging and review; ingest-token provisioning.

| Build source | production | staging | review (PR env) |
|---|---|---|---|
| PR CI (same-repo) | direct (plugin) | artifact job | artifact step, `deploy-review`-labeled PRs only |
| push to `main` | direct (plugin) | artifact job | — |
| fork PR | — (no secrets → gate skips) | — | — |

**Out:** plugin/server/schema changes; multi-target spool; proxy mirroring; `/v1/metrics`
fan-out; backfilling review/staging gaps (only prod has spool-backed delivery).

## 3. Design

**Production (authoritative record).** The plan-093 init script gets real values in the
`build` job: `BUILDHOUND_DOGFOOD_SERVER_URL` = prod dashboard origin (repo variable
`BUILDHOUND_PROD_SERVER_URL`), `BUILDHOUND_DOGFOOD_TOKEN` = **new ingest-scope-only
token** minted on prod for the `buildhound` project, stored as repo secret
`BUILDHOUND_PROD_INGEST_TOKEN`. (The dogfood env contract is `_DOGFOOD_`-namespaced —
093 §3.2 review — so the plugin's plan-027 convention fallback `BUILDHOUND_SERVER_URL`
never sees job-level env and cannot arm uploads in nested TestKit fixture builds.) Inline CI upload keeps the spool/retry path, so prod is
the loss-protected copy. *Implementation note:* the URL env is blanked for fork PRs with
an expression-level same-repo condition — repo **variables**, unlike secrets, are
readable from fork runs, and a URL without a token would make the plugin attempt (and
soft-fail) an unauthenticated upload instead of producing the exit-criteria
"no server configured" skip. This is the plan's fork row made deterministic, not an
extra gate.

**Staging.** New `ci.yml` job `publish-staging` (`needs: [build, sample-springboot]`,
runs for same-repo PRs and `main` pushes): downloads the `buildhound-payload-*` artifacts
and POSTs each to `<staging>/v1/builds` (staging origin from repo variable
`BUILDHOUND_STAGING_SERVER_URL`) with `BUILDHOUND_STAGING_INGEST_TOKEN` (repo secret,
ingest scope). The POST lives in a small shellchecked script,
`buildhound-ci-assets/bin/buildhound-publish` (metric-CLI conventions: server/token via
`BUILDHOUND_SERVER_URL`/`BUILDHOUND_TOKEN` env, token only via header, soft-fail exit 0;
the env-scoped `*_INGEST_TOKEN` naming lives in the secret names and the workflow `env:`
mapping keeps both conventions visible at the call site) — a dead staging must never
redden a PR. *Implementation note:* the script's soft-fail exit 0 is the primary
contract (missing env → skip, 4xx → drop with warning, transport/5xx → warn); the job
additionally sets `continue-on-error: true` so step-level infrastructure failures
(checkout, artifact download) can't redden a PR either — the image-scan advisory
precedent, and gh-ci-babysitter surfaces continue-on-error failures so breakage stays
visible. Plain `needs` semantics are kept: a failed producer job skips this job (prod
still gets that payload via the in-build upload; staging misses it — accepted,
non-authoritative).

**Review.** A post-smoke step in `review-environment.yml`'s deploy path: find the CI run
for `$REVIEW_SHA` (`gh api`, `actions: read` is already granted), poll bounded (~10×30 s)
for the payload artifacts, POST them to `$dashboard_url/v1/builds` with the in-run
`BUILDHOUND_REVIEW_TOKEN` (generated per deploy; it exists nowhere else, which is why this
step cannot live in `ci.yml`). Soft-fail with a job-summary note when the artifact never
appears.

**Idempotency.** Ingest dedups on `(project_id, build_id)` — `ON CONFLICT DO NOTHING`,
duplicates return `"duplicate"` — so job re-runs, spool drains, and multi-env replays are
all safe; each environment dedups independently.

**Trust model.** Repo-level secrets hold *ingest-scope* tokens only (worst case: junk
telemetry, bounded by payload caps + rate limiting). Admin/bootstrap tokens stay in the
protected GitHub Environments. Fork PRs get no secrets → clean skips. The review step
consumes an artifact produced by PR-controlled code — treated as untrusted data POSTed to
an environment that runs that same PR's code anyway (blast radius nil).

## 4. Test strategy

- The publish script gets a `metric-cli-test.sh`-style sh test (success / 4xx-drop /
  unreachable-soft-fail / missing-env skip); shellcheck already enforced in CI.
- `actionlint` covers the workflow edits.
- End-to-end proof is the exit criteria run below.

## 5. Risks

- **Prod ingest token in PR context** — accepted deliberately (owner decision, this plan):
  scope-limited, fork-excluded, revocable; prerequisite below tightens the boundary first.
- **Schema drift, accepted:** an older prod server drops unknown new payload fields
  (`ignoreUnknownKeys`) and first-write-wins means no backfill after upgrade. New fields
  are *validated* on review (runs the PR's server image) and staging — exactly the point
  of the matrix.
- **Ordering race:** review deploy may finish before the CI build; bounded polling covers
  the common case, a missed artifact is logged and dropped (review copies are
  non-authoritative).
- **Workflow contention:** touches `ci.yml` + `review-environment.yml` — coordinate with
  the open CI-recovery plans 088–090.

## 6. Prerequisites & exit criteria

Prerequisites: mint ingest-scope tokens on staging + production (via each environment's
bootstrap-token admin path) and store the two repo secrets; verify the four GitHub
Environments' protection rules match `deploy/dokploy/README.md` (a 2026-07-15 live check
found them unset — per that README this is a rollout blocker for adding credentials).

### Owner actions required before enabling

The merged YAML is deliberately safe *before* any of these exist — every publish path
skips cleanly (plugin: `UploadGate` "no server configured"; script: "skipping publish"
log + exit 0) while they are unset. Nothing uploads until the owner:

1. Mints an **ingest-scope-only** token on production and staging (each environment's
   bootstrap-token admin path).
2. Creates the repo **secrets**: `BUILDHOUND_PROD_INGEST_TOKEN`,
   `BUILDHOUND_STAGING_INGEST_TOKEN`.
3. Creates the repo **variables** (dashboard origins, `https://…`, no trailing path):
   `BUILDHOUND_PROD_SERVER_URL`, `BUILDHOUND_STAGING_SERVER_URL`.
4. Fixes the four GitHub Environments' protection rules per `deploy/dokploy/README.md`
   (2026-07-15 check found them unset — a rollout blocker for credentialed use, and
   explicitly *not* permission to fall back to repository-wide credentials).

The review path needs no owner action: it reuses the per-run `BUILDHOUND_REVIEW_TOKEN`
minted inside `review-environment.yml`.

Exit: a `deploy-review`-labeled PR lands the same `buildId` in all three environments; a
merged commit lands in prod + staging; a fork PR shows "no server configured" and skipped
publish jobs; no token appears in any log (masked).
