# 082 — BuildHound public main site

## Source

Deployment request (2026-07-10): publish a small `buildhound.dev` landing page as a
Dokploy service separate from the dashboard/ingest/database stack. The site is bound to
[`docs/brand/DESIGN-V2.md`](../brand/DESIGN-V2.md) and plan 080. It must be manually
deployable without plan 083; 083 may later automate promotion and review lifecycle.

## Scope

**In:** a top-level `site/` directory that is deliberately not a Gradle module; a static
landing page and production-owned V2 assets; a digest-pinned non-root nginx image; runtime
dashboard-link configuration; production/staging Dokploy Applications and domains; explicit
image deployment; accessibility, container, browser, and deployment tests.

**Out:** documentation, blog, authentication, forms, analytics, cookies, CMS, dashboard/API
code, DB/object-storage access, and automated review lifecycle.

## Design

- Present the V2 promise, “Track every Gradle build. Find the slow work,” a concise
  open-source/self-hosted Gradle telemetry description, the Apache-2.0 license, and two
  primary links. Hard-code `https://github.com/BuildHound/BuildHound`; configure only the
  environment-specific dashboard origin as `BUILDHOUND_SITE_DASHBOARD_URL`.
- Implement from `DESIGN-V2.md`: Trace H geometry, generated V2 tokens, spacing and status
  rules, self-hosted Fraunces/Inter fonts, copper only as brand color, responsive targets,
  visible focus, and a local Auto/Light/Dark control. Adopt assets into `site/` with their
  license/provenance; never serve or copy the `docs/brand/v2/` fixture page wholesale. The
  small theme-control file is the only client JavaScript; there are no third-party requests.
- At startup, accept only a deliberately narrow absolute HTTPS dashboard origin: reject
  missing values, userinfo, whitespace/control characters, quotes, markup/shell characters,
  query, and fragment. Substitute only the validated value into a fixed attribute, without
  `eval`, and atomically render into tmpfs. `BUILDHOUND_SITE_NOINDEX` accepts only `true` or
  `false`; staging/review use `true`, production `false`.
- Use a currently verified, digest-pinned `nginxinc/nginx-unprivileged` image on port 8080.
  Run as its declared user with a read-only root and bounded tmpfs for rendered HTML, pid,
  and nginx temporary paths. Add an HTTP healthcheck, disable version disclosure and
  absolute redirects, and reject unmatched hosts through a catch-all configuration. Serve a
  reviewed `security.txt` or explicitly document its absence without inventing a contact.
- Send a complete CSP permitting only local CSS, fonts, favicon, and theme script, plus
  `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, anti-framing, and a
  restrictive permissions policy. HSTS belongs to TLS ingress. Match `X-Robots-Tag` and
  `robots.txt`; keep HTML revalidatable and cache only fingerprinted assets as immutable.
- Create production and staging Dokploy Applications/domains here. Record and deploy the
  exact `tag@sha256` image reference; a push alone is insufficient. Provide a manual
  publish/deploy path so this plan exits before 083.

## Test strategy

- Parse the page and assert `lang`, one `h1`, meaningful links, V2 lockup/local fonts,
  keyboard-visible focus, working theme control, and zero third-party or telemetry requests.
  Visually check both themes at 390, 580, 900, 1280, and 1440 px and at 200% zoom.
- Reject missing and hostile dashboard values without producing a partial page. Test both
  noindex values and reject every other value.
- Run as the declared non-root user with read-only root/tmpfs; assert health, full CSP and
  security headers, robots behavior, HTML revalidation, fingerprinted asset caching, and no
  nginx version leakage. Verify the CSP does not break CSS, fonts, SVG, or theme control.
- Smoke production and staging through public domains, verify each dashboard link stays in
  its environment, and prove an explicit digest change plus deploy updates the site. Confirm
  `./gradlew build` is unaffected.

## Risks

Runtime rendering is an injection boundary, so the URL contract is intentionally narrower
than a general URL parser. Read-only nginx fails unless every write path uses tmpfs. Moving
tags or omitted deploy calls serve stale content. Trace H remains a release-candidate mark;
complete and record the V2 recognition/collision gate before public production launch.

## Exit criteria

- Production and staging serve the V2 site as separate healthy Dokploy Applications with
  environment-local dashboard links and no third-party requests.
- A documented manual exact-digest deploy succeeds independently of plan 083.
- URL, accessibility, theme, responsive, CSP/header, cache, noindex, non-root, read-only,
  and deployment tests pass; the V2 mark gate is recorded.
- No Gradle module or build impact is introduced.
