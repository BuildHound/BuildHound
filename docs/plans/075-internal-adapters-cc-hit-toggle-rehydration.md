# 075 — internal-adapters: re-establish toggle intent on a configuration-cache hit

## 1. Source

§3.2 security/privacy review of plan 074 (folding internal-adapters behind the central
`buildhound { internalAdapters { } }` block). The review confirmed the consent model holds on the
configuration-cache **miss** path, but found one **MEDIUM** consent-model gap on the CC-**hit** path
that plan 074 accepted-with-note and deferred here. Touches spec §3.1 and the architecture decision log.

## 2. The problem

The internal-adapters toggles (`collectCacheOrigins`, `collectDeprecations`, `collectLogWarnings`) are
read at **configuration time** — core's `taskGraph.whenReady` calls `InternalAdaptersWiring.install(...)`,
which calls `InternalAdaptersState.configure(...)` to set the **daemon-static** toggles and (when a
toggle is on) registers a **daemon-scoped** build-operation / logging listener that persists for the
JVM's life (this persistence is *how* capture legitimately survives a CC hit — plan 044).

On a configuration-cache **hit**, `whenReady` does not run, so `configure()` does not run and the
daemon-static toggles keep the **previous build's** values. Reachable sequence in one warm daemon:

1. `./gradlew help` (all toggles off) → CC miss → all-off entry stored, no listener.
2. `./gradlew help -Pbuildhound.internalAdapters.collectLogWarnings=true` → CC miss (the tracked
   property changes the CC key) → listener registered, toggle=true.
3. `./gradlew help` (all off) → **CC HIT** on step 1's entry → `whenReady` skipped → toggle stays true
   → the still-registered listener captures WARN lines → `extensions.internalAdapters` is emitted for a
   build whose own configuration consented to nothing.

Captured text is still scrubbed (no raw secret/PII leak), so this is a **consent-model** edge, not a
data-exposure bug. It pre-existed for the warning catchers (plan 044's daemon-static toggles) and plan
074 **widened** it: bundling means the victim build only needs `dev.buildhound` applied (not a second
plugin), and it now also covers the new `collectCacheOrigins`.

Pinned by the `@Disabled` functional test
`InternalAdaptersCaptureFunctionalTest.an all-off CC-hit build after a toggle-on build in the same
daemon must not capture (plan 075)` — this plan's acceptance criterion (remove `@Disabled`, it passes).

## 3. Why it is not a one-line fix

The reset must run **before the first build operation** on a CC-hit build, and a settings plugin has no
configuration-time hook that runs on a CC hit (the whole configuration is replayed from cache). So:

- Resetting toggles in the Flow finalizer (build end) or gating `whenReady` differently cannot help — a
  finalizer reset would also break the legitimate "capture survives a CC hit with the *same* toggle
  state" behavior (plan 044), which a functional test asserts.
- The fix must carry the current build's toggle **intent** through CC-surviving state and re-apply it at
  **execution time**, before any build op the listener cares about.

## 4. Candidate design (to be validated)

Bake the three capture toggles into a CC-tracked provider on the existing `TaskEventCollector`
`BuildService` parameters (they are plain booleans, CC-safe, and already resolved at config time on a
miss / replayed on a hit). On **every** build — including a CC hit — have the service, at
instantiation, call `InternalAdaptersState.configure(...)`-equivalent to set the daemon-static toggles
to *this* build's intent before the listener acts. Open questions the plan must resolve before coding:

- **Timing:** is the `TaskEventCollector` service instantiated before the first `SnapshotTaskInputs` /
  cache build-op fires? If not, the earliest ops on a hit would still see the stale toggle — quantify
  and decide whether that residual is acceptable or needs a different execution-time seam.
- **Config-phase WARN lines:** on a CC hit there is no configuration phase, so config-phase WARN capture
  is moot for hits — confirm.
- **CC-safety:** adding a service parameter must not change the CC key in a way that defeats reuse; the
  plugin's functional CC-reuse assertions must stay green.

## 5. Test strategy

- Un-`@Disable` the pinned scenario test and make it pass (the acceptance criterion).
- Keep `capture survives a configuration-cache store then reuse (same toggle state)` green — the fix
  must not break legitimate same-state CC-hit capture.
- A CC-reuse assertion proving the new service parameter adds no CC input that defeats reuse.

## 6. Exit criteria

`./gradlew build` green with the previously-`@Disabled` scenario test enabled and passing; the
same-toggle-state CC-hit capture test still green. Spec §3.1 / architecture §7 updated to record the
gap as closed. Two clean-context reviews (kotlin-gradle + mandatory §3.2) pass or findings are addressed.
