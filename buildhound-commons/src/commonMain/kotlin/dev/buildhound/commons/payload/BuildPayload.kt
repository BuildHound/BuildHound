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
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

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
