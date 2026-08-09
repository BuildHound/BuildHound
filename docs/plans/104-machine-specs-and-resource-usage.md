# 104 — Machine hardware specs and build resource usage in the report

## 1. Source

Feature request (owner, 2026-08-09): *"When creating the build report also include the hardware
specs of the machine the build was executed on — number of CPUs / memory / disk (SSD/NVMe) — and if
possible the CPU/memory usage during the build. No additional permissions; best-effort only. The
biggest must-have: it must not impact build performance whatsoever."* Follow-up: *"if some data can
only be collected via dedicated CI integrations, also add it to those in `buildhound-ci-assets`."*

Anchors: spec §3.2 (EnvironmentCollector — OS/arch/cores/RAM), §3.6 (process snapshot), §3.7
(pseudonymization), §3.8 (standalone HTML report), `docs/overhead-budget.md` (the measured
"never slow the build noticeably" guardrail).

## 2. Scope

The work is tiered by **perf risk, ascending**. Every tier is independently shippable and every
field degrades to `null`, never to a fabricated zero.

**In scope**

- **Tier 0 — render what is already collected (zero collection cost).** `environment.os`,
  `arch`, `cores`, `ramMb` already reach the payload and the report renders *none* of them. A pure
  rendering change delivers the "number of CPUs / memory" ask with no new probe at all.
- **Tier 1 — new static machine facts, in-JVM only, no subprocess.** Disk capacity/free for the
  build root's filesystem via `java.nio.file.Files.getFileStore` (pure NIO), plus a best-effort
  disk-media classification (`NVME | SSD | ROTATIONAL | NETWORK | UNKNOWN`).
- **Tier 2 — build resource usage from MXBeans (two point samples, no thread).** Daemon process CPU
  time consumed over the execution window, system CPU load, system load average, and system memory
  used — all read from `com.sun.management.OperatingSystemMXBean` at two instants that the plugin
  already owns.
- **Tier 3 — per-process CPU, at a net *reduction* in subprocess count.** The plan-029 probe already
  spawns `ps` **twice** per probed PID (`-o rss=`, `-o etime=`). Merge them into one
  `ps -o rss=,etime=,time= -p <pid>` and gain cumulative per-process CPU time — 2 execs → 1 exec per
  PID, so the highest-value usage metric arrives while making the probe *cheaper*.
- **Tier 4 — CI runner class, categorical only.** Allowlisted categorical env reads in the built-in
  CI providers, plus the genuinely CI-only datum (the runner label / VM image the job requested,
  which is not in any env var) exported by `buildhound-ci-assets` GitHub/Azure/GitLab assets.
- Report rendering for all of the above; commons golden files; server needs no change (payloads are
  stored whole — additive fields ride for free).

**Explicitly out of scope**

- **Any sampling thread or periodic poller.** A background sampler is the only way to get a true
  *peak* or a usage *timeline*, and it is exactly what the must-have forbids. v1 ships two-point
  deltas and point samples, and labels them as such. Deferred to a follow-up plan.
- **`MemoryPoolMXBean.resetPeakUsage()`** — mutates JVM-global state inside a long-lived shared
  daemon that other tooling also observes. Nothing in this plan is called "peak".
- **Disk media detection outside Linux.** macOS needs a `diskutil` subprocess and Windows needs
  WMI/PowerShell; both cost more than the datum is worth on the always-on path. Both report
  `UNKNOWN`. Even on Linux the sysfs mapping is best-effort: LVM, dm-crypt, btrfs subvolumes and
  container overlayfs commonly have no resolvable block device — `UNKNOWN` is the honest default,
  not a guess. Disk *capacity* (NIO) works everywhere and is the reliable part.
- **CPU model / brand string.** Needs a subprocess on macOS and introduces new free-form text into
  the privacy surface. Deferred.
- **Free-text CI runner fields** — `CI_RUNNER_DESCRIPTION`, `CI_RUNNER_TAGS`,
  `AGENT_MACHINENAME`, self-hosted `RUNNER_NAME`: operator-set text that routinely carries
  hostnames. Never collected by this plan.
- **DESIGN-V2 runtime adoption for the report.** `report-template.html` is still on its original
  pre-V2 styling (as are the existing Tests/Processes/Artifacts sections). CLAUDE.md states runtime
  V2 adoption *requires a separate implementation plan*; this plan therefore adds its section in the
  template's existing idiom (`.chips`, `table`, `td.num`, `.muted`) rather than half-converting one
  section. No new `<link>`/`<img>`/`url()`/`@import`/webfont/relative asset reference is introduced,
  and `ReportAssetsTest` is not weakened. Flagged here so review sees it as a deliberate,
  reconciled divergence and not an oversight.
- Dashboard and `buildhound-mcp` surfaces.

## 3. Design

### 3.1 Schema (commons — additive only)

New nested block on `EnvironmentInfo` (all fields nullable, all defaulted):

```kotlin
@Serializable
data class MachineInfo(
    val diskTotalMb: Long? = null,
    val diskFreeMb: Long? = null,
    val diskMedia: DiskMedia? = null,
)

@Serializable
enum class DiskMedia { NVME, SSD, ROTATIONAL, NETWORK, UNKNOWN }
```

`cores`/`ramMb`/`os`/`arch` stay where they are — no duplication. `MachineInfo` carries only what is
new, and hangs off `EnvironmentInfo.machine`.

New top-level block on `BuildPayload`:

```kotlin
@Serializable
data class ResourceUsageInfo(
    /** Wall length of the measurement window (execution-phase anchor → finalizer). */
    val windowMs: Long? = null,
    /** Gradle-daemon process CPU time consumed *within* [windowMs]. */
    val daemonCpuMs: Long? = null,
    /** Point sample at finalizer: system-wide CPU load, 0..100. Null when unavailable/NaN. */
    val systemCpuLoadPct: Int? = null,
    /** Point sample: OS load average. Null when the platform returns a negative (Windows). */
    val systemLoadAverage: Double? = null,
    val systemMemTotalMb: Long? = null,
    val systemMemFreeMb: Long? = null,
)
```

And one field on the existing `ProcessInfo`:

```kotlin
/** Cumulative process CPU time (ps `time=`), lifetime-to-date — not window-scoped. */
val cpuTimeMs: Long? = null,
```

Derivation (`DerivedMetricsCalculator` in commons, so it is unit-tested once and shared by the report
and any later dashboard): `daemonCpuUtilizationPct = daemonCpuMs / (windowMs * cores) * 100`, null
unless all three inputs are present and `windowMs > 0`. Clamped for reporting, never fabricated.

Golden files (added, never edited — CLAUDE.md): `build-payload-v1-machine.json` covering
`environment.machine` + `resourceUsage` + `processes[].cpuTimeMs` + the new `ci.attributes` keys.

### 3.2 Tier 1 collection — `EnvironmentValueSource`

Extends the existing execution-time value source (already CC-safe, already has the per-probe
`guarded()` degradation and the class-name-only logging rule). Two additions, both behind `guarded`:

- `Files.getFileStore(Path.of(buildRoot))` → `totalSpace` / `usableSpace`, converted to MB.
- `DiskMediaDetection.classify(...)` — a **pure** function over `(osName, fileStoreName,
  fileStoreType, sysfsReader)` so every branch is unit-testable with no real filesystem:
  1. `fileStoreType` in the network set (`nfs`, `nfs4`, `cifs`, `smbfs`, `fuse.sshfs`, `9p`) → `NETWORK`
  2. non-Linux → `UNKNOWN` (stated, not guessed)
  3. device name starts with `nvme` → `NVME`
  4. `/sys/block/<dev>/queue/rotational` (device name, then digit-trimmed base) → `0` = `SSD`,
     `1` = `ROTATIONAL`
  5. otherwise → `UNKNOWN`

**Privacy:** the FileStore's `name()` is a device path (`/dev/nvme0n1p2`, `/dev/mapper/vg-root`) and
its `type()` is a filesystem identifier. Both are read **only** as classifier input and are
**discarded** — the extraction returns an enum, so, exactly like the plan-065 jinfo allowlist reads,
there is no string to scrub. Only the enum and two rounded MB counts ship.

### 3.3 Tier 2 collection — two point samples, zero added machinery

The anchor already exists. `TaskEventCollector.init {}` calls `DaemonState.executionStarted()` — "the
first plugin-controlled instant of execution, which on a CC **hit** is the moment right after the CC
entry is deserialized". `DaemonState` gains a CPU/wall baseline stamped in that same call and
read+reset in `executionRan()`, alongside `executionStartedMs`.

- Baseline: `System.nanoTime()` + `OperatingSystemMXBean.processCpuTime` (one JNI read each).
- End sample: taken in the finalizer, where the plugin already runs.
- `windowMs` = monotonic nanos delta; `daemonCpuMs` = CPU-nanos delta.

**Why this placement is the CC-correct one:** the baseline lives in the build service's *runtime*
state, which is recreated per build and therefore fresh on a CC hit. It is never a value-source
parameter and never a config-time read — `StartMarker`'s own doc records why that distinction
matters ("a build-service parameter value source bakes into the config-cache entry and replays stale
on a hit"). A TestKit test runs the same build twice and asserts the second (CC-hit) run reports a
*different, fresh* `resourceUsage`, not a replayed one.

**Honest labelling, written into the KDoc and rendered in the report:** the window is the
**execution phase** of the **Gradle daemon process**. It excludes configuration, and it excludes CPU
burned in the Kotlin daemon, workers and forked test JVMs — those are Tier 3's job. `windowMs` ships
alongside `daemonCpuMs` precisely so a consumer can see the denominator rather than trust a
pre-divided percentage.

**Degradation:** a zero-task or `--dry-run` build may never instantiate the collector → no baseline →
`resourceUsage` is `null`. `getSystemLoadAverage()` returns a negative on Windows → `null`.
`getCpuLoad()` returns `NaN` before its first interval → `null`. Never `0`.

*As built (review fix):* the null-collapse keys off `windowMs` specifically. The first cut collapsed
only when *every* field was null, but the four system point samples are readable on essentially any
JVM — so the block would have been permanently non-null and the contract above quietly false. The
window is what the block is for; four readings taken at the finalizer of a build that executed
nothing are not a substitute for it.

**Known scope limit — composite builds (review finding, accepted).** The baseline rides the
plan-064 anchor, and in a composite topology an *included* build's task completion can instantiate
the shared collector service while the **root** build is still configuring. On such a build the
window would include some root configuration time under a label that says it does not; the bias
direction is indeterminate, since numerator and denominator are padded from the same early anchor.
This is the existing anchor's behaviour, not a new mechanism — but plan 064's own consumer
(`ccLoadMs`) is gated on a configuration-cache HIT, where configuration is skipped, so it never met
the case and plan 104's consumer does. Recorded in `ResourceUsageProbe`'s KDoc rather than silently
inherited. Untested for composites; a composite fixture is a follow-up, not a blocker for a
best-effort, honestly-labelled metric.

### 3.4 Tier 3 collection — merged `ps`, one exec instead of two

`ProcessTools.psRss(pid)` + `psEtime(pid)` collapse into `psSnapshot(pid)` running
`ps -o rss=,etime=,time= -p <pid>`. All three are POSIX-standard `ps` format keywords. Parsing adds
`ProcessParsing.parsePsSnapshot(line)` → `(rssKb, etime, time)` by whitespace split, and
`cpuTimeMs(text)` for the `time=` column.

`time=` is the same `[[dd-]hh:]mm:ss` family the existing `uptimeSeconds` parses, **except** macOS
emits fractional seconds (`MM:SS.ss`). The new parser therefore shares the clock-splitting logic and
tolerates a fractional seconds field; `uptimeSeconds` keeps its current strictness so its pinned
behaviour does not move. Malformed → `null` for that one field, per the existing per-field
degradation contract.

Existing failure semantics are unchanged: bounded exec, first timeout latches and short-circuits,
non-zero exit drops fields, no command line is ever captured or logged.

**Honest caveat (KDoc + report footnote):** `jps` runs at end of build, so worker/test JVMs that
already exited are not in the listing. This is a partial, end-of-build view — not a build total.

### 3.5 Tier 4 — CI runner class

**In the plugin/commons providers** (zero cost — the env map is already snapshotted): a fixed
**categorical allowlist** merged into the detected context's `attributes`:

| Provider | Keys added |
|---|---|
| GitHub Actions | `RUNNER_ENVIRONMENT`, `RUNNER_ARCH`, `RUNNER_OS`, `ImageOS`, `ImageVersion` |
| Azure Pipelines | `AGENT_OS`, `AGENT_OSARCHITECTURE`, `ImageVersion` |
| GitLab CI | `CI_RUNNER_EXECUTABLE_ARCH`, `CI_RUNNER_VERSION` |

Plus a provider-neutral `BUILDHOUND_CI_RUNNER_CLASS` read (see below). Values are length-capped;
keys are a compile-time constant list, so widening it is a follow-up plan's decision, not a build's
— the plan-051/065 allowlist discipline.

*As built (review fix):* the merge happens once in `CiEnvironment.detect`, on whichever provider
won, **not** inside individual providers. The first cut added it to the three providers with an
allowlist, which silently made the "provider-neutral" opt-in a no-op on the other eight built-ins,
on every third-party `ServiceLoader` provider, and on the generic provider's bare-`CI` tier — i.e.
on exactly the unsupported-CI audience an operator-set label exists for. A provider's own attribute
wins on a key clash. The `runnerClass` value is additionally shape-checked, not merely length-capped:
it is the one operator-supplied string here, so anything outside the character set a real SKU label
uses is dropped whole rather than truncated — "categorical" enforced rather than documented.

**In `buildhound-ci-assets`** — the genuinely CI-only datum. The *measured* specs (cores, RAM, disk)
are already correct from inside the runner, because `Runtime.availableProcessors()` and
`totalMemorySize` are cgroup-aware. What the JVM **cannot** see is which runner *class* the job
asked for — GitHub's `runs-on` labels and Azure's pool/`vmImage` are pipeline-definition values that
reach no environment variable. So:

- `github/action.yml`: a new optional `runner-class` input (consumer passes e.g.
  `${{ matrix.os }}` or `ubuntu-latest-8-core`), exported as `BUILDHOUND_CI_RUNNER_CLASS` on the
  Gradle step. Bound via `env:`, never interpolated into a `run:` script — the existing
  `GRADLE_TASKS` injection rule in that file.
- `azure-pipelines/buildhound-gradle-steps.yml`: a `runnerClass` template parameter, same export.
- `gitlab/buildhound-gradle.gitlab-ci.yml`: documented variable, same export.

Default empty everywhere → nothing is collected unless the operator opts in.

### 3.6 Report rendering (`buildhound-report`)

- A new **Machine** section: OS/arch, cores, RAM, disk media + free/total. Rendered with the
  template's existing `el()`/`textContent` helper — payload strings never reach `innerHTML`.
- A **Resource usage** block in the same section: daemon CPU utilization over the execution window
  (with the window length shown, and the "daemon process, execution phase only" caveat as `.muted`
  text), system CPU load, load average, system memory used/total.
- A **CPU** column appended to the existing Process snapshot table, with the "processes that exited
  before the end-of-build probe are not listed" footnote.
- CI runner-class chip when present.
- Every field is individually conditional — a payload with none of them renders the section hidden,
  exactly like the existing Tests/Processes sections.

## 4. Test strategy

- **commons (unit):** `DiskMediaDetection` truth table across all five branches incl. network FS,
  non-Linux, `nvme`, digit-trimmed sysfs base, and unresolvable device; `DerivedMetricsCalculator`
  utilization math incl. every null-input path and `windowMs == 0`.
- **commons (golden):** `build-payload-v1-machine.json` added; `GoldenPayloadTest` asserts the new
  blocks deserialize. Existing goldens untouched — additive rule.
- **plugin (unit):** `ProcessParsing.cpuTimeMs` across Linux `[dd-]hh:mm:ss` and macOS `mm:ss.ff`
  plus malformed input; `parsePsSnapshot` field split incl. short/ragged lines. Merged-`ps`
  collection driven through a fake `ProcessTools` and through the existing fake-POSIX-script seam.
- **plugin (TestKit):** a build run **twice** asserting run 2 (CC hit) reports a fresh, non-replayed
  `resourceUsage`; a build asserting `environment.machine.diskTotalMb` is populated and that no
  device path or filesystem name appears anywhere in the payload.

  *As built:* "non-replayed" is pinned two ways rather than by comparing two runs' values (which a
  fast pair of builds could coincidentally match). `DaemonStateResourceBaselineTest` proves the
  mechanism deterministically — the baseline is read **and reset** by `executionRan()`, so build N's
  anchor cannot survive into build N+1 — and `MachineSpecsFunctionalTest` asserts the observable
  consequence on the CC hit: the reported window fits inside that build's own wall duration, which a
  replayed baseline (spanning the previous build and the gap since) could not satisfy.
- **report:** `ReportScriptTest` (node) fixture extended with the new blocks — asserts the Machine
  section renders, that a build *without* them stays hidden, and that a hostile string in a new
  field lands as text. `ReportAssetsTest` zero-network invariant unchanged and not weakened.
- **ci-assets:** ~~the existing shell test harness pattern covers the new input plumbing~~ — wrong,
  and corrected in review: that pattern mirrors *shell logic* extracted from a YAML step, and this
  change adds none (it is a purely declarative `env:`/`parameters:` binding). Nor does CI lint these
  files: `actionlint` only checks a composite action a local workflow references via `uses: ./path`,
  which none does, and the Azure/GitLab templates have no lint at all. So the coverage went into
  `CiAssetsContractTest` instead — one test pinning that all three templates export
  `BUILDHOUND_CI_RUNNER_CLASS` under exactly the name `RunnerAttributes.RUNNER_CLASS_ENV` reads, and
  one pinning the injection invariant that no `${{ inputs.* }}` is interpolated into a `run:` script
  (previously a code comment only). Without these, a typo's only symptom is a consumer silently
  collecting nothing.

## 5. Risks

- **Perf (the stated must-have).** Tier 0 is rendering only. Tier 1 is NIO + at most two small
  sysfs reads. Tier 2 is two JNI MXBean reads per build. Tier 3 **removes** one subprocess per
  probed PID. Net expectation: the finalizer axis gets *cheaper*. This is an assertion until
  measured — see exit criteria.
- **CC replay** — mitigated by design §3.3 and pinned by the twice-run TestKit test.
- **Privacy** — device paths and filesystem names are classifier input only and never ship; CI
  attributes are a categorical compile-time allowlist; `runner-class` is opt-in, shape-checked and
  capped. Reviewed under CLAUDE.md §3.2: no high findings; the device-name guard was tightened from
  a "contains no slash" blocklist to a leading-alphanumeric allowlist (a bare `[A-Za-z0-9._-]+`
  class would have accepted `..`, since `.` is legal inside a real device name), with a traversal
  regression test. `machine.diskTotalMb` is a stronger stable quasi-identifier than the `cores`/
  `ramMb` beside it and is now named in spec §3.7's residual list, with bucketing (not suppression)
  as the `pseudonymize=strict` treatment when that mode lands.
- **`ps` portability** — a platform whose `ps` rejects the merged format string loses three fields
  rather than two. Accepted: all three keywords are POSIX-standard, and the fields were already
  best-effort nullable. Windows has no `ps` and reports null today and after.
- **Honest-labelling risk** — the biggest failure mode here is a number that *looks* like whole-machine
  build CPU but is one process over a partial window. Mitigated by shipping `windowMs` next to
  `daemonCpuMs`, by the report caveats, and by never naming anything "peak" or "total".

## 6. Exit criteria

1. Report renders machine specs (cores, RAM, OS/arch, disk media + capacity) and resource usage for
   a real local build, and hides the section cleanly for a payload without them.
2. `environment.machine`, `resourceUsage`, `processes[].cpuTimeMs` and the CI runner attributes are
   populated end-to-end; new golden file added, no existing golden edited.
3. TestKit proves the CC-hit run reports fresh (non-replayed) resource usage.
4. No device path, filesystem name, hostname or free-text runner description appears in any payload.
5. **`./gradlew build` green, and the `overhead-budget` CI job passes** — the finalizer axis stays
   within its ≤150 ms / ≤8 % cap (`docs/overhead-budget.md`). This is the plan's answer to "must not
   impact build performance whatsoever": measured, not asserted.

   > **Status (2026-08-09) — still open, but no longer unmeasurable.** This criterion could not be
   > met at all while the `overhead-budget` job was inert: it reported success in ~17 s without
   > running the benchmark (gradle-profiler rejected the harness's `-P` flag, and the CI step's
   > missing `pipefail` discarded the failure). Plan 106 repaired the harness and produced the first
   > real measurements, taken against **this plan's implementation** at `690f95e`, locally on macOS
   > /aarch64: finalizer Δ **1824.6 ms** against a 150 ms cap — a breach, along with the
   > configuration and per-task axes. The cause is **not** this plan's telemetry: isolation shows the
   > process probe (plan 029, four JVM-tool subprocesses per detected JVM) accounts for ~4.5 s of it,
   > and plan 104's diff *reduced* the probe's per-PID exec count. Full numbers and the isolation
   > table are in plan 106 §7.
   >
   > **Update — measured on the reference runner, and this criterion is NOT met.** The first real
   > `overhead-budget` run (Actions run 31337018812, PR #116, `blacksmith-4vcpu-ubuntu-2404`,
   > 4m25s) produced a full table:
   >
   > | Axis | Baseline (ms) | Plugin (ms) | Δ (ms) | Allowance (ms) | Verdict |
   > |---|---:|---:|---:|---:|:---:|
   > | configuration | 71.8 | 408.5 | 336.7 | 40.0 | ❌ BREACH |
   > | per-task | 487.5 | 998.0 | 510.5 | 24.4 | ❌ BREACH |
   > | finalizer | 74.6 | 666.1 | **591.5** | 150.0 | ❌ BREACH |
   > | upload | 413.4 | 409.1 | −4.3 | 250.0 | ✅ ok |
   >
   > The finalizer axis is **~4× over** its 150 ms cap, so this plan's answer to "must not impact
   > build performance whatsoever" is now measured and negative. The cause is still not this plan's
   > telemetry — it is the process probe (plan 029), per the isolation in plan 106 §7 — but the
   > criterion as written is failed, not pending.
   >
   > CI is *milder* than local (591 ms vs 1825 ms on a laptop), which confirms the predicted
   > direction: a clean runner has few live JVMs and the probe's cost scales with that count. A
   > developer's machine is the worse case, not the better one.
   >
   > Closing this criterion now requires the probe cost to be fixed — a follow-up plan — not another
   > measurement. The caps in `OverheadBudget.DEFAULT` remain uncalibrated and must not be widened to
   > make this green (`docs/overhead-budget.md`).
6. `docs/build-telemetry-spec.md` §3.2/§3.6 updated with the new fields; `docs/architecture.md`
   updated only if a review invalidates an assumption here.
