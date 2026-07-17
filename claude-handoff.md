# Dogfooding + multi-env publication — implementation handoff

## Authority and provenance

This handoff accompanies two approved, already-committed plans, implemented **in order**:

1. [`docs/plans/implemented/093-dogfood-buildhound-telemetry.md`](docs/plans/implemented/093-dogfood-buildhound-telemetry.md) — collection only, no server URL/credentials.
2. [`docs/plans/implemented/094-multi-env-build-data-publication.md`](docs/plans/implemented/094-multi-env-build-data-publication.md) — routing + credentials, depends on 093's artifacts.

**093 must fully land — implemented, tested, both fresh-context reviews clean — before 094
starts.** 094 adds real ingest tokens and new workflow jobs on top of the init script and
`sample-springboot` job 093 creates; reviewing them together would conflate a collection
change with a credential/trust-boundary change. If either plan needs to diverge during
implementation, update that plan file in the same PR and say why (CLAUDE.md workflow §2).

## Goal

Give BuildHound real telemetry on its own CI: 093 wires the `dev.buildhound` plugin into
the repo's own `build` job (via a two-invocation mavenLocal bootstrap + CI-only init-script
injection) and adds a JVM-only `sample-springboot` CI job, uploading every produced
`build-payload.json` as a workflow artifact — no server, no credentials. 094 then routes
those payloads (plus a direct in-plugin upload for prod) to production, staging, and
per-PR review environments using scope-limited ingest tokens, so prod accumulates every
build over time while new telemetry features get validated on review/staging first.

## Verified codebase facts (cite before you rely on them elsewhere)

- `UploadGate.decide` returns `Decision.Skip("no server configured")` when the URL is null/empty
  (`buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/UploadGate.kt:27`) — this is
  what makes 093 credential-free by construction.
- Spool files are destination-blind: `PayloadUploader.spool` writes `$buildId.json.gz`
  (`PayloadUploader.kt:125`) and `drainSpool` (`PayloadUploader.kt:73`) POSTs to the *current*
  uploader's single `baseUrl` (`PayloadUploader.kt:20,101`). One server URL per repo — never loop
  URLs in-plugin; this is why 094 is workflow-YAML-only, not a plugin change.
- Server ingest dedups `ON CONFLICT (project_id, build_id) DO NOTHING` and returns
  `status: "duplicate"` when the row already exists
  (`buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt:199`,
  `PostgresStores.kt:136`) — re-POSTs (job re-runs, spool drains, multi-env replays) are safe.
- `server.token` is excluded from `ConfigOverrides` by construction —
  `ConfigOverrides.EXCLUDED_KEY = "server.token"` (`ConfigOverrides.kt:49`, checked via
  `isOverridable`, `:51`). The init script must set `server.url`/`server.token` from
  `providers.environmentVariable(...)` only, never a DSL literal or `gradleProperty` — a literal
  would serialize into the on-disk CC entry (architecture.md §6, "Ingest tokens are wired from
  `providers.environmentVariable(...)` only").
- `buildhound.<key>` Gradle property / `BUILDHOUND_<KEY>` env are convention-level fallbacks for
  every DSL knob *except* the token (plan 027; architecture.md §6).
- `ci.yml` `build` job: `runs-on: ubuntu-latest`, `actions/setup-java@v5` with
  `distribution: temurin`, `java-version: 26` (ci.yml:46-49), `gradle/actions/setup-gradle@v6`
  pinning `gradle-version: "9.6.1"` (ci.yml:54-57; `build-floor` separately pins `8.14.4`).
  `paths-ignore` skips docs-only changes (`&docs-only` anchor, ci.yml:16). Existing jobs:
  `build`, `build-floor`, `server-image`, `image-scan`, `functional-cc-off`, `isolated-projects`,
  `build-macos`, `overhead-budget`, `build-windows`. `actionlint` (raven-actions/actionlint) and
  `shellcheck` already run in the `build` job (ci.yml:40-76) and will cover new workflow/script edits.
- `review-environment.yml`: triggers on `pull_request_target`; the `deploy-review` label gates
  environment creation (checked multiple places, e.g. line 54); `permissions` already includes
  `actions: read` (line 6, and per-job at line 23/332/356); `BUILDHOUND_REVIEW_TOKEN` is minted
  per-run with `openssl rand -hex 32` and masked (line 201-202) — it exists **only** inside that
  workflow run, which is why 094's review-publish step must live in `review-environment.yml`
  itself, not `ci.yml`. A smoke-test POST to `$dashboard_url/v1/builds` with that token already
  exists at line 256 — model the new publish step on it.
- Server bootstrap token env is `BUILDHOUND_BOOTSTRAP_TOKEN` (`Application.kt:172`); both
  `deploy/dokploy/stack.yaml:12` and `staging-stack.yaml:12` already pass it through. The deploy
  verify script already uses ingest/read tokens as `BUILDHOUND_INGEST_TOKEN`/`BUILDHOUND_READ_TOKEN`
  (`deploy/dokploy/verify-release.sh:3,65,74`) — reuse that naming convention, don't invent a new one.
- Payload file: `build/buildhound/build-payload.json`, written even on build failure
  (`TelemetryFinalizerAction.kt:757`) — upload the artifact with `if: always()`.
- Samples: `samples/springboot-legacy` already wires `pluginManagement { includeBuild("../..") }`
  (`samples/springboot-legacy/settings.gradle.kts:1-5`) — JVM-only, the CI candidate for 093.
  `samples/nowinandroid` and `samples/android-legacy-agp` need an Android SDK — out of scope for
  both plans.
- `buildhound-ci-assets/bin/` currently holds only `buildhound-metric`; its test harness
  (`buildhound-ci-assets/test/metric-cli-test.sh`, shellchecked + run in ci.yml:71) is the
  precedent to follow for 094's new staging-publish script (stubbed-curl, no network, soft-fail
  exit 0 on unreachable).
- `deploy/dokploy/README.md:26`: "An unset protection rule or Environment secret is a rollout
  blocker, not permission to use a repository-wide credential" — a 2026-07-15 live check (per
  plan 094 §6) found the four GitHub Environments' protection rules unset. This blocks 094's
  credentialed jobs until fixed; it does not block 093.

## Hard constraints (CLAUDE.md — apply to both plans)

- The plugin must never fail a build; every failure path degrades to a `warn` log.
- Configuration-cache compatibility for all plugin/init-script code — never disable CC to make
  something pass; fix the CC violation instead.
- Tokens/secrets only via providers/env — never in code, DSL literals, logs, or images.
- Schema changes are additive only; neither plan touches the schema, so no golden-file change
  is expected — flag it to the owner if one becomes necessary.
- Review routing (CLAUDE.md §3, subagent fleet table): `kotlin-gradle-reviewer` for any
  `*.kt`/`*.kts` touched — including `.github/buildhound-dogfood.init.gradle.kts` itself
  (it's a `.kts` file; its CC-safety and `providers.environmentVariable`-only token wiring are
  exactly this reviewer's domain) and 093's functionalTest case; `infra-reviewer` for CI YAML
  and `buildhound-ci-assets` changes (both plans touch `ci.yml`; 094 also touches
  `review-environment.yml` and adds a script under `buildhound-ci-assets/bin/`). The §3.2
  security & privacy review is **mandatory** for both plans regardless of fleet routing — no
  fleet agent substitutes for it, since 094 introduces new ingest-token trust boundaries.

## Task list

### 093 — dogfood telemetry (implement and land first)
- [ ] Add `.github/buildhound-dogfood.init.gradle.kts`: resolve `dev.buildhound` from
      `mavenLocal()`, apply via `beforeSettings`, set `server.url`/`server.token` from
      `providers.environmentVariable(...)` (unset/empty in this plan → `UploadGate` skips),
      plus low-cardinality `tags` (job name, trigger).
- [ ] Change the `build` job to run two invocations: (1)
      `gradle :buildhound-gradle-plugin:publishToMavenLocal`, (2)
      `gradle build -I .github/buildhound-dogfood.init.gradle.kts`. Local `gradle build` without
      `-I` must show zero behavior change.
- [ ] Add new `ci.yml` job `sample-springboot`: `gradle build` inside `samples/springboot-legacy`.
- [ ] Upload `build/buildhound/build-payload.json` from both jobs as
      `buildhound-payload-<job>` artifacts, `if: always()`, short (7-day) retention.
- [ ] Add a `functionalTest` TestKit case: synthetic project + init script applied, assert the
      plugin activates and writes a payload.
- [ ] Confirm `actionlint`/shellcheck already cover the new workflow edits (no new script in 093).
- [ ] Run both fresh-context reviews (kotlin-gradle-reviewer — covers the `.kts` init script
      and functionalTest case — + infra-reviewer for `ci.yml` + mandatory §3.2) before calling
      093 done; fix or explicitly record acceptance of findings.

### 094 — multi-env publication (start only after 093 is landed + reviewed)
- [ ] Prerequisite: mint ingest-scope-only tokens on staging + production (each environment's
      bootstrap-token admin path); store as repo secrets `BUILDHOUND_PROD_INGEST_TOKEN` /
      `BUILDHOUND_STAGING_INGEST_TOKEN`.
- [ ] Prerequisite: verify/fix the four GitHub Environments' protection rules against
      `deploy/dokploy/README.md` before adding credentialed jobs (currently unset — rollout blocker).
- [ ] Wire real values into 093's init script for prod: `BUILDHOUND_SERVER_URL` = prod dashboard
      origin (repo variable), `BUILDHOUND_TOKEN` = `BUILDHOUND_PROD_INGEST_TOKEN`. Keeps the
      spool/retry path — prod is the loss-protected copy.
- [ ] Add new `ci.yml` job `publish-staging` (`needs: [build, sample-springboot]`, same-repo PRs
      + `main` pushes only): download `buildhound-payload-*` artifacts, POST each to
      `<staging>/v1/builds` via a new shellchecked script under `buildhound-ci-assets/bin/`
      (token via header only, soft-fail exit 0 — a dead staging must never redden a PR).
- [ ] Add a post-smoke step in `review-environment.yml`'s deploy path: find the CI run for
      `$REVIEW_SHA` (`gh api`, `actions: read` already granted), poll bounded (~10x30s) for
      payload artifacts, POST to `$dashboard_url/v1/builds` using the in-run
      `BUILDHOUND_REVIEW_TOKEN`. Soft-fail with a job-summary note if the artifact never appears.
- [ ] Add the `metric-cli-test.sh`-style sh test for the new publish script (success / 4xx-drop /
      unreachable-soft-fail / missing-env skip).
- [ ] Coordinate `ci.yml` / `review-environment.yml` edits with open CI-recovery plans 088-090
      (merge conflicts, not design conflicts).
- [ ] Run both fresh-context reviews (kotlin-gradle-reviewer if any `.kt` touched, infra-reviewer,
      mandatory §3.2 — this time with real credential/trust-boundary scope) before merge.

## Open questions / explicitly deferred

- Instrumenting the matrix jobs (`build-floor`, macOS, Windows, `functional-cc-off`,
  `isolated-projects`) — deferred, not in 093's scope.
- Android samples (`nowinandroid`, `android-legacy-agp`) in CI — need an Android SDK; candidate
  for a future nightly job.
- Multi-target spool, proxy mirroring, `/v1/metrics` fan-out, backfilling review/staging gaps —
  explicitly out of scope for 094; only prod gets spool-backed delivery.
- Schema drift on an older prod server (unknown new fields silently dropped via
  `ignoreUnknownKeys`, first-write-wins, no backfill) — accepted risk per plan 094 §5, not a blocker.

## Verification commands

```bash
./gradlew build
./gradlew :buildhound-gradle-plugin:functionalTest
shellcheck buildhound-ci-assets/bin/* buildhound-ci-assets/test/*.sh   # after 094 adds a script
git diff --check
```

Plus, per each plan's exit criteria:
- 093: a PR run uploads `buildhound-payload-build` and `buildhound-payload-sample-springboot`
  artifacts containing valid v1 payloads (each with a fresh `buildId`); local `gradle build`
  without `-I` is unchanged; no new secrets/variables introduced.
- 094: a `deploy-review`-labeled PR lands the same `buildId` in all three environments; a merged
  commit lands in prod + staging; a fork PR shows "no server configured" and skipped publish
  jobs; no token appears in any log (masked).

Both fresh-context reviews (CLAUDE.md §3: code & architecture + mandatory security & privacy)
must run per plan and findings fixed or explicitly accepted before merge.

## Kickoff prompt for the next agent

> Implement plan 093 from `docs/plans/implemented/093-dogfood-buildhound-telemetry.md` on a fresh branch
> off latest `origin/main`. Treat `claude-handoff.md` at the repo root
> as authoritative for verified codebase facts, hard constraints, and the task list — do not
> re-derive them. Implement every task under 093 in that handoff's task list (init script,
> two-invocation `build` job, new `sample-springboot` job, artifact upload, functionalTest
> TestKit case). Do not start 094 (credentials/routing) in this session — it depends on 093
> being fully landed and reviewed first, per the handoff's sequencing section. Follow
> CLAUDE.md's hard constraints (plugin never fails a build; configuration-cache compatibility;
> tokens only via `providers.environmentVariable`; schema additive-only — none expected here).
> After implementing, run the verification commands in the handoff's "Verification commands"
> section, then run the two required fresh-context reviews per CLAUDE.md §3: route to
> `kotlin-gradle-reviewer` (the new `.kt` TestKit test **and** the `.kts` init script — its
> CC-safety and `providers.environmentVariable`-only token wiring are this reviewer's domain)
> and `infra-reviewer` (`ci.yml`), plus the mandatory §3.2 security & privacy review (token
> handling in the init script, no secrets in workflow YAML). Fix findings or
> record explicit owner acceptance before considering 093 done. If implementation diverges
> from the plan, update `docs/plans/implemented/093-dogfood-buildhound-telemetry.md` in the same PR and
> say why.
