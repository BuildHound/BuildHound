# BuildHound V2 review QA

This record applies to the static brand, dashboard, and offline-report review fixtures in
this directory. It does not replace runtime tests for the hosted product or the generated
single-file `buildhound-report` artifact.

## Automated checks

Run from the repository root:

```bash
node docs/brand/v2/tools/generate-tokens.mjs --check
node docs/brand/v2/tools/validate.mjs
find docs/brand/v2 -name '*.svg' -exec xmllint --noout {} +
git diff --check
```

The validator checks token contrast, asset-manifest completeness, local-only references,
content-security policy, SVG active content, landmark/table basics, and icon semantics.
It deliberately reports **static validation** rather than implying that it rendered a
browser.

## Browser matrix

Last reviewed: 10 July 2026.

- Pages: `index.html`, `dashboard.html`, and `report.html`.
- Themes: Auto, forced Light, and forced Dark; the forced choice persists and Auto removes
  the override.
- Computed responsive widths: 320, 390, 580, 900, 1024, and 1440 px.
- Visual inspection: desktop light/dark brand and dashboard; desktop report; 390 px light
  for all pages; 320 px dark report.
- No page-level horizontal overflow. Wide tables remain inside keyboard-focusable bounded
  scrollers.
- Mobile navigation, controls, notice actions, diagnostic links, and footer commands meet
  the 44 px minimum target.
- Keyboard focus uses a visible 2 px focus-token outline with 2 px offset.
- Timelines expose a text summary; decorative chart markup is hidden from assistive
  technology; table captions and column scopes are present.
- Bundled fonts and local image assets load without console errors or outbound requests.
- Reduced-motion CSS removes nonessential transition and animation duration.

## Deliberate limitation

This docs-only kit does not add a project-wide browser automation dependency. The browser
matrix above was run with a controlled browser during review and must be repeated when a
fixture or shared CSS changes. Runtime adoption belongs in a separate plan and must add
durable browser/report regression tests in the owning module.

## Pre-launch brand gate

Trace H is a release-candidate mark. Before trademark or irreversible launch use, run a
recognition test and an adjacent-category collision review with build, CI, developer-tool,
and observability identities. Record the result in `DESIGN-V2.md`; revise the geometry if
participants remember only a generic H.
