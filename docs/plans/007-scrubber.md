# 007 — Payload scrubber (spec §3.7)

## Source

Spec §3.7: "Payloads never include absolute paths outside the project, env dumps, or
tokens; a scrubber strips values matching secret-like patterns from execution
reasons/failure text." Plans 005/006 recorded this as the hard blocker before upload
and stripped `executionReasons` from the published artifact as an interim.

## Scope

**In:**

- `PayloadScrubber` in `buildhound-commons` (KMP-pure; the server can reuse it as a
  defensive second pass):
  - **Absolute paths** in free-text fields: paths under the project root are
    relativized (`/home/x/proj/src/A.kt` → `src/A.kt`); paths outside it become
    `<path>`. Unix and Windows shapes.
  - **Secret-like patterns**: `token/secret/password/api-key/credential/authorization/
    bearer` `=`/`:` value pairs → value replaced with `<redacted>`; standalone long
    base64/hex-looking blobs (32+ chars) → `<redacted>`.
  - Applied to `executionReasons` today; the same entry point will cover
    `FailureInfo` text when failure collection lands.
- `PayloadAssembler` scrubs at assembly time with the project root — one scrubbed
  payload everywhere (local file, artifact, future upload). The artifact's interim
  `executionReasons` stripping is removed: it now embeds the same scrubbed payload.
- This resolves plan 005 blocker #1 (record it there).

**Out:** env-dump detection (nothing collects env values into text), failure-text
collection itself, server-side scrubbing wiring.

## Design

Pure regex/string transforms over `BuildPayload` (schema untouched — values only).
Scrub order: secrets first, then paths (so a secret containing a path can't survive
as a "relativized" fragment). Conservative bias: over-redaction of free text is
acceptable (a 40-char sha in reason text may be redacted; the `vcs.sha` *field* is
untouched — only free-text fields are scrubbed).

## Hardening round (review findings, fixed pre-merge)

Both clean-context reviews attacked the regexes empirically; fixed: snake_case
secret keys (`GITHUB_TOKEN=`, the common CI shape — `\b` cannot match after `_`),
bare JWTs, URL-embedded credentials (`https://user:pass@host`), AWS access-key ids,
UNC paths, quoted multi-word secret values, base64-with-`/` blobs, camelCase
false positive (blob now requires a digit), degenerate roots (`/`, bare drive)
never relativize, multi-root support (plain + canonical for symlinked checkouts)
with a boundary-guarded literal-root pre-pass so in-project paths with spaces
relativize. Accepted limitations recorded in the scrubber KDoc: out-of-project
space-path tails, sub-32 keyless tokens, `--token abc` flag shapes — revisit
before failure-text collection lands.

## Test strategy

- commons unit: unix/windows path redaction, project-root relativization (incl. root
  with trailing slash, path exactly at root), secret pair redaction (case, `=` vs `:`,
  quoted values), long-blob redaction, plain prose untouched, empty reasons untouched.
- plugin functional: a task with a file input changed between runs produces a reason
  mentioning the input path — assert the payload's reasons contain no absolute path
  and the artifact embeds the same scrubbed text.

## Risks

- Over/under-matching regexes: bias to over-redaction; unit tests pin both directions.
- Scrubbing cost is O(reasons × patterns) — negligible at task counts.

## Exit criteria

`./gradlew build` green; no absolute path or secret-shaped value in payload or
artifact reason text; plan 005 blocker #1 closed.
