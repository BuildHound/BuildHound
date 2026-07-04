package dev.buildhound.server.connector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnrichmentQueueTest {

    private class StubConnector(
        override val capabilities: Set<Capability> = setOf(Capability.TIMELINE_PULL),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : CiConnector {
        override val id: String = "azure-devops"
        override suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun? {
            gate?.await() // hold the single worker so submissions pile up in-flight (cap test)
            return CiRun(spans = listOf(CiSpan("s1", SpanKind.STAGE, "Build")), startedAt = 0, finishedAt = 100)
        }
        override fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent? = null
        override fun buildLink(ref: CiRunRef, config: ConnectorConfig): String? = null
    }

    private val config = ConnectorConfig(
        baseUrl = "https://dev.azure.com/org", project = "p",
        credential = Credential.Pat("t"), allowedHosts = setOf("dev.azure.com"),
    )

    private fun queue(connector: CiConnector, cfg: ConnectorConfig?, store: CiSpanStore, perProjectCap: Int = 32): EnrichmentQueue {
        val configs = object : ConnectorConfigStore {
            override fun forProject(projectId: String, provider: String): ConnectorConfig? = cfg
        }
        return EnrichmentQueue(
            ConnectorEnricher(ConnectorRegistry(listOf(connector)), configs, store, sleep = {}),
            perProjectCap = perProjectCap,
        )
    }

    @Test
    fun `submit then drain runs the enrichment and stores the run`() = runBlocking {
        val store = InMemoryCiSpanStore()
        val queue = queue(StubConnector(), config, store)
        queue.submit("proj", "b1", "azure-devops", "42", "https://dev.azure.com/org/p/_build/results?buildId=42")
        queue.drain()
        assertEquals(CiRunStatus.OK, store.findRun("proj", "b1")!!.status)
    }

    @Test
    fun `submit no-ops for a missing run id`() = runBlocking {
        val store = InMemoryCiSpanStore()
        val queue = queue(StubConnector(), config, store)
        queue.submit("proj", "b1", "azure-devops", null, null)
        queue.drain()
        assertNull(store.findRun("proj", "b1"))
    }

    @Test
    fun `submit no-ops for a provider with no timeline connector`() = runBlocking {
        val store = InMemoryCiSpanStore()
        // A registry with no connector for this provider → canEnrich false → nothing queued.
        val queue = queue(StubConnector(capabilities = emptySet()), config, store)
        queue.submit("proj", "b1", "github-actions", "42", null)
        queue.drain()
        assertNull(store.findRun("proj", "b1"))
    }

    @Test
    fun `a project cannot exceed its per-project cap`() = runBlocking {
        val store = InMemoryCiSpanStore()
        val gate = CompletableDeferred<Unit>()
        // The worker is held at the gate, so all accepted jobs stay in-flight while we submit — the
        // cap is measured against live in-flight count, not throughput.
        val queue = queue(StubConnector(gate = gate), config, store, perProjectCap = 3)
        repeat(5) { i -> queue.submit("proj", "b$i", "azure-devops", "$i", null) }
        gate.complete(Unit)
        queue.drain()
        val enriched = (0 until 5).count { store.findRun("proj", "b$it") != null }
        assertEquals(3, enriched) // cap 3 accepted, 2 shed
    }

    @Test
    fun `the per-project cap is independent across projects`() = runBlocking {
        val store = InMemoryCiSpanStore()
        val gate = CompletableDeferred<Unit>()
        val queue = queue(StubConnector(gate = gate), config, store, perProjectCap = 1)
        queue.submit("a", "a1", "azure-devops", "1", null)
        queue.submit("b", "b1", "azure-devops", "1", null) // different project — own slot
        gate.complete(Unit)
        queue.drain()
        assertEquals(CiRunStatus.OK, store.findRun("a", "a1")!!.status)
        assertEquals(CiRunStatus.OK, store.findRun("b", "b1")!!.status)
    }
}
