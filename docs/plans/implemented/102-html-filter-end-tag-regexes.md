# 102 — Parser-accurate end-tag matching in HTML-filtering regexes

## Source

Bad-HTML-filtering-regexp finding (CodeQL `js/bad-tag-filter` class) on two lines:

- `docs/brand/v2/tools/validate.mjs:320` — inline-script gate pairs scripts with
  `<\/script\s*>`.
- `buildhound-report/src/test/resources/report-smoke.js:59` — script extraction +
  breakout guard pairs scripts with the exact literal `<\/script>`.

Per the HTML tokenizer, script (and style) raw text ends at `</script` followed by
tab/LF/FF/CR/space, `/`, or `>` — attributes on the end tag are ignored. Both regexes
recognise only a subset, so they pair tag boundaries differently than a browser:

- validate.mjs: `<script>evil()</script foo>` (no later plain `</script>`) never matches
  the gate regex at all → inline script passes the validator while a browser closes at
  `</script foo>` and executes it. Same divergence in the sibling `<style>` scan one line
  up (line 317): an unmatched style block silently skips CSS-reference scanning.
- report-smoke.js: the harness's breakout guard only detects an exact `</script>` in an
  unescaped payload; variants a browser honours (`</script >`, `</script/>`,
  `</script x>`) keep the count at 2 and the regression passes the smoke test.

## Scope

In: the three end-tag regexes (validate.mjs script + style scans, report-smoke.js
extraction) and the report-smoke comment describing the guard. Out: any change to
`ReportAssets.render()` escaping, fixtures, `ReportAssetsTest`, or the rendered pages —
this is a filter-accuracy fix only.

## Design

Replace each tag pattern with the exact-terminator-lookahead form
`<\/(script|style)(?=[\t\n\f\r />])[^>]*>` (same lookahead on the open side):

- The lookahead is the HTML tokenizer's tag-name terminator set verbatim. It accepts
  every browser close variant — whitespace, attributes, `/` — while rejecting
  `</scriptxyz>` *and* punctuation non-tags like `</script-x>` or `</script:evil>`,
  which a browser reads as raw text. (First cut used `\b` here; the clean-context code
  review showed `\b` fires on any word→non-word transition, recognising a close earlier
  than the parser and truncating the scanned content — a content-hiding gap for the
  style scan. Amended to the lookahead in the same PR.)
- Remaining divergence is fail-safe: a quoted `>` inside an end-tag attribute makes the
  regex close *earlier* than the browser, which can only widen the captured content →
  false positive (flag/throw), never a bypass.

report-smoke.js additionally broadens the opening tag from the bare literal `<script>`
to the same lookahead form with `i` so an attribute-carrying or case-shifted script
element can't slip past the count check. The count-of-2 assertion stays: the real
template embeds exactly two bare inline blocks.

Review addendum (security review MINOR): a raw-text element left unclosed at EOF
yields zero paired matches, so its content check silently never ran — a browser
auto-closes at EOF and executes. validate.mjs now cross-checks the generic tag scan's
`<script>`/`<style>` open count against the paired-block count and fails loud on any
mismatch, mirroring the structural count assertion report-smoke.js already had.

## Test strategy

- Node spot-check (scratchpad, not committed): new pattern matches all five browser
  close variants, rejects `</scriptxyz>`; old pattern demonstrably misses
  `</script foo>` bypass, new one flags it.
- `node docs/brand/v2/tools/validate.mjs` still passes on the committed fixtures.
- `./gradlew :buildhound-report:test` — ReportScriptTest smoke still extracts exactly
  the two blocks and all render assertions hold.

## Risks

False-positive direction only (stricter matching). The validator gate and the smoke
count check both fail loud, so a regression surfaces as a red check, not a silent pass.
Brand fixtures are static reference files (CLAUDE.md): validator behaviour change
touches no production code path.

## Exit criteria

Both files use parser-accurate end-tag patterns, validator passes on fixtures, report
tests green, and the bypass spot-check confirms the old miss / new catch.
