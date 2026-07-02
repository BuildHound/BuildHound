package io.example.btp.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenericCiEnvironmentProviderTest {

    private val provider = GenericCiEnvironmentProvider()

    @Test
    fun does_not_detect_without_btp_markers() {
        assertNull(provider.detect(mapOf("CI" to "true", "HOME" to "/home/agent")))
    }

    @Test
    fun detects_from_generic_env_vars() {
        val context = provider.detect(
            mapOf(
                "BTP_CI" to "true",
                "BTP_CI_PROVIDER" to "my-inhouse-ci",
                "BTP_CI_RUN_ID" to "42",
                "BTP_CI_BRANCH" to "main",
                "BTP_CI_BUILD_URL" to "https://ci.example.com/run/42",
            ),
        )

        assertEquals("my-inhouse-ci", context?.provider)
        assertEquals("42", context?.runId)
        assertEquals("main", context?.branch)
        assertEquals("https://ci.example.com/run/42", context?.buildUrl)
    }
}
