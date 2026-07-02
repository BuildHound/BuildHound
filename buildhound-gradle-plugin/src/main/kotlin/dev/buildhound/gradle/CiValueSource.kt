package dev.buildhound.gradle

import dev.buildhound.commons.ci.CiEnvironment
import dev.buildhound.commons.ci.CiEnvironmentProvider
import java.io.Serializable
import java.util.ServiceLoader
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * Serializable mirror of [dev.buildhound.commons.ci.CiContext] for CC-safe transport
 * into the FlowAction. Null when no CI was detected.
 */
data class CollectedCi(
    val provider: String,
    val pipelineId: String? = null,
    val pipelineName: String? = null,
    val runId: String? = null,
    val jobId: String? = null,
    val stageId: String? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val pullRequestId: String? = null,
    val targetBranch: String? = null,
    val buildUrl: String? = null,
    /** Provider-specific extras (spec §3.3) — third-party SPI values pass through. */
    val attributes: Map<String, String> = emptyMap(),
) : Serializable

/**
 * CI detection (spec §3.3) at execution time: env is read here — not in the
 * configuration model — and third-party providers are discovered via `ServiceLoader`
 * on this classloader, which sees the settings classpath where the plugin lives.
 * `agentName` is deliberately not carried forward (plan 005: quasi-PII on self-hosted
 * runners, not a §4-declared payload field).
 */
abstract class CiValueSource : ValueSource<CollectedCi, CiValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val enabled: Property<Boolean>
    }

    override fun obtain(): CollectedCi? {
        if (!parameters.enabled.getOrElse(true)) return null
        return runCatching {
            val extras = ServiceLoader.load(CiEnvironmentProvider::class.java, javaClass.classLoader).toList()
            CiEnvironment.detect(System.getenv(), extras)?.let { context ->
                CollectedCi(
                    provider = context.provider,
                    pipelineId = context.pipelineId,
                    pipelineName = context.pipelineName,
                    runId = context.runId,
                    jobId = context.jobId,
                    stageId = context.stageId,
                    branch = context.branch,
                    commitSha = context.commitSha,
                    pullRequestId = context.pullRequestId,
                    targetBranch = context.targetBranch,
                    buildUrl = context.buildUrl,
                    attributes = context.attributes,
                )
            }
        }.onFailure {
            logger.info("[buildhound] CI detection unavailable: {}", it::class.java.simpleName)
        }.getOrNull()
    }

    private companion object {
        val logger = Logging.getLogger(CiValueSource::class.java)
    }
}
