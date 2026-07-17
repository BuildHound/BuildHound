# 091 — Shrink the Dokploy delivery client

## Source

`docs/ci-pipeline-research.md` §4, §5 Phase 3. Prior art delivered the same platform on
~100 lines (headless-wordpress-v2, bare curl) and ~1,064 lines (full-rebuild, shared lib)
vs ~6,800 lines here. Runs **after** 090 has deleted the attestation/lineage surface and
the prod path has been exercised at least once.

## Scope

**In:** dead-surface removal, version-gate relaxation, helper dedup, CLI spike.
**Out:** behavior changes to deploy/reconcile semantics (fixed by 088–090); dropping the
policy test suite (it shrinks with the surface, never below the shipped commands).

## Design

1. **Delete surface orphaned by 089/090:** `cleanQueues` + the **cleanup-path**
   `getConvertedCompose` anchor-verification choreography (largely removed by 089 —
   delete residue only), attestation schema builders/validators, exact-attempt
   bookkeeping, lineage resolution helpers. **Keep** the deploy-path
   rendered-review-stack verification added by plan 088 (long-form tmpfs survival,
   `traefik.swarm.network` present / `traefik.docker.network` absent) — it is not
   orphaned by 089/090 and guards review-node isolation of PR-authored workloads. Field
   evidence (two prior projects, research §5): the Dokploy-misbehavior modes the deleted
   guards addressed did not materialize; the guards did cause the brittleness
   (exact-version pin).
2. **Version gate** — replace `settings.getDokployVersion == 0.29.12` equality with a
   documented **minimum** version check (warn above, fail below). Every Dokploy patch
   release currently breaks the client for no defect.
3. **Dedup** — one composite action (`.github/actions/dokploy-env/`) for the
   `DOKPLOY_URL`/`DOKPLOY_TOKEN` token-env boilerplate duplicated across workflows
   (composite over reusable workflow: steps inside existing jobs, not whole-job reuse).
   The single TLS-check step stays where it is (review-environment.yml only — it is not
   duplicated).
4. **CLI spike (timeboxed 30 min)** — evaluate `github.com/Dokploy/cli` (v0.29.4,
   May 2026) coverage of the surviving endpoints (compose create/update/deploy/stop,
   deployment polling, application update/deploy). Adopt only on full coverage +
   maintained; otherwise keep bare `curl`+`jq` (prior-art style). Record verdict in this
   plan file.
5. **Target:** `deploy/dokploy/` shell ≤1,500 lines (from ~6,800) with unchanged external
   behavior for the commands that remain.

## Test strategy

Policy suite is the regression harness: tests for deleted commands are removed in the
same commit as the commands; surviving commands keep their tests green throughout.
ShellCheck + `bash -n` gates unchanged. A staging deploy and a review deploy+converge run
green on the shrunk client **immediately after merge**, as the roadmap Stage E live
verification — review, converge, and staging workflows all execute the client from main,
so this check cannot run pre-merge; pre-merge gates are the policy suite, ShellCheck, and
`bash -n`.

## Risks

Deleting verification steps trades defense-in-depth for maintainability — accepted per
research §5. Note this trigger is not self-detecting once the checks are gone — nothing
in the pipeline observes what Dokploy renders. Tripwire: the version gate's warn on a
previously unseen Dokploy version; on the first deploy after any such warn, manually
spot-check `compose.getConvertedCompose` output for one review app against the submitted
compose (one-off curl + `docker compose config` diff — the server API remains available
even though the client code is deleted). Reinstate targeted checks if it diverges. No
secret-handling changes; workspaces stay `0700`.

## CLI spike verdict (Design 4) — keep bare curl+jq

`@dokploy/cli` v0.29.4 (verified still latest, npm 2026-05-12) nominally covers
every surviving endpoint (generated tRPC command per procedure; self-hosted URL +
instance-wide tokens via env). Rejected on the plan's "actively maintained" bar
plus three operational disqualifiers found in the generated source: (1)
**secrets on argv** — `--env`/`--composeFile` accept values only as command-line
arguments (no file/stdin input), so token-bearing content would hit process
listings and CI traces, violating the repo's tokens-only-via-env rule; (2)
**error-body loss** — the CLI prints only `err.message`, discarding the tRPC
error JSON that curl+jq surfaces for CI diagnostics; (3) **stalled human
release cadence** — near-daily bot spec-sync commits but no release since
0.29.4 while our server floor moves past 0.29.12, plus a silent `.env`
auto-load from cwd. The CLI is a ~100-line argv→tRPC mapper around axios;
adopting it buys no shrink and adds a Node supply-chain surface.

## Implementation divergences (Stage E PR)

1. **The ≤1,500-line exit criterion is retired as unreachable.** It was
   implicitly premised on wholesale client replacement by the Dokploy CLI;
   the spike verdict is keep-curl. A cross-checked dead-surface audit (five
   parallel analysts + adversarial re-grep synthesis) found dead code is only
   ~2–4% of the surface: everything else is must-keep plan-088 deploy-path
   verification and plan-089 converge authority. Landed totals: 8,614 →
   8,420 lines all-in, 4,462 → 4,351 excluding `test/`. The revised
   criterion below pins "no dead surface remains", not an absolute count.
2. **`release-id` command kept (map error caught by guardrail).** The audit
   map classified it dead; implementation found a third live caller
   (`test_delivery.py:75`, in the mandatory unittest suite). Test-only —
   removable in a follow-up that also ports that smoke check; not worth
   scope-widening here.
3. **Schema-3-only validation** deleted the schema-1/2 acceptance branches;
   ~25 negative fixtures were ported from `$V2` to schema-3 form with their
   semantics intact, plus new explicit schema-1/2 rejection assertions. A
   now-unreachable `[[ $schema -lt 2 ]]` branch inside
   `require_migration_compatibility` was left (outside mapped scope) —
   follow-up candidate.
4. **Revoke surface deleted; its four live guard scenarios ported to
   `scrub_review`** (attempt-mismatch, mixed-route, running-deployment,
   no-row idempotence — no-row verified empirically to match revoke's
   idempotent-proceed semantics; the port required teaching the test double
   to model the scrub deployment appearing, a harness gap, not a behavior
   change).
5. **Kept, explicitly out of 091 scope** (audit "unsure" items resolved to
   keep): exact-attempt bookkeeping and the reconciler's legacy empty-attempt
   fallbacks (live on every converge, test-pinned, and a Dokploy
   data-migration question), `current_release` legacy/bare-title parsing
   (liveness depends on live deployment history), and `api-test.sh`
   (rewritten to exercise the live `dokploy_api` transport instead of the
   deleted convenience wrappers).

## Exit criteria

- No dead command/helper surface remains: every `dokploy.sh` dispatch arm and
  every `lib/*.sh` function has a caller in workflows, scripts, or the live
  command surface (audit map satisfied; deviations recorded above).
- Version gate passes on a hypothetical 0.29.13 (unit-tested comparator:
  numeric per-segment, fail below minimum, warn-and-proceed above).
- One full cycle green on the shrunk client: review deploy → converge →
  staging → prod (approval).
- CLI spike verdict recorded here. ✔ (above)
