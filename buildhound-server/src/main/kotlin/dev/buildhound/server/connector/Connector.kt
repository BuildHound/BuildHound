package dev.buildhound.server.connector

import kotlinx.serialization.Serializable

/**
 * Backend CI connector SPI (spec §5, plan 028). A connector pulls a provider's build timeline and
 * normalizes it into a [CiRun] tree — strictly additive: a project with no connector still renders
 * fully (that is [NoopConnector]). The framework's outbound-HTTP posture is env-only credentials +
 * a host allowlist (architecture §5 decision log); a connector never fails ingest.
 */
interface CiConnector {
    /** Matches `BuildPayload.ci.provider`, e.g. `"azure-devops"`. */
    val id: String

    val capabilities: Set<Capability>

    /** Pulls + normalizes the run; null when unavailable (never throws out to the caller's ingest). */
    suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun?

    /** Parses a service-hook body into an event, or null when it is not a recognized/valid hook. */
    fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent?

    /** A deep link back to the provider's run page, or null. */
    fun buildLink(ref: CiRunRef, config: ConnectorConfig): String?

    /**
     * Builds a run ref from the ingested correlation fields. The default carries only
     * `provider`/`runId`; a provider whose REST call needs a collection/project (Azure) overrides
     * to parse them out of the ingested `ci.buildUrl` — never guessed for the outbound host.
     */
    fun refFrom(provider: String, runId: String, buildUrl: String?): CiRunRef = CiRunRef(provider, runId)
}

enum class Capability { TIMELINE_PULL, WEBHOOK, DEEP_LINKS }

/** Correlation handle for a run: the provider + its run id, plus optional collection/project scope. */
data class CiRunRef(
    val provider: String,
    val runId: String,
    val collectionUri: String? = null,
    val project: String? = null,
)

@Serializable
enum class SpanKind { STAGE, JOB, STEP }

@Serializable
enum class SpanResult { SUCCEEDED, FAILED, CANCELED, SKIPPED, UNKNOWN }

@Serializable
data class CiSpan(
    val id: String,
    val kind: SpanKind,
    val name: String,
    val startMs: Long? = null,
    val finishMs: Long? = null,
    val result: SpanResult? = null,
    /** Agent/worker name — treated like the dropped plugin `agentName`: server-side only, never exported. */
    val workerName: String? = null,
    val parentId: String? = null,
)

/** Normalized CI run tree (spec §5); serialized to jsonb + out the ci-run query API. */
@Serializable
data class CiRun(
    val spans: List<CiSpan> = emptyList(),
    val queuedMs: Long? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

sealed interface CiEvent {
    /** A provider `build.complete`-style event correlating to [ref]. */
    data class RunCompleted(val ref: CiRunRef) : CiEvent
}

/**
 * A connector credential. Deliberately **not** `@Serializable` — a PAT must never be written to
 * jsonb, a log, or an image layer (architecture §6). Resolved from env only in v1.
 */
sealed interface Credential {
    data class Pat(val token: String) : Credential
}

/**
 * Per-project connector configuration. [allowedHosts] is the SSRF guard: only these hosts are
 * dialable, so an ingested `ci.buildUrl` can select *which* configured org but never introduce a
 * new outbound host. [credential] is null when no PAT is configured (→ the run is UNCONFIGURED).
 */
data class ConnectorConfig(
    val baseUrl: String? = null,
    val project: String? = null,
    val credential: Credential? = null,
    val allowedHosts: Set<String> = emptySet(),
)
