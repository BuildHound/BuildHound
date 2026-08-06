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
- Loud failure instead of silent un-styling: count `<style(?=[\t\n\f\r />])` opens and
  `check(...)` block-count parity at class init — an unclosed or mis-paired style in a
  bundled page now fails startup, matching the file's existing "missing resource fails
  startup, not a request" posture.
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
