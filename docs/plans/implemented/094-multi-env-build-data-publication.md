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

Matrix imprecision, intentional for now: `sample-springboot` payloads reach staging and
review via the artifact paths but **not** production — the sample's `settings.gradle.kts`
sets an explicit demo `server.url` (localhost), which wins over any convention fallback,
so the direct-plugin production row effectively covers the root `build` job only.

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
"no server configured" skip (`UploadGate` keys on the URL alone). The expression is
additionally keyed on token-secret presence (094 reviews), so token-less same-repo
contexts — Dependabot PRs receive variables but not repo secrets; partial provisioning
where the URL variable exists before the token secret — also collapse to the clean skip.
This is the plan's fork row made deterministic, not an extra gate.

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

**Retention.** The new prod/staging copies fall under the spec's standard retention
defaults (raw task/kotlin/ci-span data 90 d → build-level 13 mo → daily aggregates
indefinite, enforced by the nightly `RetentionSweeper`, per-project overridable); review
copies need no policy — they live in the per-PR stack's database and die with it.

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

- **Prod + staging ingest tokens in same-repo PR context** — accepted deliberately (owner
  decision, this plan); the staging token shares the exposure exactly, it is not a lesser
  case. The honest exposure class: (a) any **write-collaborator** can exfiltrate either
  token *before any review* by editing the workflow or the publish script on a branch — a
  plain `pull_request` event runs the PR's own version of both; (b) any **compromised
  build-time dependency** executing inside `gradle build` can read the step env (bounded
  by dependency verification, `gradle/verification-metadata.xml`, and step-scoped env
  mapping). Mitigations: ingest-only scope (worst case junk telemetry, bounded by payload
  caps + rate limiting), fork exclusion, revocability; the prerequisite below tightens
  the environment boundary first. Alternatives rejected: Environment-scoped secrets with
  approval gates would put a human approval in front of every PR build, and push-only
  prod wiring loses the per-PR prod record — both incompatible with this plan's goal
  (prod accumulates every build, PR builds included).
- **Schema drift, accepted:** an older prod server drops unknown new payload fields
  (`ignoreUnknownKeys`) and first-write-wins means no backfill after upgrade. New fields
  are *validated* on review (runs the PR's server image) and staging — exactly the point
  of the matrix.
- **Ordering race:** review deploy may finish before the CI build; bounded polling covers
  the common case, a missed artifact is logged and dropped (review copies are
  non-authoritative).
- **Workflow contention:** touches `ci.yml` + `review-environment.yml` — coordinate with
  the open CI-recovery plans 088–090.
- **Silent total-collection failure, re-accepted (closes plan 093 §6's forward-pointer):**
  093 §6 deferred this to "094+, whose publish jobs are natural detectors for a missing
  artifact." As built, the publish jobs are *not* detectors: `publish-staging` (`count -eq
  0` → "producer jobs uploaded none", exit 0) and the review step (`artifact_count -eq 0` →
  "review copy skipped") both green-skip on zero payloads, indistinguishable from a genuine
  collection failure where the plugin wrote nothing. This is deliberately re-accepted, not
  fixed: the staging/review copies are non-authoritative, prod has the spool-backed plugin
  upload, and a hard detector would redden PRs on every legitimate empty case (docs-only
  change, fork, a failed producer job). No missing-artifact alarm is added; if one is ever
  wanted it belongs on the prod path (the authoritative copy), not the artifact consumers.

## 6. Prerequisites & exit criteria

Prerequisites: mint ingest-scope tokens on staging + production (via each environment's
bootstrap-token admin path) and store the two repo secrets; verify the GitHub
Environments' protection rules match `deploy/dokploy/README.md` (a 2026-07-15 live check
found them unset — per that README a rollout blocker for adding credentials).

**Status (2026-07-18, live-verified).** The rollout is *partially* complete:

- **Environment protection rules — done.** Matches the README's design (§ "trust root"):
  `production` has a required reviewer (`aegis123`, the sole maintainer, with
  *Prevent self-review* off so a one-person team can approve its own prod deploy — a
  single required reviewer is valid) plus a protected-branches deployment policy;
  `staging` is protected-branches-restricted with no human gate; `review` and
  `review-cleanup` are deliberately **free of any human approval gate** so the
  label-driven deploy/teardown flows stay automatic. A reviewer on those three would
  break the design, so their absence is correct, not a gap. (Optional, not required:
  a custom deployment-branch policy on `review`/`review-cleanup` for defence-in-depth on
  their secrets — omitted because those deploys run off PR heads, not `main`, so a
  protected-branches policy would block them; the workflow's own label + same-repo guards
  are the primary control.)
- **Repo secrets + variables — set** (`BUILDHOUND_PROD_INGEST_TOKEN`,
  `BUILDHOUND_STAGING_INGEST_TOKEN` as secrets; `BUILDHOUND_PROD_SERVER_URL`,
  `BUILDHOUND_STAGING_SERVER_URL` as variables).
- **Remaining blocker — token *values* are not yet valid.** A live run on 2026-07-18
  showed prod POSTs rejected 4xx and staging 401: the stored secrets are strings the
  servers do not recognise. The fail-safe held (payloads dropped/spooled, no PR reddened,
  no junk landed). Owner action items 2–3 below (mint each token *server-side* through the
  bootstrap-token path, then scope-probe it) are what actually close this out; item 3 was
  the check that would have caught the 401 before storing.

### Owner actions required before enabling

The merged YAML is deliberately safe *before* any of these exist — every publish path
skips cleanly (plugin: `UploadGate` "no server configured"; script: "skipping publish"
log + exit 0) while they are unset. The ordering below still matters (protection first,
then token *before* URL); the ☑/☐ marks the 2026-07-18 state.

1. ☑ **Protection rules** per `deploy/dokploy/README.md` — done. `production` gated by a
   required reviewer + protected-branches policy; `staging` protected-branches-restricted;
   `review`/`review-cleanup` intentionally human-gate-free. The README makes an unset rule
   a rollout blocker and explicitly *not* permission to fall back to a repository-wide
   credential; §5's accepted-risk posture assumes this boundary is tightened before any
   credential exists — it now is.
2. ☐ **Mint an ingest-scope-only token** on production and staging (each environment's
   bootstrap-token admin path). **This is the outstanding blocker** — the current secrets
   hold values the servers reject (prod 4xx, staging 401 on the 2026-07-18 run), i.e. they
   were stored without being minted server-side.
3. ☐ **Scope-probe each fresh token before storing** (a `verify-release.sh`-style probe):
   `POST /v1/builds` returns 2xx AND a read endpoint (e.g. `GET /v1/builds/<id>`) returns
   401/403 — proving the token can write but not read. Running this on the current values
   would have caught the 401 before they were stored.
4. ☑ **Repo secrets** created: `BUILDHOUND_PROD_INGEST_TOKEN`,
   `BUILDHOUND_STAGING_INGEST_TOKEN` (values pending re-mint per items 2–3). The two
   ingest tokens are **secrets, never variables** — a repo *variable* is stored and served
   in plaintext (readable in the UI and via the API) and is exposed to fork PR runs, which
   would hand the ingest credential to exactly the untrusted context the fork gate exists
   to exclude. ci.yml reads the tokens from `secrets.*` (`BUILDHOUND_DOGFOOD_TOKEN`,
   `publish-staging`), so a token placed in `vars` is also silently empty → uploads skip.
   (An earlier attempt stored these as *variables*; the plaintext values that exposed must
   be treated as compromised and rotated, not reused.)
5. ☑ **Repo variables** created (dashboard origins, `https://…`, no trailing path):
   `BUILDHOUND_PROD_SERVER_URL`, `BUILDHOUND_STAGING_SERVER_URL`. Create these only after
   the matching token secret exists: a URL-without-token window would otherwise fire
   unauthenticated POSTs from every same-repo build; the ci.yml expression also blanks the
   URL while the token secret is absent — two layers, rely on neither alone.

The review path needs no owner action: it reuses the per-run `BUILDHOUND_REVIEW_TOKEN`
minted inside `review-environment.yml`.

Exit: a `deploy-review`-labeled PR lands the same `buildId` in all three environments; a
merged commit lands in prod + staging; a fork PR shows "no server configured" and skipped
publish jobs; no token appears in any log (masked).
