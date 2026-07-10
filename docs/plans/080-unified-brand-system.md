# 080 — Unified BuildHound brand system

## Source

User request to create a new, stronger brand/design/asset system from the two reviewed
directions in `docs/brand/DESIGN.md` and `docs/brand/DESIGN-GPT.md`. The accepted review
conclusion is: keep the original direction's copper identity, distinctive type roles, and
complete asset posture; adopt Trail Grid's product grammar, responsive structure, semantic
token pairs, and direct diagnostic voice.

## Scope

**In:**

- Add `docs/brand/DESIGN-V2.md` as the unified review candidate and a short
  `docs/brand/README.md` that distinguishes the canonical candidate from the two source
  explorations.
- Add self-contained brand, dashboard, and offline-report review pages under
  `docs/brand/v2/`, sharing one local stylesheet and no scripts except a small theme control.
- Create a new **Trace H** identity: two compact H rails joined by a double-stepped telemetry
  path,
  delivered as full-color, monochrome, small-size, wordmark, favicon, and app-icon assets.
- Publish one portable token source (`tokens.json`) plus generated-equivalent CSS covering
  primitives, semantic foreground/background pairs, type, space, radius, and focus.
- Deliver a complete seven-state status-icon set and exercise the system with representative
  BuildHound telemetry, filters, tables, diagnostics, and an accessible timeline.
- Add repository-level adoption guidance so future public sites, product dashboards, and
  report interfaces start from `DESIGN-V2.md`, its tokens, assets, and component rules.

**Out:** runtime dashboard/report adoption; framework components; changes to telemetry,
server routes, or report generation; deletion of either source exploration; external fonts,
icons, CDNs, or network requests.

## Design

Copper remains exclusively brand-owned. Functional states use green (success), blue
(cache/information/up-to-date), red (failure), violet (flaky), gold (warning/interrupted),
teal (running), and neutral (executed/skipped/cache miss); every state has explicit
light/dark foreground and soft-surface aliases.
Fraunces is marketing-only, Inter is UI/body, and JetBrains Mono is diagnostic data. The
32 px measurement grid is limited to telemetry imagery and empty/chart surfaces rather than
covering dense application screens. Product copy leads with “Track every Gradle build. Find
the slow work.”; scent language is secondary editorial copy.

## Test strategy

- Render the three pages at 1440×900 and 390×844 in forced light and dark themes; verify no
  page-level horizontal overflow, clipped mark/wordmark, or missing asset.
- Programmatically verify regular text and semantic pairs at WCAG AA (4.5:1), non-text/focus
  colors at 3:1, 44 px mobile command targets, working keyboard focus, reduced-motion, table
  scrollers, and an accessible timeline summary.
- Check that all page/font/image references are local, every manifest asset exists, CSS and
  JSON token values agree, and `git diff --check` passes.

## Risks

- The Trace H may read as a generic letter rather than tracking; validate at 16/24/44 px,
  monochrome, and beside Gradle/Bazel marks, and keep its geometry simple enough to revise.
- Copper and gold are visually adjacent; gold is functional only and must always carry a
  label/icon, while copper never appears in status components.
- Review fixtures can be mistaken for production components; label them clearly and keep
  runtime adoption out of this plan.
- Reusing local variable fonts duplicates small binary assets; accept this for a standalone
  canonical review kit and retain the OFL notices.

## Exit criteria

- `DESIGN-V2.md`, the V2 manifest, and all three review pages describe and demonstrate one
  internally consistent system with no known brand/status collision.
- Full-color, mono, small, wordmark, favicon/app-icon, seven status icons, fonts, and CSS/JSON
  tokens are present and load locally.
- Desktop/mobile and light/dark render checks pass without page overflow; documented contrast,
  focus, target-size, semantic, and reduced-motion checks pass.
- The two source explorations remain intact and are clearly labelled as historical inputs.
- Root contributor guidance and the brand index direct site/dashboard/report work to V2 and
  require intentional divergences to be reconciled in `DESIGN-V2.md` first.

## Divergences

- The planned “Signal H” became **Trace H** during asset sketching. The detached point was
  removed because it collapsed into the right rail at favicon sizes and could be mistaken for
  a functional status signal. A single-color double-stepped crossbar preserves the
  tracking/telemetry idea, is less generic than a standard H, and remains legible in
  monochrome at 16 px.
- Cache and flaky were split from success and warning after the component pass. Blue now means
  cache/information, violet exclusively means flaky, and gold is reserved for warning or an
  interrupted build. The extra separation keeps timeline and badge colors outcome-specific.
- The review pages remain a multi-file design kit so they can share inspectable assets. The
  report page is labelled as an offline review fixture; the production `buildhound-report`
  adoption must inline those assets to preserve its single-file contract.
- No project-wide browser dependency was added for this docs-only kit. The required render
  matrix was completed in a controlled browser and recorded in `docs/brand/v2/QA.md`;
  durable runtime browser/report tests remain part of the separate adoption work.
