package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UploadGateTest {

    private fun decide(
        enabled: Boolean = true,
        serverUrl: String? = "https://buildhound.example.com/",
        mode: BuildMode = BuildMode.CI,
        localBuildsEnabled: Boolean = true,
        requireOptInFile: Boolean = true,
        optInFileExists: Boolean = false,
    ) = UploadGate.decide(enabled, serverUrl, mode, localBuildsEnabled, requireOptInFile, optInFileExists)

    @Test
    fun `ci mode uploads when a server is configured`() {
        val decision = assertIs<UploadGate.Decision.Upload>(decide())
        assertEquals("https://buildhound.example.com", decision.url, "trailing slash trimmed")
    }

    @Test
    fun `no server or non-http server means no upload`() {
        assertIs<UploadGate.Decision.Skip>(decide(serverUrl = null))
        assertIs<UploadGate.Decision.Skip>(decide(serverUrl = "  "))
        assertIs<UploadGate.Decision.Skip>(decide(serverUrl = "ftp://host"))
    }

    @Test
    fun `disabled telemetry never uploads`() {
        assertIs<UploadGate.Decision.Skip>(decide(enabled = false))
    }

    @Test
    fun `local mode requires the opt-in chain`() {
        // default: opt-in file required and missing
        assertIs<UploadGate.Decision.Skip>(decide(mode = BuildMode.LOCAL))
        // marker present
        assertIs<UploadGate.Decision.Upload>(decide(mode = BuildMode.LOCAL, optInFileExists = true))
        // marker not required
        assertIs<UploadGate.Decision.Upload>(decide(mode = BuildMode.LOCAL, requireOptInFile = false))
        // local uploads disabled entirely
        assertIs<UploadGate.Decision.Skip>(
            decide(mode = BuildMode.LOCAL, localBuildsEnabled = false, optInFileExists = true),
        )
    }

    @Test
    fun `benchmark mode uploads like ci`() {
        assertIs<UploadGate.Decision.Upload>(decide(mode = BuildMode.BENCHMARK))
    }
}
