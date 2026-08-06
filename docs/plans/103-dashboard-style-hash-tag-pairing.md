# 103 — Parser-accurate style-tag pairing in the dashboard/docs CSP hash extraction

## Source

Follow-up flagged by plan 102's clean-context reviews: both CSP style-hash computations
in `buildhound-server/.../DashboardRoutes.kt` (DashboardAssets ~37, DocsAssets ~69) pair
inline styles with the bare literal `Regex("<style>(.*?)</style>", DOT_MATCHES_ALL)` —
no attribute, whitespace, case, or close-variant handling, the same divergence from the
HTML tokenizer plan 102 fixed in validate.mjs and report-smoke.js.

Risk today is low: input is the first-party bundled `web/index.html` / `web/docs.html`,
and a missed block only *drops* a hash — CSP then blocks that style (fails closed, page
un-styles) rather than allowing anything. This is hygiene + loud-failure hardening, not
a vulnerability fix.

## Scope

In: the two hash computations (deduplicated into one shared helper), a tokenizer-accurate
tag regex, an init-time count-parity check, and tests. Out: any change to the bundled
pages, the policy's directive set, or other routes.

## Design

- Extract the duplicated `csp` computation into one `internal fun styleHashCsp(html):
  String` in DashboardRoutes.kt; both objects call it. Policy string unchanged.
- Port plan 102's exact-terminator lookahead to Kotlin:
  `<style(?=[\t\n\f\r />])[^>]*>([\s\S]*?)</style(?=[\t\n\f\r />])[^>]*>` with
  IGNORE_CASE (`[\s\S]` replaces DOT_MATCHES_ALL). The lookahead is the tokenizer's
  tag-name terminator set; `\b` is wrong in both directions (plan 102 review lesson).
- Loud failure instead of silent un-styling: after extracting paired blocks, scan only
  the text *outside* them for leftover `<style(?=[\t\n\f\r />])` opens and `check(...)`
  that none remain. (First cut compared a whole-document open count against the block
  count; the quality review showed that overcounts on well-formed input — a block's
  raw-text body may legally contain a literal `<style` in a CSS string or comment, which
  a tokenizer never rescans. Amended to the residual-text scan in the same PR.)
- Fail the boot, not the first request: both asset objects are lazily initialized Kotlin
  objects, so class-init alone would surface a malformed page as an
  `ExceptionInInitializerError` on the first page request (both reviews flagged the
  original "fails startup" claim as wrong for this reason). `dashboardRoutes()` now
  touches both CSPs during route registration, which runs at module setup — restoring
  the "fails before serving traffic" property the file's missing-resource posture
  documents.
- Bundled pages use bare `<style>` blocks, so produced hashes — and therefore the served
  CSP header — are byte-identical before/after.

## Test strategy

- New unit test for `styleHashCsp`: attribute/case/close-variant tags are all found and
  hashed; a known body hashes to the expected sha256 token; unclosed `<style>` throws;
  zero styles yields `style-src 'none'`.
- Route tests (Dashboard + Docs): the number of sha256 tokens in the served CSP equals
  the number of tokenizer-recognised `<style` opens in the served page body — pins "every
  style block is hashed" against the real bundled pages end to end.
- `./gradlew :buildhound-server:test` green; served CSP values unchanged.

## Risks

Init-time `check` turns a malformed bundled page into a startup failure — intended, and
only reachable by shipping a broken first-party page past review. No schema, endpoint,
or payload surface touched; §3.2 security & privacy review still runs (server change).

## Exit criteria

One shared helper with the lookahead regex, parity check at init, both new tests green,
CSP header value unchanged for the current pages.
