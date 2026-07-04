package dev.buildhound.server.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvConnectorConfigStoreTest {

    private fun store(vararg env: Pair<String, String>) = EnvConnectorConfigStore(mapOf(*env))

    @Test
    fun `an unset provider token yields no config so it never dials outbound`() {
        val store = store() // empty env
        assertNull(store.forProject("p", "github-actions"))
        assertNull(store.forProject("p", "gitlab"))
        assertNull(store.forProject("p", "azure-devops"))
    }

    @Test
    fun `an unknown provider yields no config`() {
        assertNull(store("BUILDHOUND_CONNECTOR_GITHUB_TOKEN" to "x").forProject("p", "jenkins"))
    }

    @Test
    fun `github defaults to the api-github-com host and rest root`() {
        val config = store("BUILDHOUND_CONNECTOR_GITHUB_TOKEN" to "ght").forProject("p", "github-actions")!!
        assertEquals("https://api.github.com", config.baseUrl)
        assertEquals(setOf("api.github.com"), config.allowedHosts)
        assertEquals(Credential.Pat("ght"), config.credential)
    }

    @Test
    fun `github enterprise overrides the base url and host allowlist`() {
        val config = store(
            "BUILDHOUND_CONNECTOR_GITHUB_TOKEN" to "ght",
            "BUILDHOUND_CONNECTOR_GITHUB_BASEURL" to "https://ghe.corp/api/v3",
            "BUILDHOUND_CONNECTOR_GITHUB_HOSTS" to "ghe.corp",
        ).forProject("p", "github-actions")!!
        assertEquals("https://ghe.corp/api/v3", config.baseUrl)
        assertEquals(setOf("ghe.corp"), config.allowedHosts)
    }

    @Test
    fun `gitlab defaults to gitlab-com v4 api and host`() {
        val config = store("BUILDHOUND_CONNECTOR_GITLAB_TOKEN" to "glt").forProject("p", "gitlab")!!
        assertEquals("https://gitlab.com/api/v4", config.baseUrl)
        assertEquals(setOf("gitlab.com"), config.allowedHosts)
        assertEquals(Credential.Pat("glt"), config.credential)
    }

    @Test
    fun `self-managed gitlab overrides the base url and host allowlist`() {
        val config = store(
            "BUILDHOUND_CONNECTOR_GITLAB_TOKEN" to "glt",
            "BUILDHOUND_CONNECTOR_GITLAB_BASEURL" to "https://gitlab.corp/api/v4",
            "BUILDHOUND_CONNECTOR_GITLAB_HOSTS" to "gitlab.corp, mirror.corp",
        ).forProject("p", "gitlab")!!
        assertEquals("https://gitlab.corp/api/v4", config.baseUrl)
        assertEquals(setOf("gitlab.corp", "mirror.corp"), config.allowedHosts)
    }

    @Test
    fun `a blank token is treated as unset`() {
        assertNull(store("BUILDHOUND_CONNECTOR_GITHUB_TOKEN" to "   ").forProject("p", "github-actions"))
    }

    @Test
    fun `azure still resolves via its own PAT and defaults`() {
        val config = store("BUILDHOUND_CONNECTOR_AZURE_PAT" to "pat").forProject("p", "azure-devops")!!
        assertTrue(config.allowedHosts.contains("dev.azure.com"))
        assertEquals(Credential.Pat("pat"), config.credential)
    }
}
