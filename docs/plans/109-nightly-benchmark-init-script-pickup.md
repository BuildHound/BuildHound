# 109 — Nightly benchmark publishes nothing: init-script pickup repair

## 1. Source

Defect in the shipped implementation of [105](105-composite-action-ci-and-nightly-sample-benchmark.md)
(composite action in CI + nightly sample benchmark), reported by the owner 2026-08-14:

> currently nightly benchmarks have run 4 times but none have been uploaded to the production or
> staging dashboards.

Plan 105's exit criterion 6 — one `workflow_dispatch` run, and production `#/benchmark` showing
rows for each `(pilot, scenario, isolation)` under a single seed ref — is why that plan is still
open in `docs/plans/`. Four scheduled runs have now had the chance to satisfy it and could not:
the defect below makes it unsatisfiable. This plan removes the defect and the reason four nights of
it went unnoticed; 105 then closes on its own terms.

## 2. Diagnosis (empirical, not inferred)

`.github/workflows/nightly-benchmark.yml` installs the redirect init script into the *runner's*
Gradle user home:

```yaml
mkdir -p "$HOME/.gradle/init.d"
cp .github/buildhound-sample-benchmark.init.gradle.kts "$HOME/.gradle/init.d/"
```

`gradle-profiler` does not use `$HOME/.gradle`. It defaults to a dedicated Gradle user home under
the working directory, so that a benchmark starts from a known state. From
`profiler-out/*/profile.log` of run `31770996903` (2026-08-14, and identically in the three runs
before it):

```
Gradle User Home: /home/runner/_work/BuildHound/BuildHound/gradle-user-home
```

`$HOME` on the runner is `/home/runner`, so `~/.gradle/init.d/*.kts` was never on any measured
build's init-script path. Gradle reads `init.d` only from the Gradle user home actually in use.

The consequences are all visible in the same log:

- the script's own `lifecycle` marker — `sample benchmark init: telemetry redirected to the
  BUILDHOUND_SAMPLE_* ingest target` — appears **nowhere**, in any job, on any night;
- every measured build therefore kept the sample's committed local-development target
  (`server.url = "http://localhost:8080"`, `samples/springboot-legacy/settings.gradle.kts`);
- the plugin armed the upload (a non-empty URL is all `UploadGate` keys on), POSTed to a port
  nothing listens on, and degraded exactly as designed:

```
[buildhound] build SUCCESS: 323 task(s) {...}, 0 test(s), mode=BENCHMARK, cc=DISABLED, hitRate=1.00
[buildhound] payload written: .../build/buildhound/build-payload.json (buildId=...)
[buildhound] upload failed; payload spooled to .../build/buildhound/spool (retried on the next build)
```

`mode=BENCHMARK` is the useful half of that evidence: the `BUILDHOUND_BENCHMARK_*` env does reach
the measured daemon and benchmark activation works. Nothing about the plan's benchmark design is
wrong. One line of file-path arithmetic is. Each night produced ~20 correctly-labelled payloads,
spooled them into a workspace that the runner then destroyed.

Two things that are **not** the cause, checked and excluded:

- **Ingest origin.** The nightly reads `vars.BUILDHOUND_PROD_SERVER_URL` /
  `secrets.BUILDHOUND_PROD_INGEST_TOKEN` — byte-identical to the wiring of the `ci.yml` dogfood
  step (plan 093/094), whose payloads do land. The job log confirms both were populated
  (`BUILDHOUND_SAMPLE_SERVER_URL: https://dashboard.buildhound.dev`, token masked).
- **Job failures.** 18 of 20 cells succeeded on 2026-08-14 and produced complete benchmark results.
  The two failures (`android-legacy-agp · cc_hit · no_build_cache`, `nowinandroid · clean ·
  full_cache`) are a separate matter, out of scope here (§2.1).
- **A pilot-specific fault.** All three pilots were checked, not just the cheap one:
  `nowinandroid · no_op · full_cache` (4 builds, `cc=MISS_STORED`) and `android-legacy-agp · no_op ·
  full_cache` (5 builds, including `cc=HIT` builds) show the identical signature — plugin applied,
  `mode=BENCHMARK`, payload written, `upload failed; payload spooled`, no redirect marker. One cause
  across the whole matrix, so the repair covers all 20 cells rather than the one that was easy to
  read.

**Staging was never a target.** The report that prompted this plan mentions "production or staging".
Plan 105 §3.2 wires this workflow to `vars.BUILDHOUND_PROD_SERVER_URL` /
`secrets.BUILDHOUND_PROD_INGEST_TOKEN` only, deliberately — the absence of benchmark rows on staging
is that design, not this defect. Publishing the series to staging as well would be a separate wiring
change and is not in scope here.

### 2.1 Why four nights of this were invisible

Plan 105 §5 named "silent non-publication" as a risk and mitigated it with the `Report ingest
wiring` step. That step checks whether the env vars are *set*. They were set — correctly — on all
four nights, so it printed `✅ ingest target configured — benchmark builds publish to production`
into the job summary while the job published nothing. The mitigation was placed upstream of the
failure it existed to catch.

`SampleBenchmarkInitScriptFunctionalTest` has the mirrored gap: it applies the real script with
`-I <abs path>` (`SampleBenchmarkInitScriptFunctionalTest.kt:113`). That is the right way to test
what the script *does*, and it is structurally incapable of testing whether the workflow *delivers*
the script to the builds it means to affect. Between them, the two checks covered both sides of the
mechanism and neither covered the join.

## 3. Scope

**In**

- `.github/workflows/nightly-benchmark.yml`:
  - an explicit `--gradle-user-home` for the profiler invocation, with the init script installed
    into *that* home's `init.d/`;
  - a post-run publication check that reads `profile.log` and fails the cell when the measured
    builds did not upload.
- `.github/buildhound-sample-benchmark.init.gradle.kts`: header comment says `-I`, which is no
  longer how any caller applies it. Correct it to the mechanism in use.
- `docs/recipes/benchmark-and-experiments.md`: documents the `~/.gradle/init.d/` install as the
  mechanism; it has to describe the real one.

**Out**

- `nowinandroid · clean · full_cache`, which failed with a real
  `org.gradle.tooling.BuildException` from the measured build — independent of publication, its own
  plan.
- `android-legacy-agp · cc_hit · no_build_cache` was originally deferred with it, wrongly (review
  finding): it died 18s in at `curl: (22) The requested URL returned error: 429` inside
  `Install gradle-profiler`, before any Gradle build ran. Nothing about it depended on a publishing
  nightly. The download had no retry, so the very `workflow_dispatch` that is supposed to verify
  this plan could lose cells the same way — a bounded `--retry` is therefore **in** scope (§4.4).
  The identical un-retried download in `ci.yml`'s `overhead-budget` job is out of scope here.
- Draining the spooled payloads from the four dead nights. The workspaces are gone; those builds
  are unrecoverable. The series starts at the first fixed run.
- The shipped consumer template `buildhound-ci-assets/profiler-pipeline/github-nightly-benchmark.yml`.
  Checked, and it does **not** carry this bug: it has no init script at all, wiring the pilot through
  the plugin's plan-027 convention-fallback env vars (`BUILDHOUND_SERVER_URL`/`BUILDHOUND_TOKEN`)
  directly, which the daemon reads without any init-script pickup. Giving it the §4.2 publication
  check would be a genuine improvement for adopters, but it is a new feature for a shipped asset
  rather than repair of this defect — noted as a follow-up, not done here.
- `samples/README.md`'s manual invocation (`./gradlew -I ../../.github/buildhound-sample-benchmark.init.gradle.kts`).
  `-I` is correct for a hand-run `./gradlew`; only the profiler path needs the Gradle-user-home
  install.
- Making the nightly blocking. It stays `continue-on-error` (plan 105 §3.2, roadmap guardrail).
- Any plugin, schema, or server change. Nothing in `buildhound-gradle-plugin` or
  `buildhound-server` misbehaved.

## 4. Design

### 4.1 An explicit Gradle user home the init script is installed into

```yaml
env:
  BENCHMARK_GRADLE_USER_HOME: ${{ github.workspace }}/benchmark-gradle-home
```

- install step: `mkdir -p "$BENCHMARK_GRADLE_USER_HOME/init.d"` + copy;
- profiler step: `--gradle-user-home "$BENCHMARK_GRADLE_USER_HOME"`.

Explicit rather than "write into gradle-profiler's default and hope": the default location is an
implementation detail of the profiler version, and the failure mode of guessing it wrong is
precisely the silent one this plan is fixing. The flag is already this repository's practice —
`buildhound-ci-assets/overhead/run-overhead.sh:93` passes `--gradle-user-home "$out/guh-$variant"`
for the same reason (isolation the caller can name and inspect).

The directory sits outside `profiler-out/`, which is uploaded wholesale as an artifact
(`path: profiler-out/**`); a Gradle user home inside it would put a downloaded Gradle distribution
and a build cache into every artifact.

The cold-home-per-job property the workflow's header comment relies on is preserved: a fresh
runner, a fresh checkout, and a directory created by the job itself.

`$HOME/.gradle/init.d` is *not* also written. One install location, and it is the one that is read.

### 4.2 Publication check keyed on the POST, not on the env

A new step after the benchmark, reading the profiler's own build log:

```
profiler-out/$PILOT-$ISOLATION-$SCENARIO/profile.log
```

Assertion, in order:

1. the log exists (a profiler that never got to a build is the `Run benchmark` step's failure, not
   this step's);
2. it contains `[buildhound] payload uploaded` at least once — `PayloadUploader.kt:65`, logged at
   `lifecycle`, so it is in `profile.log`;
3. it contains no `[buildhound] upload failed` line.

Failing that, the step emits `::error` and exits non-zero. Inside a `continue-on-error: true` job
that keeps the run green (deliberate, §3 Out) while the existing `Flag failed cell` step —
`if: failure()` still evaluates in a `continue-on-error` job — annotates the run page and the job
summary with the cell.

A positive assertion, not merely the absence of a failure string: a build that silently skipped the
upload (`UploadGate` "no server configured", logged at `info` and thus invisible) prints neither
line, and must be caught. This is the check that would have turned all four dead nights red on the
first one, and it is downstream of every layer that was quiet — the gate's skip, the plugin's
never-fail posture, and the job's `continue-on-error`.

`Report ingest wiring` stays. It is still the right early signal for a missing credential — it is
just no longer the *only* signal, and it no longer claims that publication happened.

### 4.3 Warm-up builds count

`warm-ups = 1` per scenario, and warm-up builds upload too (plan 105 §3.3, accepted). So a passing
cell publishes `warm-ups + iterations` payloads, plus one for gradle-profiler's own build-inspection
invocation — 5, in the archived `springboot-legacy · no_op` log. The check asserts ≥1
`payload uploaded`, not an exact count: `iterations` differs per scenario file and pinning the
arithmetic in a shell step would make an unrelated scenario edit fail the nightly for the wrong
reason.

The redirect marker is deliberately *not* a per-build assertion, only a diagnostic printed when the
upload check has already failed. `settingsEvaluated` does not run on a configuration-cache hit, so
the marker is emitted on CC-miss builds only — visible in the archived `android-legacy-agp` log,
which has `cc=HIT` builds among its five. Those hits still upload, because the CC entry stored on
the cold-home first build carries the redirected Flow-action parameters. A future "improvement"
requiring one marker per upload would redden every `cc_hit` cell; the comment in the step says so.

### 4.4 What the pre-merge review changed

The §3 reviews (4 clean-context reviewers, 2 adversarial verifiers per finding, completeness critic)
produced four repairs to §4.2's check and one to the profiler download. Recorded here because each
is a hole this plan's own reasoning left:

- **Server rejections were uncounted.** `PayloadUploader` has three terminal states, not two:
  `Rejected` (a 4xx) logs `[buildhound] server rejected …` and **deletes** the payload rather than
  spooling it. The check counted `payload uploaded` and `upload failed` only, so a cell that
  uploaded four and had one rejected exited 0 with a green summary line over a permanent loss. The
  gate was asymmetric in exactly the wrong direction — red on the recoverable case, green on the
  unrecoverable one.
- **A POST is necessary but not sufficient.** The server files a row under `#/benchmark` only when
  the payload is labelled `mode=BENCHMARK` with a benchmark block; `BenchmarkValueSource` drops to CI
  mode and logs `benchmark mode not activated` at `warn` when a `BUILDHOUND_BENCHMARK_*` value falls
  outside its allowlist — which a matrix or scenario rename does. That is the same user-visible
  outcome this plan exists to prevent (green cell, empty dashboard) reachable by a different route,
  so the check now also asserts the label.
- **The failure message contradicted its own counters**, asserting "published nothing" for a cell
  that published four payloads and spooled a fifth. Each condition now reports what it observed.
- **A credentials-absent run was blamed on the init script.** With `BUILDHOUND_SAMPLE_*` absent the
  script *does* apply and takes its documented DISABLED branch, printing no redirect marker — which
  looked identical to "never applied". The diagnostic now names both causes.
- **The profiler download is retried** (§3 Out, the 429).

## 5. Test strategy

- `actionlint` (existing CI step) covers the edited workflow.
- `CiAssetsContractTest` — check whether it pins anything in the edited template; if it does, the
  assertion moves with the fix.
- `SampleBenchmarkInitScriptFunctionalTest` is unchanged. Its `-I` application is correct for what
  it tests (§2.1); the pickup regression is now guarded in the workflow, which is where pickup
  lives.
- **The fix is not proven by CI.** As plan 105 §4 already stated and this defect demonstrates, green
  jobs are not evidence. Verification is a `workflow_dispatch` run after merge to `main`, then
  reading production `#/benchmark` for rows under that run's `seedRef` with the expected
  `(scenario, isolationMode)` pairs. The publication check in §4.2 makes the *next* regression of
  this kind loud, but it does not substitute for looking at the dashboard once.

## 6. Exit criteria

1. The nightly workflow passes an explicit `--gradle-user-home` and installs the init script into
   that home's `init.d/`.
2. A dispatched run's `profile.log` contains `[buildhound] sample benchmark init: telemetry
   redirected` and `[buildhound] payload uploaded`, and no `[buildhound] upload failed`.
3. The publication check fails the cell (and annotates the run) when uploads do not happen —
   confirmed by the check's own logic against the archived `profile.log` of run `31770996903`,
   which must be rejected by it.
4. Production `#/benchmark` shows rows for the dispatched run's seed ref, across the pilots whose
   cells passed — which is plan 105's exit criterion 6, and closes that plan too.
5. The init script's header comment and `docs/recipes/benchmark-and-experiments.md` describe the
   mechanism the workflow actually uses.

## 7. Risks

- **The fix works and the dashboard still shows nothing.** Then the next suspect is the ingest
  target or the server's benchmark-row filtering, and the `payload uploaded` line from §4.2
  separates the two halves for the first time: uploaded-but-absent is a server question,
  not-uploaded is a plugin/config question. Previously indistinguishable.
- **A cell fails before the benchmark step** — the publication check must not mask the real error.
  It runs only after a successful `Run benchmark` step (default `success()` condition), so a
  profiler crash surfaces as itself.
- **Cost is unchanged.** Same matrix, same 20 jobs; the explicit Gradle user home is the same cold
  home the profiler was already creating under a different name.
- **Security/privacy: unchanged.** Same credential, same env-only flow, same repo-secret exposure
  accepted in plan 094 §5 and plan 105 §5. The init script's token gating (URL disabled unless the
  token is present) is untouched and is what keeps a half-configured runner from POSTing
  unauthenticated. `profile.log` is grepped for plugin log lines only; no credential is read,
  echoed, or written to the summary.
- **A `profile.log` format change in a future gradle-profiler bump** would break the check. It
  breaks *loudly* (missing `payload uploaded` → red cell), which is the correct direction for a
  check whose whole purpose is to fail closed.
