# BuildHound Brand Identity

> **Status: draft, unreviewed.** This is a first pass at a visual identity for BuildHound,
> not a locked system. Nothing here has been through the review process the rest of this
> repo uses for code — treat every choice (the mark, the copper hue, the "hound" premise
> itself) as redirectable. See [Open questions](#8-open-questions) before building on top
> of it.
>
> The live version of everything below — rendered, with working theme switching and real
> asset previews — is at [`site/index.html`](site/index.html). This document is the spec;
> the site is the demo.

## 1. Positioning

Develocity tells you a build happened. BuildHound is built around a narrower, more useful
question: **what in this build is worth chasing?** A slow task, a flaky test, a cache that
should have hit and didn't. A hound doesn't watch a build — it tracks it. That's meant to be
a real product difference (BuildHound's actual feature surface: task/cache/test-flakiness
telemetry, per `build-telemetry-spec.md`), not just a mascot bolted onto a generic dashboard.

Tagline: *"Every build leaves a scent. We follow it."*

## 2. Method

The competitive research behind this identity was done two ways:

1. **Direct source inspection** — `curl`-fetching each competitor's homepage HTML/CSS this
   session (meta `theme-color`, inline hex values, linked font families, favicon SVGs) and
   reading the raw bytes. This is what the [field notes](#3-field-notes) table below is
   built from. The in-session browser tool was unreachable, so this is verified
   computed-source data, not a visual screenshot pass.
2. **General design-research pass** (a separate `deep-research` run) that surfaced
   reusable structural lessons from Vercel's Geist system, Linear's 2024 rebrand, and
   Bazel/Gradle's own identity histories — cited inline where used.

Both methods have a real gap: **logo *shape*** (as opposed to color/type) was only
confirmed for Develocity, Depot, and Kiro, where a favicon SVG was actually fetched and
read. Every other "Mark" cell in the table below marked `—` is an honest gap, not a
placeholder — filling it in needs a real look at the rendered page.

## 3. Field notes

What the category already owns, pulled from live source:

| Site | Ground | Accent(s) | Typeface | Mark |
|---|---|---|---|---|
| Develocity | near-black `#13171B` | none — monochrome | — | lowercase "d", black-on-white for favicon safety (brand-kit comment left in source) |
| Bazel | light docs shell `#F8F9FA` | green `#0C713A` / `#1E431E` (kept from prior mark, "rooted into identity") | Roboto + Roboto Mono | interlocking assembled blocks — 2017 redesign, community-voted |
| Honeycomb | slate navy `#25303E` | honey amber `#FFB000` + blue `#0278CD` | Inter + Matter | hexagon cell (name-literal) |
| Datadog | near-black `#110617` | violet `#8000FF` + a full 6-hue status ramp | Inter | barking dog in a ring |
| Dash0 | neutral charcoal `#101010`–`#2C2C2C` | coral `#F8494D` + mint `#A8FBBE`, used sparingly | Roobert + a dedicated `--type-mono` token | — |
| Depot | off-white default `#EEEEF0` | green `#46A759` + blue `#4B9DD6` | Inter | grid-of-blocks glyph, swaps fill for light/dark automatically |
| Kiro | — | violet `#9046FF` / lavender `#C6A0FF` | — | rounded-square app icon, white glyph on violet |
| Blacksmith | near-black `#202020` | acid lime `#F0FB29`, one hue only | — | — |
| Confident AI | cool gray `#CED8E2` | magenta `#FF006B` + violet `#6E00FF` | IBM Plex Mono + Lexend Deca | — |
| Better Stack | navy-black `#0B0C14`–`#171926` | periwinkle indigo `#7C87F7` | — | — |
| Tuist | mostly neutral | not distinct in source | Space Grotesk + Space Mono | — |

**The gap:** purple is Datadog's and, one shade lighter, Kiro's and Confident AI's.
Honeycomb sits on amber-and-navy. Depot and Bazel share green. Better Stack has indigo,
Blacksmith has lime, Dash0 leans neutral-plus-coral. Almost every ground is a near-black
charcoal — that part of the category is settled and worth following, not fighting. What's
open is a **warm hue**: something that isn't blue-adjacent at all. Copper reads as neither
"SaaS cool" nor "alert red," and it's the literal color of a hound's coat.

## 4. The mark

Three concepts, one lead:

| | Role | Rationale |
|---|---|---|
| **Paw** | Lead | A pad and four toes, nothing else — five shapes, reads at 16px. Most ownable, ties directly to "tracking down what's wrong." |
| **Assembled H** | Alternate | Bazel proved a build-tool mark can be pure geometry. A straightforward H (for Hound) built from discrete blocks locked together, one block left "lit" — in case the paw reads too literal next to Bazel's and Gradle's own marks. |
| **Scent Ping** | Alternate | Concentric rings for a "running" / "just found something" state rather than static branding. Animated as a single soft pulse; inert under `prefers-reduced-motion`. |

**Usage rules:**
- The paw is always built from the same 5 primitives (1 rounded-rect palm, 4 circle toes) —
  never redrawn or restyled per surface.
- Minimum size: 16px (favicon). Below that, don't — use the flat wordmark instead.
- Clear space: at least half the glyph's own height on all sides.
- The "scent trail" (a row of 3–4 fading dots, see the site's section dividers) is a
  **lockup device**, not part of the glyph — it never gets baked into the icon itself, so
  the icon alone stays legible at favicon size.
- Don't recolor the mark per status (that's what the [icon set](#6-iconography) is for) —
  the mark is always copper (or `currentColor` in themed contexts via
  `paw-mark-mono.svg`).

Files: [`site/assets/logo/`](site/assets/logo/).

## 5. Color

Brand accent and functional status color are **kept separate on purpose** — the mistake
Datadog's own docs warn against (their violet brand hue is distinct from their red/green
status ramp). Copper never appears in a status chip; the semantic colors never appear as
decoration.

### Brand

| Token | Dark | Light | Use |
|---|---|---|---|
| `--bh-char` | `#15130F` | `#FBF7F1` | page ground |
| `--bh-ember` | `#221D17` | `#F1E8DC` | surface / card |
| `--bh-copper` | `#DD8148` | `#B85A28` | brand accent — logo, links, focus rings |
| `--bh-bone` | `#F2EBDF` | `#211C15` | primary text |
| `--bh-slate` | `#8A8177` | `#7A7266` | secondary text, borders |

### Semantic (build status only)

| Token | Dark | Light | State |
|---|---|---|---|
| `--bh-ok` | `#4ABD8C` | `#1F8055` | success |
| `--bh-bad` | `#E5646A` | `#C22E33` | failed |
| `--bh-warn` | `#F0B158` | `#A5680E` | flaky |
| `--bh-info` | `#6FA8FF` | `#2A6FD1` | cache hit |
| — uses `--bh-copper` | | | running |
| — uses `--bh-slate` | | | cache miss |

Dark is the default theme (the category convention for anything showing logs/metrics —
Develocity, Dash0, Blacksmith, Better Stack, Bazel's console views are all dark-first).
Light is a real, separately-tuned theme, not an inversion — see
[`site/assets/tokens/colors.css`](site/assets/tokens/colors.css) for the
`prefers-color-scheme` + `data-theme` override pattern, and
[`colors.json`](site/assets/tokens/colors.json) for a non-CSS copy of the same values.

## 6. Typography

Three faces, three jobs — no face is used outside its lane:

| Face | Role | Weight range | Why |
|---|---|---|---|
| **Fraunces** | Display — headlines, the wordmark, marketing only | 300–900 (variable) | The one deliberate departure from category convention: every competitor in the field notes skews geometric/grotesk (Roobert, Space Grotesk, Matter, Inter, Roboto). A warm, characterful display face gives BuildHound a personality the "cold SaaS blue/purple" look doesn't have — used only for headlines, never in-product UI. |
| **Inter** | Body / UI — every screen in the product | 100–900 (variable) | Pragmatic, not a default: free, ubiquitous, and already proven at dashboard scale by Datadog, Honeycomb, and Depot themselves. Paired with a distinctive display face, it stops reading as "the safe choice" and starts reading as "the correct choice." |
| **JetBrains Mono** | Durations, task paths, cache keys, logs | 100–800 (variable) | Not decoration — a build-telemetry surface is mostly numbers and identifiers. Needs a face built for tabular alignment (`font-variant-numeric: tabular-nums`), not the display face dressed down. |

All three are SIL Open Font License 1.1 (variable, self-hostable, no CDN dependency — fits
the zero-network posture the rest of this repo holds `buildhound-report` to). License text
and per-font copyright notices: [`site/assets/fonts/OFL.txt`](site/assets/fonts/OFL.txt).

## 7. Iconography

Six build-status states, one visual language — 24×24, 2px rounded stroke, same geometry
family as the Assembled-H mark so status chips and the logo read as one system:

| Icon | State | Color token |
|---|---|---|
| ✓ in circle | Success | `--bh-ok` |
| ✕ in circle | Failed | `--bh-bad` |
| wavy line in circle | Flaky | `--bh-warn` |
| concentric rings + dot | Running | `--bh-copper` |
| circle + filled center | Cache hit | `--bh-info` |
| dashed circle | Cache miss | `--bh-slate` |

Files: [`site/assets/icons/`](site/assets/icons/). Each ships with a fixed hex `stroke`/
`fill` for drop-in `<img>` use — for theme-aware inlining, set `color` via CSS and swap the
attribute to `currentColor` (see how `site/index.html` does it).

**Portability note:** these icons are hand-authored with `stroke="currentColor"` +
`style="color: var(...)"` rather than `stroke="var(...)"` directly. Recent Chromium
resolves `var()` inside SVG presentation attributes, but that's a known cross-engine
divergence (Firefox/Safari have historically not) — `currentColor` is universally
supported and was verified via headless-Chrome render before/after the switch, so don't
revert this pattern when adding new icons.

## 8. Open questions

Carried over from the original research pass, still unresolved:

- **Mascot vs. abstract**, decided provisionally in favor of the paw (see [§4](#4-the-mark))
  — redirectable if the direct animal reference feels wrong once it's next to Bazel's and
  Gradle's own marks in practice.
- **Logo shape for the rest of the field-notes table** — only Develocity, Depot, and Kiro
  were visually confirmed this session; a real look at the other eight sites (screenshot,
  not source-fetch) would sharpen the gap analysis in [§3](#3-field-notes).
- **Bespoke vs. off-the-shelf type** — this draft picked off-the-shelf (Fraunces + Inter +
  JetBrains Mono, all free/OFL) on the assumption that an open-source project doesn't have
  a type-commissioning budget. Worth confirming that assumption holds.
- **Does the semantic ramp need a 6th color?** Datadog's dashboard docs use a fuller ramp
  (6 hues) than BuildHound's 4 dedicated + 2 borrowed. Whether BuildHound's simpler set
  (pass/fail/flaky/cache-hit/running/cache-miss) stays sufficient depends on what the
  dashboard actually ships — revisit once `buildhound-server`'s dashboard views exist.

## 9. Asset manifest

```
docs/brand/
├── DESIGN.md                 — this file
└── site/
    ├── index.html            — live brand site (self-contained, relative asset paths)
    └── assets/
        ├── logo/
        │   ├── paw-mark.svg            — fixed copper fill
        │   ├── paw-mark-mono.svg       — currentColor, for themed inlining
        │   ├── assembled-h.svg         — alternate mark
        │   ├── scent-ping.svg          — alternate mark / running state
        │   ├── wordmark-lockup-dark.png   — rasterized, transparent bg, for dark surfaces
        │   └── wordmark-lockup-light.png  — rasterized, transparent bg, for light surfaces
        ├── favicon/
        │   ├── favicon.svg             — primary, modern browsers
        │   ├── favicon.ico             — multi-res 16/32/48 fallback
        │   ├── favicon-{16,32,48}.png
        │   ├── apple-touch-icon-180.png
        │   └── icon-512.png            — PWA manifest / large app icon
        ├── icons/
        │   └── status-{success,failed,flaky,running,cache-hit,cache-miss}.svg
        ├── fonts/
        │   ├── Fraunces-Variable.woff2
        │   ├── Inter-Variable.woff2
        │   ├── JetBrainsMono-Variable.woff2
        │   └── OFL.txt                 — bundled license + per-font copyright notices
        └── tokens/
            ├── colors.css              — CSS custom properties (dark default + overrides)
            └── colors.json             — same palette, non-CSS consumers
```
