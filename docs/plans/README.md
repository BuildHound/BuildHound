# Implementation plans

One file per feature/phase slice, written **before** implementation starts and committed
on its own (see the workflow in the repository root `CLAUDE.md`).

Naming: `NNN-short-title.md` (e.g. `001-task-event-collector.md`), numbered in creation
order. Numbering continues across directories — the next plan takes the next free number.

Lifecycle: active plans live in this directory; once a plan's exit criteria are met and the
work is merged, the file moves to [implemented/](implemented/) (`git mv`, same PR or a sweep).
Plans 000–080 are implemented and live there, except [035](035-cc-miss-reason-capture.md),
[037](037-test-quarantine-addon.md), and
[075](075-internal-adapters-cc-hit-toggle-rehydration.md) below — including
[045](implemented/045-composite-task-dictionary.md), which was
superseded/closed by [056](implemented/056-composite-build-logic-dictionary-priority.md) rather
than shipping standalone, but whose own exit criteria were met by that work.

The list below is **exhaustive** as of 2026-08-14: every `NNN-*.md` file in this directory
appears, with the exit criterion that keeps it here. It was reconciled by verifying each plan's
own exit criteria against the tree, treating a criterion that can only be observed live (a CI
run, a deployed environment) or performed by the owner as met **only where some document records
the observation**. Two different files still claim number 092 — a real collision, left as-is
because both filenames are distinct. [109](109-nightly-benchmark-init-script-pickup.md) and
[110](110-interrupted-marker-retention.md) landed after that pass; their entries below restate
their own plans' outstanding criteria rather than an independent verification.

Blocked — not implementable as specified:

- [035](035-cc-miss-reason-capture.md) — CC miss-reason capture. Gradle writes no
  machine-readable miss reason before the Flow finalizer runs; no code shipped.
- [037](037-test-quarantine-addon.md) — test-quarantine addon. Deferred behind locked gate #3
  (flaky-detection precision ≥ 0.90 on real pilot data), which is recorded as un-run.

Open — plugin, commons, server:

- [075](075-internal-adapters-cc-hit-toggle-rehydration.md) — internal-adapters CC-hit toggle
  rehydration. A warm-daemon CC hit can still replay a previously-enabled capture toggle. Design
  and a `@Disabled` acceptance test exist; the fix hasn't landed.
- [092](092-windows-guh-tempdir-locks.md) — Windows daemon-locked Gradle User Home. The fix
  landed and the first green Windows run is recorded (architecture §7), but criterion 2
  ("no new failures on the Linux/macOS/floor/CC-off legs") is recorded nowhere — and those jobs
  are not required status checks, so the merge itself does not stand in as evidence.
- [092](092-gradle-plugin-portal-release.md) — Gradle Plugin Portal release. Criteria 1 and 5
  (credentialed `publishPlugins --validate-only` at a fixed non-SNAPSHOT version; pre-approval
  and publish digests matching) are unrecorded, and no release tag exists. Its
  dependency-verification scope was dropped repo-wide (2026-07-21 architecture decision log; see
  the plan's own status update) — the rest of the plan's scope is unaffected.
- [098](098-dashboard-ingest-token-generation.md) — dashboard ingest-token minting with a 6-hour
  activation window. Code, tests and migration V16 are in; criterion 2 ("migration applies on a
  fresh TimescaleDB via compose") is recorded nowhere, and the integration test runs plain
  Postgres by its own docstring.
- [101](101-persistent-dashboard-login-read-tokens.md) — persistent dashboard login via scoped
  read tokens. Self-declares `Status: open`; the manual browser verification of the persistence
  behaviours (survival across restart, all-scope session-only, Forget, 401 wipe) is unrecorded.
- [104](104-machine-specs-and-resource-usage.md) — machine specs and resource usage in the
  report. Exit criterion 5 is recorded **failed** by the plan itself: the reference-runner
  overhead run measured a finalizer Δ of 591.5 ms against a 150 ms cap, and the job is now parked
  behind `workflow_dispatch`, so it neither passes nor runs.
- [105](105-composite-action-ci-and-nightly-sample-benchmark.md) — composite action in CI plus a
  nightly sample benchmark. Criterion 6 (production build/benchmark rows under one seed ref) was
  blocked by a defect in its own implementation — the nightly run never picked up the init script,
  so nothing published. [109](109-nightly-benchmark-init-script-pickup.md) repairs that and closes
  this criterion with it.
- [109](109-nightly-benchmark-init-script-pickup.md) — nightly benchmark init-script pickup repair.
  The fix is in; criterion 4 (production `#/benchmark` rows for the dispatched run's seed ref) needs
  a post-merge `workflow_dispatch` plus a dashboard read.
- [110](110-interrupted-marker-retention.md) — retain an interrupted build's marker until it can
  publish. Criterion 6 is a §3 review round covering the plugin change, which the earlier 109 rounds
  do not.

Open — site and Dokploy delivery (081–087, 096; each predates the CI-recovery track below, which
corrected parts of them):

- [081](081-dokploy-long-lived-stack.md) — long-lived stack and encrypted recovery. No recorded
  fresh-volume restore drill and no measured RPO/RTO; architecture §7 still calls 24h/4h
  provisional. Backup-failure and disk-pressure alerting are unrecorded.
- [082](082-buildhound-main-site.md) — public main site. Live since 2026-07-18, but the V2 mark
  gate is still an open, unrecorded release gate (`DESIGN-V2.md`, `brand/v2/QA.md` both ask for a
  result recorded in `DESIGN-V2.md`; none exists).
- [083](083-dokploy-environment-delivery.md) — release delivery and review lifecycle. Criterion
  1's manual-dispatch gate no longer exists (deliberately replaced by
  [090](implemented/090-promotion-chain-collapse.md)) and criterion 3's manager-file scrub gate
  was deleted by [089](implemented/089-review-cleanup-reconciler-authority.md). Needs closing as
  superseded, on the [045](implemented/045-composite-task-dictionary.md) precedent, or rewriting.
- [084](084-shell-dokploy-delivery-client.md) — shell Dokploy delivery client. The Python client
  is gone and the shell surface is complete and reviewed; criterion 5's "with Dokploy's persisted
  isolated-deployment setting" is unrecorded, and `deploy/dokploy/README.md` still lists isolated
  review networking among the staging verifications owed.
- [085](085-dokploy-isolated-review-deployments.md) — isolated review deployments. Criterion 6
  (PR 24's review deploy plus both public smoke URLs passing) is unrecorded; the only recorded PR
  24 outcomes are failures.
- [086](086-dokploy-role-placement-readiness.md) — role placement and review readiness. 086's own
  review deploy never passed — the first green review needed
  [088](implemented/088-ci-staging-review-bugfixes.md)'s `traefik.swarm.network` fix on PR #42,
  not PR 24 — and its clean-context review outcome is unrecorded.
- [087](087-staging-environment-credentials.md) — staging environment credentials. The manifest
  split and checksum binding are in and tested; two of the three owner mitigations criterion 2
  depends on (disable staging build-error notifications, restrict Dokploy log access/retention)
  appear only as imperative policy text, never as an observation that they were done.
- [096](096-site-review-staging-delivery.md) — site review and staging delivery. Superseded by
  [097](implemented/097-site-compose-delivery.md), but **not** on the 045 precedent: 096's own
  prerequisite (a staging site **Application** with a registry pull relation) is recorded as
  having *failed*, and 097 then tore the Application and its ID variable down. The delivery
  contract the tree enforces is Compose, and a policy test now asserts the Application API is
  absent from the client. Needs closing as abandoned rather than implemented.
- [099](099-owner-provisioned-review-token.md) — owner-provisioned review dashboard token. The
  workflow prefers the `review`-environment variable with the per-run fallback intact; the plan
  itself still calls the owner-browse confirmation a "Remaining human check".

Plans [093](implemented/093-dogfood-buildhound-telemetry.md) (dogfood telemetry) and
[094](implemented/094-multi-env-build-data-publication.md) (multi-environment publication) are
implemented; 094's credentialed paths stay dormant until the owner actions in its §6 are done.

Swept to [implemented/](implemented/) on 2026-08-14 after the exit-criteria verification above:
[095](implemented/095-robots-header-release-gate.md) (robots-header release gate),
[100](implemented/100-prod-staging-token-rotation-verification.md) (prod/staging token rotation),
[102](implemented/102-html-filter-end-tag-regexes.md) (HTML end-tag regexes),
[103](implemented/103-dashboard-style-hash-tag-pairing.md) (dashboard style-hash tag pairing),
[106](implemented/106-overhead-harness-repair.md) (overhead-harness repair), and
[107](implemented/107-dashboard-machine-specs-and-resource-usage.md) (dashboard machine specs).
106's former "open" note here was stale on both counts: two reference-runner CI runs are recorded
in its §7, and the budget breach it surfaced is excluded by its own §2 Out — that breach is
tracked on [104](104-machine-specs-and-resource-usage.md)'s criterion 5 above.

CI recovery track (research: `docs/ci-pipeline-research.md`; orchestrator runbook:
[ci-recovery-roadmap.md](ci-recovery-roadmap.md)) — strictly sequential:

- [088](implemented/088-ci-staging-review-bugfixes.md) — staging & review-env bug fixes —
  **implemented** (Gate H1's host-gc dry-run report is still owed to 089's log).
- [089](implemented/089-review-cleanup-reconciler-authority.md) — review cleanup:
  reconciler becomes source of truth — **implemented**.
- [090](implemented/090-promotion-chain-collapse.md) — collapse promotion chain into one
  gated workflow — **implemented**.
- [091](implemented/091-dokploy-client-shrink.md) — shrink the Dokploy delivery client —
  **implemented**; its full review→staging→production cycle rode plan 097's merge run.
- [097](implemented/097-site-compose-delivery.md) — site delivery via Dokploy Compose
  stacks (supersedes 096's site-Application design) — **implemented**; staging + production
  site live via `compose.deploy`.

A plan contains, briefly:

1. **Source** — the spec/roadmap section(s) or feature request it implements.
2. **Scope** — what is in and explicitly out.
3. **Design** — modules touched, new types/endpoints/schema fields, data flow.
4. **Test strategy** — unit / TestKit / Testcontainers / golden files.
5. **Risks** — CC hazards, schema compatibility, security/privacy touchpoints.
6. **Exit criteria** — how we know it is done.

The plan is a commitment device, not bureaucracy: keep it under ~1 page. If
implementation diverges from the plan, update the plan file in the same PR and note why.
