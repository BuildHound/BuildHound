package dev.buildhound.server.connector

/**
 * The honest default connector (spec §5): no capabilities, no fetch. A project with no configured
 * connector resolves to this, so ingest and rendering are unaffected — the extension point is
 * public from day one without forcing every deployment to wire a real connector.
 */
class NoopConnector : CiConnector {
    override val id: String = "noop"
    override val capabilities: Set<Capability> = emptySet()
    override suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun? = null
    override fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent? = null
    override fun buildLink(ref: CiRunRef, config: ConnectorConfig): String? = null
}

/** Resolves a `ci.provider` to its connector; the [Noop][NoopConnector] fallback keeps it total. */
class ConnectorRegistry(
    private val connectors: List<CiConnector>,
    private val noop: CiConnector = NoopConnector(),
) {
    fun byProvider(provider: String?): CiConnector =
        connectors.firstOrNull { it.id == provider } ?: noop

    fun byId(id: String): CiConnector? = connectors.firstOrNull { it.id == id }

    /** True when a real (capability-bearing) connector handles this provider. */
    fun canEnrich(provider: String?): Boolean =
        byProvider(provider).capabilities.contains(Capability.TIMELINE_PULL)
}
