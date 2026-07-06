# Sample projects — the BuildHound dev harness

These are real Gradle builds wired to apply the in-development BuildHound plugin, so you can
exercise the **plugin** and the **ingest server** end-to-end without publishing anything.

| Sample | What it is | Why it's here |
|---|---|---|
| [`nowinandroid/`](nowinandroid/) | A checkout of Google's [Now in Android](https://github.com/android/nowinandroid) app — a large, multi-module Android build | A realistic build (many modules, tasks, tests, caching) to see the plugin's telemetry and overhead on something non-trivial |

The sample applies BuildHound as an **included build** — no published artifact, no `mavenLocal`.
Its [`settings.gradle.kts`](nowinandroid/settings.gradle.kts) does:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    includeBuild("../..")   // BuildHound lives two levels up; supplies the dev.buildhound plugin
    // ...
}
plugins { id("dev.buildhound") }

buildhound {
    htmlReport { enabled = true }
    server {
        url = "http://localhost:8080"
        token = providers.environmentVariable("BUILDHOUND_TOKEN")
            .orElse("buildhound-local-dev-token")   // the committed local-dev token (see below)
    }
    localBuilds {
        enabled = true
        requireOptInFile = false   // demo only — real local uploads want the ~/.buildhound/optin marker
    }
}
```

Any change you make to the plugin or `buildhound-commons` is picked up on the next sample build —
Gradle rebuilds the included plugin from source automatically.

## Prerequisites

- **JDK 21+** — the BuildHound plugin ships JVM 21 bytecode and raises the sample's floor to 21
  (the sample's `settings.gradle.kts` asserts this and fails fast with a clear message otherwise).
- **Android SDK** — Now in Android is an Android build. Point Gradle at an SDK via either
  `ANDROID_HOME` / `ANDROID_SDK_ROOT` or a `samples/nowinandroid/local.properties` with
  `sdk.dir=/path/to/Android/sdk`. (`local.properties` is git-ignored; it's machine-specific.)
- **Docker** (optional) — to run the ingest server + database with Compose. You can also run the
  server straight from Gradle (`:buildhound-server:run`, in-memory storage) if you don't need
  persistence.

## End-to-end dev loop

Run these from the **repo root** unless noted.

### 1. Start the ingest server

Full stack (server + TimescaleDB), the closest match to production:

```bash
docker compose -f deploy/compose.yaml up --build
```

Or, for a quick loop with no database (in-memory storage — data is lost on restart):

```bash
./gradlew :buildhound-server:run
```

Either way the server comes up on `http://localhost:8080`:

```bash
curl http://localhost:8080/health        # liveness
open http://localhost:8080/              # dashboard (paste a read token on first visit — see below)
open http://localhost:8080/docs          # API reference (OpenAPI)
```

The dashboard's queries are authenticated: on first visit paste the token
**`buildhound-local-dev-token`** into its token bar (it needs `read` scope; the bootstrap token
is `all`-scope, so it works). The token is kept in the browser session only.

The Compose stack bootstraps a project named **`pilot`** with the ingest token
**`buildhound-local-dev-token`** (see [Local development credentials](#local-development-credentials)).
The sample is already configured to send to that project with that token, so no extra wiring is needed.

### 2. Run a build in the sample

```bash
cd samples/nowinandroid
./gradlew :core:common:assemble        # or any task — assembleDebug, test, etc.
```

On completion the plugin:
- writes a standalone **HTML report** under `samples/nowinandroid/build/buildhound/` (open it in a
  browser — it needs no network), and
- **uploads** the build's telemetry to `http://localhost:8080` (local uploads are enabled for the
  demo). Refresh the dashboard (with the token entered, per step 1) to see the build, its tasks,
  cache stats, and test results.

To point at a different server or token without editing the sample, override via the environment:

```bash
BUILDHOUND_TOKEN=some-other-token ./gradlew :core:common:assemble
```

### 3. Iterate

Edit the plugin (`buildhound-gradle-plugin/`) or `buildhound-commons/`, re-run the sample build,
and the change is compiled and applied automatically — no republish. Watch the server logs and
dashboard to confirm the payload changed as you expect.

## Local development credentials

These come straight from [`deploy/compose.yaml`](../deploy/compose.yaml) and are **local-development
only** — they are deliberately checked in so the harness works out of the box. For any shared or
real deployment, generate a high-entropy token with `openssl rand -hex 32` and supply it through the
environment (never in code, DSL, or an image layer). See [`docs/self-hosting.md`](../docs/self-hosting.md).

| What | Value | Where |
|---|---|---|
| Server URL | `http://localhost:8080` | `deploy/compose.yaml` → `server.ports` |
| Bootstrap project | `pilot` | `BUILDHOUND_BOOTSTRAP_PROJECT` |
| Ingest token | `buildhound-local-dev-token` | `BUILDHOUND_BOOTSTRAP_TOKEN` (the token the sample sends) |
| DB user / password | `buildhound` / `buildhound-local-dev` | `POSTGRES_USER` / `POSTGRES_PASSWORD` |
| Dashboard / API docs | `/` and `/docs` on port 8080 | server routes |

The bootstrap token is `all`-scope (every scope: ingest, read, addon, admin). Token scopes and operator-grade
provisioning are documented in [`docs/self-hosting.md` §3](../docs/self-hosting.md#3-token-provisioning).

## Troubleshooting

- **`SDK location not found`** — set `ANDROID_HOME` or add `local.properties` (see Prerequisites).
- **`This sample requires JDK 21+`** — run Gradle on a JDK 21+ (`java -version`); Android Studio's
  bundled JDK may be older.
- **Nothing appears in the dashboard** — confirm the server is up (`curl .../health`), that you ran
  the build (not just `--dry-run`), and that `BUILDHOUND_TOKEN` (if set) matches the server's token.
- **Connection refused on upload** — the plugin never fails the build on a bad server; it logs a
  warning and still writes the HTML report. Start the server, then re-run the build.
- **`0 test(s)` in the summary** — either the test tasks were `UP-TO-DATE`/`FROM-CACHE` (BuildHound
  only ingests tests that actually ran this build), or you ran a task that executes no tests. Force
  a real run with `--rerun-tasks`, and use the task that matches the module kind: `test` for
  JVM/Kotlin modules (e.g. `:core:common:test`), `testDemoDebugUnitTest` for the flavored Android
  modules, `connectedDemoDebugAndroidTest` for instrumented tests (needs an emulator/device).
