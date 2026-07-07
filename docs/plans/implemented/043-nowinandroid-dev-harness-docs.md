# Plan 043 — nowinandroid dev harness: document the end-to-end plugin + server dev loop

**Status: planned** · 2026-07-05

## 1. Source

Feature request: "set up the project to use `samples/nowinandroid` during development to test
the plugin and the webservice; update the README with how to set up the plugin, the webservice,
and tokens, and surface the local-dev tokens from the compose deploy so they are easy to find."

Supporting context:
- `samples/nowinandroid/settings.gradle.kts` already wires the sample to BuildHound as an
  included build (`includeBuild("../..")`), applies `dev.buildhound`, and configures
  `server { url; token }` + `htmlReport` + `localBuilds`. The wiring exists but is **undocumented**.
- `deploy/compose.yaml` bootstraps a local-dev project/token (`pilot` /
  `buildhound-local-dev-token`) and DB password (`buildhound-local-dev`) — checked-in,
  local-only values (spec §5, plan 042, `docs/self-hosting.md` §1/§3).
- `docs/self-hosting.md` already covers operator-grade token provisioning + scopes.

## 2. Scope

**In (docs only — no plugin/server/commons behaviour change):**

1. `samples/README.md` (new) — the developer harness: what the sample is, prerequisites
   (JDK 21+, Android SDK via `ANDROID_HOME`/`local.properties`), and the full end-to-end loop
   (start the server via compose → run a build in the sample → HTML report + dashboard/ingest),
   including the local-dev token and how to override it via `BUILDHOUND_TOKEN`.
2. `README.md` (update) — replace the generic "apply the plugin" snippet with a real
   webservice + plugin + token setup section: start the stack, apply against `localhost:8080`,
   the full `server {}`/`localBuilds {}`/`htmlReport {}` DSL, an env-var-first token with the
   committed local-dev fallback surfaced, and a short "local development credentials" table
   (project, token, DB password, ports) so the compose values are findable. Point developers at
   the sample as the ready-made harness and at `docs/self-hosting.md` for real deployments.

**Out:** any change to the plugin, server, commons, or the sample's build wiring (already
correct); per-token minting (a self-hosting follow-up); publishing to a real registry.

## 3. Design

Documentation only. The values documented are read verbatim from `deploy/compose.yaml`
(`BUILDHOUND_BOOTSTRAP_PROJECT=pilot`, `BUILDHOUND_BOOTSTRAP_TOKEN=buildhound-local-dev-token`,
`BUILDHOUND_DB_PASSWORD=buildhound-local-dev`, port `8080`) and the `BuildHoundExtension` DSL
(`server.url`, `server.token`, `localBuilds.enabled/requireOptInFile`, `htmlReport.enabled`).
No new files under the plugin/server classpath; nothing enters an image layer.

## 4. Test strategy

No code paths change. Verification is by inspection: the documented commands, DSL, URLs, and
token values match `deploy/compose.yaml`, `docs/self-hosting.md`, `BuildHoundExtension.kt`, and
`samples/nowinandroid/settings.gradle.kts`.

## 5. Risks

- **Security/privacy:** the only credentials surfaced are the *already checked-in* local-dev
  values, explicitly labelled local-only with the `openssl rand -hex 32` guidance for real
  deployments (mirrors compose + self-hosting.md). No real secret is introduced; nothing lands
  in an image layer or the plugin classpath. Env-var-first token wiring is kept as the norm.
- **Doc drift:** values are duplicated from compose into the README. Mitigated by keeping the
  README table minimal and pointing to `deploy/compose.yaml` / `docs/self-hosting.md` as the
  sources of truth.

## 6. Exit criteria

- `samples/README.md` walks a developer from a clean checkout to telemetry landing in the local
  server and an HTML report, with prerequisites and the local-dev token.
- `README.md` documents the webservice + plugin + token setup end-to-end and surfaces the
  compose local-dev credentials in one findable place.
- Both §3 reviews (code & architecture; security & privacy) pass with findings addressed.
