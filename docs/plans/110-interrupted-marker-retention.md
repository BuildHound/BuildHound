# 110 — Keep an interrupted build's marker until it can actually be published

## 1. Source

Non-blocking finding from the plan 109 pre-merge review (delta round). Plan 109 scoped out every
plugin change, so it was recorded there and deferred.

**Branch note (owner decision).** This plan ships on plan 109's branch
(`claude/nightly-benchmark-uploads-88d277`) rather than its own, so that PR carries a plugin change
109 declared out of scope. The consequence is stated rather than smuggled: the two review rounds
already run on 109 do not cover this work, and a further §3 round covering the plugin change is part
of this plan's exit criteria.

## 2. Diagnosis

`TelemetryFinalizerAction.reconcileStartMarkers` deletes a stale start marker whenever
`routeInterruptedBuild` returns normally:

```kotlin
runCatching {
    routeInterruptedBuild(parameters, marker)
    byId[marker.buildId]?.delete()
}
```

`routeInterruptedBuild` writes the synthesized `INTERRUPTED` payload to
`<outputDir>/interrupted/<buildId>.json`, then runs `UploadGate`. On `Decision.Skip` it logs at
`info` and **returns normally** — so the marker is deleted. The lost build's record survives locally
and is never published, including by a later build that *does* have a server configured. Nothing
drains `interrupted/`; it is a local mirror, TTL-pruned by mtime.

### 2.1 This is a deliberate change of documented intent, not a bug report

Stated plainly, because a reviewer will otherwise (correctly) argue the premise:
`routeInterruptedBuild`'s doc comment says the local write exists "so a lost build is visible even
with no server", the skip logs "kept local", and
`LostBuildFunctionalTest.a stale marker is reconciled into an INTERRUPTED build and then removed`
asserts the deletion. Under the current design the marker's job ends once the payload exists on
disk, and deleting it is correct.

This plan changes that intent for one case: a skip caused by a **missing or malformed server URL** is
a configuration gap that a later build may not have, so the marker should survive until the build can
actually be published. The doc comment, the log wording, and that test move with the change.

### 2.2 Why it matters more after plan 109

Plan 109 added a scaffolding gate to `.github/buildhound-sample-benchmark.init.gradle.kts`: for
gradle-profiler's non-measurement invocations (`:help`, `cleanup-tasks`) the ingest target is cleared
so those builds publish nothing. A scaffolding invocation is therefore a build with no server that
still runs the finalizer — and it will consume any stale marker in the workspace, spending a real
measured build's interruption record on a build that was never going to upload.

Masked in CI (a killed measured build aborts gradle-profiler, so the cell fails and the
`success()`-gated verification step never runs) but live on the hand-run path documented in
`samples/README.md`, and via the stray `~/.gradle/init.d` copy that script's own comments anticipate.

### 2.3 Accumulation is already bounded — no new cap

The spawning task asked whether retaining markers can grow without limit. It cannot, and nothing new
is needed: `MarkerReconciler` reconciles at most `MAX_RECONCILE = 20` per build, prunes anything older
than `TTL_MS` (14 days) without synthesizing it, and leaves live overflow for the next build. A marker
that is never publishable is pruned by the TTL.

## 3. Scope

**In**

- `UploadGate.Decision.Skip` gains a typed cause so callers can distinguish a configuration gap from
  a consent decision without matching on the reason string.
- `reconcileStartMarkers` deletes the marker only when the build was published **or** the skip was
  not a server-configuration gap.
- `routeInterruptedBuild` stops re-synthesizing and re-writing a mirror that already exists, so a
  retained marker costs a read plus a gate decision rather than an assemble-encode-write.
- Doc comment, skip log wording, and the affected `LostBuildFunctionalTest` case.
- Regression coverage: unit tests for the new cause, functional tests for retain-then-publish.

**Out**

- Draining `interrupted/` as an upload source. The mirror stays a local record; the marker remains
  the thing that drives publication. Changing that is a redesign of plan 033's model.
- Any change to `MarkerReconciler`'s caps or TTL (§2.3).
- Schema or server changes. No payload field moves.
- Plan 109's init script. The scaffolding gate is correct as it stands; this plan removes the
  consequence, not the cause.

## 4. Design

### 4.1 A typed skip cause

`Decision.Skip(reason)` becomes `Decision.Skip(reason, cause, retryWhenServerConfigured)`:

| Cause | Reason today | Marker |
|---|---|---|
| `TELEMETRY_DISABLED` | `telemetry disabled` | delete |
| `NO_SERVER` | `no server configured` | **keep — but only when the server gap is the sole blocker (§4.2)** |
| `SERVER_URL_NOT_HTTP` | `server url is not http(s)` | **keep — same qualification** |
| `LOCAL_UPLOADS_DISABLED` | `local uploads disabled` | delete |
| `LOCAL_OPT_IN_MISSING` | `local opt-in marker missing (~/.buildhound/optin)` | delete |

The qualification is not cosmetic: in the plugin's default configuration (offline,
`requireOptInFile = true`, no marker) the reported cause is `NO_SERVER` and the marker is
nevertheless **deleted**, because consent would have refused it too. The cause names the blocker
that was reported; `retryWhenServerConfigured` decides the marker. §4.2 is the authority.

A typed cause rather than `reason.startsWith("no server")`: the reason strings are user-facing log
text and are expected to be reworded, which would silently flip retention behaviour.

### 4.2 Only configuration gaps retain the marker

The split is deliberate, and it is a privacy decision as much as a tidiness one:

- `NO_SERVER` / `SERVER_URL_NOT_HTTP` — the machine *could not* publish. A later build with the
  server configured should. Keep.
- `LOCAL_OPT_IN_MISSING` — the user has not consented (spec §3.7). Retaining the marker would mean
  that if they later create `~/.buildhound/optin`, a build recorded **before** consent gets
  published. Delete, exactly as today.
- `LOCAL_UPLOADS_DISABLED` / `TELEMETRY_DISABLED` — a standing choice not to publish. Delete.

So the change never widens *what* leaves the machine or *where* it goes; it defers publication of
builds whose own configuration permitted it.

Stated precisely, because the loose version ("builds that were always eligible") overclaims:
eligibility is re-derived from the **current** build's configuration on every reconcile pass, not
recorded at crash time — `UploadGate.decide` is called with `marker.mode` but with the live
`localBuildsEnabled` / `requireOptInFile` / `optInFileExists`. So a developer who crashes offline
*without* consent, then creates `~/.buildhound/optin`, then configures a server, does publish a
build recorded before they consented. That ordering behaves identically before and after this plan
(and this plan strictly narrows the retained set), and spec §3.9 states the consent gate as an
upload-time condition with no record-time rule — so it is out of scope here rather than introduced
here. Recording it because a reader of §4.2 would otherwise take the stronger guarantee.

**The cause alone is not enough to express that, and the first cut of this plan got it wrong (review
finding B1, fixed before merge).** `UploadGate.decide` reports the *first* blocker, and the server
check runs before the mode/consent rules — so in the plugin's **default** configuration (offline,
`requireOptInFile = true`, no opt-in marker) a `LOCAL` build is blocked by the missing server *and*
by consent, and was reported as `NO_SERVER`. Keying retention on the cause therefore retained exactly
the builds this section promises to drop, and a developer who later configured a server and created
the opt-in marker published builds recorded before they consented. Reproduced end-to-end before
fixing: the stub ingest server received the pre-consent payload.

The decision therefore carries `Skip.retryWhenServerConfigured`, true only when a server gap was the
**sole** blocker — `decide` evaluates the mode rules regardless of server presence and reports the
first blocker while answering the retry question honestly. Which blocker is *named* stays as it was,
so an offline user is still told "no server configured" rather than being sent to look for an opt-in
file they also need.

### 4.3 Not paying synthesis twice

A retained marker is re-visited by every subsequent build. `routeInterruptedBuild` currently
assembles the payload, encodes it, and writes the mirror before consulting the gate. It now checks
for `interrupted/<buildId>.json` first and, when present, decodes that instead — `prettyJson` is
`Json(from = BuildHoundJson.payload) { prettyPrint = true }`, and `prettyPrint` affects encoding
only, so the mirror decodes with the wire configuration and re-encodes to the compact wire form.

Per-build cost for a retained marker therefore drops to an existence check, a read, and a pure gate
call — no assemble, no encode-and-write. This matters because the plugin's **default** is no server
(`server { }` unset ⇒ offline), so the retention path is the common configuration, and it sits on the
always-on finalizer path that `docs/architecture.md` §2 rule 14 budgets.

If the mirror is missing (pruned by its own mtime TTL while the marker survived), it is synthesized
as before.

### 4.4 Log wording

The skip currently logs `interrupted build {} kept local: {}`. When the marker is retained that is
misleading — it will be retried. Retained skips say so; deleted skips keep the existing wording.

## 5. Test strategy

- `UploadGateTest` — a case per cause, asserting the cause and not just `Skip`.
- `LostBuildFunctionalTest`:
  - the existing "reconciled … and then removed" case is rewritten to the new contract: with no
    server the marker is **retained** and the mirror is written;
  - a new case: seed a marker, run with no server (marker retained), then run again against a stub
    HTTP server and assert the interrupted payload is uploaded and the marker is then deleted —
    the retain-then-publish path this plan exists for;
  - a new case: a `LOCAL` build with `requireOptInFile = true` and no opt-in marker still deletes,
    proving the consent path did not change;
  - a new case: the mirror is not rewritten on a second visit, covering §4.3.
- No golden-file or schema work: nothing serialized changes.
- Configuration-cache safety is unchanged — this is all inside the Flow action, and
  `LostBuildFunctionalTest.marker IO does not invalidate the configuration cache` already guards it.

## 6. Exit criteria

1. A stale marker skipped for `NO_SERVER` survives the build, and a later build with a server
   uploads it and then deletes it.
2. A stale marker skipped for a consent/standing-choice cause is deleted exactly as before.
3. A retained marker costs no re-synthesis on later builds (§4.3), evidenced by a test.
4. `UploadGate` callers no longer need the reason string to make a decision.
5. `docs/architecture.md` decision log records the intent change from §2.1.
6. A §3 review round covers the plugin change (§1 branch note) — the earlier 109 rounds do not.

## 7. Risks

- **Overhead on the offline default.** The common configuration now carries up to 20 marker visits
  per build for up to 14 days. Mitigated by §4.3 (read, not re-synthesize) and bounded by the
  existing caps. If the overhead harness shows this on the finalizer axis, the fallback is to retain
  only for non-`LOCAL` modes, where a server is expected.
- **A changed contract.** §2.1. Anyone reading `interrupted/` as "everything that was lost" is
  mostly unaffected — the mirror is still written the first time a marker is *visited*, and only the
  marker's lifetime changes. "Visited" is the caveat, not "created": see the `MAX_RECONCILE` bullet
  below, which is the one case where a marker can be pruned before it is ever mirrored.
- **Privacy.** No new data, no new destination, and §4.2 keeps consent-gated builds unpublishable.
  The only behavioural change is *when* an already-eligible build is uploaded.
- **A retained marker for a build that will never publish** lingers up to the TTL. That is the
  existing bound for every marker, not a new class of garbage.
- **Above `MAX_RECONCILE` live markers, the newest are no longer mirrored on their first visit**
  (review finding N1). `MarkerReconciler.plan` takes the oldest 20 of the live set; before this plan
  every visited marker was deleted, so the window advanced by 20 each build, and the class comment
  says as much ("each build clears up to `max` and adds none of its own, so the dir converges").
  Retained markers now re-occupy their slots, so a burst of more than 20 crashes between builds can
  leave the overflow un-mirrored until the TTL prunes it. Accepted, not fixed: the casualty is a
  local `interrupted/*.json` that nothing reads, the marker itself stays in `started/` for the full
  TTL, and publication is unaffected — once a server exists, uploads free 20 slots per build and the
  backlog drains. Raising the cap or visiting every live marker would remove the bound the class
  exists to provide, on the always-on finalizer path. The comment and this plan now say so instead of
  claiming convergence.
- **PR scope.** Plan 109's PR now mixes a CI-pipeline repair with a plugin behaviour change (§1). If
  that proves hard to review as one unit, the split is mechanical — these commits touch no file that
  109 touches.
