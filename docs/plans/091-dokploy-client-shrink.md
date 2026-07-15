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

## Exit criteria

- `find deploy/dokploy -name '*.sh' | xargs wc -l` total ≤1,500.
- Version gate passes on a hypothetical 0.29.13 (unit-test the comparator).
- One full cycle green on the shrunk client: review deploy → converge → staging → prod
  (approval).
- CLI spike verdict recorded here.
