package dev.buildhound.server.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectorRegistryTest {

    private class FakeConnector(
        override val id: String,
        override val capabilities: Set<Capability>,
    ) : CiConnector {
        override suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun? = null
        override fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent? = null
        override fun buildLink(ref: CiRunRef, config: ConnectorConfig): String? = null
    }

    private val azure = FakeConnector("azure-devops", setOf(Capability.TIMELINE_PULL, Capability.WEBHOOK))
    private val registry = ConnectorRegistry(listOf(azure))

    @Test
    fun `byProvider returns the matching connector`() {
        assertSame(azure, registry.byProvider("azure-devops"))
    }

    @Test
    fun `unknown or null provider falls back to noop`() {
        assertEquals("noop", registry.byProvider("does-not-exist").id)
        assertEquals("noop", registry.byProvider(null).id)
    }

    @Test
    fun `noop connector enriches nothing and returns null everywhere`() {
        val noop = registry.byProvider(null)
        assertTrue(noop.capabilities.isEmpty())
        assertNull(noop.buildLink(CiRunRef("noop", "1"), ConnectorConfig()))
        assertNull(noop.parseWebhook(emptyMap(), "{}", ConnectorConfig()))
    }

    @Test
    fun `canEnrich is true only for a timeline-pull connector`() {
        assertTrue(registry.canEnrich("azure-devops"))
        assertFalse(registry.canEnrich("noop"))
        assertFalse(registry.canEnrich(null))
    }

    @Test
    fun `byId returns null when no connector matches`() {
        assertSame(azure, registry.byId("azure-devops"))
        assertNull(registry.byId("noop"))
    }
}
