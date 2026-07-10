# BuildHound brand and product design system

> **Status: canonical V2 design direction; Trace H release candidate.** This document
> reconciles the strongest decisions from the two earlier brand explorations into one
> system. Its product grammar, tokens, typography, and voice are the source of truth for
> future work. Trace H still requires recognition and adjacent-category collision testing
> before trademark or irreversible launch use.
>
> [DESIGN.md](DESIGN.md), [DESIGN-GPT.md](DESIGN-GPT.md), [site/](site/), and
> [gpt/](gpt/) are preserved historical explorations. They explain how this direction was
> reached, but their marks, tokens, and component rules are not current.

## 1. Direction

BuildHound is an open-source, self-hosted build-observability product. It follows a Gradle
build through task execution, cache decisions, tests, CI, and the standalone report, then
points engineers toward the work worth fixing.

The V2 identity combines:

- the original direction's warm charcoal, copper, expressive display type, and tracking
  premise;
- Trail Grid's compact diagnostic surfaces, responsive behavior, semantic color pairs,
  and evidence-led product imagery.

### Positioning hierarchy

**Product promise**

> Track every Gradle build. Find the slow work.

**Product descriptor**

> Open-source, self-hosted build observability for task time, cache outcomes, failures,
> flaky tests, CI spans, and offline reports.

**Marketing line**

> Every build leaves a scent. We follow it.

The product promise is the default headline in application, documentation, onboarding,
and product-led marketing. The scent line is reserved for brand-led marketing; it is not
used as operational UI copy.

### Principles

1. **Evidence before decoration.** Show real task paths, durations, cache outcomes,
   failures, and timelines rather than generic code or atmospheric art.
2. **Warm precision.** Warm charcoal, paper, and copper provide identity. Regular spacing,
   strong hierarchy, and measured data graphics keep the product technical.
3. **Brand is not status.** Copper identifies BuildHound. It never means running, warning,
   failure, cache state, or any other telemetry outcome.
4. **Dense, not cramped.** Operational data stays compact, but controls, headings, and
   diagnostic groups retain enough space to scan.
5. **The hound tracks; it does not perform.** Avoid mascot poses, dog puns, bones, kennels,
   eyes, or playful product vocabulary.
6. **One system across surfaces.** Hosted pages and production standalone reports use the
   same tokens, components, statuses, and language. The production report remains a single,
   fully self-contained HTML file; the V2 review fixture uses adjacent inspectable assets.

## 2. Mark and lockup

### Primary mark: Trace H

Trace H is a geometric H whose crossbar rises and returns like a compact telemetry trace.
The double step is more ownable than a standard crossbar while keeping the build-tool
clarity of the assembled-H exploration and the directional movement of Trail Grid. It
does not read as a pet brand, mountain, or lightning bolt.

The mark contains no semantic-colored signal point. The stepped path itself represents
the finding.

### Construction

- Master canvas: 32 by 32.
- Master path:

      M7 26V6 M25 6v20 M7 21h6v-9h6v6h6

- Stroke: 4 units, rounded caps and rounded joins.
- Fill: none.
- Color: copper or currentColor only.
- The two rails and double-stepped crossbar must remain a single visual system. Do not redraw,
  stretch, rotate, skew, or independently recolor parts.

The field-free SVG uses currentColor. Theme-specific copper applications use:

- light surfaces: #B85A28;
- dark surfaces: #DD8148.

A stable app-tile or favicon may place the dark-theme copper mark on neutral charcoal
#15130F. The tile field is neutral brand structure, not a second logo color. Do not add a
gradient or a green, blue, red, violet, teal, or gold point.

### Size and clear space

- Preferred product-header size: 32 px.
- Minimum standard glyph: 24 px.
- At 16 px, use an optically adjusted favicon export of the same geometry. Only stroke
  alignment may change; the double-step construction may not.
- Clear space: at least one quarter of the glyph's rendered width on every side.
- Do not place the mark directly on copper, a semantic status fill, or a busy chart.

### Wordmark

The wordmark is BuildHound as one word in Fraunces SemiBold. A marketing lockup may set
Hound in copper-text while keeping the wordmark roman. Product headers use a copper Trace H
with a neutral wordmark for maximum legibility.

Do not outline or rasterize the only source asset. Keep an editable vector or type-based
master, then export SVG and PNG delivery variants.

### Secondary graphic device

Three fading copper dots may divide marketing sections or imply a trail. They are never
part of Trace H, never replace navigation icons, and never indicate live status.

## 3. Color

Expose primitive values and semantic aliases. Components consume aliases rather than raw
hex values. Light and dark themes are tuned independently.

### Foundation

| Role | Light | Dark | Use |
|---|---:|---:|---|
| Canvas | #FBF7F1 | #15130F | Application and page ground |
| Surface | #FFFDF9 | #1D1A16 | Standard panel |
| Subtle surface | #F1E8DC | #26211B | Grouped or inset content |
| Raised surface | #FFFFFF | #2C261F | Menus, controls, raised content |
| Measurement grid | #E7DED3 | #2B261F | Charts and selected editorial fields |
| Primary text | #211C15 | #F2EBDF | Titles and body copy |
| Secondary text | #594F45 | #C9BFAF | Descriptions and supporting copy |
| Muted text | #6F655A | #A89D8E | Metadata and low-emphasis labels |
| Border | #D8CDBF | #40382F | Dividers and nonessential boundaries |
| Control border | #96897B | #7D705F | Required control and state boundaries |
| Brand copper | #B85A28 | #DD8148 | Mark, selected indicator, decoration |
| Brand copper text | #93451B | #E9A878 | Links and meaningful copper text |
| Brand copper surface | #F3DED0 | #3E281C | Selected or branded low-emphasis surface |
| Focus | #2A6FD1 | #6FA8FF | Keyboard focus only |

Light-theme brand copper does not meet 4.5:1 for normal text on the canvas. Use
brand-copper-text for links, labels, and other meaningful text. Primary actions use
high-contrast neutral fills; do not place white text on light-theme copper.

### Semantic pairs

Each state defines a foreground, soft background, and solid data-visualization color.
Every foreground/background pair meets WCAG AA for normal text.

| Meaning | Light foreground / background / solid | Dark foreground / background / solid |
|---|---|---|
| Success | #0F7048 / #DFF8E9 / #1F8055 | #7BE0AE / #123326 / #4ABD8C |
| Failure | #A1332D / #FFE2DF / #C22E33 | #FF9A94 / #3C1E1C / #E5646A |
| Cache or information | #2553A6 / #E3EDFF / #2A6FD1 | #91B9FF / #142642 / #6FA8FF |
| Flaky | #5740A8 / #E8E1FF / #6B54C8 | #C3B4FF / #2E254D / #A995FF |
| Running or live | #0B6670 / #D8F5F4 / #0C7984 | #72DDE0 / #123237 / #56C9CF |
| Warning or interrupted | #7A4A00 / #FFF0C7 / #A5680E | #FFD27A / #3F2C12 / #F0B158 |
| Neutral | #4A4F59 / #E7E5DF / #6F655A | #D1D5DB / #303640 / #A89D8E |

### Status mapping

| Product state | Semantic role |
|---|---|
| Successful build or healthy signal | Success |
| Failed build, task, regression, or destructive action | Failure |
| From cache, up-to-date, contextual span, or information | Cache or information |
| Flaky, retry plus history, or inconsistent test | Flaky |
| Running build, active upload, or live update | Running or live |
| Interrupted build, warning, or attention required | Warning or interrupted |
| Executed, cache miss, skipped, no source, or unavailable | Neutral |

Never rely on color alone. Every state includes a label and, where space permits, a
recognizable icon. Copper is absent from this mapping.

## 4. Typography

All fonts are self-hosted and licensed under the SIL Open Font License. No product or
report surface fetches a font from a CDN.

| Face | Role | Weights |
|---|---|---|
| Fraunces Variable | Wordmark and marketing display only | 600-750, roman |
| Inter Variable | Product UI, body copy, controls, and tables | 400, 500, 600, 700 |
| JetBrains Mono Variable | Task paths, hashes, durations, cache keys, logs, and traces | 400, 500, 600 |

Fraunces does not appear in dense product tables or controls. Mono is functional, not a
decorative substitute for body text. Numeric diagnostic columns use tabular numerals.

### Type scale

| Role | Desktop | Narrow mobile | Face / weight | Line height |
|---|---:|---:|---|---:|
| Marketing display | 56 px | 40 px | Fraunces 650 | 1.0 / 1.05 |
| Marketing heading | 40 px | 32 px | Fraunces 650 | 1.1 |
| Marketing section | 28 px | 26 px | Fraunces 650 | 1.2 |
| Product page title | 24 px | 22 px | Inter 700 | 1.2 |
| Product section title | 18 px | 18 px | Inter 650 | 1.3 |
| Body | 16 px | 16 px | Inter 400-500 | 1.5 |
| Table | 14 px | 14 px | Inter 400-700 | 1.4 |
| Metadata / mono | 13 px | 13 px | Inter or mono 500 | 1.4 |
| Label | 12 px | 12 px | Inter 650-700 | 1.3 |

Uppercase labels use no more than 0.06 em letter spacing. Do not use fluid viewport-based
type for product UI; use explicit responsive steps and allow wrapping.

## 5. Layout and shape

- Hosted application maximum width: 1200 px.
- Standalone report maximum width: 1152 px.
- Outer gutter: 24 px on desktop, 16 px on mobile.
- Responsive breakpoints: 900 px and 580 px.
- Layout columns: 12 desktop, 6 tablet, 4 mobile.
- Column gap: 24 px desktop, 16 px tablet and mobile.
- Spacing scale: 4, 8, 12, 16, 24, 32, 48, and 64 px.
- Standard radius: 8 px.
- Editorial and hero radius: 12 px.
- Status-badge radius: 6 px.
- Minimum interactive height: 36 px desktop, 44 px mobile.

The 32 px measurement grid belongs in chart canvases, empty-state diagrams, and selected
marketing imagery. Do not place it beneath every dense application surface.

Use one boundary per content group. Avoid cards inside cards, excessive pills, heavy
shadows, and decoration that competes with telemetry. Tables remain structurally tabular
and scroll inside a bounded wrapper on narrow screens.

## 6. Components

| Component | Required behavior |
|---|---|
| AppHeader | Trace H lockup, real product navigation, context label, theme control |
| ThemeControl | Auto, light, and dark options; persists preference; Auto follows the OS |
| OnboardingHero | Product promise, concise setup action, evidence-led signal graphic |
| CredentialNotice | Explicit severity and session/storage behavior; never vague |
| FilterBar | Wrapping controls; full-width controls and 44 px targets on mobile |
| MetricGrid / Metric | Tabular values, semantic deltas, no color-only meaning |
| DataTable | One frame, keyboard-reachable rows, bounded horizontal scroll |
| StatusBadge | Semantic foreground/background, label, optional 14 px icon |
| MetricChipList | Compact neutral metadata; not a replacement for status |
| InlineNotice | Severity icon, title, direct explanation, next action |
| EmptyState | Small diagnostic motif, missing input, and one clear next action |
| CodeBlock | Fixed dark code surface in both themes; copy action remains accessible |
| BuildTimeline | Visible axes or scale, semantic bars, keyboard access, text summary |
| ReportHeader | Same lockup and status grammar as hosted UI; build metadata prioritized |

Primary buttons use high-contrast neutral fills. Copper may indicate selection or brand
emphasis but is not the default fill for every action.

Avoid the three-signal navigation texture from Trail Grid. Navigation icons must describe
their destination and come from one consistent rounded-stroke command family.

## 7. Iconography and data imagery

Build-specific status icons use a 24 by 24 view box, 2 px rounded strokes, and currentColor.
Badge variants may render at 14 px without changing geometry.

Required states:

- success: check in circle;
- failed: cross in circle;
- flaky: wave in circle;
- running: center point with one ring, using running/live teal;
- cache hit: filled center in circle, using cache/information blue;
- cache miss: dashed neutral circle;
- interrupted: broken gold ring or pause/break construction.

Author the master icons without baked theme colors. Inline them through an SVG symbol
sprite or equivalent so currentColor inherits reliably. Fixed-color exports are secondary
delivery assets, not the product source.

Data imagery represents real telemetry:

- signal tracks always include a numeric value;
- task timelines include labels, scale, and accessible summaries;
- path diagrams describe conceptual flow and must not imply a literal task graph;
- empty-state diagrams stay smaller than the explanation and action they support.

## 8. Interaction and motion

- Every keyboard-focusable element receives a visible 2 px focus outline using the focus
  token, with a 2 px offset.
- Never remove an outline without replacing it with an equally visible focus treatment.
- Hover may change color, border, or surface but never layout dimensions.
- Rows and charts that respond to a pointer must expose the same action to keyboard users.
- Motion is limited to live state, route transition, or chart update feedback.
- Transitions should finish within 180 ms.
- Respect prefers-reduced-motion; live state remains understandable without animation.
- Do not animate Trace H as a character.

## 9. Accessibility requirements

- Body, table, and meaningful small text: at least 4.5:1 contrast.
- Large display text and essential graphical objects: at least 3:1.
- Control boundaries that communicate affordance: at least 3:1 against adjacent surfaces.
- Mobile command targets: at least 44 by 44 px.
- Status includes text or an accessible name and never relies on hue alone.
- Informative SVGs include a title and description; decorative SVGs are hidden from
  assistive technology.
- Charts expose a text summary and keyboard-reachable details.
- Long task paths preserve access to the full value through disclosure, wrapping, or an
  accessible tooltip.
- Light and dark themes override semantic foregrounds and backgrounds together.
- Test both themes under common color-vision deficiencies and at 200% zoom.
- The production standalone report makes no network requests and remains usable without
  JavaScript for its essential build summary. The V2 report page is explicitly labelled as
  a multi-file review fixture and is not the generated report artifact.

## 10. Voice and content

The voice is calm, direct, and diagnostic. Prefer concrete nouns, measured values, and an
immediate next action.

Use:

- Track every Gradle build. Find the slow work.
- 12 tasks missed the cache.
- :buildhound-server:test added 18.4 s.
- This test failed in 3 of the last 5 builds.
- Add a read token to view build telemetry.
- No task data was captured. Run a build with the plugin enabled.

Avoid:

- unlock insights;
- single pane of glass;
- unleash performance;
- sniff out problems;
- good build / bad dog jokes;
- unsupported speed, savings, or reliability claims.

Error and empty-state copy explains what is missing, why it matters, and what the user can
do next. Privacy-sensitive UI states name what is stored, uploaded, or session-only.

## 11. Asset manifest

The V2 implementation belongs under docs/brand/v2/. Required delivery assets:

    docs/brand/
    ├── README.md
    ├── DESIGN-V2.md
    └── v2/
        ├── index.html
        ├── dashboard.html
        ├── report.html
        ├── QA.md
        ├── tools/
        │   ├── generate-tokens.mjs
        │   └── validate.mjs
        └── assets/
            ├── brand.css
            ├── theme.js
            ├── manifest.json
            ├── logo/
            │   ├── trace-h-mark.svg
            │   ├── trace-h-glyph.svg
            │   ├── trace-h-small.svg
            │   ├── lockup-light.svg
            │   ├── lockup-dark.svg
            │   ├── lockup-light.png
            │   └── lockup-dark.png
            ├── favicon/
            │   ├── favicon.svg
            │   ├── favicon.ico
            │   ├── favicon-16.png
            │   ├── favicon-32.png
            │   ├── favicon-48.png
            │   ├── apple-touch-icon-180.png
            │   ├── icon-192.png
            │   ├── icon-512.png
            │   ├── icon-maskable.svg
            │   ├── icon-maskable-512.png
            │   └── site.webmanifest
            ├── icons/
            │   ├── status-success.svg
            │   ├── status-failed.svg
            │   ├── status-flaky.svg
            │   ├── status-running.svg
            │   ├── status-cache-hit.svg
            │   ├── status-cache-miss.svg
            │   └── status-interrupted.svg
            ├── fonts/
            │   ├── Fraunces-Variable.woff2
            │   ├── Inter-Variable.woff2
            │   ├── JetBrainsMono-Variable.woff2
            │   ├── OFL.txt
            │   └── PROVENANCE.md
            └── tokens/
                ├── tokens.css
                └── tokens.json

The manifest describes the required V2 kit; a path is not considered shipped until the
file exists and has been visually verified.

## 12. Handoff and validation

1. Treat a structured token file as the single value source. Generate or mechanically
   verify CSS and JSON copies so they cannot drift.
2. Build the brand board, dashboard concept, and offline report review fixture from the same
   component and token layer. Inline the layer only during separate runtime report adoption.
3. Render at 390, 580, 900, 1280, and 1440 px in both themes. Check clipping, bounded table
   overflow, content wrapping, and 44 px mobile targets.
4. Test Trace H at 16, 24, 32, 44, and 180 px; in monochrome, print, light, dark, and beside
   common Gradle/build-tool marks.
5. Run automated contrast checks, then manually inspect focus, color-blindness, 200% zoom,
   keyboard order, and reduced motion.
6. Verify every page has working navigation and an actual Auto/Light/Dark control.
7. Verify the review fixture and bundled fonts produce zero network requests; separately
   enforce the production report's single-file rule during runtime adoption.
8. Adopt V2 in runtime product code only through a separate implementation plan with
   regression tests.

### Known risks

- Trace H's double-step is more specific than the prior candidates, but recognition and
  adjacent-category collision testing remain release gates. Until those tests pass, treat
  the included mark as a release candidate rather than trademark clearance.
- Copper and warning gold are adjacent warm hues. Their roles remain distinct through
  labels, icons, backgrounds, and the rule that copper never appears in status.
- Fraunces must be checked at the 18-20 px product-lockup size before final vector exports.
- External SVG image files do not inherit a page's currentColor. Product icons should use
  inline symbols or an equivalent themed delivery mechanism.
- Three retained explorations can create token drift unless contributors consistently
  start from this document and the V2 token files.
