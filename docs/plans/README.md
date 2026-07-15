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
than shipping standalone, but whose own exit criteria were met by that work. Four plans remain
active in this directory:

- [035](035-cc-miss-reason-capture.md) — CC miss-reason capture — **blocked**, not implementable
  as specified.
- [037](037-test-quarantine-addon.md) — test-quarantine addon — **blocked**, deferred behind
  gate #3 (flaky-detection precision validation against real pilot data).
- [075](075-internal-adapters-cc-hit-toggle-rehydration.md) — internal-adapters CC-hit toggle
  rehydration — **open**; a warm-daemon configuration-cache hit can still replay a
  previously-enabled capture toggle even when the current build's config has it off. Design and
  a `@Disabled` acceptance test exist; the fix hasn't landed.
- [092](092-gradle-plugin-portal-release.md) — Gradle Plugin Portal release — **open**; prepares
  `dev.buildhound` for a protected, reproducible Portal publication.

CI recovery track (research: `docs/ci-pipeline-research.md`; orchestrator runbook:
[ci-recovery-roadmap.md](ci-recovery-roadmap.md)) — strictly sequential:

- [088](088-ci-staging-review-bugfixes.md) — staging & review-env bug fixes — **open**.
- [089](089-review-cleanup-reconciler-authority.md) — review cleanup: reconciler becomes
  source of truth — **open**, after 088.
- [090](090-promotion-chain-collapse.md) — collapse promotion chain into one gated
  workflow — **open**, after 089.
- [091](091-dokploy-client-shrink.md) — shrink the Dokploy delivery client — **open**,
  after 090 and the first production deploy.

A plan contains, briefly:

1. **Source** — the spec/roadmap section(s) or feature request it implements.
2. **Scope** — what is in and explicitly out.
3. **Design** — modules touched, new types/endpoints/schema fields, data flow.
4. **Test strategy** — unit / TestKit / Testcontainers / golden files.
5. **Risks** — CC hazards, schema compatibility, security/privacy touchpoints.
6. **Exit criteria** — how we know it is done.

The plan is a commitment device, not bureaucracy: keep it under ~1 page. If
implementation diverges from the plan, update the plan file in the same PR and note why.
