package dev.buildhound.commons.payload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Payload schema v1 (spec §4). One build == one document, gzip-compressed on the wire,
 * idempotent on [buildId]. Schema changes must be additive within a major version;
 * the golden-file tests in `jvmTest` enforce the contract.
 */
@Serializable
data class BuildPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val buildId: String,
    val projectKey: String? = null,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: BuildOutcome,
    val failure: FailureInfo? = null,
    val requestedTasks: List<String> = emptyList(),
    val mode: BuildMode = BuildMode.CI,
    val environment: EnvironmentInfo? = null,
    val toolchain: ToolchainInfo? = null,
    val vcs: VcsInfo? = null,
    val ci: CiInfo? = null,
    val tags: Map<String, String> = emptyMap(),
    val values: Map<String, String> = emptyMap(),
    val tasks: List<TaskExecution> = emptyList(),
    val derived: DerivedMetrics? = null,
    /** What the caps enforcement dropped/truncated (plan 019); null when nothing was capped. */
    val caps: CapsSummary? = null,
    /** Salted input fingerprints for cache-miss comparison (plan 022); null when uncaptured. */
    val fingerprints: FingerprintInfo? = null,
    /** Bundled Kotlin build-report metrics (plan 023); null when unwired or not observable. */
    val kotlin: KotlinInfo? = null,
    /** Per-test-task results parsed from JUnit XML (plan 024); empty when no test ran or disabled. */
    val tests: List<TestTaskResult> = emptyList(),
    /** Source/commit/PR links composed from the git remote + CI context (plan 027); null when uncomposable. */
    val links: LinksInfo? = null,
    /** End-of-build JVM process snapshot (plan 029, spec §3.6); empty when disabled or unobservable. */
    val processes: List<ProcessInfo> = emptyList(),
    /** gradle-profiler benchmark context (plan 030, spec §7); null on non-benchmark builds. */
    val benchmark: BenchmarkInfo? = null,
    /** APK/AAB/AAR sizes for an Android build (plan 031, spec §4); null on non-Android builds. */
    val artifacts: ArtifactSizes? = null,
    /**
     * Per-project configuration (evaluation) time, ranked slowest-first (plan 052, research F2).
     * Populated by the settings plugin's `beforeProject`/`afterProject` collector on a CC-miss/DISABLED
     * build (including under isolated projects — the public `GradleLifecycle` hooks are IP-safe); null
     * on a configuration-cache **hit** (configuration did not run this build, so there is nothing to
     * report) and when nothing was captured. Not a decomposition of `derived.configurationMs` — see
     * [ProjectEvaluation].
     */
    val projectEvaluations: List<ProjectEvaluation>? = null,
    /**
     * Declared project-tree inventory (plan 069, research F19): project/depth/included-build counts
     * plus the filesystem-derived `buildSrcPresent`/`sourcesInRoot`/`emptyIntermediateCandidates`.
     * Null when uncaptured (master switch off at apply time, or a guarded walk/probe failure) —
     * collection-only in v1; the modularization-ROI analysis that consumes it is a later plan.
     */
    val buildStructure: BuildStructureInfo? = null,
    /**
     * Wrapper distribution/pinning posture + Gradle-User-Home dist warmth (plan 066, research F16);
     * null when uncaptured (master switch off at apply time, or every probe degraded).
     */
    val wrapper: WrapperInfo? = null,
    /**
     * Addon-contributed payload sections (plan 039), keyed by addon id (e.g. `"testQuarantine"`).
     * The value is addon-owned JSON carrying its own `schemaVersion`, so core stays decoupled from
     * addon types and needs no schema bump when an addon evolves. Empty on a build with no addon
     * applied. Contributors are `ServiceLoader`-discovered via [BuildHoundCollectorRegistry]; the
     * plan-019 size budget still applies (the largest offending entries are dropped, never the
     * envelope). Additive-only — an addon must never require a core schema change (spec §4).
     */
    val extensions: Map<String, JsonElement> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** The kind of Android build output whose size was measured (plan 031). */
@Serializable
enum class ArtifactType { APK, AAB, AAR }

/**
 * One measured Android artifact (plan 031, spec §4). Byte size only — no path, no contents (§3.7).
 * [module]/[variant] are project-internal Gradle names (`:app`, `release`), not PII.
 */
@Serializable
data class ArtifactSize(
    val variant: String,
    val module: String? = null,
    val type: ArtifactType,
    val sizeBytes: Long,
)

/**
 * Android artifact sizes (plan 031). A single `android` list mixing APK/AAB/AAR (disambiguated by
 * [ArtifactSize.type]); null on the payload means "not an Android build / nothing produced".
 */
@Serializable
data class ArtifactSizes(val android: List<ArtifactSize> = emptyList())

/**
 * One project's configuration (evaluation) time (plan 052, research F2), timed by the settings
 * plugin's `gradle.lifecycle.beforeProject`/`afterProject` hooks around script evaluation + plugin
 * application + `afterEvaluate` for that project. [path] is the Gradle project path (`:app`,
 * `:core:common`) — project-internal, not PII (same bar as [ArtifactSize.module]/`TaskExecution.module`).
 *
 * **Not a decomposition of `derived.configurationMs`** (narrowing 1): settings/init evaluation,
 * buildSrc/included builds, task-graph population, and configuration-cache store time fall outside
 * these two hooks, and under parallel/isolated-projects configuration the per-project windows overlap
 * wall-clock — so the list will not sum to `configurationMs`. Treat it as "project evaluation time
 * (top-N)", a distinct signal for finding the guilty module, not a full breakdown.
 */
@Serializable
data class ProjectEvaluation(
    val path: String,
    val evaluationMs: Long,
)

/**
 * Declared build-structure inventory (plan 069, research finding F19: modularization/IDE-sync ROI).
 * Collection-only in v1 — the module-count trend and modularization rules are a later, foregrounded
 * follow-up that ships once this data has ridden across enough builds (the fingerprints-then-compare
 * ordering of plan 022 → its comparison endpoint).
 *
 * [projectCount]/[maxDepth]/[includedBuildCount] come from a config-time walk of the declared
 * `ProjectDescriptor` tree (`settingsEvaluated`, settings-level metadata — IP-legal, unlike the
 * plan-016 task dictionary); [buildSrcPresent]/[sourcesInRoot]/[emptyIntermediateCandidates] are
 * resolved by execution-time filesystem probes so no config-phase file read becomes a CC fingerprint
 * input (architecture §2 rule 9). Every field is nullable — a build where the walk/probes never ran
 * (master switch off, a guarded failure) reports the whole block as `null` (honest nulls, plan 005),
 * never a half-populated one.
 */
@Serializable
data class BuildStructureInfo(
    val projectCount: Int? = null,
    /** Deepest declared project path, in path segments (`:libs:legacy:foo` = 3); root is 0. */
    val maxDepth: Int? = null,
    val includedBuildCount: Int? = null,
    /** `<rootDir>/buildSrc` exists. */
    val buildSrcPresent: Boolean? = null,
    /** `<rootDir>/src` exists (a root-level source set alongside declared subprojects). */
    val sourcesInRoot: Boolean? = null,
    /**
     * Gradle paths (`:libs:legacy`, never a filesystem path — spec §3.7) of declared projects that
     * have children but no build file of their own — the `allprojects{}`-configured-aggregator shape.
     * A **ranked heuristic candidate list, not a verdict**: this is a *recommended* Gradle pattern too,
     * so the plugin ships only the structural fact; the modularize/delete judgment is downstream,
     * longitudinal, and deferred. Sorted for determinism and capped in the collecting `ValueSource`
     * (no `PayloadCapper` change — there is no free text here to bound).
     */
    val emptyIntermediateCandidates: List<String> = emptyList(),
)

/**
 * Wrapper distribution variant (plan 066, research F16): parsed from `distributionUrl` in
 * `gradle-wrapper.properties`. `CUSTOM` covers anything that isn't a stock `services.gradle.org`
 * `-bin`/`-all` filename (typically a private mirror) — the raw URL itself is never shipped (a
 * custom-mirror host can embed credentials or an internal hostname, spec §3.7); only this coarse
 * classification survives.
 */
@Serializable
enum class WrapperDistributionType { BIN, ALL, CUSTOM }

/**
 * Gradle-User-Home wrapper-dist warmth (plan 066, research F16): whether *this daemon's* unpacked
 * distribution under `<GUH>/wrapper/dists/…` looks freshly created (an ephemeral-CI cold start —
 * the Docker-image-rebuild / first-checkout re-download cost the finding calls out) or a reused,
 * already-warm directory. `UNKNOWN` is honest, not a guess (plan 005): a system/IDE Gradle run has
 * no wrapper dist to compare, or the probe itself never ran.
 *
 * **The anchor is this daemon's own JVM start time, not any in-build task timestamp** — a
 * plan-066-review correction. The wrapper's bootstrap unpacks the distribution *before* launching
 * the daemon JVM, which itself starts before configuration, which itself precedes every task — so
 * comparing the dist mtime against `startedAt` (the first task's start) can **never** observe
 * `COLD`: the unpack is structurally always earlier than any task-derived timestamp, cold build or
 * not. `jvmStartMs` (`ManagementFactory.getRuntimeMXBean().startTime`, captured in
 * `WrapperValueSource.obtain()`) sits only moments after the actual unpack in a genuinely cold
 * bootstrap, so it is the tightest anchor obtainable without hooking the wrapper's own (separate,
 * already-exited) bootstrap process.
 */
@Serializable
enum class GuhWarmth {
    COLD, WARM, UNKNOWN;

    companion object {
        /**
         * How close the dist's mtime must sit to this daemon's own [jvmStartMs] to count as
         * unpacked *for this daemon's own bootstrap* rather than pre-existing. Generous enough to
         * absorb a slow `-all` download over a throttled CI network (typically well under a
         * minute), while staying far tighter than "reused from an earlier run" (typically hours to
         * days old). Known limitation: a long-lived, **reused** daemon (`environment.daemonReused`)
         * keeps the same `jvmStartMs` across every build it serves, so `COLD` can stay pinned for
         * subsequent builds on that daemon even though only the first one paid the download cost —
         * cross-reference `daemonReused` downstream to disambiguate. Ephemeral CI (this finding's
         * primary target) typically launches a fresh daemon per job, where this limitation does not
         * apply.
         */
        const val FRESH_WINDOW_MS: Long = 5 * 60 * 1000L

        /**
         * Pure classification (plan 066, corrected): `COLD` when the dist's mtime sits within
         * [FRESH_WINDOW_MS] of this daemon's own [jvmStartMs] (unpacked at/around this daemon's own
         * bootstrap); `WARM` when meaningfully older (a persisted, reused Gradle User Home).
         * `UNKNOWN` when [distPresent] isn't `true` (no wrapper dist at all — system/IDE Gradle, or
         * the probe never ran) or either timestamp is unavailable (a guarded stat/JVM-introspection
         * failure).
         */
        fun classify(
            distMtimeMs: Long?,
            distPresent: Boolean?,
            jvmStartMs: Long?,
        ): GuhWarmth {
            if (distPresent != true) return UNKNOWN
            val mtime = distMtimeMs ?: return UNKNOWN
            val jvmStart = jvmStartMs ?: return UNKNOWN
            return if (kotlin.math.abs(jvmStart - mtime) <= FRESH_WINDOW_MS) COLD else WARM
        }
    }
}

/**
 * Wrapper & startup-phase telemetry (plan 066, research F16): the committed wrapper config's
 * distribution variant + SHA-pinning posture, the wrapper jar's own hash (rides for a later
 * server-side cross-check against gradle.org's published checksums — deferred, own follow-up
 * plan), and this build's Gradle-User-Home dist warmth. [distributionVariant]/
 * [distributionSha256Pinned] describe the *committed* config and are reported even when
 * [guhWarmth] is `UNKNOWN` (a deliberate decision — they are the drift/unpinned signal regardless
 * of how this particular build was invoked). Null on the whole payload when uncaptured (master
 * switch off, or every probe degraded) — never a half-populated block.
 */
@Serializable
data class WrapperInfo(
    val distributionVariant: WrapperDistributionType? = null,
    /** `distributionSha256Sum=` present in `gradle-wrapper.properties` — the drift/unpinned signal. */
    val distributionSha256Pinned: Boolean? = null,
    /** Full lower-hex SHA-256 of `gradle-wrapper.jar` — a public, distribution-independent artifact. */
    val wrapperJarSha256: String? = null,
    val guhWarmth: GuhWarmth? = null,
)

/**
 * Benchmark-run context (plan 030, spec §7): set when a gradle-profiler pipeline drives the pilot's
 * real build with `BUILDHOUND_BENCHMARK_*` env. A typed block (not just tags) so the server can group
 * + compute percentiles per (scenario, isolationMode) robustly; the same keys are *also* mirrored into
 * `tags` (the spec's tag contract). [scenario] is allowlist-validated plugin-side so a typo can't mint
 * a new series.
 */
@Serializable
data class BenchmarkInfo(
    /** One of "clean" | "no_op" | "incremental_non_abi" | "cc_hit". */
    val scenario: String,
    /** gradle-profiler measurement index within the scenario. */
    val iteration: Int? = null,
    /** Telltale cache-isolation label, e.g. "full_cache" | "no_build_cache". */
    val isolationMode: String? = null,
    /** Correlates measure builds back to their seed run. */
    val seedRef: String? = null,
)

/**
 * One `Test` task's results, parsed from its JUnit XML output (plan 024, spec §3.5/§4). Per-class
 * rollups always; per-case detail *only* for cases that failed/errored or were retried (locked
 * granularity, non-goal #2). `allCases` is reserved-empty in v1 so widening to all cases later
 * stays additive (spec §3.5). Class/method names and counts are declared data; the only free text
 * is [TestCaseDetail.message], which the scrubber covers.
 */
@Serializable
data class TestTaskResult(
    val taskPath: String,
    val module: String? = null,
    val durationMs: Long? = null,
    val classes: List<TestClassResult> = emptyList(),
    val failedOrRetried: List<TestCaseDetail> = emptyList(),
    /** How many class rollups the per-task cap dropped (kept the slowest). */
    val truncatedClasses: Int = 0,
    /** How many failed/retried case details the per-task cap dropped (spec §3.9 truncate+count). */
    val truncatedDetail: Int = 0,
    /** Reserved for a future additive plan that ingests passing cases too (spec §3.5); empty in v1. */
    val allCases: List<TestCaseDetail> = emptyList(),
)

/**
 * THE `module/class` join key, defined once here (plan 024 is its canonical site). Plans 036
 * (flaky), 037 (quarantine), and 040 (sharding) reference `TestUnitKey.of` verbatim to join
 * client-side timings against server-side per-class data — so Tuist's bare-FQCN-vs-`module/class`
 * degeneration bug (research §2.6) cannot recur. A null module yields an empty-string prefix.
 */
object TestUnitKey {
    fun of(module: String?, classFqcn: String): String = "${module ?: ""}/$classFqcn"
}

@Serializable
data class TestClassResult(
    /** Fully-qualified class name. */
    val className: String,
    val passed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val durationMs: Long = 0,
) {
    /** Convenience delegating to the canonical [TestUnitKey.of]; never the source of truth. */
    fun unitKey(module: String?): String = TestUnitKey.of(module, className)
}

@Serializable
data class TestCaseDetail(
    val className: String,
    val name: String,
    /** Ordered outcome sequence; more than one entry means the case was retried. */
    val outcomes: List<TestCaseOutcome> = emptyList(),
    val durationMs: Long = 0,
    /** SHA-256 hex of the raw (pre-scrub, pre-truncation) message — a stable flaky-signal key. */
    val messageHash: String? = null,
    /** Failure/assertion text, scrubbed then truncated; null when the case carried none. */
    val message: String? = null,
)

@Serializable
enum class TestCaseOutcome { PASSED, FAILED, ERROR, SKIPPED }

/**
 * Kotlin build-report metrics bundled from the KGP json report (plan 023, spec §4). The KGP
 * json is an unstable internal format, so only a name-keyed allowlist is retained — never
 * compiler arguments, changed-file lists, or IC log lines (they carry absolute paths, §3.7).
 */
@Serializable
data class KotlinInfo(
    /** Version tag derived from the report (e.g. the KGP language version), else "unknown". */
    val reportSchema: String? = null,
    val perTask: List<KotlinTaskReport> = emptyList(),
    /** How many task records the per-task cap dropped. */
    val truncatedTasks: Int = 0,
)

@Serializable
data class KotlinTaskReport(
    val taskPath: String,
    val durationMs: Long? = null,
    val incremental: Boolean? = null,
    /** KGP rebuild/non-incremental reason enum names (no paths). */
    val nonIncrementalReasons: List<String> = emptyList(),
    /** Compiler phase → milliseconds (name-keyed). */
    val compilerTimesMs: Map<String, Long> = emptyMap(),
    val linesOfCode: Long? = null,
)

/**
 * Salted-hash fingerprints of build inputs (plan 022, spec §4). Values are 16-hex-char + `…`
 * HMAC digests keyed with the per-project identity salt — equality within a project is all the
 * comparison endpoint needs, and no plaintext (e.g. absolute `jdk.home`) ever leaves the machine.
 */
@Serializable
data class FingerprintInfo(
    /** Build-level inputs: key (e.g. `jdk.home`, `env-CI`) → salted hash. */
    val build: Map<String, String> = emptyMap(),
    /** Per-task inputs (opt-in Test system properties): task path → (key → salted hash). */
    val tasks: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * Records exactly what [PayloadCapper] removed to keep the payload within budget (spec §3.9):
 * "truncate + count what was dropped". Non-null on a payload only when something was capped;
 * a server-side re-cap merges its counts into this instead of overwriting them.
 */
@Serializable
data class CapsSummary(
    val droppedTags: Int = 0,
    val droppedValues: Int = 0,
    /** Over-long map values (in `tags` or `values`) truncated to the char cap. */
    val truncatedValues: Int = 0,
    val droppedExecutionReasons: Int = 0,
    val truncatedExecutionReasons: Int = 0,
    /** `nonCacheableReason` free text truncated to the char cap (plan 016 populates it). */
    val truncatedNonCacheableReasons: Int = 0,
    val droppedTasks: Int = 0,
    /** Per-outcome counts of the dropped tasks, so the totals stay reconstructable. */
    val droppedTaskOutcomes: Map<String, Int> = emptyMap(),
    /** Android artifact records dropped past the per-payload cap (plan 031), smallest-first. */
    val droppedArtifacts: Int = 0,
    /** Addon `extensions` entries dropped past the extensions byte budget (plan 039), largest-first. */
    val droppedExtensions: Int = 0,
    /** `projectEvaluations` entries dropped past the per-payload cap (plan 052), fastest-first. */
    val droppedProjectEvaluations: Int = 0,
)

/**
 * Terminal build state. `INTERRUPTED` (plan 033, additive) is a build that started but never
 * finalized — the daemon died mid-build (OOM kill, `kill -9`, agent eviction) so the in-process Flow
 * finalizer never ran. It is synthesized from a start-marker by the *next* build's finalizer (or by
 * the connector expected-build check), never emitted by a build that reaches finalization.
 */
@Serializable
enum class BuildOutcome { SUCCESS, FAILED, INTERRUPTED }

@Serializable
enum class BuildMode {
    @SerialName("ci") CI,
    @SerialName("local") LOCAL,
    @SerialName("benchmark") BENCHMARK,
}

/**
 * Build-failure detail (spec §3.7/§4). `messageHash` is a SHA-256 of the raw message (computed
 * pre-scrub, so it stays a stable cross-build/flaky key), retained alongside the plaintext for
 * back-compat and correlation. `message` and `stackTrace` are collected only when a build fails;
 * both are **scrubbed** (absolute paths relativized/redacted, secret-shaped values stripped) and
 * **truncated** by [PayloadScrubber] before they ship — the highest PII-leak surface in the payload,
 * so over-redaction is deliberate. `taskPath` is reserved for per-task attribution (build-level
 * capture leaves it null in v1).
 */
@Serializable
data class FailureInfo(
    val taskPath: String? = null,
    val exceptionClass: String? = null,
    val messageHash: String? = null,
    val message: String? = null,
    val stackTrace: String? = null,
)

@Serializable
data class EnvironmentInfo(
    val os: String? = null,
    val arch: String? = null,
    val cores: Int? = null,
    val ramMb: Long? = null,
    val hostnameHash: String? = null,
    val userId: String? = null,
    val daemonReused: Boolean? = null,
    val configurationCache: ConfigurationCacheState? = null,
    /** Host IDE (plan 027): "Android Studio"/"IntelliJ IDEA"/"Eclipse"/"VS Code"; null = command line. */
    val ide: String? = null,
    val ideVersion: String? = null,
    /** True when the build ran inside an IDE Gradle sync rather than a user-triggered build. */
    val ideSync: Boolean? = null,
    /** Coarse AI-agent attribution (plan 027): "Claude Code"/"Cursor"/…; null when none detected. */
    val aiAgent: String? = null,
    /** Invocation-switch & performance-flag posture (plan 051); null when uncaptured. */
    val invocation: InvocationInfo? = null,
    /**
     * Isolated-projects feature activation (plan 069, research F19): the plugin has computed
     * `buildFeatures.isolatedProjects.active` at `whenReady` since plan 021 but never shipped it;
     * starts the series accruing for a future before/after-IP benefit comparison (not built here).
     */
    val isolatedProjects: Boolean? = null,
)

@Serializable
enum class ConfigurationCacheState { HIT, MISS_STORED, DISABLED, INCOMPATIBLE }

/**
 * Invocation-switch & performance-flag posture (plan 051, spec §3.2/§4, research finding F1):
 * seven public `StartParameter` scalars, plus genuinely-new plaintext `fileEncoding`/`locale`
 * which — together with [parallel]/[maxWorkerCount] — stand **alongside** the salted
 * `FingerprintInfo` hashes (never replacing them) so absolute-value rules can fire (e.g.
 * "Cp1252 fleet → set UTF-8"). [properties] is a fixed, non-secret `gradle.properties` allowlist
 * (`org.gradle.caching`, `org.gradle.parallel`, `org.gradle.vfs.watch`,
 * `android.enableJetifier`, `android.nonTransitiveRClass`) attributed to the layer that declared
 * it — see [PropertyOrigin]. Every field is nullable/empty-defaulted (additive).
 *
 * Field names are load-bearing: the server's `baselineWindow` hygiene filter (plan 051) reads
 * [rerunTasks]/[refreshDependencies] straight out of the wire JSON via a jsonb path
 * (`payload->'environment'->'invocation'->>'rerunTasks'`), so a rename here silently breaks that
 * filter with no compile error.
 */
@Serializable
data class InvocationInfo(
    val buildCacheEnabled: Boolean? = null,
    val offline: Boolean? = null,
    /** No avoidance by design (plan-025 baseline hygiene excludes these, like INTERRUPTED in plan 033). */
    val rerunTasks: Boolean? = null,
    val refreshDependencies: Boolean? = null,
    val configureOnDemand: Boolean? = null,
    val maxWorkerCount: Int? = null,
    val parallel: Boolean? = null,
    /** `file.encoding` sysprop, plaintext (already salted in `FingerprintInfo`; this rides alongside). */
    val fileEncoding: String? = null,
    /** `user.language[-user.country]`, plaintext (already salted in `FingerprintInfo`; rides alongside). */
    val locale: String? = null,
    val properties: List<GradlePropertyPosture> = emptyList(),
)

/** One allowlisted `gradle.properties` key's effective value + declaring layer (plan 051). */
@Serializable
data class GradlePropertyPosture(
    val key: String,
    val value: String,
    val origin: PropertyOrigin,
)

/**
 * Which layer's `gradle.properties` (or system property) declares an allowlisted key (plan 051).
 * Presence-by-precedence, not value-matching: project and Gradle User Home can both declare the
 * same value, and only "which layer declares it" reveals a silent GUH win — the finding's whole
 * point. `UNKNOWN` is honest, not a guess: attributed whenever a layer cannot be confirmed as the
 * effective source (spec §3.7 — a confident-but-wrong "developer overrode this locally" would
 * defeat the feature).
 */
@Serializable
enum class PropertyOrigin { PROJECT, GRADLE_USER_HOME, OVERRIDE, UNKNOWN }

@Serializable
data class ToolchainInfo(
    val gradle: String? = null,
    val jdk: String? = null,
    val agp: String? = null,
    val kgp: String? = null,
    val ksp: String? = null,
)

@Serializable
data class VcsInfo(
    val branch: String? = null,
    val sha: String? = null,
    val dirty: Boolean? = null,
    /** Git remote origin URL with any userInfo redacted, all schemes (plan 027); null when absent. */
    val remoteUrl: String? = null,
)

/** Source-control web links composed from the remote URL + CI context (plan 027, spec §4). */
@Serializable
data class LinksInfo(
    val commitUrl: String? = null,
    val pullRequestUrl: String? = null,
)

@Serializable
data class CiInfo(
    val provider: String,
    val runId: String? = null,
    val pipelineName: String? = null,
    val jobId: String? = null,
    val buildUrl: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class TaskExecution(
    val path: String,
    val module: String? = null,
    val type: String? = null,
    val startMs: Long,
    val durationMs: Long,
    val outcome: TaskOutcome,
    val cacheable: Boolean? = null,
    val nonCacheableReason: String? = null,
    val incremental: Boolean = false,
    val worker: Int? = null,
    val executionReasons: List<String> = emptyList(),
)

@Serializable
enum class TaskOutcome { EXECUTED, UP_TO_DATE, FROM_CACHE, SKIPPED, NO_SOURCE, FAILED }

@Serializable
data class DerivedMetrics(
    val cacheableHitRate: Double? = null,
    val avoidedMs: Long? = null,
    val criticalPathMs: Long? = null,
    val parallelUtilization: Double? = null,
    val configurationMs: Long? = null,
)

/** Which JVM a [ProcessInfo] snapshot describes (plan 029); the only correlation key — no PID. */
@Serializable
enum class ProcessRole { GRADLE_DAEMON, KOTLIN_DAEMON, GRADLE_WORKER }

/**
 * One JVM's end-of-build snapshot (plan 029, spec §3.6). All metrics nullable — a single JDK-tool
 * exec (jstat/jinfo/ps) can fail per field without dropping the process. No PID or command line is
 * carried (host-local noise; jinfo/ps args can embed secrets — spec §3.7): [role] is the only key,
 * so multiple workers collapse to repeated `GRADLE_WORKER` rows.
 *
 * `heapMax` is JVM *capacity* (jstat `-gccapacity` NGCMX+OGCMX), distinct from [configuredXmxMb]
 * (the `-Xmx`/`-XX:MaxHeapSize` the JVM was launched with) — "configured vs used" needs both.
 */
@Serializable
data class ProcessInfo(
    val role: ProcessRole,
    val heapUsedMb: Long? = null,
    val heapCommittedMb: Long? = null,
    val heapMaxMb: Long? = null,
    val configuredXmxMb: Long? = null,
    val gcTimeMs: Long? = null,
    val rssMb: Long? = null,
    val uptimeS: Long? = null,
)
