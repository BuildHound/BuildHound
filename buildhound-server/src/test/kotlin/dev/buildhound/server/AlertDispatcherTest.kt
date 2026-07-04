package dev.buildhound.server

import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertDispatcherTest {

    private val directExecutor = Executor { it.run() } // synchronous, so assertions are deterministic

    // Resolve every host to a public literal IP so the SSRF filter passes without real DNS.
    private val publicResolver: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("93.184.216.34")) }

    private fun dispatcher(send: HttpSend, allowLoopbackHttp: Boolean = false) =
        HttpAlertDispatcher(send = send, allowLoopbackHttp = allowLoopbackHttp, executor = directExecutor, resolver = publicResolver)

    private val context = VerdictAlert(
        projectKey = "pilot",
        buildId = "b-1",
        baselineKey = "android-ci|sig|main|CI",
        verdict = Verdict(
            status = VerdictStatus.FAIL.name,
            metrics = listOf(MetricVerdict("durationMs", 60000.0, 1000.0, 20.0, 20.0, null, VerdictStatus.FAIL.name)),
            baselineKey = "android-ci|sig|main|CI",
        ),
        dashboardBaseUrl = "https://buildhound.example.com",
    )

    private class RecordingSend(var throwOnPost: Boolean = false) : HttpSend {
        val calls = mutableListOf<Pair<String, String>>()
        override fun post(url: String, body: String): Int {
            calls.add(url to body)
            if (throwOnPost) throw java.io.IOException("unreachable")
            return 200
        }
    }

    @Test
    fun `an https channel is dispatched once with a text body`() {
        val send = RecordingSend()
        dispatcher(send).dispatch(listOf(AlertChannel("slack", "https://hooks.slack.com/services/xxx")), context)
        assertEquals(1, send.calls.size)
        assertTrue(send.calls.single().second.contains("\"text\""))
    }

    @Test
    fun `a non-https url is refused and never sent`() {
        val send = RecordingSend()
        dispatcher(send).dispatch(listOf(AlertChannel("webhook", "http://evil.example.com/hook")), context)
        assertTrue(send.calls.isEmpty(), "plain http to a non-loopback host must be refused")
    }

    @Test
    fun `an https url that resolves to an internal or metadata host is refused`() {
        val send = RecordingSend()
        // Literal internal IPs parse without DNS; the default resolver returns them as-is.
        val dispatcher = HttpAlertDispatcher(send = send, executor = directExecutor)
        for (host in listOf("169.254.169.254", "10.0.0.1", "127.0.0.1", "192.168.1.1", "[::1]")) {
            dispatcher.dispatch(listOf(AlertChannel("webhook", "https://$host/hook")), context)
        }
        assertTrue(send.calls.isEmpty(), "SSRF guard must refuse loopback/link-local/private/metadata hosts")
    }

    @Test
    fun `a url with userinfo is refused`() {
        val send = RecordingSend()
        dispatcher(send).dispatch(listOf(AlertChannel("webhook", "https://user:pass@hooks.example.com/hook")), context)
        assertTrue(send.calls.isEmpty(), "userinfo is never needed and is an obfuscation vector")
    }

    @Test
    fun `loopback http is allowed only when explicitly enabled`() {
        val send = RecordingSend()
        dispatcher(send, allowLoopbackHttp = true)
            .dispatch(listOf(AlertChannel("webhook", "http://127.0.0.1:9099/hook")), context)
        assertEquals(1, send.calls.size)
    }

    @Test
    fun `an unreachable endpoint logs and never throws`() {
        val send = RecordingSend(throwOnPost = true)
        // Must not propagate — a failed alert can never affect ingest.
        dispatcher(send).dispatch(listOf(AlertChannel("slack", "https://hooks.slack.com/services/xxx")), context)
        assertEquals(1, send.calls.size)
    }

    @Test
    fun `the webhook body carries only pseudonymized verdict data, no identity or secrets`() {
        val send = RecordingSend()
        dispatcher(send).dispatch(listOf(AlertChannel("webhook", "https://hooks.example.com/generic")), context)
        val body = send.calls.single().second
        assertTrue(body.contains("\"buildId\""))
        assertTrue(body.contains("durationMs"))
        // No task detail, tags, values, identity, or token material.
        for (forbidden in listOf("token", "hostnameHash", "userId", "tags", "values", "executionReasons")) {
            assertTrue(!body.contains(forbidden), "alert body must not carry '$forbidden': $body")
        }
    }

    // --- Flaky alert body (plan 036): the second AlertContext kind through the real dispatcher. ---

    private val flakyContext = FlakyAlert(
        projectKey = "pilot",
        record = FlakyRecord(
            module = ":lib",
            className = "com.example.FooTest",
            signal = FlakySignal.CROSS_RUN.name,
            flakeRate = 0.3333,
            sampleCount = 6,
            firstSeenMs = 1_000,
            lastSeenMs = 2_000,
            affectedBuildIds = listOf("b1", "b2"),
        ),
        dashboardBaseUrl = "https://buildhound.example.com/",
    )

    @Test
    fun `a flaky alert dispatches a text summary with the class, rate, and flaky deep-link`() {
        val send = RecordingSend()
        dispatcher(send).dispatch(listOf(AlertChannel("slack", "https://hooks.slack.com/services/xxx")), flakyContext)
        val body = send.calls.single().second
        assertTrue(body.contains("\"text\""))
        // The one-liner names the class, the rounded percentage, the signal, and the #/flaky link.
        for (fragment in listOf(":lib/com.example.FooTest", "33%", "CROSS_RUN", "/#/flaky")) {
            assertTrue(body.contains(fragment), "flaky summary must contain '$fragment': $body")
        }
    }

    @Test
    fun `the flaky webhook body carries only pseudonymized flaky data, no identity or secrets`() {
        val send = RecordingSend()
        dispatcher(send).dispatch(listOf(AlertChannel("webhook", "https://hooks.example.com/generic")), flakyContext)
        val body = send.calls.single().second
        assertTrue(body.contains("\"kind\":\"buildhound.flaky\""), body)
        for (present in listOf(":lib", "com.example.FooTest", "CROSS_RUN", "flakeRate", "sampleCount", "/#/flaky")) {
            assertTrue(body.contains(present), "flaky webhook body must contain '$present': $body")
        }
        // Narrower than the source: no build ids, identity, or token material cross the wire.
        for (forbidden in listOf("token", "hostnameHash", "userId", "affectedBuildIds", "firstSeenMs", "buildId")) {
            assertTrue(!body.contains(forbidden), "flaky webhook body must not carry '$forbidden': $body")
        }
    }
}
