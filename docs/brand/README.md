# BuildHound brand

This directory contains the current BuildHound brand system and the explorations that led
to it.

## Current system

[DESIGN-V2.md](DESIGN-V2.md) is the canonical source for the V2 product and brand
direction. Trace H is the supplied release-candidate mark and still requires recognition
and adjacent-category collision testing before trademark or irreversible launch use.

The document covers:

- positioning and product voice;
- the Trace H mark and lockup;
- light, dark, brand, and semantic color tokens;
- typography, layout, components, interaction, and accessibility;
- the required V2 asset kit and implementation handoff.

New brand and product-design work starts there. The corresponding review implementation,
QA record, and exported assets live under v2/. The offline report page is a multi-file
review fixture, not the production single-file `buildhound-report` artifact.

## Historical explorations

The earlier directions are intentionally preserved for decision history:

- [DESIGN.md](DESIGN.md) and [site/](site/) contain the original warm-charcoal, copper,
  paw/assembled-H exploration and its initial asset kit.
- [DESIGN-GPT.md](DESIGN-GPT.md) and [gpt/](gpt/) contain the Trail Grid exploration,
  dashboard concept, and standalone-report concept.

These files are references, not competing sources of truth. Do not copy their tokens,
marks, or component rules into new work without reconciling the change in DESIGN-V2.md.
Do not overwrite the historical pages to make them resemble V2.

## Contribution rule

When a brand decision changes:

1. update DESIGN-V2.md first;
2. update the structured V2 token or vector source;
3. regenerate or verify delivery assets;
4. render the brand board, dashboard, and report in both themes;
5. record any intentional divergence in the same change.

Runtime product adoption remains a separate implementation task. Files in this directory
define and demonstrate the system; they do not silently change application behavior.
