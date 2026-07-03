# Plan 027 — CI/environment breadth: provider matrix, IDE/AI-agent detection, links, config overrides

**Status: planned — roadmap phase 2b** · 2026-07-03

## 1. Source

- [Roadmap phase 2b](../build-telemetry-roadmap.md), CI/environment-breadth bullet: the
  10-provider CCUD detection matrix, IDE + AI-agent detection, redacted git remote URL +
  source/PR links + GHA run-attempt, `uploadInBackground` knob, `BUILDHOUND_*` /
  `buildhound.*` config overrides.
- [Spec §3.3](../build-telemetry-spec.md) (CI SPI, generic fallback, first-match order),
  [§3.2](../build-telemetry-spec.md) (env + VCS collectors), [§3.4](../build-telemetry-spec.md)
  (DSL, `upload {}` sketch), [§3.9](../build-telemetry-spec.md) (upload semantics — **rewritten
  in [plan 020](020-doc-repairs-spec-drift.md), prerequisite reading**: `uploadInBackground` is
  an opt-out from *blocking* local builds on the upload attempt, **not** a return of the
  never-built background thread), [§4](../build-telemetry-spec.md) (schema).
- Research: [plugin-ecosystem-gap-analysis.md](../research/plugin-ecosystem-gap-analysis.md)
  §1 items 2/5/10/11/12, §4.1–§4.5 (source-verified CCUD 2.7.0 matrix, IDE/agent env vars,
  redaction rules, `Overrides` pattern); [daemonitor.md](../research/repos/daemonitor.md)
  (agent attribution from env-var names with **ambient subtraction**; IDE-beats-terminal);
  [comparison-to-spec.md §2.4](../research/comparison-to-spec.md).
- Builds on already-landed [plan 014](014-bare-ci-env-detection.md) (bare-`CI` → `mode=ci`,
  generic kill switch) and [plan 015](015-vcs-exec-timeout.md) (10 s bounded git). **Neither is
  re-planned here.**

## 2. Scope

**In:** (1) nine new built-in `CiEnvironmentProvider`s (Jenkins, TeamCity, CircleCI, Bamboo,
GitLab, Travis, Bitrise, GoCD, Buildkite) per the source-verified §4.4 mapping — with Azure +
GHA already shipped this is the CCUD 10-matrix plus generic; (2) IDE + AI-agent detection as
additive `EnvironmentInfo` fields; (3) redacted `vcs.remoteUrl`, a new `links` block
(commit/PR URLs), and GHA `runAttempt` (fixing the re-run `buildUrl` collision); (4) an
`uploadInBackground` DSL knob that opts a local build out of blocking on the upload attempt
(spools instead); (5) `BUILDHOUND_*` env / `buildhound.*` sysprop overrides for every DSL knob
except the token.

**Out (owned elsewhere):** server-side `CiConnector` / Azure Timeline pull → plan 028 (this is
plugin + payload only). Server rollups over the new providers → plan 026. Rendering the new
fields in dashboard/HTML → plans 017/018. Tag/value + payload-size caps → plan 019 (new string
fields route through `PayloadCapper` when it lands; this plan does not touch it). `values`-map
population → plan 019. Re-planning bare-`CI` mode (014) or git timeout (015). `agentName` stays
dropped from the payload (plan 005) — IDE/agent fields are a separate, pseudonymization-clean
dimension.

## 3. Design

**Current behavior (verified).** `CiEnvironment.builtIns` holds only Azure + GHA
([CiEnvironmentProviders.kt:111](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/ci/CiEnvironmentProviders.kt));
`detect()` is built-ins → SPI extras → generic, first-match-wins
([:118](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/ci/CiEnvironmentProviders.kt)).
`EnvironmentInfo` has no IDE/agent fields
([BuildPayload.kt:53](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt));
`EnvironmentValueSource` probes only os/arch/cores/ram/identity/gradle/jdk from
`System.getenv()`/`getProperty()` at execution time
([EnvironmentValueSource.kt:54](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/EnvironmentValueSource.kt)).
`VcsValueSource` collects branch/sha/dirty, discarding `git status` paths
([VcsValueSource.kt:43](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/VcsValueSource.kt));
`VcsInfo` has no remote URL. The GHA `buildUrl` uses `GITHUB_RUN_ID` only, so re-run attempts
collide ([CiEnvironmentProviders.kt:73](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/ci/CiEnvironmentProviders.kt)).
`BuildHoundExtension` has no `upload {}` and no override plumbing
([BuildHoundExtension.kt:13](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundExtension.kt));
`apply()` already reads one `gradleProperty` seam
([BuildHoundSettingsPlugin.kt:66](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)).
The server stores the full payload as jsonb and extracts only hot columns
([V1__core.sql:32](../../buildhound-server/src/main/resources/db/migration/V1__core.sql)),
so every additive field rides along **with no server change**.

**CI matrix.** Each provider is ~20-30 lines: marker check → null when absent, else a
`CiContext` per §4.4. Reuse the existing `stripGitRef` / `isHttpUrl` / `encodeUrlSegment`
helpers; any composed `buildUrl` passes the `isHttpUrl` gate before reaching a payload (the
anti-`javascript:` rule Azure/GHA already enforce). Register most-specific-first in `builtIns`
so a unique marker beats one keyed on a common var (§4.5). TeamCity is deliberately partial —
its rich metadata lives in a properties file the env-only SPI cannot read (§4.4 caveat, KDoc'd).
The ⚠-marked §4.4 vars were not CCUD-verified; each is confirmed against the provider's own docs
at implementation and cited in KDoc. GHA gains `runAttempt` = `GITHUB_RUN_ATTEMPT` in
`attributes` and an `/attempts/N` `buildUrl` suffix when attempt > 1.

**IDE + agent** (new pure `EnvironmentDetection` in commons; `EnvironmentValueSource` calls it):
- IDE (§4.1): `idea.vendor.name=="Google"` → Android Studio (`ideVersion` from
  `android.studio.version`), `=="JetBrains"` → IntelliJ (`idea.version`), `eclipse.buildId` →
  Eclipse, `VSCODE_PID`/`VSCODE_INJECTION` → VS Code, else null (a null IDE *is* command-line —
  keep the field honest); `idea.sync.active` → `ideSync`. Skipped when a CI context is present.
- Agent (§4.1 + daemonitor): ordered first-match over env-var **names** — `CLAUDECODE` → Claude
  Code, `CODEX_SANDBOX_NETWORK_DISABLED`/`CODEX_THREAD_ID` → Codex, `CURSOR_AGENT` → Cursor,
  `OPENCODE` → OpenCode, `GEMINI_CLI` → Gemini CLI, `android.studio.agent`/`ANDROID_STUDIO_AGENT`
  → Gemini in Studio. Robustness per daemonitor: an ambient-baseline subtraction so a marker with
  an empty/falsy value counts as ambient, not an active agent (the plugin runs in-build and cannot
  take a pre-launch snapshot, so this is the in-build analogue, KDoc'd). Only `CLAUDECODE` is a
  confirmed signal; the rest are best-effort. Positive match only, never a guess; nullable field.

**Links + remote URL.** Add a bounded `git config --get remote.origin.url` probe to
`VcsValueSource` (reusing the `GitProbe` timeout,
[VcsValueSource.kt:54](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/VcsValueSource.kt)).
New pure `VcsParsing.redactRemoteUrl` redacts userInfo **for all schemes** (§4.5 — fixing CCUD's
http-only leak of `ssh://user:pass@…`) and **fails closed** (drops the value) when unparseable.
New commons `SourceLinks.compose(remoteUrl, sha, prNumber)` builds commit/PR URLs for
`github`/`gitlab` hosts only, fail-closed and `isHttpUrl`-gated. New schema: `VcsInfo.remoteUrl`
and top-level `links: LinksInfo?` (`commitUrl`, `pullRequestUrl`).

**`uploadInBackground`.** New `UploadSpec.uploadInBackground` (default false = today's synchronous
behavior). When true **and** mode is local, `TelemetryFinalizerAction` spools the payload directly
instead of attempting the inline upload (next build's `drainSpool` sends it,
[TelemetryFinalizerAction.kt:145](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TelemetryFinalizerAction.kt)).
CI/benchmark are unaffected (short-lived agents must upload inline). **No new thread** — the
never-built background thread stays un-built.

**Config overrides.** New plugin helper `ConfigOverrides`: for each knob, read
`providers.gradleProperty("buildhound.<key>")` then `providers.environmentVariable("BUILDHOUND_<KEY>")`
(`<KEY>` = `<key>.uppercase().replace('.','_')`, the CCUD mechanical mapping). Precedence
(via an `orElse` provider chain): **explicit DSL value → override → built-in convention**. Keys
mirror the DSL: `enabled`, `mode`, `server.url`, `identity.pseudonymize`, `htmlReport.enabled`,
`localBuilds.enabled`, `localBuilds.requireOptInFile`, `upload.uploadInBackground`. **`server.token`
is excluded by construction** — a token sysprop would serialize into the CC entry on disk, so
tokens stay env-provider-only (architecture §6); the exclusion is tested. Boolean/enum parsing is
fail-safe (unparseable → ignored, info log).

## 4. Implementation steps

1. **commons schema (additive):** in `payload/BuildPayload.kt` add `ide`/`ideVersion`/`ideSync`/
   `aiAgent` to `EnvironmentInfo`, `remoteUrl` to `VcsInfo`, and `LinksInfo` + top-level `links`
   — all null defaults (`explicitNulls=false` keeps existing bytes/golden files unchanged).
2. **commons CI providers:** add the nine classes to `ci/CiEnvironmentProviders.kt` per §4.4,
   register most-specific-first in `CiEnvironment.builtIns`; add GHA `runAttempt` + `/attempts/N`.
3. **commons links/redaction:** add `SourceLinks` and `VcsParsing.redactRemoteUrl` (all-scheme,
   fail-closed, KMP-pure).
4. **commons detection:** add `EnvironmentDetection` (`detectIde`, `detectAgent`), KDoc'd with the
   confirmed-vs-best-effort split and the ambient rule.
5. **plugin env collector:** extend `CollectedEnvironment` + `EnvironmentValueSource.obtain()` to
   call `EnvironmentDetection` (guarded like every probe,
   [EnvironmentValueSource.kt:109](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/EnvironmentValueSource.kt)),
   skipping IDE detection under a CI marker.
6. **plugin VCS collector:** add the `remote.origin.url` probe and thread the redacted URL into
   `CollectedVcs`.
7. **plugin assembler:** map the new env/vcs fields, compose `LinksInfo`, carry `runAttempt` into
   `ci.attributes` ([PayloadAssembler.kt:92](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadAssembler.kt));
   every composed URL passes `isHttpUrl`.
8. **plugin DSL:** add `UploadSpec` + `upload {}`, wire into Flow params
   ([BuildHoundSettingsPlugin.kt:78](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt))
   and honor it in the finalizer (spool-directly in local mode when set).
9. **plugin overrides:** add `ConfigOverrides`, apply as the `orElse` layer over each convention;
   exclude `server.token`.
10. **golden file:** add `golden/build-payload-v1-ci-env.json` (populated IDE/agent/remoteUrl/links,
    a GitLab provider, a GHA-style `runAttempt`) + a `GoldenPayloadTest` case;
    `build-payload-v1.json` is **not touched**
    ([GoldenPayloadTest.kt:13](../../buildhound-commons/src/jvmTest/kotlin/dev/buildhound/commons/payload/GoldenPayloadTest.kt)).
11. **docs, same PR:** spec §3.3 provider list expanded; §4 documents the new fields; §3.4
    documents `upload { uploadInBackground }` and the override table with the `server.token`
    exclusion. `docs/architecture.md` §6 gains a bullet (overrides never carry the token;
    remote-URL redaction is all-scheme fail-closed) and a decision-log row dated 2026-07-03. Update
    this plan in the same PR if implementation diverges.

## 5. Test strategy

- **commons unit — providers** (`CiEnvironmentProvidersTest`, per provider): marker present →
  mapping; absent/other env → null; `buildUrl` `isHttpUrl`-gated (`javascript:` base → null);
  first-match order (specific marker + bare `CI` → specific provider; generic only when nothing
  matches); TeamCity's documented env-only partial; GHA `runAttempt` + `/attempts/2` when
  attempt > 1, absent at 1.
- **commons unit — redaction/links** (`VcsParsingTest`, `SourceLinksTest`): `https://`, `ssh://`,
  `git@host:` all redact userInfo; unparseable → dropped; commit/PR URLs for github/gitlab, null
  for others; a credentialed remote never leaks into a link.
- **commons unit — detection** (`EnvironmentDetectionTest`): IDE sysprop combos → expected fields;
  each agent var → expected `aiAgent`; ambient marker → no attribution; ordered first-match; IDE
  skipped under a CI marker.
- **commons golden** (`GoldenPayloadTest`): new file deserializes; v1 golden + round-trip + unknown-
  field tolerance untouched.
- **plugin unit** (`PayloadAssemblerTest`): env/vcs fields map through; `links` only when remote +
  sha present; `runAttempt` reaches `ci.attributes`; override precedence (DSL > override >
  convention); `server.token` override refused.
- **TestKit functionalTest** (existing `ciNeutralEnv()`/`withEnvironment` seam,
  [BuildHoundSettingsPluginFunctionalTest.kt:234](../../buildhound-gradle-plugin/src/functionalTest/kotlin/dev/buildhound/gradle/BuildHoundSettingsPluginFunctionalTest.kt)):
  GitLab env → `ci.provider=="gitlab"`, `mode=ci`; `CLAUDECODE` env → `environment.aiAgent`;
  `-Dbuildhound.mode=disabled` while DSL says auto → nothing written (override end-to-end);
  `uploadInBackground=true` local → spool file present, no inline attempt. CC store/reuse stays
  green. **Failure-injection** (guardrail): a throwing provider, a malformed remote URL, and a
  hostile override value never fail the build (build success + payload present).
- **server** (`ApplicationTest`, in-memory): a payload with the new provider + fields ingests and
  round-trips through GET unchanged (jsonb pass-through, no server code change).

## 6. Risks

- **CC / isolated-projects:** all detection is execution-time inside existing ValueSources (env/
  sysprop reads + one new bounded git probe) — no new config-phase file access, no new CC inputs;
  overrides use CC-safe `gradleProperty`/`environmentVariable` providers. Nothing reads the task
  graph or project model, so isolated-projects behavior is unchanged.
- **Schema compatibility:** every field is additive/null-default; `ignoreUnknownKeys` +
  `explicitNulls=false` keep old servers and existing golden bytes unaffected. Pinned by the
  untouched v1 golden and the new one.
- **Security/privacy:** (a) remote-URL redaction is all-scheme fail-closed — CCUD's http-only leak
  is *not* copied, pinned by an `ssh://user:pass@` test; (b) links are host-gated and `isHttpUrl`-
  gated so an env `javascript:` origin can't become a hyperlink; (c) `git status` paths stay
  discarded (only remote URL added, never porcelain — gap-analysis "do NOT adopt"); (d) overrides
  **exclude** `server.token` (env-provider-only, architecture §6), tested; (e) IDE/agent fields are
  coarse, PII-free strings (no session ids/usernames), distinct from the dropped `agentName`;
  (f) new string fields are bounded by `PayloadCapper` once plan 019 lands.
- **CCUD ⚠ vars unverified:** a wrong mapping degrades to a null field, never a build failure;
  mitigated by per-var doc confirmation + KDoc citation.
- **Agent false positives:** in-build ambient subtraction is weaker than daemonitor's external
  snapshot; mitigated by treating only `CLAUDECODE` as confirmed, conservative positive-match-only
  attribution, and a nullable field (a miss is silent, not wrong).
- **Override precedence:** the explicit-DSL-wins subtlety is unit-tested across all three layers.

## 7. Exit criteria

- `./gradlew build` green (new commons, plugin, functional, server tests).
- A CircleCI/GitLab/Jenkins/Travis/Bitrise/Bamboo/GoCD/Buildkite/TeamCity build ingests with the
  correct `ci.provider` and mapped fields (previously `generic`/misclassified); Azure/GHA still win
  when their markers are present.
- A build in Android Studio / IntelliJ / VS Code records `environment.ide` (+ version/`ideSync`);
  a Claude Code / Cursor / Codex / OpenCode / Gemini build records `environment.aiAgent`; a plain
  terminal build records neither.
- `vcs.remoteUrl` is present and credential-free for every scheme (incl. `ssh://`); `links` carries
  commit/PR URLs for github/gitlab; a GHA re-run's `buildUrl` carries `/attempts/N`.
- `upload { uploadInBackground = true }` (or the `-D`/env override) makes a local build spool
  instead of blocking on the upload attempt; CI is unaffected.
- Every DSL knob except `server.token` is overridable via `buildhound.<key>` / `BUILDHOUND_<KEY>`;
  a `buildhound.server.token` override is refused and tested.
- Spec §3.3/§3.4/§4 and `docs/architecture.md` (incl. decision log) updated in the same PR; the new
  v1 golden is added and the existing one is byte-identical.
