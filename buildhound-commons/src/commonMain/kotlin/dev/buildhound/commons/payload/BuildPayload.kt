package dev.buildhound.commons.payload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)

@Serializable
enum class BuildOutcome { SUCCESS, FAILED }

@Serializable
enum class BuildMode {
    @SerialName("ci") CI,
    @SerialName("local") LOCAL,
    @SerialName("benchmark") BENCHMARK,
}

@Serializable
data class FailureInfo(
    val taskPath: String? = null,
    val exceptionClass: String? = null,
    val messageHash: String? = null,
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
)

@Serializable
enum class ConfigurationCacheState { HIT, MISS_STORED, DISABLED, INCOMPATIBLE }

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
