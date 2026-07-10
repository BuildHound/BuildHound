# BuildHound brand exploration: Trail Grid

> Status: review candidate. This is not an approved brand system, a production design kit,
> or an instruction to edit the dashboard/report directly. Reconcile it with `DESIGN.md`
> before implementation.

This document captures the visual direction first prototyped in the hosted dashboard and
standalone report. The prototype has been removed from runtime files. Its decisions now
live here and in isolated review pages:

- [Brand board](gpt/index.html)
- [Dashboard concept](gpt/dashboard.html)
- [Standalone report concept](gpt/report.html)

## 1. Direction

**Working name:** Trail Grid

**Idea:** BuildHound follows a build through task execution, cache decisions, tests, and CI.
The brand combines a directional hound/trail glyph with a measurement grid and compact
telemetry signals. It should feel observant, practical, and fast without becoming a mascot
brand or a generic monitoring dashboard.

**Product promise:** Track every Gradle build. Find the slow work.

**Supporting description:** A self-hosted build observability surface for task time, cache
outcomes, failures, flaky tests, CI spans, and offline reports.

### Principles

1. **Evidence before decoration.** Product imagery shows timelines, paths, percentages,
   cache states, and failures. Do not use generic code screenshots or atmospheric art.
2. **Warm precision.** A warm paper-like ground makes long diagnostic sessions less stark;
   charcoal structure and a regular 32 px grid preserve an engineering-tool character.
3. **Brand color and state color are separate.** Amber identifies BuildHound. Green, blue,
   red, and violet communicate telemetry meaning and must not be used randomly.
4. **Dense, not cramped.** Tables and filters stay compact, while headers, empty states,
   and diagnostic groups provide enough rhythm to scan quickly.
5. **The hound points; it does not perform.** Use the mark as a directional signal. Avoid
   cartoon poses, speech, eyes, bones, kennels, or dog-themed product vocabulary.

## 2. Mark and lockup

The primary mark is a 44 x 44 square containing an angular hound/trail contour. A diagonal
charcoal-to-amber field expresses the transition from unknown work to identified signal.
The green point is the detected event.

Assets:

- [`hound-trail-mark.svg`](gpt/assets/hound-trail-mark.svg): default square product mark.
- [`hound-trail-glyph.svg`](gpt/assets/hound-trail-glyph.svg): field-free glyph for diagrams
  and larger editorial use.

### Construction

- Container: 44 x 44, 8 px corner radius, 1 px neutral border.
- Field split: charcoal through 58%, amber from 59%.
- Trail: 3 px white rounded stroke.
- Signal point: 3 px radius, healthy green.
- Do not redraw the path at small sizes; use the supplied SVG.

### Lockup

Place the mark to the left of the word `BuildHound`. Product UI may add the descriptor
`Build telemetry for Gradle` below the wordmark. The wordmark is text, not an outlined
logo asset, until a design kit selects and licenses a production typeface.

### Clear space and size

- Keep at least 8 px clear space around the 44 px UI mark.
- Minimum square mark: 24 px. Below 24 px, validate a simplified favicon separately.
- Do not place the mark directly on amber, green, or a busy telemetry chart.
- Do not recolor individual parts to match a page theme. Dark mode changes the surrounding
  surface, not the mark asset.

## 3. Color

Color names describe roles, not marketing prose. The future design kit should expose both
primitive values and semantic aliases.

### Light theme primitives

| Token | Value | Role |
|---|---:|---|
| `bg` | `#F7F4EE` | warm application ground |
| `grid` | `#DAD2C3` | 32 px measurement grid |
| `ink` | `#12161D` | primary text and high-emphasis structure |
| `muted` | `#667085` | secondary text and metadata |
| `panel` | `#FFFDF8` | standard surface |
| `panel-strong` | `#FFFFFF` | controls and raised content |
| `line` | `#D9D2C5` | borders and dividers |
| `line-strong` | `#BDB5A7` | control borders and dashed states |
| `brand` | `#151A22` | wordmark, primary action, mark field |
| `amber` | `#F3A528` | brand accent, executed work, attention |
| `amber-soft` | `#FFF0C7` | amber status background |
| `green` | `#17A66A` | success, cache hit, healthy signal |
| `green-soft` | `#DFF8E9` | green status background |
| `blue` | `#2D6CDF` | informational, up-to-date, CI span |
| `blue-soft` | `#E3EDFF` | blue status background |
| `red` | `#D94B43` | failed or regressed work |
| `red-soft` | `#FFE2DF` | red status background |
| `violet` | `#8067D8` | combined flaky-state exception |
| `violet-soft` | `#E8E1FF` | violet status background |
| `code` | `#111820` | snippets and failure traces |
| `code-ink` | `#E7EEF8` | code text |

### Semantic foreground pairs

Status surfaces use a dark foreground on light backgrounds and a light foreground on dark
backgrounds. These aliases are required; do not reuse a light-theme foreground in dark
mode.

| State | Light foreground | Dark foreground | Background alias |
|---|---:|---:|---|
| Executed / attention | `#7A4A00` | `#FFD27A` | `amber-soft` |
| Success / cache hit | `#0F7048` | `#7BE0AE` | `green-soft` |
| Informational / up-to-date | `#2553A6` | `#91B9FF` | `blue-soft` |
| Failed / regression | `#A1332D` | `#FF9A94` | `red-soft` |
| Combined flaky signal | `#5740A8` | `#C3B4FF` | `violet-soft` |

### Dark theme overrides

| Token | Value |
|---|---:|
| `bg` | `#101318` |
| `grid` | `#2A3039` |
| `ink` | `#F1F4F8` |
| `muted` | `#A1AABA` |
| `panel` | `#171C24` |
| `panel-strong` | `#1D2430` |
| `line` | `#2B3340` |
| `line-strong` | `#455064` |
| `brand` | `#F5F7FB` |
| `amber-soft` | `#3F2C12` |
| `green-soft` | `#123326` |
| `blue-soft` | `#142642` |
| `red-soft` | `#3C1E1C` |
| `violet-soft` | `#2E254D` |

### Usage rules

- Amber may identify the product, current execution, or a warning. A text label must make
  the meaning clear when those contexts could overlap.
- Green only means a positive outcome or healthy signal.
- Blue only means informational state, cache/up-to-date state, or neutral span data.
- Red only means failure, regression, destructive action, or a blocked condition.
- Violet is rare and reserved for combined or ambiguous flaky-test state.
- Never rely on color alone. Pair status color with a label, icon, or table heading.

## 4. Typography

The concept intentionally uses local system fonts so the review pages and offline report
make no network requests. The design kit may later select bundled fonts, but product UI
must remain legible and compact before brand typography is loaded.

### UI stack

`Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`

If Inter is not bundled, the system fallback is the expected behavior. Do not fetch it from
a CDN.

### Mono stack

`"SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace`

Use mono only for task paths, hashes, durations in diagnostic contexts, cache keys, log
lines, and failure traces. Numeric columns still use tabular numerals.

### Scale

| Role | Size | Weight | Notes |
|---|---:|---:|---|
| Concept headline | 53.6 px | 850 | review/first-run surface only |
| Mobile concept headline | 35.2 px | 850 | explicit breakpoint, not fluid type |
| Section title | 21.6 px | 700 | page section |
| Product title | 18.4 px | 700 | compact app header |
| Body | 16 px | 400-500 | default reading size |
| Table | 14 px | 400-700 | dense operational data |
| Metadata | 12.5-13.6 px | 500-750 | secondary information |
| Eyebrow/table heading | 11.5 px | 800-850 | uppercase; 0.08 em max tracking |

No type size should scale continuously with viewport width. Use explicit responsive
breakpoints and let text wrap.

## 5. Layout and shape

- Application maximum width: 1220 px.
- Standalone report maximum width: 1152 px.
- Outer gutter: 16 px per side on desktop, 10 px per side on narrow mobile.
- Background grid: 32 x 32 px, one-pixel line centered around the 32 px boundary.
- Spacing rhythm: 4, 8, 12, 16, 24, 32, 48 px.
- Standard corner radius: 8 px.
- Status badge radius: 6 px.
- Avoid pills except for a true binary/status token that needs a compact silhouette.
- Avoid cards inside cards. Tables, diagnostic groups, modals, and repeated assets may be
  framed; full page sections remain unframed or use one surface boundary.
- Use restrained shadows only on navigation/header and first-run hero surfaces.

## 6. Iconography

Status icons use a 24 x 24 view box, a 2 px rounded stroke, no fill except for the center
point in cache-hit/running states, and the state color from the palette.

Included review assets:

- [`status-success.svg`](gpt/assets/icons/status-success.svg)
- [`status-failed.svg`](gpt/assets/icons/status-failed.svg)
- [`status-cache-hit.svg`](gpt/assets/icons/status-cache-hit.svg)
- [`status-flaky.svg`](gpt/assets/icons/status-flaky.svg)

Navigation may use the compact three-signal motif: blue, green, and amber squares on a
single horizontal axis. It is a navigation texture, not a replacement for meaningful
feature icons. A production design kit should use a consistent icon library for commands
and preserve these custom assets only for BuildHound-specific states.

## 7. Data imagery

BuildHound imagery is a simplified view of real telemetry, not abstract decoration.

### Signal path

Three bordered nodes sit on a measured horizontal path. Blue is ingestion/context, amber
is work under investigation, and green is a resolved or healthy outcome. Use this in
first-run, empty, or editorial contexts; never imply that three nodes represent a literal
task graph.

### Signal tracks

Horizontal tracks pair a label, a colored measure, and a numeric value. They can summarize
task time, cache hits, CI spans, or failures. Always show the number; track length alone is
not sufficient.

### Timeline

Timeline bars use the same semantic palette and a visible grid. Labels use mono text and
remain outside the plotted area where possible. Production charts must supply axes,
tooltips, keyboard access, and accessible summaries.

### Empty-state motif

Two outlined nodes connected by an amber diagonal suggest missing telemetry or a broken
path. It appears with a direct next action and optional configuration snippet. Do not use a
large illustration where a compact diagnostic state is enough.

## 8. Product components captured from the prototype

The review pages preserve these patterns for later componentization:

| Pattern | Design-kit candidate | Key behavior |
|---|---|---|
| Brand header | `AppHeader` | mark, product nav, environment/site label |
| Read-token notice | `CredentialNotice` | amber left rule, session-only helper copy |
| First-run hero | `OnboardingHero` | product promise, three capabilities, signal board |
| Filters | `FilterBar` | wrapping controls; full-width controls on narrow screens |
| KPI strip | `MetricGrid` / `Metric` | tabular values and semantic deltas |
| Data table | `DataTable` | framed once, sticky/scroll strategy defined later |
| Status badge | `StatusBadge` | semantic foreground/background pair plus text |
| Chip list | `MetricChipList` | compact summary metadata; 8 px radius |
| Warning | `InlineNotice` | amber surface, explicit severity copy |
| Empty state | `EmptyState` | motif, title, explanation, action/snippet |
| Code/failure trace | `CodeBlock` | fixed dark surface in either theme |
| Report header | `ReportHeader` | mark, report title/meta, four-color signal strip |
| Timeline | `BuildTimeline` | semantic bars, labels, grid, accessible summary |

The HTML samples are not component APIs. They exist to review visual direction and content
density before naming properties, states, slots, or framework bindings.

## 9. Responsive behavior

- At 900 px and below, the product header becomes a vertical stack and navigation becomes
  a three-column grid.
- The first-run hero becomes one column; metric grids become one column.
- Tables stay structurally tabular and scroll horizontally inside a bounded wrapper.
- At 580 px and below, review navigation, toolbars, token controls, and actions become full
  width. Asset and swatch galleries become one column.
- Text wraps inside panels. Long task paths truncate only when the full value remains
  available through the real product's disclosure/tooltip behavior.
- The production design kit should raise mobile command targets to at least 44 x 44 px;
  the 34 px dense desktop control is not the mobile target.

## 10. Interaction and motion

- Focus states use a visible 2 px blue-derived outline with 2 px offset.
- Hover changes border/surface, not layout dimensions.
- Data rows may highlight on hover, but must remain actionable by keyboard.
- Use motion only for live/running state, route transitions, or chart updates. Prefer an
  opacity/position transition under 180 ms and honor `prefers-reduced-motion`.
- Do not animate the hound as a character.

## 11. Accessibility checks for the design kit

- The review palette's body, muted, and ten semantic text/background pairs pass the 4.5:1
  WCAG AA threshold. The narrowest margin is light-theme `muted` on `bg` at 4.53:1, so do
  not lighten either value without retesting.
- Validate every semantic foreground/background pair to WCAG AA before approval.
- Keep body and table text at 4.5:1 or better; large display text at 3:1 or better.
- Status always includes text or a programmatic name, not color alone.
- Inline SVGs need a title/description when informative and `aria-hidden="true"` when
  decorative.
- Charts require a text summary and keyboard-reachable details.
- Dark mode must override semantic foregrounds as well as backgrounds.
- Do not hide focus rings. Do not encode hover with movement that shifts neighboring UI.

## 12. Voice and product copy

The voice is direct and diagnostic. Prefer concrete nouns and verbs:

- `Track every Gradle build. Find the slow work.`
- `12 tasks missed the cache.`
- `This test failed in 3 of the last 5 builds.`
- `Add a read token to view build telemetry.`

Avoid vague monitoring language (`unlock insights`, `single pane of glass`), dog puns, and
claims that the product cannot substantiate. Empty states explain the missing input and the
next action in one short sequence.

## 13. Reference-site takeaways

The direction borrows patterns, not visual assets:

- [Develocity](https://develocity.ai/product/build-scan/): build evidence and task-level
  diagnostics are the product story.
- [Honeycomb](https://www.honeycomb.io/): bright semantic signals can coexist with a
  serious observability surface.
- [Datadog](https://www.datadoghq.com/): dense operational navigation and recognizable
  status conventions.
- [Dash0](https://www.dash0.com/): technical diagrams should communicate real system
  relationships.
- [Tuist](https://tuist.dev/): developer-tool onboarding benefits from contextual empty
  states and direct setup actions.
- [Bazel](https://bazel.build/): build tools benefit from clear, durable technical language.
- [Depot](https://depot.dev/), [Blacksmith](https://www.blacksmith.sh/), and
  [Kiro](https://kiro.dev/): product identity should be visible in the first viewport
  without displacing the actual tool.

## 14. Design-kit handoff

Before adopting this direction in product code:

1. Review Trail Grid beside `DESIGN.md`; select or reconcile the mark, palette, typography,
   and voice explicitly.
2. Run contrast, color-blindness, small-size mark, and dark-mode tests.
3. Split primitives from semantic tokens and publish token names independent of CSS.
4. Define components and variants for every pattern in section 8.
5. Decide the command icon library and map BuildHound-specific status icons into it.
6. Produce favicon, monochrome, print, and social/export variants only after mark approval.
7. Implement dashboard and offline-report adoption in a separate plan with regression and
   zero-network tests.

## 15. Review questions

- Does the angular trail mark feel specific to build observability, or merely abstract?
- Is amber distinctive enough beside common CI warning colors?
- Does the warm ground improve long-session readability, or make the product feel less
  precise than the darker alternative in `DESIGN.md`?
- Should BuildHound own the three-signal navigation motif, or reserve custom imagery for
  the mark and telemetry diagrams only?
- Which components need to be approved before a design-kit package can be considered
  stable?
