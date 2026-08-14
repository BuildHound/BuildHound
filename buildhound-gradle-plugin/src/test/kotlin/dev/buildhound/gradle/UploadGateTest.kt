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
        assertEquals(UploadGate.Cause.NO_SERVER, assertIs<UploadGate.Decision.Skip>(decide(serverUrl = null)).cause)
        assertEquals(UploadGate.Cause.NO_SERVER, assertIs<UploadGate.Decision.Skip>(decide(serverUrl = "  ")).cause)
        assertEquals(
            UploadGate.Cause.SERVER_URL_NOT_HTTP,
            assertIs<UploadGate.Decision.Skip>(decide(serverUrl = "ftp://host")).cause,
        )
    }

    @Test
    fun `disabled telemetry never uploads`() {
        assertEquals(
            UploadGate.Cause.TELEMETRY_DISABLED,
            assertIs<UploadGate.Decision.Skip>(decide(enabled = false)).cause,
        )
    }

    @Test
    fun `local mode requires the opt-in chain`() {
        // default: opt-in file required and missing
        assertEquals(
            UploadGate.Cause.LOCAL_OPT_IN_MISSING,
            assertIs<UploadGate.Decision.Skip>(decide(mode = BuildMode.LOCAL)).cause,
        )
        // marker present
        assertIs<UploadGate.Decision.Upload>(decide(mode = BuildMode.LOCAL, optInFileExists = true))
        // marker not required
        assertIs<UploadGate.Decision.Upload>(decide(mode = BuildMode.LOCAL, requireOptInFile = false))
        // local uploads disabled entirely
        assertEquals(
            UploadGate.Cause.LOCAL_UPLOADS_DISABLED,
            assertIs<UploadGate.Decision.Skip>(
                decide(mode = BuildMode.LOCAL, localBuildsEnabled = false, optInFileExists = true),
            )
                .cause,
        )
    }

    @Test
    fun `benchmark mode uploads like ci`() {
        assertIs<UploadGate.Decision.Upload>(decide(mode = BuildMode.BENCHMARK))
    }

    /**
     * The split plan 110's marker retention keys on. A configuration gap means the machine *could
     * not* publish and a later build may be able to; everything else is a decision not to publish,
     * and re-offering those would push out something the user declined — including, for
     * [UploadGate.Cause.LOCAL_OPT_IN_MISSING], a build recorded before consent existed.
     */
    @Test
    fun `only a missing or unusable server url counts as a configuration gap`() {
        assertEquals(
            setOf(UploadGate.Cause.NO_SERVER, UploadGate.Cause.SERVER_URL_NOT_HTTP),
            UploadGate.Cause.entries.filter { it.isServerConfigGap }.toSet(),
            "retention must not widen to a consent or standing-choice skip",
        )
    }
}
