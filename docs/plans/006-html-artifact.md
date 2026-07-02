# 006 — Standalone HTML build-report artifact (v0)

## Source

Roadmap Phase 1: "Standalone HTML artifact (same renderer as build detail, embedded
data, zero network)". Spec §3.8 (locked decision #4: fully self-contained, no CDN).

## Scope

**In:**

- Real `report-template.html` in `buildhound-report`: header (build id, outcome,
  duration, mode, toolchain), summary chips (task count, hit rate, CC state, daemon
  reuse), task table sorted by duration with outcome badges. Inline CSS/JS only; all
  payload strings rendered via `textContent` (payload data — branch names, task paths,
  execution reasons — is untrusted in an HTML context).
- Payload JSON embedded as one blob replacing `/*__BUILDHOUND_DATA__*/` — the artifact
  doubles as an offline payload copy (spec §3.8). `ReportAssets.render` escapes `<`
  as `\u003c` so `</script>` inside payload strings cannot break out of the script
  element.
- Plugin renders `buildhound-report.html` next to the payload JSON in the finalizer.
  The plugin gets `implementation(projects.buildhoundReport)` — "embedded at build
  time" is satisfied via a module dependency for now; the publishing chunk decides
  whether to shade/copy resources instead (recorded as an open point, not a schema or
  behavior concern).
- `htmlReport { enabled }` DSL block (default true). Custom `outputDir` deferred —
  the settings context has no project layout; revisit with the upload chunk.

**Out (later):** timeline by worker lane, cache-miss details, Kotlin/tests/process
panels (need their collectors), dashboard link (needs server config plumbed), custom
output dir.

## Divergences after review

- The placeholder now includes the `null` sentinel (`/*__BUILDHOUND_DATA__*/null`):
  replacing only the comment left `{…} null;` — a SyntaxError that blanked every
  rendered report (HIGH review finding; tests now assert the exact assignment).
- `executionReasons` were stripped from the *embedded* payload copy as an interim;
  since plan 007 the payload is scrubbed at assembly, so the artifact embeds the same
  scrubbed payload (stripping removed).
- CSP meta tag (`default-src 'none'`) added as defense-in-depth.

## Test strategy

- report unit: template has placeholder + no external requests (existing, kept);
  render escapes `</script>` payloads; rendered page keeps DOCTYPE.
- plugin functional: artifact exists after a build, starts with `<!DOCTYPE html>`,
  contains the buildId; `htmlReport { enabled = false }` writes payload but no HTML.

## Risks

- XSS surface: payload-controlled strings (branch, tags, executionReasons) must never
  hit `innerHTML` — template uses `textContent`/`createElement` exclusively; JSON
  embedding escapes `<`. The zero-network test keeps guarding decision #4 at the
  template level (the rendered file legitimately contains `https://` inside embedded
  data strings, so the check stays on the template).
- Report module stays Gradle-free and dependency-free (KMP §3 rules; js target later).

## Exit criteria

`./gradlew build` green; a real build produces `buildhound-report.html` that renders
offline (no external requests) and shows the build's tasks.
