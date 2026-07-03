# Implementation plans

One file per feature/phase slice, written **before** implementation starts and committed
on its own (see the workflow in the repository root `CLAUDE.md`).

Naming: `NNN-short-title.md` (e.g. `001-task-event-collector.md`), numbered in creation
order. Numbering continues across directories — the next plan takes the next free number.

Lifecycle: active plans live in this directory; once a plan's exit criteria are met and the
work is merged, the file moves to [implemented/](implemented/) (`git mv`, same PR or a sweep).
Plans 000–013 (roadmap phases 0–1) are implemented and live there.

A plan contains, briefly:

1. **Source** — the spec/roadmap section(s) or feature request it implements.
2. **Scope** — what is in and explicitly out.
3. **Design** — modules touched, new types/endpoints/schema fields, data flow.
4. **Test strategy** — unit / TestKit / Testcontainers / golden files.
5. **Risks** — CC hazards, schema compatibility, security/privacy touchpoints.
6. **Exit criteria** — how we know it is done.

The plan is a commitment device, not bureaucracy: keep it under ~1 page. If
implementation diverges from the plan, update the plan file in the same PR and note why.
