# Cache-Miss Explanation & Build Comparison — Input Fingerprints

**Date:** 2026-07-03 · **Status:** research (design input for a future `docs/plans/` entry)
**Goal UX** (from the Develocity screenshot `docs/research/ge.solutions-team.gradle.com_s_rmnsxrhdqytwo (14).png`,
a scan of `gradle/android-cache-fix-gradle-plugin` CI): every build records per-task custom
values like `:testAndroid7_0_4#maxParallelForks = 1` and
`:testAndroid7_0_4#sysProps-local.repo = c9c70b330f4c13fb…` (hashed system-property values),
plus git/CI identity; Develocity's "Compare Build Scans" then diffs two builds so the input that
changed — and caused the cache miss — is visible. This doc establishes how to reproduce that
without Develocity, tiered by API risk.

**Bottom line:** the screenshot pattern is ~50 lines of public-API Gradle code (Gradle publishes
the exact script); real per-task cache keys and per-property hashes remain internal-API-only as
of Gradle 9.x (maintainers explicitly refused a public API); the comparison itself is pure
server work over data BuildHound already ingests. Tier (a)+(c) fit roadmap phase 2; tier (b) is
the already-planned phase-4 internal-adapters item, now with exact type names.

Related: [plugin-ecosystem-gap-analysis.md](plugin-ecosystem-gap-analysis.md) (ranking, addon
architecture), spec §3.2/§4, roadmap phases 2 & 4, architecture §2 (binding plugin rules).

---

## 1. The screenshot pattern, decoded (verified from source)

### 1.1 Where those custom values come from

The `sysProps-*` values are **not** produced by the android-cache-fix plugin itself. They come
from a debugging script on investigation branches of that repo (surviving branch
`cj/exp5-cache-miss`, head commit `c45dbab` "TEMPORARY: Add system property debugging script";
the screenshot's branch `investigate-local-remote-cache-misses` no longer exists on the remote).
`exp5` names build-validation experiment 05 — remote caching, CI agent vs local machine (§3) —
i.e. the script exists precisely to explain CI↔local cache misses, BuildHound's goal UX.

The script is a verbatim copy of Gradle's canonical public sample
`develocity-build-config-samples/build-data-capturing-gradle-samples/capture-test-execution-system-properties/gradle-test-execution-system-properties.gradle.kts`:

```kotlin
tasks.withType<Test>().configureEach {
    doFirst {
        systemProperties.forEach { (k, v) ->
            Capture.addbuildScanValue(api, "${identityPath}#sysProps-${k}", v)
        }
    }
}
```

with SHA-256 hashing that emits only the first `size/4` bytes (= 8 bytes = **16 hex chars**)
suffixed `"..."` — exactly the `c9c70b330f4c13fb…` format in the screenshot. The
`#maxParallelForks` key comes from the sibling sample `capture-max-parallel-forks` (configuration
time, unhashed) — a capture that historically lived in CCUD itself and was removed 2023-10-12
(commit `73eb485`) as "often unnecessary noise", i.e. a cardinality warning for defaults.

### 1.2 Lessons for BuildHound (all from verified source)

- **Key naming** `:<taskPath>#<scope>-<key>` is proven UX — adopt.
- The samples use `identityPath` — **internal** (`TaskInternal`). Core must use public
  `Task.getPath()`; the difference only matters in composite builds, where BuildHound's own build
  coordinates disambiguate.
- **Injecting a `doFirst` action perturbs the cache key itself** (action class names +
  classloader hashes are inputs — §2.3). CCUD's removed code used an anonymous inner class "to
  keep Test task instance cacheable" and avoided lambdas. Consequence to document: enabling or
  upgrading capture causes a one-time miss on affected tasks. (Develocity avoids this entirely by
  observing build operations instead — tier b.)
- **Hash values, ship prefixes** — 16 hex chars suffice for equality diffing. But the sample's
  hash is **unsalted** SHA-256 of low-entropy strings (versions, paths, `"true"`) — trivially
  dictionary-reversible. BuildHound salts with the per-project salt (spec §3.7); equality within
  a project still holds.
- Sample bug, don't copy: one shared `MessageDigest` across parallel test tasks —
  `MessageDigest` is not thread-safe (inferred from JDK contract; sharing verified in source).
- The ACF branch runs this with `org.gradle.configuration-cache=true` — the doFirst-capture shape
  coexists with CC (not independently re-verified on Gradle 9).

---

## 2. API landscape (what exists, what doesn't)

### 2.1 `-Dorg.gradle.caching.debug=true` — console-only

Prints per-task lines ("Appending input value fingerprint for 'options' to build cache key …",
"Build cache key for task ':compileJava' is [hash]"). Human-readable, "not formally structured";
the userguide itself says manual matching is required and points to Develocity for the real
comparison. **Verdict:** opt-in "diagnostic rerun" recipe only (Bitrise-style diagnostic build) —
never a default capture path; format is uncontracted.

### 2.2 Develocity's comparison = target feature shape

Per docs: compares task inputs, resolved dependencies, invocation switches, infrastructure.
Per task: **the overall hash of each input property**; since Develocity plugin 3.17, per-file
paths + content hashes captured by default (older tutorial had file-level off by default —
superseded). ⚠ Tutorial pages 404'd on direct fetch; quotes came via search-indexed text.

### 2.3 Internal build operations (tier-b substrate) — verified on gradle/gradle master

`org.gradle.api.internal.tasks.SnapshotTaskInputsBuildOperationType`
(`platforms/enterprise/enterprise-operations/`, `@since 4.0`, "executed only when the build cache
is enabled or when the Develocity plugin is applied"). Result exposes:
`getHashBytes()` (**the build cache key**), `getClassLoaderHashBytes()`,
`getActionClassLoaderHashesBytes()`/`getActionClassNames()`,
`getInputValueHashesBytes()` (**per-property value hashes**, lexicographic),
`visitInputFileProperties(...)` (per-file paths/hashes via visitor), `getOutputPropertyNames()`.
The module name and `@UsedByScanPlugin` annotations say whom this serves: versioned-with-care for
the Develocity plugin, not for third parties. Subscription requires internal
`BuildOperationListenerManager` (registration mechanism inferred from architecture, not fetched).

Newer siblings worth the same adapter:

- `org.gradle.operations.execution.ExecuteWorkBuildOperationType` (`@since 8.3`; tasks **and**
  transforms): `getSkipMessage()`, `getCachingDisabledReasonMessage()/Category()`,
  `getExecutionReasons()`, `getOriginBuildInvocationId()`, and
  **`getOriginBuildCacheKeyBytes()` (`@since 8.7`** — inside BuildHound's 8.14 floor).
- `org.gradle.caching.internal.operations.BuildCache{Local,Remote}{Load,Store}BuildOperationType`
  (+ pack/unpack, remote-disabled-due-to-failure) → **local-vs-remote origin + transfer timings**
  — the roadmap phase-4 cache-origin item, same adapter, same risk class. Tuist's plugin uses
  exactly these internal types for its LOCAL_HIT/REMOTE_HIT split and per-task cache keys —
  independent confirmation there is no public route.

### 2.4 Public API status: none, refused

gradle/gradle#9456 ("API to access gradle build cache key of task"), maintainer verbatim:
"The cache key Gradle calculates is an implementation detail that we don't want users to rely
on. This is why there is no public API to access it." Closed 2019; nothing public shipped through
Gradle 9.x (current userguide still offers only caching.debug + Develocity). Public event
surfaces (`BuildEventsListenerRegistry`, Tooling API) remain outcome-level. ⚠ Issue #38493
referenced in early notes does not exist; open request #1729 (input hashes for incremental tasks)
unverified state.

### 2.5 `task.inputs.properties` reflection (tier-b-lite, DIY)

`TaskInputs.getProperties()` is public; reading it in a static injected `Action<Task>` that
resolves a `BuildService` provider is CC-safe in shape (no `Project` capture). Verdict: *usable
but second-choice* — `String.valueOf` of a `FileCollection`/`Provider` ≠ Gradle's fingerprint
(approximate value identity only), it forces early evaluation of every input property, and it
perturbs the action list (§1.2). The special case actually proven by the ACF investigation is
`Test.getSystemProperties()` (public `JavaForkOptions`) — that one is worth shipping.

### 2.6 Configuration-cache miss reasons — no public API either

- Console patterns (verbatim, current docs): miss → "Calculating task graph as no cached
  configuration is available for tasks: […]"; invalidation → "… cannot be reused because system
  property '[name]' has changed."; hit → "Reusing configuration cache."
- Report: `build/reports/configuration-cache/<hash>/configuration-cache-report.html`, backed by
  `configuration-cache-report-data.js` defining a JS global (`configurationCacheProblems`) — a
  JSON-ish model, but an **undocumented internal contract** between two Gradle components; parse
  defensively or not at all. ⚠ Whether the invalidation *reason* (vs problems/inputs) is present
  in the report data was not confirmed.
- Practical v1 shape (inference, flagged): BuildHound already derives the CC state enum via a
  static configuration-phase `AtomicBoolean` observer (`DaemonState.configuredSinceLastExecution`,
  set only from `BuildHoundSettingsPlugin.apply`) combined with public `BuildFeatures` (8.5+) —
  the same pattern Talaiot calls `ConfigurationPhaseObserver`, i.e. already shipped (verified
  2026-07-03; [comparison-to-spec.md](comparison-to-spec.md)'s "replace the counter heuristic"
  recommendation describes plan 003's *text*, not the shipped code). The *reason* is best-effort:
  Flow-action read of the newest
  report-data file (execution-time file read — CC-safe per plan-004 precedent), tolerant parser,
  absent ⇒ omit. eBay's summarizer proves the per-reason frequency rollup is worth having
  (`configCacheMissReport-P7D` with regex consolidation — see gap-analysis doc §3).

---

## 3. Two-build diff methodology (develocity-build-validation-scripts)

Five Gradle experiments, each = run two builds, diff outcomes: 01 incremental same-location,
02 local cache same-location, 03 local cache **different checkouts** (catches absolute-path
inputs), 04 remote cache CI↔CI, 05 remote cache **CI↔local** (the ACF branch's namesake).
Without Develocity the scripts only leave raw run data — analysis depends on scan comparison.

**Copy the experiment design, not the bash:** (i) canonical pairs: same-commit CI↔CI, CI↔local,
two-checkout relocatability; (ii) verdict list = tasks EXECUTED in run B that were
FROM_CACHE/UP_TO_DATE (or cacheable-stored) in run A — "misses to explain"; (iii) per such task,
diff fingerprint maps; (iv) rank differing keys by how many misses they explain. BuildHound
already ingests both builds' task outcomes, so this is pure server work plus a documented recipe
("run the pair with `buildhound.tags.put("experiment","exp5")`").

---

## 4. OSS prior art (checked)

- **develocity-build-config-samples** — the direct model (§1); also ships `capture-processor-arch`,
  `capture-thermal-throttling`, `capture-slow-workunit-executions`, `capture-os-processes`,
  `capture-git-diffs` — a menu of build-level fingerprint candidates.
- **runningcode/gradle-doctor** — env-sanity diagnostics: JAVA_HOME set & matches IDE, remote
  cache connection-speed benchmark, negative-avoidance-savings warnings, GC >10% warning, Dagger
  processor timing, Rosetta-JDK warning. No input hashes; its checks feed the tier-(a) catalog /
  server rules.
- **Talaiot** — durations/dashboards, no input hashes, no miss explanation.
- **android-cache-fix-gradle-plugin `main`** = the known-volatile-input catalog (§5): e.g.
  `JdkImageWorkaround` ("cache misses due to the custom Java runtime used when source
  compatibility > Java 9 … still able to reproduce cache misses with different JDK vendors"),
  bootclasspath relocatability, and tasks it disables caching on for **negative savings**:
  `MergeNativeLibsTask`, `StripDebugSymbols`, `MergeJavaResources`, `MergeSourceSetFolders`,
  `BundleLibraryClassesJar`, `DataBindingMergeDependencyArtifacts`, `LibraryJniLibs`,
  `ZipMergingTask`.
- Foursquare/Spotify/Cash/Slack OSS: nothing found capturing input hashes (negative result).
  Bitrise "Task Inputs" tab: closed-source commercial (per our own research doc §2.4).

---

## 5. Tiered design for BuildHound

### Tier (a) — CORE, public APIs only (roadmap phase 2)

**Build-level fingerprints** (EnvironmentCollector/Flow path, read at execution time via
providers so they never become CC inputs — plan-004 precedent): JDK home path + vendor + full
version (Gradle tracks only the major version in cache keys → vendor/minor drift is a documented
miss cause), `file.encoding`, `user.language`/`user.country`, TZ, OS/arch, parallel/worker
settings, requested-tasks signature.

**Allowlist DSL** (additive to `buildhound {}`):

```kotlin
buildhound {
    fingerprints {
        systemProperties("local.repo", "org.gradle.android.cache-fix.version") // hashed
        envVars("JAVA_HOME", "ANDROID_HOME")                                   // hashed
        gradleProperties("agpVersion")                                         // hashed
        testTaskSystemProperties = true  // opt-in: the ACF pattern, per Test task
    }
}
```

**Per-Test-task sysProps** (opt-in flag): a static capture action class (CCUD cacheability
lesson) added via `tasks.withType(Test).configureEach { doFirst(...) }`; values →
`hash(projectSalt + String.valueOf(v))`; keys `:<taskPath>#sysProps-<name>`; hard caps
(~100 keys/task, 64-char key length) per the cardinality-abuse risk (research doc §6). Because
this mutates task action lists, it is **opt-in and documented** ("enabling causes a one-time
cache miss on Test tasks") — observation-only builds leave it off. If review deems even opt-in
action injection too close to the no-mutation line, this one flag ships in a
`dev.buildhound.fingerprints` micro-addon instead; build-level + allowlist capture stays core
either way.

**Payload (additive):**

```jsonc
"fingerprints": { "build": { "jdk.home": "a1b2…", ... },
                  "tasks": { ":app:testDebugUnitTest": { "sysProps-local.repo": "c9c7…" } } }
```

Salted SHA-256, 16-hex prefix + `…` (screenshot-compatible). Size: ~20 build keys + 30 test
tasks × 40 props ≈ tens of KB pre-gzip — negligible vs the task array. Known-boring values
(e.g. `maxParallelForks`) may opt into plaintext via an explicit `plaintext()` DSL only.

**Privacy:** hash-by-default with per-project salt (strictly stronger than the Develocity
sample's unsalted hashes), allowlist-only capture, absolute paths never leave unhashed.
Limitation to state honestly: explains only allowlisted/cataloged inputs; unknown volatile
*file* inputs stay invisible until tier (b).

**Known-volatile catalog as server rules** (keeps the plugin dumb): flag compared-build diffs on
JDK path/vendor, encoding/locale, line-endings, AGP/KGP/plugin versions; statically warn when
the §4 negative-savings Android tasks are cacheable without android-cache-fix applied.

### Tier (b) — internal-adapters (roadmap phase 4 item 2, unchanged scope, now concrete)

Adapter subscribes via internal `BuildOperationListenerManager` to:
`SnapshotTaskInputsBuildOperationType` (cache key, impl/classloader/action hashes, per-property
value hashes; per-file capture **off by default** — payload explodes on 1000-module builds,
mirror Develocity's gating history), `ExecuteWorkBuildOperationType` (execution reasons,
caching-disabled reason + category, origin build id, **origin cache key** ≥8.7), and the
`BuildCache{Local,Remote}{Load,Store}` ops (origin split + transfer timings). Risk mitigations
already mandated by spec §3.1: isolated module, feature-flag per Gradle version, reflection
adapter per minor range, TestKit matrix job per supported Gradle, every listener body
`try/catch → warn` (never-fail rule). Tuist's identical usage is the canary: watch their repo
for breakage patterns.

### Tier (c) — server comparison (phase 2 lite, phase 4 full)

- `GET /v1/builds/{a}/compare/{b}` + the "Comparisons" dashboard page already reserved in spec
  §6. Default pair pickers: same requested-tasks signature + same project; suggested pairs:
  same-sha CI↔CI, CI↔local, PR↔baseline.
- Diff: for each task EXECUTED in B but avoided (or cacheable-stored) in A, diff
  `fingerprints.tasks[path]` (tier a) or per-property hashes + cache key (tier b); union with
  build-level fingerprint diffs.
- **Ranking:** score differing key K by `|missed tasks whose map contains K| / |missed tasks|` —
  a build-level key (JDK path) co-occurring with 100 % of misses ranks first. Attach catalog
  explanations ("JDK vendor differences defeat jdkImage relocatability — see android-cache-fix
  JdkImageWorkaround").
- CC lane: show `configurationCache` state per build; when the best-effort reason (§2.6) exists,
  render it as a separate "configuration inputs" section.

### Phasing (maps to existing roadmap, no re-ordering needed)

1. **Phase 2:** tier (a) + tier (c)-lite compare endpoint/view — consistent with research doc's
   own "Phase 2: task-input fingerprints" note; slots next to metric-CLI/custom-values work.
   Deliverable = the screenshot UX minus per-file hashes.
2. **Phase 3:** grow the volatile catalog from pilot data; ship the exp-recipe docs (§3).
3. **Phase 4 item 2:** tier (b) — cache origin + real keys + per-property diffs; tier (c)
   upgraded to per-property cause ranking. Matches the existing roadmap line verbatim.
4. **Not doing:** default parsing of `caching.debug` output (uncontracted); waiting for a public
   Gradle API (refused, #9456); plaintext fingerprint values.

---

## 6. Flagged / unverified items

Develocity tutorial quotes via search-index (both tutorial URLs 404 on direct fetch) · DV-plugin
build-operation *registration* mechanism inferred, not fetched · CC report data containing the
invalidation reason unconfirmed · gradle/gradle#38493 does not exist; #1729 state unchecked ·
`MessageDigest` thread-safety risk and doFirst cache-key perturbation are inferences from
verified API contracts, not reproduced experimentally · eBay-reads-missReasons-from-Develocity
verified from its source; no BuildHound-side equivalent API exists.
