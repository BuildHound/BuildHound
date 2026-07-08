# 076 — BuildHound brand system

## Source

User request: create branding, design, icons, and imagery for BuildHound based on the
existing Gradle plugin, server dashboard, and standalone web report, with reference from
modern developer-tool websites.

## Scope

In:

- Define a product-facing visual language for BuildHound in the hosted dashboard.
- Add first-party logo/icon treatment and lightweight product imagery without external
  network dependencies.
- Carry the same brand primitives into the standalone HTML report while preserving its
  zero-network artifact contract.
- Keep copy aligned with the existing open-source Gradle telemetry positioning.

Out:

- New server routes, schema fields, or telemetry capture.
- External image/font/icon dependencies.
- A separate marketing site build.

## Design

Touch only static web assets:

- `buildhound-server/src/main/resources/web/index.html`
- `buildhound-server/src/main/resources/web/dashboard.js`
- `buildhound-report/src/main/resources/dev/buildhound/report/report-template.html`

The brand direction is "observability for builds": dark-on-light precision surfaces,
amber/green/blue status colors, a hound-mark made from simple inline CSS/SVG primitives,
and dashboard imagery made from real product concepts (timeline, cache, flaky tests,
CI spans). All payload-derived text continues to reach the DOM through `textContent`;
static decorative SVG/CSS is fixed markup only.

## Test Strategy

- Run focused report/static asset tests that protect the standalone artifact contract.
- Run server resource tests if available.
- Smoke the dashboard and report in a browser-compatible local flow when practical.

## Risks

- Standalone report CSP and zero-network guarantees must remain intact.
- Dashboard styles must stay responsive without adding a build step or dependencies.
- Icons must be static/allowlisted, not generated from untrusted payload values.

## Exit Criteria

- Dashboard has a coherent BuildHound header, visual system, icons, and first-run/empty
  states that explain the product without a marketing page.
- Standalone reports visually match the dashboard and remain offline-safe.
- Focused tests pass, or any unrun tests are explicitly called out.
