# 008 — Upload: gzip POST, spool/retry, foreground-on-CI, local opt-in

## Source

Roadmap Phase 1: "gzip upload with spool/retry, foreground-on-CI". Spec §3.4 (DSL
`server`/`localBuilds`), §5 upload section (synchronous on CI, 15s timeout, spool to
`build/buildhound/spool/`, idempotent on buildId, gzip), §3.7 (local opt-in).

## Scope

**In:**

- DSL: nested `server { url; token }` replaces the scaffold's flat
  `serverUrl`/`serverToken` (pre-release, no deprecation cycle — recorded here);
  `localBuilds { enabled; requireOptInFile }` (defaults true/true per spec §3.4).
- `UploadGate` (pure, unit-tested): upload happens iff enabled, server url set, and
  (mode == CI, or mode == LOCAL with `localBuilds.enabled` and — when
  `requireOptInFile` — the `~/.buildhound/optin` marker present). Artifact/payload
  writing is unaffected either way.
- `PayloadUploader` (JDK `HttpClient`, no new dependencies): synchronous
  `POST <url>/v1/builds`, gzip body, `Content-Encoding: gzip`,
  `Authorization: Bearer <token>`, `Content-Type: application/json`, 15s
  connect+request timeout. 2xx → success; anything else (or exception) → spool.
- Spool: `<rootDir>/build/buildhound/spool/<buildId>.json.gz`. At the next build's
  finalizer, up to 10 spooled files (oldest first) are retried before the current
  payload; success deletes the file; the spool is capped at 20 files (oldest dropped)
  so it cannot grow unbounded. Server dedupes on buildId (already idempotent).
- Token via `Property<String>` — wire from env/providers; never logged, never in the
  payload, redacted from failure messages (log exception class only, like the probes).

**Divergences from spec, recorded up front:**

- Local uploads are also synchronous (same 15s timeout) — the spec's "background
  thread with JVM-exit flush" needs daemon-lifecycle machinery that isn't worth the
  complexity before the pilot; spool/retry covers the failure path either way.
- The gzip size hard-cap + overflow strategy (drop reasons → truncate tasks) is
  deferred: today's payloads are KBs. Blocker note for the pilot rollout, not for
  this chunk.

## Test strategy

- Unit: `UploadGate` decision matrix; endpoint building (trailing slash); gzip
  roundtrip of a payload.
- Functional (TestKit + in-test `com.sun.net.httpserver`): CI-mode build (generic
  `BUILDHOUND_CI` env) uploads — server sees gzip body decoding to the payload and
  the Bearer token; unreachable server → spool file + warn, build green; next build
  against a live server drains the spool; local mode without opt-in skips upload
  (`requireOptInFile = false` path covered too).

## Risks

- CC safety: uploader runs inside the FlowAction only; token flows as a
  `Property<String>` input, url as String. No new config-time state.
- Security: token in the Authorization header only; TLS left to the JDK; no redirects
  followed cross-origin (HttpClient NEVER follow redirects — a redirect must not
  re-send the token elsewhere).
- Privacy: payload is already scrubbed (007); local opt-in enforced by UploadGate.

## Exit criteria

`./gradlew build` green; functional proof of upload + spool + drain + local gating;
zero build failures from unreachable servers.
