package dev.buildhound.server.connector

/**
 * Resolves a project's [ConnectorConfig] (plan 028, extended plan 041). v1 is **env-only** and global
 * (one credential per provider for the deployment) — a per-project credentials table is a follow-up.
 * Returns null (→ `UNCONFIGURED`) when no credential is set for the provider, so a deployment without
 * a token for that provider never dials it outbound.
 */
interface ConnectorConfigStore {
    fun forProject(projectId: String, provider: String): ConnectorConfig?
}

/**
 * Env-backed config (architecture §4/§6: outbound credentials live in env, never code/DSL/image). The
 * per-provider token gates enrichment; the `_HOSTS` allowlist is the SSRF guard and defaults to the
 * provider's SaaS API host, so an ingested build URL can only select a configured org, never introduce
 * a new host. A self-hosted instance (Azure DevOps Server, GitHub Enterprise, self-managed GitLab) must
 * name its host in the `_HOSTS` var **and** point the `_BASEURL` at its API root.
 */
class EnvConnectorConfigStore(private val env: Map<String, String> = System.getenv()) : ConnectorConfigStore {

    override fun forProject(projectId: String, provider: String): ConnectorConfig? = when (provider) {
        AZURE_PROVIDER -> azure()
        GITHUB_PROVIDER -> github()
        GITLAB_PROVIDER -> gitlab()
        else -> null
    }

    private fun azure(): ConnectorConfig? {
        val pat = value("BUILDHOUND_CONNECTOR_AZURE_PAT") ?: return null
        return ConnectorConfig(
            baseUrl = value("BUILDHOUND_CONNECTOR_AZURE_BASEURL"),
            project = value("BUILDHOUND_CONNECTOR_AZURE_PROJECT"),
            credential = Credential.Pat(pat),
            allowedHosts = hosts("BUILDHOUND_CONNECTOR_AZURE_HOSTS", DEFAULT_AZURE_HOST),
        )
    }

    private fun github(): ConnectorConfig? {
        val token = value("BUILDHOUND_CONNECTOR_GITHUB_TOKEN") ?: return null
        // baseUrl defaults to the github.com REST root; GitHub Enterprise sets it to https://ghe.host/api/v3.
        val baseUrl = value("BUILDHOUND_CONNECTOR_GITHUB_BASEURL") ?: DEFAULT_GITHUB_BASEURL
        return ConnectorConfig(
            baseUrl = baseUrl,
            project = value("BUILDHOUND_CONNECTOR_GITHUB_REPO"),
            credential = Credential.Pat(token),
            allowedHosts = hosts("BUILDHOUND_CONNECTOR_GITHUB_HOSTS", DEFAULT_GITHUB_HOST),
        )
    }

    private fun gitlab(): ConnectorConfig? {
        val token = value("BUILDHOUND_CONNECTOR_GITLAB_TOKEN") ?: return null
        // baseUrl defaults to gitlab.com's v4 API; a self-managed instance sets it to https://host/api/v4.
        val baseUrl = value("BUILDHOUND_CONNECTOR_GITLAB_BASEURL") ?: DEFAULT_GITLAB_BASEURL
        return ConnectorConfig(
            baseUrl = baseUrl,
            project = value("BUILDHOUND_CONNECTOR_GITLAB_PROJECT"),
            credential = Credential.Pat(token),
            allowedHosts = hosts("BUILDHOUND_CONNECTOR_GITLAB_HOSTS", DEFAULT_GITLAB_HOST),
        )
    }

    private fun value(key: String): String? = env[key]?.takeIf { it.isNotBlank() }

    private fun hosts(key: String, default: String): Set<String> =
        env[key]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(default)

    private companion object {
        const val AZURE_PROVIDER = "azure-devops"
        const val GITHUB_PROVIDER = "github-actions"
        const val GITLAB_PROVIDER = "gitlab"

        const val DEFAULT_AZURE_HOST = "dev.azure.com"
        const val DEFAULT_GITHUB_HOST = "api.github.com"
        const val DEFAULT_GITHUB_BASEURL = "https://api.github.com"
        const val DEFAULT_GITLAB_HOST = "gitlab.com"
        const val DEFAULT_GITLAB_BASEURL = "https://gitlab.com/api/v4"
    }
}
