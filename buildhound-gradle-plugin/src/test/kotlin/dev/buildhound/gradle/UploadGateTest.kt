package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
     * What plan 110's marker retention keys on: a server-configuration gap that was the **sole**
     * blocker. `decide` reports the first blocker, and the server check comes first, so a payload can
     * be reported as `NO_SERVER` while consent would have refused it too — that must not be
     * retryable, or a `~/.buildhound/optin` created later would publish a build recorded before
     * consent existed.
     */
    @Test
    fun `a skip is retryable only when the server gap is the sole blocker`() {
        // CI with no server: nothing else objects, so a later build with a server may publish it.
        assertTrue(
            assertIs<UploadGate.Decision.Skip>(decide(serverUrl = null)).retryWhenServerConfigured,
        )
        assertTrue(
            assertIs<UploadGate.Decision.Skip>(decide(serverUrl = "ftp://host")).retryWhenServerConfigured,
        )
        // LOCAL with no server AND no opt-in — the plugin's DEFAULT configuration. Reported as
        // NO_SERVER because that is the first blocker, but consent would have refused it as well.
        val masked = assertIs<UploadGate.Decision.Skip>(decide(serverUrl = null, mode = BuildMode.LOCAL))
        assertEquals(UploadGate.Cause.NO_SERVER, masked.cause, "the reported blocker is unchanged")
        assertFalse(
            masked.retryWhenServerConfigured,
            "a build consent would also have blocked must never become retryable",
        )
        // Same, for the standing choice.
        assertFalse(
            assertIs<UploadGate.Decision.Skip>(
                decide(serverUrl = null, mode = BuildMode.LOCAL, localBuildsEnabled = false, optInFileExists = true),
            )
                .retryWhenServerConfigured,
        )
        // LOCAL that consent permits, blocked only by the missing server.
        assertTrue(
            assertIs<UploadGate.Decision.Skip>(
                decide(serverUrl = null, mode = BuildMode.LOCAL, requireOptInFile = false),
            )
                .retryWhenServerConfigured,
        )
        // A consent skip is never retryable.
        assertFalse(
            assertIs<UploadGate.Decision.Skip>(decide(mode = BuildMode.LOCAL)).retryWhenServerConfigured,
        )
    }
}
