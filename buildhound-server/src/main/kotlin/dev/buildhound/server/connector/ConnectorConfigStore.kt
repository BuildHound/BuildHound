package dev.buildhound.server.connector

/**
 * Resolves a project's [ConnectorConfig] (plan 028). v1 is **env-only** and global (one PAT for the
 * deployment) — a per-project credentials table is a follow-up. Returns null (→ `UNCONFIGURED`)
 * when no credential is set, so a deployment without a PAT never dials outbound.
 */
interface ConnectorConfigStore {
    fun forProject(projectId: String, provider: String): ConnectorConfig?
}

/**
 * Env-backed config (architecture §4/§6: outbound credentials live in env, never code/DSL/image).
 * The PAT gates enrichment; [allowedHosts] is the SSRF guard and defaults to the Azure SaaS host,
 * so an ingested `ci.buildUrl` can only select a configured org, never introduce a new host. A
 * self-hosted Azure DevOps Server host must be named explicitly in `BUILDHOUND_CONNECTOR_AZURE_HOSTS`.
 */
class EnvConnectorConfigStore(private val env: Map<String, String> = System.getenv()) : ConnectorConfigStore {

    override fun forProject(projectId: String, provider: String): ConnectorConfig? {
        if (provider != AZURE_PROVIDER) return null
        val pat = env["BUILDHOUND_CONNECTOR_AZURE_PAT"]?.takeIf { it.isNotBlank() } ?: return null
        val hosts = env["BUILDHOUND_CONNECTOR_AZURE_HOSTS"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(DEFAULT_AZURE_HOST)
        return ConnectorConfig(
            baseUrl = env["BUILDHOUND_CONNECTOR_AZURE_BASEURL"]?.takeIf { it.isNotBlank() },
            project = env["BUILDHOUND_CONNECTOR_AZURE_PROJECT"]?.takeIf { it.isNotBlank() },
            credential = Credential.Pat(pat),
            allowedHosts = hosts,
        )
    }

    private companion object {
        const val AZURE_PROVIDER = "azure-devops"
        const val DEFAULT_AZURE_HOST = "dev.azure.com"
    }
}
