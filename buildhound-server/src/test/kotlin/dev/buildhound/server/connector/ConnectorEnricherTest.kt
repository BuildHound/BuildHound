package dev.buildhound.server.connector

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Failure-injection guardrail (plan 028 §5): every connector failure mode maps to the right stored
 * [CiRunStatus] and **never** throws out of the enrichment path.
 */
class ConnectorEnricherTest {

    private class StubConnector(
        override val id: String = "azure-devops",
        override val capabilities: Set<Capability> = setOf(Capability.TIMELINE_PULL),
        val onFetch: (CiRunRef, ConnectorConfig) -> CiRun?,
    ) : CiConnector {
        var calls = 0
            private set

        override suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun? {
            calls++
            return onFetch(ref, config)
        }

        override fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent? = null
        override fun buildLink(ref: CiRunRef, config: ConnectorConfig): String? = null
    }

    private fun configs(config: ConnectorConfig?) = object : ConnectorConfigStore {
        override fun forProject(projectId: String, provider: String): ConnectorConfig? = config
    }

    private val store = InMemoryCiSpanStore()
    private val config = ConnectorConfig(
        baseUrl = "https://dev.azure.com/org",
        project = "p",
        credential = Credential.Pat("t"),
        allowedHosts = setOf("dev.azure.com"),
    )

    private fun enricher(connector: CiConnector, cfg: ConnectorConfig?, attempts: Int = 3) = ConnectorEnricher(
        registry = ConnectorRegistry(listOf(connector)),
        configs = configs(cfg),
        spans = store,
        maxPollAttempts = attempts,
        sleep = {}, // instant — no real backoff in tests
    )

    // --- expected-build fallback (plan 033) ---

    private fun expectedBuildEnricher(
        connector: CiConnector,
        cfg: ConnectorConfig?,
        record: (String, String, String, CiRun) -> Unit,
    ) = ConnectorEnricher(
        registry = ConnectorRegistry(listOf(connector)),
        configs = configs(cfg),
        spans = store,
        sleep = {},
        recordInterrupted = record,
    )

    @Test
    fun `checkExpectedBuild records interrupted for a completed run`() = runBlocking {
        val recorded = mutableListOf<Triple<String, String, CiRun>>()
        val connector = StubConnector(onFetch = { _, _ -> CiRun(startedAt = 100, finishedAt = 400) })
        expectedBuildEnricher(connector, config) { _, provider, runId, run -> recorded += Triple(provider, runId, run) }
            .checkExpectedBuild("proj", "azure-devops", "99", null)
        assertEquals(1, recorded.size)
        assertEquals("99", recorded.single().second)
        assertEquals(400L, recorded.single().third.finishedAt)
    }

    @Test
    fun `checkExpectedBuild does nothing for a still-running run`() = runBlocking {
        var called = false
        val connector = StubConnector(onFetch = { _, _ -> CiRun(startedAt = 100, finishedAt = null) })
        expectedBuildEnricher(connector, config) { _, _, _, _ -> called = true }
            .checkExpectedBuild("proj", "azure-devops", "99", null)
        assertTrue(!called, "a run still in progress is not interrupted")
    }

    @Test
    fun `checkExpectedBuild no-ops without a credential and never throws on a bad fetch`() = runBlocking {
        var called = false
        val noCred = StubConnector(onFetch = { _, _ -> CiRun(finishedAt = 1) })
        expectedBuildEnricher(noCred, config.copy(credential = null)) { _, _, _, _ -> called = true }
            .checkExpectedBuild("proj", "azure-devops", "99", null)
        assertTrue(!called, "no credential → no synthesis")

        val throwing = StubConnector(onFetch = { _, _ -> throw RuntimeException("boom") })
        expectedBuildEnricher(throwing, config) { _, _, _, _ -> called = true }
            .checkExpectedBuild("proj", "azure-devops", "99", null) // must not throw
        assertTrue(!called)
    }

    @Test
    fun `a finished run stores OK with the tree`() = runBlocking {
        val connector = StubConnector(onFetch = { _, _ ->
            CiRun(spans = listOf(CiSpan("s1", SpanKind.STAGE, "Build")), queuedMs = 200, startedAt = 0, finishedAt = 300)
        })
        val status = enricher(connector, config).enrich("proj", "b1", "azure-devops", "42", null)
        assertEquals(CiRunStatus.OK, status)
        val stored = store.findRun("proj", "b1")!!
        assertEquals(CiRunStatus.OK, stored.status)
        assertEquals(1, stored.run!!.spans.size)
        assertEquals(1, connector.calls)
    }

    @Test
    fun `a throwing fetch stores FAILED and never propagates`() = runBlocking {
        val connector = StubConnector(onFetch = { _, _ -> throw RuntimeException("boom") })
        val status = enricher(connector, config).enrich("proj", "b1", "azure-devops", "42", null)
        assertEquals(CiRunStatus.FAILED, status)
        val stored = store.findRun("proj", "b1")!!
        assertEquals(CiRunStatus.FAILED, stored.status)
        assertNull(stored.run)
    }

    @Test
    fun `a transient throw on one attempt is retried, not fatal to the whole poll`() = runBlocking {
        var n = 0
        val connector = StubConnector(onFetch = { _, _ ->
            n++
            if (n == 1) throw RuntimeException("transient 503")
            CiRun(spans = listOf(CiSpan("s1", SpanKind.STAGE, "Build")), startedAt = 0, finishedAt = 100)
        })
        val status = enricher(connector, config, attempts = 3).enrich("proj", "b1", "azure-devops", "42", null)
        assertEquals(CiRunStatus.OK, status)
        assertEquals(CiRunStatus.OK, store.findRun("proj", "b1")!!.status)
        assertEquals(2, connector.calls) // attempt 1 threw, attempt 2 succeeded — not aborted on the throw
    }

    @Test
    fun `a fetch that always returns null stores FAILED after the budget`() = runBlocking {
        val connector = StubConnector(onFetch = { _, _ -> null })
        val status = enricher(connector, config, attempts = 3).enrich("proj", "b1", "azure-devops", "42", null)
        assertEquals(CiRunStatus.FAILED, status)
        assertEquals(CiRunStatus.FAILED, store.findRun("proj", "b1")!!.status)
        assertEquals(3, connector.calls) // polled the full budget, then gave up
    }

    @Test
    fun `a still-running build (no finishedAt) stores PENDING with its partial tree`() = runBlocking {
        val connector = StubConnector(onFetch = { _, _ ->
            CiRun(spans = listOf(CiSpan("s1", SpanKind.STAGE, "Build")), startedAt = 0, finishedAt = null)
        })
        val status = enricher(connector, config, attempts = 4).enrich("proj", "b1", "azure-devops", "42", null)
        assertEquals(CiRunStatus.PENDING, status)
        val stored = store.findRun("proj", "b1")!!
        assertEquals(CiRunStatus.PENDING, stored.status)
        assertEquals(1, stored.run!!.spans.size) // the partial tree is kept
        assertEquals(4, connector.calls)
    }

    @Test
    fun `no credential stores UNCONFIGURED and never dials`() = runBlocking {
        val connector = StubConnector(onFetch = { _, _ -> error("must not be called") })
        val status = enricher(connector, cfg = null).enrich("proj", "b1", "azure-devops", "42", null)
        assertEquals(CiRunStatus.UNCONFIGURED, status)
        assertEquals(CiRunStatus.UNCONFIGURED, store.findRun("proj", "b1")!!.status)
        assertEquals(0, connector.calls)
    }

    @Test
    fun `a non-timeline connector does not enrich`() = runBlocking {
        val noop = StubConnector(capabilities = emptySet(), onFetch = { _, _ -> error("must not be called") })
        val status = enricher(noop, config).enrich("proj", "b1", "azure-devops", "42", null)
        assertEquals(CiRunStatus.UNCONFIGURED, status)
        assertNull(store.findRun("proj", "b1")) // no row written
        assertTrue(noop.calls == 0)
    }
}
