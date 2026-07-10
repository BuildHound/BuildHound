# Plan 045 — task type/cacheable dictionary lost in composite builds

**Status: closed** · 2026-07-08 — superseded/closed by
[056](056-composite-build-logic-dictionary-priority.md), which un-deferred this plan (research
finding F6: the gap hits the classpath-applied `build-logic` composite path, not just
`includeBuild`) and implemented its option (b), refined to ride a Flow-action parameter
(`TelemetryFinalizerAction.Parameters.taskMetadata`, the plan-046 channel) rather than a sidecar.
All exit criteria in §4 below are met. Original deferred status/content preserved below for history.

**Status (original): deferred** · 2026-07-06

## 1. Source

Follow-up split from plan 044. Same root cause: in a composite (`includeBuild`) build the
collector `BuildService` is instantiated by included-build task-finish events **before** the root
`taskGraph.whenReady` fills the plan-016 mailbox, freezing the service param empty. Plan 044 fixed
the finalizer-side consumer (test locations, via a durable sidecar file). This plan covers the
*other* consumer of the same frozen param: the task **type/cacheable dictionary**
(`TaskEventCollector.Params.taskMetadata`, plan 016), which is read on the collector's `onFinish`
path and therefore left null for every task in a composite build.

## 2. Impact (why deferred, not urgent)

Cosmetic + composite-only. In a composite build every `tasks[].type` / `cacheable` /
`nonCacheableReason` is null and `derived.cacheableHitRate` is null (the same shape as the
isolated-projects degradation, architecture §2 rule 13). It affects only the dev harness and any
consumer who applies the plugin via `includeBuild`; the **classpath path (published plugin, the
real adoption path) is unaffected** — the service instantiates after `whenReady` there. No data is
wrong, only absent (honest-null, plan 005). Test telemetry — the load-bearing signal — is already
restored by plan 044.

## 3. Why it is not a sidecar (the hard part)

Test locations are read **once**, by the finalizer at build end — a file read fits. The task
dictionary is read **per task-finish event** on a hot path (`onFinish`), so a per-event file read
is the wrong shape, and the first `onFinish` (an included-build task) fires *before* `whenReady`,
so a read-once-and-cache latches empty. Options to weigh when picked up:

- **(a)** Load the sidecar once, lazily, but only after the first *root-build* task finishes
  (skip included-build events by build path) — needs a cheap "is this the root build?" test on the
  event descriptor.
- **(b)** Defer the type/cacheable join to the finalizer: `onFinish` records only raw
  `TaskExecution`, and the finalizer enriches from the sidecar dictionary (moves the join off the
  hot path entirely; larger diff to `PayloadAssembler`/collector).
- **(c)** Accept the gap permanently and document it as a composite-build limitation (cheapest;
  the classpath path is the one that ships).

## 4. Exit criteria (when un-deferred)

- A composite functional test asserts non-null `type` on an executed task (extend
  `CompositeBuildTestCollectionFunctionalTest`).
- No regression on the classpath path or the `onFinish` hot-path overhead budget (plan 034).
- Architecture §2 rule 12 composite-build caveat updated to note the dictionary is fixed.
