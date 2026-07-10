# 076 — BuildHound brand exploration

## Source

User request: create a reviewable BuildHound brand direction based on the existing Gradle
plugin, server dashboard, standalone web report, and modern developer-tool websites. This
phase produces guidelines and isolated concept pages only. Product integration is a later
design-kit task.

## Scope

In:

- Capture the visual decisions from the dashboard/report prototype in
  `docs/brand/DESIGN-GPT.md`.
- Define color, typography, spacing, shape, iconography, imagery, interaction, responsive,
  accessibility, and product-copy guidance for later design-kit implementation.
- Create self-contained HTML review pages under `docs/brand/gpt/` that demonstrate the
  brand system, dashboard direction, report direction, and exportable SVG assets.
- Keep the concepts grounded in real BuildHound surfaces: task timing, cache outcomes,
  failures, flaky tests, CI spans, and offline reports.
- Remove the prototype styling and markup from runtime dashboard/report resources after
  its design decisions have been captured.

Out:

- Any change to `buildhound-server/src/main/resources/web/index.html`.
- Any change to `buildhound-server/src/main/resources/web/dashboard.js`.
- Any change to
  `buildhound-report/src/main/resources/dev/buildhound/report/report-template.html`.
- Product integration, a production design-kit package, server routes, schema fields, or
  telemetry capture.
- External image, font, or icon dependencies in the review pages.

## Design

The brand direction is "observability for builds": warm precision surfaces over a subtle
measurement grid, a charcoal-and-amber hound/trail mark, and distinct semantic colors for
healthy, cached, informational, and failed work. Imagery is built from actual product
concepts such as timelines, signal tracks, cache states, flaky tests, and CI spans rather
than generic developer illustrations.

The deliverable is intentionally implementation-neutral. `DESIGN-GPT.md` records tokens,
rules, examples, and accessibility constraints; the HTML pages are review fixtures, not
source files for direct product copying. A later design-kit plan will reconcile this
direction with other brand explorations, turn approved choices into tokens/components,
and define adoption by the dashboard and standalone report.

## Test Strategy

- Confirm runtime dashboard/report resources have no remaining changes in this branch.
- Check the review pages for missing local assets, external network references, malformed
  HTML, and accidental horizontal overflow.
- Review the brand overview, dashboard sample, and report sample at desktop and mobile
  viewport sizes in a browser.
- Run `git diff --check`.

## Risks

- Review fixtures may be mistaken for production-ready components; label their status and
  document what still needs design-kit normalization.
- A self-contained concept can drift from the real dashboard information architecture;
  use representative BuildHound data and name mappings explicitly.
- Semantic colors can lose contrast across light/dark themes; document paired foreground
  and background values and verify the samples visually.
- The concept may overlap with another brand draft; keep `DESIGN-GPT.md` clearly identified
  as an alternative for review rather than silently replacing approved guidance.

## Exit Criteria

- `docs/brand/DESIGN-GPT.md` fully captures the prototype's visual tokens, mark, component
  patterns, imagery language, responsive behavior, and design-kit handoff notes.
- `docs/brand/gpt/` contains self-contained, navigable brand, dashboard, and report sample
  pages plus reusable local SVG/CSS assets.
- The three runtime HTML/JavaScript resources listed above match the branch base.
- The samples have been visually reviewed on desktop and mobile, and validation results
  are recorded in the implementation handoff.
