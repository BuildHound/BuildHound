# 095 — Robots-header release gate

## Source

Operational finding (2026-07-17): `deploy/dokploy/staging-stack.yaml` labels the Traefik
middleware `X-Robots-Tag=${BUILDHOUND_ROBOTS_HEADER:-all}`. The documented convention
(`deploy/dokploy/README.md` step 4) — staging `noindex, nofollow`, production `all` — is
enforced by nothing. The default is the *production* value, so a reset/recreated staging
Dokploy environment silently makes staging indexable. Live staging currently serves the
correct header; this plan adds the automatic catch for when it stops doing so.

## Scope

**In:** a post-deploy assertion in `deploy/dokploy/verify-release.sh` that the dashboard
serves exactly the expected `X-Robots-Tag`; per-environment expected values pinned in
`.github/workflows/deploy.yml`; mock + cases in
`deploy/dokploy/test/verify-release-test.sh`; README step-4 note.

**Out:** changing the manifest default (kept — production must not break if its env is
reset), the site Application's headers (separate Dokploy app, no Traefik labels in this
repo), review environments (ephemeral, never DNS-published).

## Design

- `verify-release.sh` requires a new env `BUILDHOUND_EXPECTED_ROBOTS_HEADER` (fail-closed
  `:?` like the existing four). After the health retry loop proves `/health` is up, one
  `curl -fsS -D - -o /dev/null "$BUILDHOUND_DASHBOARD_URL/health"` captures response
  headers (GET, not HEAD — no assumption the server routes HEAD); the `X-Robots-Tag`
  value must equal the expectation exactly. Missing or duplicated headers both mismatch.
- `deploy.yml` verify steps pin the expectation in the workflow itself — staging
  `noindex, nofollow`, production `all` — so the check is independent of the very Dokploy
  environment it audits.

## Test strategy

Extend the mock `curl` in `verify-release-test.sh` to emit a header block for `-D` calls
(`MOCK_ROBOTS_VALUE` / `MOCK_ROBOTS_OMIT` knobs). Cases: matching header passes (call
count grows 4→5; skip-site 3→4), wrong value fails, absent header fails, unset
expectation fails before any curl. `dokploy-policy.yml` already runs this test in CI.

## Risks

Deploy-blocking check on a non-functional header: a mismatch now pauses the promotion
chain. Intended — the header is the only thing keeping staging out of search indexes, and
the failure mode (reset env) is otherwise invisible. No secrets involved; header values
are public and safe to print in failure output.

## Exit criteria

- Staging deploy with `BUILDHOUND_ROBOTS_HEADER` unset/reset fails verification with an
  explicit mismatch message; correct env passes.
- `deploy/dokploy/test/verify-release-test.sh` covers pass, wrong-value, missing-header,
  and unset-expectation paths and is green locally + in `dokploy-policy.yml`.
- README step 4 documents the enforcement.
